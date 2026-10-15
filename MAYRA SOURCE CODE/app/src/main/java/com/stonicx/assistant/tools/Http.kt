package com.stonicx.assistant.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Tiny shared HTTP GET for tools that read public web APIs / pages. */
object Http {
    class Res(val code: Int, val body: String) { val ok get() = code in 200..299 }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).followRedirects(true).build()

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): Res = withContext(Dispatchers.IO) {
        try {
            val b = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 9) Mayra/1.0")
            headers.forEach { (k, v) -> b.header(k, v) }
            client.newCall(b.build()).execute().use { r -> Res(r.code, r.body?.string().orEmpty()) }
        } catch (e: Exception) {
            Res(-1, "Net se jawab nahi aaya: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /** Very small HTML -> text: drops script/style/tags, collapses whitespace. */
    fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim()
}
