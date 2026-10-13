package com.stonicx.assistant.tools.coding

import com.stonicx.assistant.StonicXApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Thin GitHub REST client. Token comes from encrypted preferences; public reads work without one. */
class GitHubApi(private val app: StonicXApp) {

    class Resp(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
        fun obj(): JSONObject? = try { JSONObject(body) } catch (e: Exception) { null }
        fun arr(): JSONArray? = try { JSONArray(body) } catch (e: Exception) { null }
        fun errorText(): String {
            val m = obj()?.optString("message").orEmpty()
            return when {
                code == 401 -> "GitHub token galat ya expire hai (401)."
                code == 403 && body.contains("rate limit", true) -> "GitHub rate limit lag gayi — token lagao ya thodi der baad try karo."
                code == 403 -> "GitHub ne mana kar diya (403): ${m.ifBlank { "token ke paas permission nahi" }}"
                code == 404 -> "Nahi mila (404) — naam/branch galat hai ya private repo ka token nahi hai."
                code == 409 -> "Conflict (409): ${m.ifBlank { "branch ya file badal chuki hai" }}"
                code == 422 -> "GitHub ne request reject ki (422): ${m.ifBlank { body.take(200) }}"
                code == -1 -> body
                else -> "GitHub error $code: ${m.ifBlank { body.take(200) }}"
            }
        }
    }

    val token: String get() = app.preferences.githubToken
    val hasToken: Boolean get() = token.isNotBlank()

    suspend fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        accept: String = "application/vnd.github+json"
    ): Resp = withContext(Dispatchers.IO) {
        try {
            val url = if (path.startsWith("http")) path else API + path
            val rb = body?.toString()?.toRequestBody(JSON)
            val req = Request.Builder().url(url)
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Mayra-Assistant")
                .apply { if (hasToken) header("Authorization", "Bearer $token") }
                .method(method, if (method == "GET" || method == "HEAD") null else (rb ?: "".toRequestBody(JSON)))
                .build()
            client.newCall(req).execute().use { r -> Resp(r.code, r.body?.string().orEmpty()) }
        } catch (e: Exception) {
            Resp(-1, "GitHub tak pahunch nahi paayi (net check karo): ${e.message ?: e.javaClass.simpleName}")
        }
    }

    suspend fun get(path: String) = call("GET", path)
    suspend fun post(path: String, body: JSONObject) = call("POST", path, body)
    suspend fun patch(path: String, body: JSONObject) = call("PATCH", path, body)
    suspend fun put(path: String, body: JSONObject) = call("PUT", path, body)
    suspend fun delete(path: String, body: JSONObject? = null) = call("DELETE", path, body)

    suspend fun username(): String? {
        val cached = app.preferences.githubUsername
        if (cached.isNotBlank()) return cached
        if (!hasToken) return null
        val r = get("/user")
        val login = r.obj()?.optString("login").orEmpty()
        if (r.ok && login.isNotBlank()) app.preferences.githubUsername = login
        return login.ifBlank { null }
    }

    /** "owner/name", "name", or a github.com URL -> (owner, name). */
    suspend fun resolve(input: String): Pair<String, String>? {
        var s = input.trim().removeSuffix("/").removeSuffix(".git")
        s = s.substringAfter("github.com/", s).substringAfter("github.com:", s)
        val parts = s.split("/").filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> parts[0] to parts[1]
            parts.size == 1 -> username()?.let { it to parts[0] }
            else -> null
        }
    }

    suspend fun defaultBranch(owner: String, name: String): String? =
        get("/repos/$owner/$name").obj()?.optString("default_branch")?.ifBlank { null }

    /** Stream a (zipball) download to [dest]. Returns null on success or an error text. */
    suspend fun download(path: String, dest: File): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(if (path.startsWith("http")) path else API + path)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Mayra-Assistant")
                .apply { if (hasToken) header("Authorization", "Bearer $token") }
                .build()
            downloadClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext Resp(r.code, r.body?.string().orEmpty()).errorText()
                dest.parentFile?.mkdirs()
                r.body!!.byteStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
            null
        } catch (e: Exception) {
            "Download fail: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    companion object {
        const val API = "https://api.github.com"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS).build()
        private val downloadClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()
    }
}
