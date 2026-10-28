/*
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin.glide

import java.text.Normalizer
import java.util.Locale

/** One lexicon word. [frequency] is a non-negative unigram weight, higher is more common. */
data class GlideLexiconEntry(
    val word: String,
    val frequency: Int,
    val offensive: Boolean = false,
)

/**
 * Lowercase words, frequencies, and optional bigrams for one set of locales.
 * [compile] turns this into key-index tables for a specific keyboard. That table is what
 * [GlideDecoder] reads, so a layout change does not require reading the dictionary again
 * when the letter set is unchanged... a different letter set does need a new compile.
 */
class GlideLexicon internal constructor(
    val words: Array<String>,
    val frequency: IntArray,
    val offensive: BooleanArray,
    val bigrams: Map<String, Map<String, Int>>,
) {
    val size: Int get() = words.size

    fun compile(geometry: GlideKeyGeometry, localeKey: String): CompiledGlideLexicon {
        val codes = geometry.codes.copyOf()
        val keyCount = geometry.keyCount
        if (keyCount == 0 || words.isEmpty()) return CompiledGlideLexicon.empty(localeKey, codes, bigrams)
        val scratch = IntArray(MAX_LETTERS)
        val lengths = IntArray(words.size)
        val first = IntArray(words.size)
        val last = IntArray(words.size)
        var kept = 0
        var indexCount = 0
        for (w in words.indices) {
            val n = collapse(words[w], geometry, scratch)
            if (n < 2) continue
            lengths[w] = n
            first[w] = scratch[0]
            last[w] = scratch[n - 1]
            indexCount += n
            kept++
        }
        if (kept == 0) return CompiledGlideLexicon.empty(localeKey, codes, bigrams)
        val buckets = keyCount * keyCount
        val counts = IntArray(buckets)
        for (w in words.indices) {
            if (lengths[w] == 0) continue
            counts[first[w] * keyCount + last[w]]++
        }
        val bucketStart = IntArray(buckets + 1)
        var sum = 0
        for (b in 0 until buckets) {
            bucketStart[b] = sum
            sum += counts[b]
        }
        bucketStart[buckets] = sum
        val cursor = bucketStart.copyOf(buckets)
        val bucketWords = IntArray(kept)
        val keyIndices = IntArray(indexCount)
        val indexOffset = IntArray(kept + 1)
        val outWords = Array(kept) { "" }
        val outFreq = IntArray(kept)
        val outOff = BooleanArray(kept)
        var id = 0
        var indexCursor = 0
        for (w in words.indices) {
            val n = lengths[w]
            if (n == 0) continue
            collapse(words[w], geometry, scratch)
            indexOffset[id] = indexCursor
            for (i in 0 until n) keyIndices[indexCursor++] = scratch[i]
            outWords[id] = words[w]
            outFreq[id] = frequency[w]
            outOff[id] = offensive[w]
            val slot = cursor[first[w] * keyCount + last[w]]++
            bucketWords[slot] = id
            id++
        }
        indexOffset[id] = indexCursor
        return CompiledGlideLexicon(
            localeKey, codes, outWords, outFreq, outOff, indexOffset, keyIndices, bucketStart, bucketWords, bigrams
        )
    }

    companion object {
        private const val MAX_LETTERS = 64

        fun fromEntries(
            entries: Iterable<GlideLexiconEntry>,
            bigrams: Map<String, Map<String, Int>> = emptyMap(),
        ): GlideLexicon {
            val builder = GlideLexiconBuilder()
            for (entry in entries) {
                val word = Normalizer.normalize(entry.word, Normalizer.Form.NFC).lowercase(Locale.ROOT)
                builder.add(word, entry.frequency, entry.offensive)
            }
            for ((previous, row) in bigrams) {
                val prev = Normalizer.normalize(previous, Normalizer.Form.NFC).lowercase(Locale.ROOT)
                for ((next, probability) in row) {
                    val target = Normalizer.normalize(next, Normalizer.Form.NFC).lowercase(Locale.ROOT)
                    builder.addBigram(prev, target, probability)
                }
            }
            return builder.build()
        }

        /**
         * Collapses a word onto letter-key indices. Repeated letters, including doubles such as
         * "ll", are one visit. Apostrophe and hyphen are ignored so a contraction still glides.
         * Returns 0 when a letter is not on this keyboard.
         */
        private fun collapse(word: String, geometry: GlideKeyGeometry, out: IntArray): Int {
            var n = 0
            var prev = -1
            var i = 0
            while (i < word.length) {
                val cp = word.codePointAt(i)
                i += Character.charCount(cp)
                if (cp == '\''.code || cp == '-'.code || cp == '\u2019'.code) continue
                val key = geometry.indexOf(cp)
                if (key < 0) return 0
                if (key == prev) continue
                if (n == out.size) return 0
                out[n++] = key
                prev = key
            }
            return n
        }
    }
}

