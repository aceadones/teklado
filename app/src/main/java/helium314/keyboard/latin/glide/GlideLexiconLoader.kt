/*
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin.glide

import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.DictionaryFacilitator
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.WordComposer
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.makedict.WordProperty
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SuggestionResults
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loads dictionary words off the gesture thread and keeps one compiled lexicon.
 * Until that load finishes, [compiledFor] returns null and glide produces no suggestions.
 */
internal object GlideLexiconCache {
    private const val TAG = "GlideLexicon"
    private const val REFRESH_MS = 120_000L
    private const val FAILURE_BACKOFF_MS = 60_000L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GlideLexicon").apply { isDaemon = true }
    }
    private val loading = AtomicBoolean(false)

    @Volatile private var raw: RawHolder? = null
    @Volatile private var published: CompiledGlideLexicon? = null
    @Volatile private var loadedAt = 0L
    @Volatile private var failedKey = ""
    @Volatile private var failedAt = 0L

    /** True when a lexicon for the facilitator's current locales is already compiled, so no work is needed. */
    fun isReadyFor(facilitator: DictionaryFacilitator): Boolean =
        published?.localeKey == localeKey(facilitator)

    fun compiledFor(facilitator: DictionaryFacilitator, geometry: GlideKeyGeometry): CompiledGlideLexicon? {
        val key = localeKey(facilitator)
        val now = System.currentTimeMillis()
        val snap = published
        if (snap != null && snap.localeKey == key && geometry.codes.contentEquals(snap.codes)) {
            if (now - loadedAt >= REFRESH_MS && !recentlyFailed(key)) schedule(facilitator, geometry, key)
            return snap
        }
        val holder = raw
        if (holder != null && holder.key == key && geometry.keyCount >= 2) {
            val built = holder.lexicon.compile(geometry, key)
            if (raw === holder) published = built
            if (now - loadedAt >= REFRESH_MS && !recentlyFailed(key)) schedule(facilitator, geometry, key)
            return built
        }
        if (!recentlyFailed(key)) schedule(facilitator, geometry, key)
        return null
    }

    private fun schedule(facilitator: DictionaryFacilitator, geometry: GlideKeyGeometry, key: String) {
        if (!loading.compareAndSet(false, true)) return
        executor.execute {
            try {
                load(facilitator, geometry, key)
            } catch (t: Throwable) {
                Log.w(TAG, "Glide lexicon load failed", t)
                noteFailure(key)
            } finally {
                loading.set(false)
            }
        }
    }

    private fun load(facilitator: DictionaryFacilitator, geometry: GlideKeyGeometry, key: String) {
        if (!facilitator.hasAtLeastOneInitializedMainDictionary()) return
        if (localeKey(facilitator) != key) return
        val lexicon = readLexicon(facilitator)
        if (localeKey(facilitator) != key) return
        if (lexicon.size == 0) {
            Log.i(TAG, "Glide lexicon empty for $key")
            noteFailure(key)
            return
        }
        val holder = RawHolder(key, lexicon)
        raw = holder
        failedKey = ""
        loadedAt = System.currentTimeMillis()
        if (geometry.keyCount >= 2 && localeKey(facilitator) == key) {
            val built = lexicon.compile(geometry, key)
            if (raw === holder) published = built
        }
        Log.i(TAG, "Glide lexicon ready: ${lexicon.size} words for $key")
    }

    private fun readLexicon(facilitator: DictionaryFacilitator): GlideLexicon {
        val builder = GlideLexiconBuilder()
        facilitator.forEachUnigram { word, dictType, locale ->
            if (word == null || dictType == Dictionary.TYPE_EMOJI) return@forEachUnigram false
            accept(builder, word, dictType, locale ?: facilitator.mainLocale)
            false
        }
        addBundledWords(builder)
        return builder.build()
    }

    /** Extra word lists shipped in assets/glide, so Filipino words glide even without a Filipino dictionary. */
    private fun addBundledWords(builder: GlideLexiconBuilder) {
        val app = GlideAssets.context ?: return
        try {
            app.assets.open("glide/fil_words.tsv").bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line[0] == '#') continue
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val word = line.substring(0, tab)
                    val freq = line.substring(tab + 1).trim().toIntOrNull() ?: continue
                    builder.add(word, freq, false)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read bundled glide words", t)
        }
    }

    private fun accept(builder: GlideLexiconBuilder, word: WordProperty, dictType: String, locale: Locale) {
        if (word.mIsNotAWord || word.mIsBeginningOfSentence) return
        val raw = word.mWord ?: return
        val text = normalize(raw, locale, 2, strict = true) ?: return
        val info = word.mProbabilityInfo ?: return
        if (info.mProbability < 0) return
        val bonus = when (dictType) {
            Dictionary.TYPE_USER_HISTORY, Dictionary.TYPE_USER, Dictionary.TYPE_CONTACTS -> 48
            Dictionary.TYPE_APPS -> 16
            else -> 0
        }
        builder.add(text, (info.mProbability + bonus).coerceAtMost(320), word.mIsPossiblyOffensive)
        if (!builder.acceptingBigrams || !word.mHasNgrams) return
        val bigrams = word.bigrams ?: return
        for (bigram in bigrams) {
            val target = bigram.mWord ?: continue
            val probability = bigram.mProbabilityInfo?.mProbability ?: continue
            if (probability < 40) continue
            val next = normalize(target, locale, 2, strict = true) ?: continue
            builder.addBigram(text, next, probability)
            if (!builder.acceptingBigrams) return
        }
    }

    private fun recentlyFailed(key: String): Boolean =
        failedKey == key && System.currentTimeMillis() - failedAt < FAILURE_BACKOFF_MS

    private fun noteFailure(key: String) {
        failedKey = key
        failedAt = System.currentTimeMillis()
    }

    private fun localeKey(facilitator: DictionaryFacilitator): String {
        val tags = facilitator.locales.map { it.toLanguageTag() }.filter { it.isNotEmpty() && it != "und" }.sorted()
        if (tags.isNotEmpty()) return tags.joinToString("|")
        return facilitator.mainLocale.toLanguageTag()
    }

    private class RawHolder(val key: String, val lexicon: GlideLexicon)
}

