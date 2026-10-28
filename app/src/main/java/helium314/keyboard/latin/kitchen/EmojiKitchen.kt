// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.kitchen

import android.content.Context
import android.view.inputmethod.EditorInfo
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputConnection
import helium314.keyboard.latin.settings.SettingsValues
import helium314.keyboard.latin.suggestions.SuggestionStripView
import helium314.keyboard.latin.utils.Log

private const val TAG = "EmojiKitchen"
private const val LOOKBEHIND = 64

fun refreshEmojiKitchen(
    strip: SuggestionStripView?,
    settings: SettingsValues?,
    connection: RichInputConnection,
    context: Context,
    reload: Runnable,
) {
    if (strip == null) return
    if (settings == null || !settings.mEmojiKitchenEnabled) {
        strip.setKitchenSticker(null, "")
        return
    }
    val before = try {
        connection.getTextBeforeCursor(LOOKBEHIND, 0)
    } catch (e: Exception) {
        Log.w(TAG, "text before cursor failed", e)
        null
    }
    val pair = extractKitchenEmojiPair(before)
    if (pair == null) {
        strip.setKitchenSticker(null, "")
        return
    }
    if (!KitchenData.isLoaded()) {
        strip.setKitchenSticker(null, "")
        KitchenData.ensureLoaded(context) { reload.run() }
        return
    }
    val sticker = KitchenData.lookup(pair.leftHex, pair.rightHex)
    if (sticker == null) strip.setKitchenSticker(null, "")
    else strip.setKitchenSticker(sticker, pair.text)
}

fun pickKitchenSticker(
    context: Context,
    connection: RichInputConnection,
    editorInfo: EditorInfo?,
    switcher: KeyboardSwitcher,
    sticker: KitchenSticker,
    emojiText: String,
    onFinished: Runnable,
) {
    KitchenImageCache.fetchFile(context, sticker) { file ->
        try {
            if (file != null) {
                val result = KitchenCommit.commit(context, connection, editorInfo, file, emojiText)
                if (result == KitchenCommit.Result.COPIED) {
                    switcher.showToast(context.getString(R.string.emoji_kitchen_copied), true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "kitchen pick failed", e)
        } finally {
            onFinished.run()
        }
    }
}
