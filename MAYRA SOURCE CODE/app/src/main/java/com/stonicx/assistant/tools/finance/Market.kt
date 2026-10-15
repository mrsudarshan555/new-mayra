package com.stonicx.assistant.tools.finance

import com.stonicx.assistant.tools.Http
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/** Yahoo Finance (public chart/search endpoints) + the maths for indicators. */
object Market {
    data class Quote(
        val symbol: String, val name: String, val price: Double, val prev: Double, val currency: String, val exchange: String,
        val dayHigh: Double, val dayLow: Double, val high52: Double, val low52: Double, val volume: Long
    ) {
        val change: Double get() = price - prev
        val changePct: Double get() = if (prev != 0.0) change / prev * 100 else 0.0
    }

    class Series(val times: LongArray, val closes: DoubleArray, val quote: Quote)

    fun money(v: Double, cur: String): String {
        val sym = when (cur) { "INR" -> "₹"; "USD" -> "$"; "EUR" -> "€"; "GBP" -> "£"; "JPY" -> "¥"; else -> "$cur " }
        return sym + "%,.2f".format(v)
    }

    fun pct(v: Double) = (if (v >= 0) "+" else "") + "%.2f%%".format(v)

    /** Turn a company name or ticker into a Yahoo symbol; prefers NSE for Indian names. */
    suspend fun resolve(input: String): String? {
        val q = input.trim()
        if (q.isEmpty()) return null
        if (Regex("^[\\^A-Za-z0-9.=\\-]{1,15}$").matches(q) && (q == q.uppercase() || q.contains('.') || q.startsWith("^"))) return q.uppercase().takeIf { !q.startsWith("^") } ?: q
        val hits = search(q)
        val pick = hits.firstOrNull { it.optString("exchange") == "NSI" } ?: hits.firstOrNull { it.optString("exchange") == "BSE" } ?: hits.firstOrNull()
        return pick?.optString("symbol")?.ifBlank { null }
    }

    suspend fun search(q: String): List<JSONObject> {
        val r = Http.get("https://query1.finance.yahoo.com/v1/finance/search?q=${Http.enc(q)}&quotesCount=8&newsCount=0")
        if (!r.ok) return emptyList()
        val arr = JSONObject(r.body).optJSONArray("quotes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("quoteType") in setOf("EQUITY", "ETF", "INDEX", "MUTUALFUND") }
    }

    /** Returns the series, or an error text (as Pair second). */
    suspend fun series(symbol: String, range: String = "6mo", interval: String = "1d"): Pair<Series?, String?> {
        val r = Http.get("https://query1.finance.yahoo.com/v8/finance/chart/${Http.enc(symbol)}?range=$range&interval=$interval")
        if (r.code == 404) return null to "'$symbol' naam ka koi stock nahi mila."
        if (!r.ok) return null to if (r.code == -1) r.body else "Market data nahi mila (HTTP ${r.code}) - thodi der baad try karo."
        return try {
            val res = JSONObject(r.body).optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0) ?: return null to "'$symbol' ka data khaali aaya."
            val meta = res.optJSONObject("meta") ?: return null to "Data adhoora aaya."
            val ts = res.optJSONArray("timestamp"); val cl = res.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")
            val times = ArrayList<Long>(); val closes = ArrayList<Double>()
            if (ts != null && cl != null) for (i in 0 until minOf(ts.length(), cl.length())) {
                val c = cl.optDouble(i, Double.NaN); if (!c.isNaN()) { times += ts.optLong(i); closes += c }
            }
            val price = meta.optDouble("regularMarketPrice", closes.lastOrNull() ?: Double.NaN)
            if (price.isNaN()) return null to "'$symbol' ka price nahi mila."
            val prev = when {
                closes.size >= 2 -> closes[closes.size - 2]
                !meta.isNull("chartPreviousClose") -> meta.optDouble("chartPreviousClose", price)
                else -> price
            }
            val q = Quote(
                symbol = meta.optString("symbol", symbol), name = meta.optString("longName").ifBlank { meta.optString("shortName", symbol) },
                price = price, prev = prev, currency = meta.optString("currency", ""), exchange = meta.optString("fullExchangeName", meta.optString("exchangeName", "")),
                dayHigh = meta.optDouble("regularMarketDayHigh", price), dayLow = meta.optDouble("regularMarketDayLow", price),
                high52 = meta.optDouble("fiftyTwoWeekHigh", Double.NaN), low52 = meta.optDouble("fiftyTwoWeekLow", Double.NaN), volume = meta.optLong("regularMarketVolume", 0)
            )
            Series(times.toLongArray(), closes.toDoubleArray(), q) to null
        } catch (e: Exception) { null to "Market data samajh nahi aaya: ${e.message}" }
    }

