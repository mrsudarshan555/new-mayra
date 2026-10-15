package com.stonicx.assistant.tools.web

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import com.stonicx.assistant.tools.*
import org.json.JSONObject

/** Tools that read the live web: search, a page's text, translation, weather and news. */
object WebTools {
    private val PINNED = setOf("search_web", "get_weather")

    fun all(): List<MayraTool> = listOf(searchWeb, readUrl, translateText, getWeather, getNews).map { if (it.name in PINNED) it.pin() else it }

    private val searchWeb = tool("search_web", "Search the live web (Google) for current facts, news, prices, scores, anything that may have changed recently, and get a short sourced answer.",
        p("query", "What to find out, as a clear question", true)) { ctx, a ->
        val q = a.optString("query").trim().ifEmpty { return@tool ToolResult.fail("Kya search karna hai?") }
        val ans = SubAgent.ask(ctx.app,
            "You answer questions using fresh Google Search results. Reply in 3-6 short sentences with the concrete facts (numbers, dates, names) and mention the source names. If results disagree or are unclear, say so. Reply in the same language as the question.",
            q, temperature = 0.2, googleSearch = true)
        if (ans.text != null) ToolResult.ok(ans.text.trim()) else ToolResult.fail(ans.error ?: "Search nahi ho paayi.")
    }

    private val readUrl = tool("read_url", "Fetch a web page and return its readable text (for summarising an article or checking a page).",
        p("url", "Full http(s) address", true), p("max_chars", "How much text, default 5000, max 12000", type = "INTEGER")) { _, a ->
        val url = a.optString("url").trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@tool ToolResult.fail("URL http:// ya https:// se shuru hona chahiye.")
        val r = Http.get(url)
        if (!r.ok) return@tool ToolResult.fail(if (r.code == -1) r.body else "Page nahi khuli (HTTP ${r.code}).")
        val text = Http.htmlToText(r.body)
        if (text.isBlank()) return@tool ToolResult.fail("Page me padhne layak text nahi mila (shayad JavaScript se banta hai).")
        val max = a.optInt("max_chars", 5000).coerceIn(500, 12000)
        ToolResult.ok(text.take(max) + if (text.length > max) "\n...(page aur lamba hai)" else "")
    }

    private val translateText = tool("translate_text", "Translate text into another language.",
        p("text", "Text to translate", true), p("to", "Target language, e.g. English, Hindi, Tamil", true)) { ctx, a ->
        val ans = SubAgent.ask(ctx.app, "You are a translator. Output ONLY the translation, nothing else.",
            "Translate into ${a.optString("to")}:\n\n${a.optString("text")}", temperature = 0.1)
        if (ans.text != null) ToolResult.ok(ans.text.trim()) else ToolResult.fail(ans.error ?: "Translate nahi ho paaya.")
    }

    private fun weatherText(code: Int) = when (code) {
        0 -> "saaf aasmaan"; 1, 2 -> "halke baadal"; 3 -> "badal chhaye"; 45, 48 -> "kohra"
        in 51..57 -> "halki phuhar"; in 61..67 -> "baarish"; in 71..77 -> "barfbaari"; in 80..82 -> "tez baarish ki bauchhar"
        85, 86 -> "barf ki bauchhar"; in 95..99 -> "bijli ke saath toofan"; else -> "mausam mila-jula"
    }

    private fun lastKnown(ctx: Context): Location? {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        } catch (e: SecurityException) { null }
    }

    private val getWeather = tool("get_weather", "Current weather and the next 3 days for a city. If no city is given, uses where the phone last was.",
        p("place", "City name, e.g. Hyderabad; empty = current location")) { ctx, a ->
        var lat: Double; var lon: Double; var name: String
        val place = a.optString("place").trim()
        if (place.isNotEmpty()) {
            val g = Http.get("https://geocoding-api.open-meteo.com/v1/search?name=${Http.enc(place)}&count=1&language=en")
            val hit = if (g.ok) JSONObject(g.body).optJSONArray("results")?.optJSONObject(0) else null
            if (hit == null) return@tool ToolResult.fail(if (g.ok) "'$place' naam ki jagah nahi mili." else g.body)
            lat = hit.optDouble("latitude"); lon = hit.optDouble("longitude")
            name = listOf(hit.optString("name"), hit.optString("admin1")).filter { it.isNotBlank() }.joinToString(", ")
        } else {
            val loc = lastKnown(ctx.context) ?: return@tool ToolResult.fail("Location nahi mili - shehar ka naam batao.")
            lat = loc.latitude; lon = loc.longitude; name = "tumhari location"
        }
        val r = Http.get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=3")
        if (!r.ok) return@tool ToolResult.fail(if (r.code == -1) r.body else "Mausam ka data nahi mila (HTTP ${r.code}).")
        val j = JSONObject(r.body); val c = j.optJSONObject("current"); val d = j.optJSONObject("daily")
        val sb = StringBuilder("$name: abhi ${c?.optDouble("temperature_2m")?.toInt()}°C (mehsoos ${c?.optDouble("apparent_temperature")?.toInt()}°C), ${weatherText(c?.optInt("weather_code") ?: -1)}, " +
            "humidity ${c?.optInt("relative_humidity_2m")}%, hawa ${c?.optDouble("wind_speed_10m")?.toInt()} km/h.")
        val days = d?.optJSONArray("time")
        if (days != null) for (i in 0 until days.length()) {
            sb.append("\n${days.optString(i)}: ${d.optJSONArray("temperature_2m_min")?.optDouble(i)?.toInt()}-${d.optJSONArray("temperature_2m_max")?.optDouble(i)?.toInt()}°C, " +
                "${weatherText(d.optJSONArray("weather_code")?.optInt(i) ?: -1)}, baarish ka chance ${d.optJSONArray("precipitation_probability_max")?.optInt(i)}%")
        }
        ToolResult.ok(sb.toString())
    }

    private fun unescape(s: String) = s.replace("<![CDATA[", "").replace("]]>", "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").trim()

    private val getNews = tool("get_news", "Latest news headlines, on a topic or top stories in India.",
        p("topic", "Topic or keywords; empty = top stories"), p("language", "hi for Hindi headlines, otherwise English")) { _, a ->
        val hindi = a.optString("language").lowercase().startsWith("hi")
        val loc = if (hindi) "hl=hi&gl=IN&ceid=IN:hi" else "hl=en-IN&gl=IN&ceid=IN:en"
        val topic = a.optString("topic").trim()
        val url = if (topic.isEmpty()) "https://news.google.com/rss?$loc" else "https://news.google.com/rss/search?q=${Http.enc(topic)}&$loc"
        val r = Http.get(url)
        if (!r.ok) return@tool ToolResult.fail(if (r.code == -1) r.body else "News nahi mili (HTTP ${r.code}).")
        val items = Regex("(?s)<item>.*?<title>(.*?)</title>.*?<pubDate>(.*?)</pubDate>").findAll(r.body).take(8).map { unescape(it.groupValues[1]) }.toList()
        if (items.isEmpty()) ToolResult.ok("Is topic pe abhi koi khabar nahi mili.") else ToolResult.ok(items.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n"))
    }
}
