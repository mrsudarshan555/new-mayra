package com.stonicx.assistant.tools.creator

import android.content.Context
import android.util.Base64
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

/** Gemini image generation / editing through the same API key the app already uses. */
object ImageGen {
    class Result(val file: File?, val error: String?, val note: String = "")

    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val MODEL = "gemini-2.5-flash-image"

    fun outDir(ctx: Context): File = (ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: File(ctx.filesDir, "pictures")).apply { mkdirs() }

    suspend fun run(app: StonicXApp, prompt: String, input: File? = null): Result = withContext(Dispatchers.IO) {
        val key = app.preferences.apiKey
        if (key.isBlank()) return@withContext Result(null, "Gemini API key set nahi hai.")
        try {
            val parts = JSONArray().put(JSONObject().put("text", prompt))
            if (input != null) {
                if (input.length() > 6_000_000) return@withContext Result(null, "Photo bahut badi hai (6 MB se zyada).")
                val mime = if (input.extension.lowercase() == "png") "image/png" else "image/jpeg"
                parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", mime).put("data", Base64.encodeToString(input.readBytes(), Base64.NO_WRAP))))
            }
            val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("IMAGE").put("TEXT")))
            val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
                .header("x-goog-api-key", key).post(body.toString().toRequestBody(JSON)).build()
            client.newCall(req).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val m = try { JSONObject(txt).optJSONObject("error")?.optString("message") } catch (e: Exception) { null }
                    val hint = if (r.code == 429 || (m ?: "").contains("quota", true) || (m ?: "").contains("billing", true)) " (image model ke liye API key ka quota/billing check karo)" else ""
                    return@withContext Result(null, "Image API error ${r.code}: ${m ?: txt.take(150)}$hint")
                }
                val ps = JSONObject(txt).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                var note = ""; var data: String? = null; var ext = "png"
                if (ps != null) for (i in 0 until ps.length()) {
                    val p = ps.optJSONObject(i) ?: continue
                    p.optJSONObject("inlineData")?.let { d -> data = d.optString("data"); if (d.optString("mimeType").contains("jpeg")) ext = "jpg" }
                    p.optString("text").takeIf { it.isNotBlank() }?.let { note = it }
                }
                val b64 = data ?: return@withContext Result(null, "Model ne image nahi banayi" + if (note.isNotBlank()) ": ${note.take(200)}" else " (shayad prompt allowed nahi tha).")
                val f = File(outDir(app), "mayra_${System.currentTimeMillis()}.$ext")
                f.writeBytes(Base64.decode(b64, Base64.DEFAULT))
                Result(f, null, note)
            }
        } catch (e: Exception) {
            Result(null, "Image banane me dikkat: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
