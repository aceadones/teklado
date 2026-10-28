// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.kitchen

import helium314.keyboard.latin.common.StringUtils.mightBeEmoji
import helium314.keyboard.latin.common.singleEmojiRegex

/**
 * The two emoji clusters at the end of the text before the cursor, when that text ends with
 * emoji+emoji. Geographic flags (regional indicators and tag sequences) are not supported.
 */
data class EmojiPair(
    val leftHex: String,
    val rightHex: String,
    /** The two clusters as typed, so a failed image insert can put them back. */
    val text: String,
)

private const val LOOKBEHIND_CHARS = 64

fun extractKitchenEmojiPair(text: CharSequence?): EmojiPair? {
    if (text.isNullOrEmpty()) return null
    val s = text.toString()
    if (!couldEndWithEmoji(s)) return null
    var from = (s.length - LOOKBEHIND_CHARS).coerceAtLeast(0)
    if (from > 0 && s[from].isLowSurrogate()) from++
    val window = s.substring(from)
    val matches = singleEmojiRegex.findAll(window).toList()
    if (matches.size < 2) return null
    val right = matches[matches.size - 1]
    val left = matches[matches.size - 2]
    if (right.range.last != window.lastIndex) return null
    if (left.range.last + 1 != right.range.first) return null
    if (isUnsupportedFlag(left.value) || isUnsupportedFlag(right.value)) return null
    return EmojiPair(
        leftHex = codePointsToHex(left.value),
        rightHex = codePointsToHex(right.value),
        text = left.value + right.value,
    )
}

private fun couldEndWithEmoji(text: String): Boolean {
    if (text.isEmpty()) return false
    val cp = Character.codePointBefore(text, text.length)
    if (cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3) return true
    if (cp in 0x1F3FB..0x1F3FF) return true
    if (cp in 0xE0020..0xE007F) return true
    return mightBeEmoji(cp)
}

/** Country flags and subdivision-flag tag sequences. */
private fun isUnsupportedFlag(cluster: String): Boolean {
    var i = 0
    while (i < cluster.length) {
        val cp = Character.codePointAt(cluster, i)
        if (cp in 0x1F1E6..0x1F1FF) return true
        if (cp in 0xE0020..0xE007F) return true
        i += Character.charCount(cp)
    }
    return false
}

private fun codePointsToHex(cluster: String): String {
    val parts = ArrayList<String>(4)
    var i = 0
    while (i < cluster.length) {
        val cp = Character.codePointAt(cluster, i)
        parts.add(Integer.toHexString(cp))
        i += Character.charCount(cp)
    }
    return parts.joinToString("-")
}