/** Application context for reading bundled glide word lists; set from LatinIME.onCreate. */
object GlideAssets {
    @Volatile @JvmField var context: android.content.Context? = null
}

internal object GlideGeometryAdapter {
    fun fromKeyboard(keyboard: Keyboard): GlideKeyGeometry {
        val locale = keyboard.mId.locale ?: Locale.ROOT
        val keys = ArrayList<GlideKey>()
        for (key in keyboard.sortedKeys) {
            if (key.isSpacer || key.backgroundType != Key.BACKGROUND_TYPE_NORMAL) continue
            if (key.width <= 0 || key.height <= 0) continue
            val cp = letterCode(key, locale) ?: continue
            keys.add(GlideKey(
                cp,
                key.x + key.width * 0.5f,
                key.y + key.height * 0.5f,
                key.width.toFloat(),
                key.height.toFloat(),
            ))
        }
        return GlideKeyGeometry.fromKeys(keys)
    }

    fun points(pointers: InputPointers): List<GlidePoint> {
        val n = pointers.pointerSize
        if (n <= 0) return emptyList()
        val xs = pointers.xCoordinates
        val ys = pointers.yCoordinates
        val ts = pointers.times
        val out = ArrayList<GlidePoint>(n)
        for (i in 0 until n) out.add(GlidePoint(xs[i].toFloat(), ys[i].toFloat(), ts[i]))
        return out
    }

    private fun letterCode(key: Key, locale: Locale): Int? {
        val output = key.outputText
        val cp = when {
            output != null && output.isNotEmpty() -> {
                if (Character.codePointCount(output, 0, output.length) != 1) return null
                output.codePointAt(0)
            }
            else -> key.code
        }
        if (!Character.isLetter(cp)) return null
        val lower = String(Character.toChars(cp)).lowercase(locale)
        if (lower.codePointCount(0, lower.length) != 1) return null
        val letter = lower.codePointAt(0)
        return if (Character.isLetter(letter)) letter else null
    }
}

