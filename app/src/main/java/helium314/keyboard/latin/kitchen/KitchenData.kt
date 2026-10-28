// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.kitchen

import android.content.Context
import android.os.Handler
import android.os.Looper
import helium314.keyboard.latin.utils.Log
import java.io.BufferedReader
import java.util.Locale

data class KitchenSticker(
    val url: String,
    val cacheFileName: String,
    /** Table key used in the image URL, which can differ from the typed hex by fe0f or order. */
    val leftHex: String,
    val rightHex: String,
)

/**
 * Emoji Kitchen pair table. [pairs.tsv] is parsed on a background thread; [lookup] returns null
 * until that finishes and never waits on the typing thread.
 */
object KitchenData {
    private const val TAG = "KitchenData"
    private const val ASSET = "emoji_kitchen/pairs.tsv"

    private val loadLock = Any()

    @Volatile
    private var pairs: Map<String, String>? = null
    private var loadStarted = false
    private var pendingLoaded: (() -> Unit)? = null

    fun isLoaded(): Boolean = pairs != null

    fun ensureLoaded(context: Context, onLoaded: (() -> Unit)? = null) {
        if (pairs != null) {
            if (onLoaded != null) dispatchMain(onLoaded)
            return
        }
        val app = context.applicationContext
        synchronized(loadLock) {
            if (pairs != null) {
                if (onLoaded != null) dispatchMain(onLoaded)
                return
            }
            if (onLoaded != null) pendingLoaded = onLoaded
            if (loadStarted) return
            loadStarted = true
            Thread({
                val loaded = loadAsset(app)
                val callback: (() -> Unit)?
                synchronized(loadLock) {
                    pairs = loaded
                    callback = pendingLoaded
                    pendingLoaded = null
                }
                Log.i(TAG, "loaded ${loaded.size} emoji kitchen pairs")
                if (callback != null) dispatchMain(callback)
            }, "emoji-kitchen-load").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun lookup(leftHex: String, rightHex: String): KitchenSticker? {
        val map = pairs ?: return null
        return lookupIn(map, leftHex, rightHex)
    }

    fun lookupIn(pairs: Map<String, String>, leftHex: String, rightHex: String): KitchenSticker? {
        val left = leftHex.lowercase(Locale.US)
        val right = rightHex.lowercase(Locale.US)
        find(pairs, left, right)?.let { return it }
        if (left != right) find(pairs, right, left)?.let { return it }
        return null
    }

    fun parsePairs(reader: BufferedReader): HashMap<String, String> {
        val map = HashMap<String, String>(200_000)
        reader.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '#') return@forEachLine
            val tab1 = line.indexOf('\t')
            if (tab1 <= 0) return@forEachLine
            val tab2 = line.indexOf('\t', tab1 + 1)
            if (tab2 < 0) return@forEachLine
            val left = line.substring(0, tab1).trim().lowercase(Locale.US)
            val right = line.substring(tab1 + 1, tab2).trim().lowercase(Locale.US)
            val date = line.substring(tab2 + 1).trim()
            if (left.isEmpty() || right.isEmpty() || date.isEmpty()) return@forEachLine
            map["$left|$right"] = date
        }
        return map
    }

    fun stickerUrl(date: String, leftHex: String, rightHex: String): String {
        val left = urlToken(leftHex)
        val right = urlToken(rightHex)
        return "https://www.gstatic.com/android/keyboard/emojikitchen/$date/$left/${left}_$right.png"
    }

    private fun find(pairs: Map<String, String>, leftHex: String, rightHex: String): KitchenSticker? {
        stickerFor(pairs, leftHex, rightHex)?.let { return it }
        val lefts = fe0fVariants(leftHex)
        val rights = fe0fVariants(rightHex)
        for (left in lefts) {
            for (right in rights) {
                if (left == leftHex && right == rightHex) continue
                stickerFor(pairs, left, right)?.let { return it }
            }
        }
        return null
    }

    private fun stickerFor(pairs: Map<String, String>, leftHex: String, rightHex: String): KitchenSticker? {
        val date = pairs["$leftHex|$rightHex"] ?: return null
        return KitchenSticker(
            url = stickerUrl(date, leftHex, rightHex),
            cacheFileName = "${date}_${leftHex}_${rightHex}.png",
            leftHex = leftHex,
            rightHex = rightHex,
        )
    }

    private fun loadAsset(context: Context): Map<String, String> {
        return try {
            context.assets.open(ASSET).bufferedReader().use { parsePairs(it) }
        } catch (e: Exception) {
            Log.w(TAG, "could not load emoji kitchen pairs", e)
            emptyMap()
        }
    }

    private fun dispatchMain(callback: () -> Unit) {
        val main = Looper.getMainLooper()
        if (main != null && Thread.currentThread() === main.thread) {
            callback()
        } else if (main != null) {
            Handler(main).post(callback)
        }
    }
}

/** Typed order, then the same key with fe0f removed and with fe0f added after emoji bases. */
internal fun fe0fVariants(hex: String): List<String> {
    val out = LinkedHashSet<String>()
    val normalized = hex.lowercase(Locale.US)
    out.add(normalized)
    val parts = normalized.split('-').filter { it.isNotEmpty() && it != "fe0f" }
    if (parts.isEmpty()) return out.toList()
    out.add(parts.joinToString("-"))
    val withAll = ArrayList<String>(parts.size * 2)
    for (part in parts) {
        withAll.add(part)
        if (isEmojiBaseToken(part)) withAll.add("fe0f")
    }
    out.add(withAll.joinToString("-"))
    if (isEmojiBaseToken(parts[0])) {
        out.add((listOf(parts[0], "fe0f") + parts.drop(1)).joinToString("-"))
    }
    return out.toList()
}

private fun isEmojiBaseToken(token: String): Boolean {
    if (token == "200d" || token == "fe0e" || token == "20e3") return false
    return !isSkinTone(token)
}

private fun isSkinTone(token: String): Boolean {
    if (token.length != 5 || !token.startsWith("1f3f")) return false
    return token[4] in 'b'..'f'
}

private fun urlToken(hex: String): String = "u" + hex.replace("-", "-u")