internal class GlideLexiconBuilder {
    private val order = ArrayList<String>()
    private val freq = ArrayList<Int>()
    private val off = ArrayList<Boolean>()
    private val index = HashMap<String, Int>()
    private val bigrams = HashMap<String, HashMap<String, Int>>()
    private var bigramCount = 0

    val acceptingBigrams: Boolean get() = bigramCount < MAX_BIGRAMS

    fun add(word: String, frequency: Int, offensive: Boolean) {
        if (word.isEmpty() || word.indexOf(' ') >= 0) return
        val value = frequency.coerceAtLeast(0)
        val existing = index[word]
        if (existing != null) {
            if (value > freq[existing]) freq[existing] = value
            if (offensive) off[existing] = true
            return
        }
        if (order.size >= MAX_WORDS) return
        index[word] = order.size
        order.add(word)
        freq.add(value)
        off.add(offensive)
    }

    fun addBigram(previous: String, next: String, probability: Int) {
        if (probability < MIN_BIGRAM || previous.isEmpty() || next.isEmpty()) return
        var row = bigrams[previous]
        if (row == null) {
            if (bigramCount >= MAX_BIGRAMS) return
            row = HashMap()
            bigrams[previous] = row
        }
        val old = row[next]
        if (old == null) {
            if (bigramCount >= MAX_BIGRAMS) return
            row[next] = probability
            bigramCount++
        } else if (probability > old) {
            row[next] = probability
        }
    }

    fun build(): GlideLexicon {
        val keptBigrams = HashMap<String, Map<String, Int>>(bigrams.size)
        for ((previous, row) in bigrams) {
            if (previous !in index) continue
            val kept = HashMap<String, Int>()
            for ((next, probability) in row) {
                if (next in index && probability > 0) kept[next] = probability
            }
            if (kept.isNotEmpty()) keptBigrams[previous] = kept
        }
        return GlideLexicon(
            order.toTypedArray(),
            freq.toIntArray(),
            BooleanArray(off.size) { off[it] },
            keptBigrams,
        )
    }

    private companion object {
        const val MAX_WORDS = 100_000
        const val MAX_BIGRAMS = 40_000
        const val MIN_BIGRAM = 40
    }
}

/**
 * Words packed by (first key, last key) for the keyboard they were compiled against.
 * [codes] is the sorted letter set, so a resize that keeps the same letters can reuse this.
 */
class CompiledGlideLexicon(
    val localeKey: String,
    val codes: IntArray,
    val words: Array<String>,
    val frequency: IntArray,
    val offensive: BooleanArray,
    val indexOffset: IntArray,
    val keyIndices: IntArray,
    val bucketStart: IntArray,
    val bucketWords: IntArray,
    val bigrams: Map<String, Map<String, Int>>,
) {
    val wordCount: Int get() = words.size

    fun bigram(previous: String, word: String): Int = bigrams[previous]?.get(word) ?: 0

    companion object {
        fun empty(localeKey: String, codes: IntArray, bigrams: Map<String, Map<String, Int>>) = CompiledGlideLexicon(
            localeKey,
            codes,
            emptyArray(),
            IntArray(0),
            BooleanArray(0),
            intArrayOf(0),
            IntArray(0),
            IntArray(1),
            IntArray(0),
            bigrams,
        )
    }
}
