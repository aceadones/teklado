// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.net

import android.os.Looper
import helium314.keyboard.latin.utils.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * The only HTTP client in TEKLADO. HTTPS, an explicit host allowlist, and a hard response cap.
 * Returns null on the main thread, on any disallowed URL, and on any network or protocol failure.
 */
object TekladoNet {
    private const val TAG = "TekladoNet"
    private const val TIMEOUT_MS = 8_000
    private const val MAX_BYTES = 5 * 1024 * 1024

    /**
     * Hosts this client may contact. Adding a host later is a one-line change.
     * api.groq.com and openrouter.ai are the expected next entries.
     */
    private val ALLOWED_HOSTS = setOf(
        "www.gstatic.com",
        // "api.groq.com",
        // "openrouter.ai",
    )

    fun fetch(url: String): ByteArray? {
        if (isMainThread()) {
            Log.w(TAG, "refusing network on the main thread")
            return null
        }
        if (url.isEmpty() || url.any { it.isWhitespace() }) return null
        val parsed = try {
            URL(url)
        } catch (e: Exception) {
            return null
        }
        if (!parsed.protocol.equals("https", ignoreCase = true)) return null
        if (parsed.userInfo != null) return null
        if (parsed.port != -1 && parsed.port != 443) return null
        val host = parsed.host?.lowercase(Locale.US) ?: return null
        if (host !in ALLOWED_HOSTS) return null

        var connection: HttpURLConnection? = null
        try {
            connection = parsed.openConnection() as? HttpURLConnection ?: return null
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doInput = true
            connection.doOutput = false
            connection.setRequestProperty("User-Agent", "TEKLADO")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val declared = connection.contentLength
            if (declared > MAX_BYTES) return null
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream(if (declared > 0) declared else 16 * 1024)
                val buf = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return null
                    out.write(buf, 0, n)
                }
                val bytes = out.toByteArray()
                return if (bytes.isEmpty()) null else bytes
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed", e)
            return null
        } finally {
            connection?.disconnect()
        }
    }

    private fun isMainThread(): Boolean {
        val main = Looper.getMainLooper() ?: return false
        return Thread.currentThread() === main.thread
    }
}
