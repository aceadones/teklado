// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.kitchen

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.view.inputmethod.EditorInfo
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import helium314.keyboard.latin.RichInputConnection
import helium314.keyboard.latin.utils.Log
import java.io.File

object KitchenCommit {
    private const val TAG = "KitchenCommit"

    enum class Result { INSERTED, COPIED, FAILED }

    /**
     * Inserts [file] at the cursor when the editor accepts image/png.
     * The typed emoji pair is removed first and put back if commitContent returns false.
     * Otherwise the PNG is placed on the clipboard as a content URI.
     */
    fun commit(
        context: Context,
        connection: RichInputConnection,
        editorInfo: EditorInfo?,
        file: File,
        emojiText: String,
    ): Result {
        val uri = try {
            FileProvider.getUriForFile(context, kitchenProviderAuthority(), file)
        } catch (e: Exception) {
            Log.w(TAG, "kitchen uri failed", e)
            return Result.FAILED
        }
        val description = ClipDescription("Emoji Kitchen", arrayOf("image/png"))
        if (editorInfo != null && editorAcceptsPng(editorInfo, description)) {
            val inserted = insertReplacingEmojis(connection, editorInfo, uri, description, emojiText)
            if (inserted) return Result.INSERTED
        }
        return if (copyToClipboard(context, uri)) Result.COPIED else Result.FAILED
    }

    private fun insertReplacingEmojis(
        connection: RichInputConnection,
        editorInfo: EditorInfo,
        uri: Uri,
        description: ClipDescription,
        emojiText: String,
    ): Boolean {
        var deleted = false
        connection.beginBatchEdit()
        try {
            if (emojiText.isNotEmpty()) {
                connection.deleteTextBeforeCursor(emojiText.length)
                deleted = true
            }
            val info = InputContentInfoCompat(uri, description, null)
            val committed = connection.commitContent(info, editorInfo)
            if (committed) return true
            if (deleted) {
                connection.commitText(emojiText, 1)
                deleted = false
            }
            return false
        } catch (e: Exception) {
            Log.w(TAG, "kitchen insert failed", e)
            if (deleted) {
                try {
                    connection.commitText(emojiText, 1)
                } catch (restore: Exception) {
                    Log.w(TAG, "could not restore emoji pair", restore)
                }
            }
            return false
        } finally {
            connection.endBatchEdit()
        }
    }

    private fun editorAcceptsPng(editorInfo: EditorInfo, description: ClipDescription): Boolean {
        return EditorInfoCompat.getContentMimeTypes(editorInfo).any { mime ->
            mime.isNotEmpty() && description.hasMimeType(mime)
        }
    }

    private fun copyToClipboard(context: Context, uri: Uri): Boolean {
        return try {
            val clip = ClipData.newUri(context.contentResolver, "Emoji Kitchen", uri)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            Log.w(TAG, "kitchen clipboard failed", e)
            false
        }
    }
}