    suspend fun quote(symbol: String): Pair<Quote?, String?> {
        val (s, e) = series(symbol, "5d", "1d"); return s?.quote to e
    }

    fun line(q: Quote): String {
        val ch = "%.2f".format(q.change)
        return "${q.name} (${q.symbol}): ${money(q.price, q.currency)} ${pct(q.changePct)} (${if (q.change >= 0) "+" else ""}$ch)"
    }

    // ---------------- indicators
    fun sma(v: DoubleArray, n: Int): Double? = if (v.size < n) null else v.takeLast(n).average()

    fun emaSeries(v: DoubleArray, n: Int): DoubleArray {
        if (v.isEmpty()) return v
        val k = 2.0 / (n + 1); val out = DoubleArray(v.size); out[0] = v[0]
        for (i in 1 until v.size) out[i] = v[i] * k + out[i - 1] * (1 - k)
        return out
    }

    fun rsi(v: DoubleArray, n: Int = 14): Double? {
        if (v.size <= n) return null
        var g = 0.0; var l = 0.0
        for (i in 1..n) { val d = v[i] - v[i - 1]; if (d >= 0) g += d else l -= d }
        g /= n; l /= n
        for (i in n + 1 until v.size) { val d = v[i] - v[i - 1]; g = (g * (n - 1) + maxOf(d, 0.0)) / n; l = (l * (n - 1) + maxOf(-d, 0.0)) / n }
        return if (l == 0.0) 100.0 else 100 - 100 / (1 + g / l)
    }

    /** MACD line, signal, histogram (last values). */
    fun macd(v: DoubleArray): Triple<Double, Double, Double>? {
        if (v.size < 35) return null
        val e12 = emaSeries(v, 12); val e26 = emaSeries(v, 26)
        val line = DoubleArray(v.size) { e12[it] - e26[it] }
        val sig = emaSeries(line, 9)
        return Triple(line.last(), sig.last(), line.last() - sig.last())
    }

    fun bollinger(v: DoubleArray, n: Int = 20): Triple<Double, Double, Double>? {
        if (v.size < n) return null
        val w = v.takeLast(n); val m = w.average(); val sd = sqrt(w.sumOf { (it - m) * (it - m) } / n)
        return Triple(m - 2 * sd, m, m + 2 * sd)
    }

    fun indicatorsText(s: Series): String {
        val c = s.closes; val q = s.quote; val sb = StringBuilder()
        sma(c, 20)?.let { sb.append("SMA20 ${money(it, q.currency)} (price ${if (q.price >= it) "upar" else "neeche"})\n") }
        sma(c, 50)?.let { sb.append("SMA50 ${money(it, q.currency)} (price ${if (q.price >= it) "upar" else "neeche"})\n") }
        rsi(c)?.let { sb.append("RSI14 %.1f (%s)\n".format(it, when { it >= 70 -> "overbought zone"; it <= 30 -> "oversold zone"; else -> "neutral" })) }
        macd(c)?.let { sb.append("MACD %.2f, signal %.2f, histogram %.2f (%s)\n".format(it.first, it.second, it.third, if (it.third >= 0) "bullish side" else "bearish side")) }
        bollinger(c)?.let { sb.append("Bollinger: %s - %s - %s\n".format(money(it.first, q.currency), money(it.second, q.currency), money(it.third, q.currency))) }
        if (!q.high52.isNaN() && !q.low52.isNaN()) sb.append("52 hafte: ${money(q.low52, q.currency)} - ${money(q.high52, q.currency)}\n")
        return sb.toString().trim().ifEmpty { "Indicators ke liye kaafi history nahi hai." }
    }

    fun absPct(a: Double, b: Double) = if (b == 0.0) 0.0 else abs(a - b) / b * 100
}