/**
 * Batch suggestions from the built-in decoder. Used when the external gesture library is absent.
 * A whole stroke is one word; space-aware multi-word gestures stay on the native path.
 */
object GlideBatchSuggestions {
    private const val TAG = "GlideLexicon"

    /** Set to false (tests) to always use the dictionary facilitator's own gesture results. */
    @Volatile @JvmField var builtInEnabled = true
    private val source = Dictionary.PhonyDictionary(Dictionary.TYPE_MAIN)

    /** Starts loading the word list in the background so the first swipe already has candidates. */
    fun prewarm(keyboard: Keyboard, facilitator: DictionaryFacilitator) {
        try {
            if (GlideLexiconCache.isReadyFor(facilitator)) return
            GlideLexiconCache.compiledFor(facilitator, GlideGeometryAdapter.fromKeyboard(keyboard))
        } catch (t: Throwable) {
            Log.w(TAG, "Glide prewarm failed", t)
        }
    }

    fun suggestionResults(
        wordComposer: WordComposer,
        ngramContext: NgramContext,
        keyboard: Keyboard,
        facilitator: DictionaryFacilitator,
        settingsValuesForSuggestion: SettingsValuesForSuggestion,
    ): SuggestionResults {
        val results = SuggestionResults(SuggestedWords.MAX_SUGGESTIONS, ngramContext, false)
        try {
            val geometry = GlideGeometryAdapter.fromKeyboard(keyboard)
            val lexicon = GlideLexiconCache.compiledFor(facilitator, geometry)
            if (lexicon == null) {
                Log.i(TAG, "Glide: lexicon not ready (keys=${geometry.keyCount})")
                return results
            }
            val points = GlideGeometryAdapter.points(wordComposer.composedDataSnapshot.mInputPointers)
            val locale = keyboard.mId.locale ?: facilitator.mainLocale
            val previous = if (!ngramContext.isValid || ngramContext.isBeginningOfSentenceContext) {
                null
            } else {
                ngramContext.getNthPrevWord(1)?.toString()?.let { normalize(it, locale, 1, strict = false) }
            }
            val ranked = GlideDecoder.decode(
                points, geometry, lexicon, previous, settingsValuesForSuggestion.mBlockPotentiallyOffensive
            )
            Log.i(TAG, "Glide: points=${points.size} keys=${geometry.keyCount} lexicon=${lexicon.wordCount} results=${ranked.size} top=${ranked.firstOrNull()?.word}")
            val context = ngramContext.extractPrevWordsContext()
            for (item in ranked) {
                val info = SuggestedWordInfo(
                    item.word,
                    context,
                    item.score,
                    SuggestedWordInfo.KIND_CORRECTION,
                    source,
                    SuggestedWordInfo.NOT_AN_INDEX,
                    SuggestedWordInfo.NOT_A_CONFIDENCE,
                )
                results.add(info)
                results.mRawSuggestions?.add(info)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Glide decode failed", t)
        }
        return results
    }
}

private fun normalize(raw: String, locale: Locale, minLetters: Int, strict: Boolean): String? {
    val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC).lowercase(locale)
    val sb = StringBuilder(nfc.length)
    var letters = 0
    var i = 0
    while (i < nfc.length) {
        val cp = nfc.codePointAt(i)
        i += Character.charCount(cp)
        when {
            Character.isLetter(cp) -> {
                sb.appendCodePoint(cp)
                letters++
            }
            cp == '\''.code || cp == '-'.code || cp == '\u2019'.code -> sb.appendCodePoint(cp)
            cp == ' '.code || cp == '\n'.code || cp == '\t'.code -> return null
            else -> if (strict) return null
        }
    }
    if (letters < minLetters || letters > 48) return null
    val text = sb.toString()
    var start = 0
    var end = text.length
    while (start < end && !Character.isLetter(text.codePointAt(start))) {
        start += Character.charCount(text.codePointAt(start))
    }
    while (end > start && !Character.isLetter(text.codePointBefore(end))) {
        end -= Character.charCount(text.codePointBefore(end))
    }
    if (start >= end) return null
    return if (start == 0 && end == text.length) text else text.substring(start, end)
}
