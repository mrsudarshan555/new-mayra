package com.stonicx.assistant.coding.agent

import com.stonicx.assistant.StonicXApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Streams a Gemini answer token by token (SSE) so the coding overlay can show it as it is being written. */
object GeminiStream {
    class Result(val text: String?, val error: String?, val retryAfterSec: Int = 0, val truncated: Boolean = false)

    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun run(app: StonicXApp, system: String, user: String, temperature: Double = 0.3, maxTokens: Int = 24000, onDelta: (String) -> Unit): Result =
        withContext(Dispatchers.IO) {
            val key = app.preferences.apiKey
            if (key.isBlank()) return@withContext Result(null, "Gemini API key set nahi hai.")
            val body = JSONObject().apply {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
                put("generationConfig", JSONObject().put("temperature", temperature).put("maxOutputTokens", maxTokens))
            }
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/${app.preferences.subAgentModel}:streamGenerateContent?alt=sse")
                .header("x-goog-api-key", key).post(body.toString().toRequestBody(JSON)).build()
            val call = client.newCall(req)
            try {
                call.execute().use { r ->
                    if (!r.isSuccessful) {
                        val txt = r.body?.string().orEmpty()
                        val msg = try { JSONObject(txt).optJSONObject("error")?.optString("message") } catch (e: Exception) { null }
                        val retry = Regex("retry in ([0-9]+)").find(msg.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: if (r.code == 429) 20 else 0
                        return@withContext Result(null, "Coding brain error ${r.code}: ${msg ?: txt.take(150)}", retry)
                    }
                    val sb = StringBuilder(); var finish = ""
                    val src = r.body!!.source()
                    while (true) {
                        coroutineContext.ensureActive()
                        val line = src.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim(); if (payload.isEmpty() || payload == "[DONE]") continue
                        val cand = try { JSONObject(payload).optJSONArray("candidates")?.optJSONObject(0) } catch (e: Exception) { null } ?: continue
                        cand.optJSONObject("content")?.optJSONArray("parts")?.let { ps ->
                            for (i in 0 until ps.length()) ps.optJSONObject(i)?.optString("text")?.takeIf { it.isNotEmpty() && !ps.optJSONObject(i).optBoolean("thought") }?.let { sb.append(it); onDelta(it) }
                        }
                        cand.optString("finishReason").takeIf { it.isNotBlank() }?.let { finish = it }
                    }
                    if (sb.isEmpty()) Result(null, "Coding brain se jawab nahi aaya" + if (finish.isNotBlank()) " ($finish)" else "")
                    else Result(sb.toString(), null, truncated = finish == "MAX_TOKENS")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                call.cancel(); throw e
            } catch (e: Exception) {
                Result(null, "Coding brain tak pahunch nahi paayi: ${e.message ?: e.javaClass.simpleName}")
            }
        }
}
