package com.collabcode.app.data

import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val code: Int, message: String) : Exception(message)

data class HttpResult(val code: Int, val body: String, val location: String? = null)

object Http {
    private const val NO_NETWORK = "No network connection. Check your internet and try again."

    /**
     * Only https calls to [allowedHost] are made, and redirects are never followed here,
     * so the Authorization header can never leak to another host.
     */
    fun call(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        allowedHost: String,
    ): HttpResult {
        val u = URL(url)
        require(u.protocol == "https" && u.host == allowedHost) { "Blocked request to an unexpected host." }

        val conn = u.openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "CollabCode/0.3")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val location = conn.getHeaderField("Location")
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return HttpResult(code, text, location)
        } catch (e: IOException) {
            throw ApiException(0, NO_NETWORK)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Downloads a pre-signed https URL (artifact or log) into [out]. No secrets are sent.
     * Follows up to 4 redirects by hand. Returns the number of bytes written.
     */
    fun download(url: String, out: OutputStream, maxBytes: Long): Long {
        var current = url
        for (hop in 0..4) {
            val u = URL(current)
            require(u.protocol == "https") { "Blocked non-https download." }
            val conn = u.openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", "CollabCode/0.3")
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw ApiException(code, "Download redirect had no address.")
                    current = URL(u, loc).toString()
                    continue
                }
                if (code !in 200..299) throw ApiException(code, "Download failed (HTTP $code).")
                var total = 0L
                conn.inputStream.use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw ApiException(0, "The file is too large to download.")
                        out.write(buf, 0, n)
                    }
                }
                return total
            } catch (e: IOException) {
                throw ApiException(0, NO_NETWORK)
            } finally {
                conn.disconnect()
            }
        }
        throw ApiException(0, "Too many redirects.")
    }
}
