package com.stonicx.assistant.tools

import com.stonicx.assistant.StonicXApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Plain (non-live) Gemini text call used by tools that need a "sub-agent" — code edits,
 * content writing, research summaries. Uses the same API key the user already saved.
 */
object SubAgent {
    class Answer(val text: String?, val error: String?)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun ask(
        app: StonicXApp,
        system: String,
        user: String,
        temperature: Double = 0.2,
        model: String = app.preferences.subAgentModel,
        googleSearch: Boolean = false,
        imagesJpeg: List<ByteArray> = emptyList()
    ): Answer = withContext(Dispatchers.IO) {
        val key = app.preferences.apiKey
        if (key.isBlank()) return@withContext Answer(null, "Gemini API key set nahi hai.")
        try {
            val body = JSONObject().apply {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                put("contents", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    val parts = JSONArray().put(JSONObject().put("text", user))
                    imagesJpeg.forEach { img ->
                        parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg")
                            .put("data", android.util.Base64.encodeToString(img, android.util.Base64.NO_WRAP))))
                    }
                    put("parts", parts)
                }))
                put("generationConfig", JSONObject().put("temperature", temperature))
                if (googleSearch) put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            }
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", key)
                .post(body.toString().toRequestBody(JSON)).build()
            client.newCall(req).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val m = try { JSONObject(txt).optJSONObject("error")?.optString("message") } catch (e: Exception) { null }
                    return@withContext Answer(null, "Sub-agent error ${r.code}: ${m ?: txt.take(160)}")
                }
                val parts = JSONObject(txt).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                val sb = StringBuilder()
                if (parts != null) for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text").orEmpty())
                if (sb.isBlank()) Answer(null, "Sub-agent ne koi jawab nahi diya.") else Answer(sb.toString(), null)
            }
        } catch (e: Exception) {
            Answer(null, "Sub-agent tak pahunch nahi paayi: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
