// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.kitchen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import helium314.keyboard.latin.net.TekladoNet
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.util.concurrent.Executors

/** PNG files in cacheDir/emoji_kitchen, trimmed to 40 MB, plus a small bitmap cache for the strip. */
object KitchenImageCache {
    private const val TAG = "KitchenImageCache"
    const val CACHE_DIR = "emoji_kitchen"
    private const val MAX_DISK_BYTES = 40L * 1024 * 1024
    private const val MAX_BITMAP_BYTES = 2 * 1024 * 1024

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "emoji-kitchen-net").apply { isDaemon = true }
    }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val thumbnails = object : LruCache<String, Bitmap>(MAX_BITMAP_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }

    fun loadThumbnail(context: Context, sticker: KitchenSticker, heightPx: Int, onBitmap: (Bitmap?) -> Unit) {
        val cached = synchronized(thumbnails) { thumbnails.get(sticker.cacheFileName) }
        if (cached != null && !cached.isRecycled) {
            onBitmap(cached)
            return
        }
        val app = context.applicationContext
        val target = heightPx.coerceAtLeast(1)
        executor.execute {
            val bitmap = try {
                val file = ensureFile(app, sticker)
                val decoded = file?.let { decodeScaled(it, target) }
                if (file != null && decoded == null) file.delete()
                if (decoded != null) synchronized(thumbnails) { thumbnails.put(sticker.cacheFileName, decoded) }
                decoded
            } catch (e: Exception) {
                Log.w(TAG, "thumbnail failed", e)
                null
            }
            mainHandler.post { onBitmap(bitmap) }
        }
    }

    fun fetchFile(context: Context, sticker: KitchenSticker, onFile: (File?) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val file = try {
                ensureFile(app, sticker)
            } catch (e: Exception) {
                Log.w(TAG, "cache fetch failed", e)
                null
            }
            mainHandler.post { onFile(file) }
        }
    }

    private fun ensureFile(context: Context, sticker: KitchenSticker): File? {
        val name = sticker.cacheFileName
        if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val dir = File(context.cacheDir, CACHE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val file = File(dir, name)
        if (file.isFile && file.length() > 0L) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        val bytes = TekladoNet.fetch(sticker.url) ?: return null
        if (!isPng(bytes)) return null
        val tmp = File(dir, "$name.tmp")
        try {
            tmp.writeBytes(bytes)
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            Log.w(TAG, "cache write failed", e)
            return null
        }
        trim(dir)
        return if (file.isFile && file.length() > 0L) file else null
    }

    private fun trim(dir: File) {
        val files = dir.listFiles { candidate -> candidate.isFile && !candidate.name.endsWith(".tmp") } ?: return
        var total = 0L
        for (file in files) total += file.length()
        if (total <= MAX_DISK_BYTES) return
        val oldestFirst = files.sortedBy { it.lastModified() }
        for (file in oldestFirst) {
            if (total <= MAX_DISK_BYTES) break
            val len = file.length()
            if (file.delete()) total -= len
        }
    }

    private fun decodeScaled(file: File, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outHeight <= 0 || bounds.outWidth <= 0) return null
        var sample = 1
        while (sample < 64 && bounds.outHeight / sample > targetHeight * 2) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        if (decoded.height <= targetHeight) return decoded
        val width = (decoded.width * (targetHeight.toFloat() / decoded.height)).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(decoded, width, targetHeight, true)
        if (scaled != decoded) decoded.recycle()
        return scaled
    }

    private fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        return bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
    }
}
