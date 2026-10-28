// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.kitchen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmojiKitchenTest {
    @Test fun heartAndFireAtEndOfText() {
        val pair = extractKitchenEmojiPair("hello $HEART$FIRE")
        assertNotNull(pair)
        assertEquals("2764-fe0f", pair.leftHex)
        assertEquals("1f525", pair.rightHex)
        assertEquals(HEART + FIRE, pair.text)
        assertEquals(4, pair.text.length)
    }

    @Test fun lastTwoOfThreeEmojis() {
        val pair = extractKitchenEmojiPair("$SMILE$HEART$FIRE")
        assertNotNull(pair)
        assertEquals("2764-fe0f", pair.leftHex)
        assertEquals("1f525", pair.rightHex)
    }

    @Test fun zwjClusterStaysIntact() {
        assertNull(extractKitchenEmojiPair(HEART_ON_FIRE))
        val pair = extractKitchenEmojiPair(EXHALE + FIRE)
        assertNotNull(pair)
        assertEquals("1f62e-200d-1f4a8", pair.leftHex)
        assertEquals("1f525", pair.rightHex)
        val heartOnFireThenTree = extractKitchenEmojiPair(HEART_ON_FIRE + TREE)
        assertNotNull(heartOnFireThenTree)
        assertEquals("2764-fe0f-200d-1f525", heartOnFireThenTree.leftHex)
        assertEquals("1f384", heartOnFireThenTree.rightHex)
    }

    @Test fun skinToneStaysOnItsEmoji() {
        val pair = extractKitchenEmojiPair(THUMB_LIGHT + FIRE)
        assertNotNull(pair)
        assertEquals("1f44d-1f3fb", pair.leftHex)
        assertEquals("1f525", pair.rightHex)
    }

    @Test fun rejectsFlagsSpacesAndASingleEmoji() {
        assertNull(extractKitchenEmojiPair(HEART))
        assertNull(extractKitchenEmojiPair(HEART + FIRE + " "))
        assertNull(extractKitchenEmojiPair("$HEART $FIRE"))
        assertNull(extractKitchenEmojiPair("plain"))
        assertNull(extractKitchenEmojiPair(FLAG_US + FIRE))
        assertNull(extractKitchenEmojiPair(FIRE + FLAG_US))
        assertNull(extractKitchenEmojiPair(FLAG_US + FLAG_US))
    }

    @Test fun smileWithVariationSelector() {
        val pair = extractKitchenEmojiPair("\u263a\uFE0F$FIRE")
        assertNotNull(pair)
        assertEquals("263a-fe0f", pair.leftHex)
        assertEquals("1f525", pair.rightHex)
    }

    @Test fun urlUsesTableCodepoints() {
        assertEquals(
            "https://www.gstatic.com/android/keyboard/emojikitchen/20230821/u2764-ufe0f/u2764-ufe0f_u1f525.png",
            KitchenData.stickerUrl("20230821", "2764-fe0f", "1f525"),
        )
        assertEquals(
            "https://www.gstatic.com/android/keyboard/emojikitchen/20210218/u1f62e-u200d-u1f4a8/u1f62e-u200d-u1f4a8_u1f525.png",
            KitchenData.stickerUrl("20210218", "1f62e-200d-1f4a8", "1f525"),
        )
    }

    @Test fun fe0fAndSwappedOrderUseTheTableKey() {
        val map = mapOf(
            "2764-fe0f|1f525" to "20230821",
            "2615|1f525" to "20211111",
            "aaaa|bbbb" to "20200101",
            "bbbb|aaaa" to "20200202",
        )
        val added = KitchenData.lookupIn(map, "2764", "1f525")
        assertNotNull(added)
        assertEquals("2764-fe0f", added.leftHex)
        assertEquals("1f525", added.rightHex)
        assertEquals(KitchenData.stickerUrl("20230821", "2764-fe0f", "1f525"), added.url)
        assertEquals("20230821_2764-fe0f_1f525.png", added.cacheFileName)

        val stripped = KitchenData.lookupIn(map, "2615-fe0f", "1f525-fe0f")
        assertNotNull(stripped)
        assertEquals("2615", stripped.leftHex)
        assertEquals("1f525", stripped.rightHex)
        assertEquals(
            "https://www.gstatic.com/android/keyboard/emojikitchen/20211111/u2615/u2615_u1f525.png",
            stripped.url,
        )

        val swapped = KitchenData.lookupIn(map, "1f525", "2764-fe0f")
        assertNotNull(swapped)
        assertEquals("2764-fe0f", swapped.leftHex)
        assertEquals("1f525", swapped.rightHex)

        assertEquals("aaaa", KitchenData.lookupIn(map, "aaaa", "bbbb")!!.leftHex)
        assertEquals("bbbb", KitchenData.lookupIn(map, "bbbb", "aaaa")!!.leftHex)
        assertNull(KitchenData.lookupIn(map, "1111", "2222"))
    }

    @Test fun realPairsFileHeartFireAndZwj() {
        val pairs = realPairs()
        val heart = KitchenData.lookupIn(pairs, "2764-fe0f", "1f525")
        assertNotNull(heart)
        assertEquals(
            "https://www.gstatic.com/android/keyboard/emojikitchen/20230821/u2764-ufe0f/u2764-ufe0f_u1f525.png",
            heart.url,
        )
        assertEquals(heart.url, KitchenData.lookupIn(pairs, "2764", "1f525")!!.url)
        assertEquals(heart.url, KitchenData.lookupIn(pairs, "1f525", "2764-fe0f")!!.url)

        val smile = KitchenData.lookupIn(pairs, "263a", "1f525")
        assertNotNull(smile)
        assertEquals("263a-fe0f", smile.leftHex)
        assertEquals(
            "https://www.gstatic.com/android/keyboard/emojikitchen/20201001/u263a-ufe0f/u263a-ufe0f_u1f525.png",
            smile.url,
        )

        val typed = extractKitchenEmojiPair(EXHALE + FIRE)
        assertNotNull(typed)
        val zwj = KitchenData.lookupIn(pairs, typed.leftHex, typed.rightHex)
        assertNotNull(zwj)
        assertEquals(
            "https://www.gstatic.com/android/keyboard/emojikitchen/20210218/u1f62e-u200d-u1f4a8/u1f62e-u200d-u1f4a8_u1f525.png",
            zwj.url,
        )
        assertEquals(zwj.url, KitchenData.lookupIn(pairs, "1f62e-fe0f-200d-1f4a8", "1f525")!!.url)
        assertTrue(pairs.size > 100_000)
    }

    private fun realPairs(): Map<String, String> {
        val cached = pairsCache
        if (cached != null) return cached
        val loaded = findPairsTsv().bufferedReader().use { KitchenData.parsePairs(it) }
        pairsCache = loaded
        return loaded
    }

    private fun findPairsTsv(): File {
        val relative = listOf(
            "app/src/main/assets/emoji_kitchen/pairs.tsv",
            "src/main/assets/emoji_kitchen/pairs.tsv",
        )
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val current = dir ?: return@repeat
            for (path in relative) {
                val file = File(current, path)
                if (file.isFile) return file
            }
            dir = current.parentFile
        }
        throw IllegalStateException("pairs.tsv not found from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val HEART = "\u2764\uFE0F"
        const val FIRE = "\uD83D\uDD25"
        const val EXHALE = "\uD83D\uDE2E\u200D\uD83D\uDCA8"
        const val HEART_ON_FIRE = "\u2764\uFE0F\u200D\uD83D\uDD25"
        const val TREE = "\uD83C\uDF84"
        const val THUMB_LIGHT = "\uD83D\uDC4D\uD83C\uDFFB"
        const val SMILE = "\uD83D\uDE00"
        const val FLAG_US = "\uD83C\uDDFA\uD83C\uDDF8"

        var pairsCache: Map<String, String>? = null
    }
}
