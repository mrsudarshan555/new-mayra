package com.stonicx.assistant.tools.finance

import com.stonicx.assistant.tools.*
import org.json.JSONObject

/** Stocks, crypto, forex, watchlist, portfolio, alerts and AI stock analysis. Data only - never trade advice. */
object FinanceTools {
    private val PINNED = setOf("get_stock_quote", "get_crypto_price")

    fun all(): List<MayraTool> = listOf(
        searchStock, getStockQuote, getStockHistory, getStockIndicators, compareStocks, getTrending, getMarketSnapshot, getMarketNews,
        getCrypto, getForex, addToWatchlist, removeFromWatchlist, getWatchlist, addHolding, removeHolding, getPortfolio, setPriceAlert,
        analyzeStock, scanWatchlist, reviewPortfolio
    ).map { if (it.name in PINNED) it.pin() else it }

    /** Direct ticker, then NSE suffix, then name search. */
    private suspend fun load(input: String, range: String = "6mo", interval: String = "1d"): Pair<Market.Series?, String?> {
        val q = input.trim()
        if (q.isEmpty()) return null to "Kaunsa stock?"
        val tickerLike = Regex("^[\\^A-Za-z0-9.=\\-]{1,15}$").matches(q) && !q.contains(' ')
        var firstErr: String? = null
        if (tickerLike && (q == q.uppercase() || q.contains('.') || q.startsWith("^"))) {
            val r = Market.series(q.uppercase().let { if (q.startsWith("^")) q else it }, range, interval)
            if (r.first != null) return r
            firstErr = r.second
            if (!q.contains('.') && !q.startsWith("^") && !q.contains('=')) {
                val ns = Market.series(q.uppercase() + ".NS", range, interval); if (ns.first != null) return ns
            }
        }
        val sym = Market.search(q).let { hits -> hits.firstOrNull { it.optString("exchange") == "NSI" } ?: hits.firstOrNull { it.optString("exchange") == "BSE" } ?: hits.firstOrNull() }?.optString("symbol")
        if (!sym.isNullOrBlank()) return Market.series(sym, range, interval)
        return null to (firstErr ?: "'$q' naam ka koi stock nahi mila.")
    }

    private val searchStock = tool("search_stock", "Find the ticker symbol of a company, index, ETF or fund by name.", p("query", "Company or fund name", true)) { _, a ->
        val hits = Market.search(a.optString("query"))
        if (hits.isEmpty()) ToolResult.ok("Is naam ka kuch nahi mila.")
        else ToolResult.ok(hits.take(6).joinToString("\n") { "${it.optString("symbol")} - ${it.optString("longname").ifBlank { it.optString("shortname") }} (${it.optString("exchDisp")})" })
    }

    private val getStockQuote = tool("get_stock_quote", "Live price, day change, day range and 52-week range for a stock/index/ETF. Accepts a name (Reliance) or ticker (TCS.NS, AAPL, ^NSEI).",
        p("symbol", "Company name or ticker", true)) { _, a ->
        val (s, e) = load(a.optString("symbol"), "5d")
        if (s == null) return@tool ToolResult.fail(e ?: "Quote nahi mila.")
        val q = s.quote
        ToolResult.ok(Market.line(q) + "\nAaj: ${Market.money(q.dayLow, q.currency)} - ${Market.money(q.dayHigh, q.currency)}" +
            (if (!q.high52.isNaN() && !q.low52.isNaN()) "\n52 hafte: ${Market.money(q.low52, q.currency)} - ${Market.money(q.high52, q.currency)}" else "") +
            "\n${q.exchange}. (Ye data thoda late ho sakta hai.)") { put("symbol", q.symbol); put("price", q.price) }
    }

    private val getStockHistory = tool("get_stock_history", "How a stock moved over a period: start/end price, change, high, low and a few sample points.",
        p("symbol", "Company name or ticker", true), p("range", "1mo, 3mo, 6mo, 1y, 5y - default 3mo")) { _, a ->
        val range = a.optString("range").ifBlank { "3mo" }.takeIf { it in setOf("5d", "1mo", "3mo", "6mo", "1y", "2y", "5y") } ?: "3mo"
        val (s, e) = load(a.optString("symbol"), range, if (range in setOf("2y", "5y")) "1wk" else "1d")
        if (s == null || s.closes.size < 2) return@tool ToolResult.fail(e ?: "History nahi mili.")
        val c = s.closes; val q = s.quote
        val first = c.first(); val last = c.last()
        val step = maxOf(1, c.size / 6)
        val samples = (c.indices step step).joinToString(", ") { Market.money(c[it], q.currency) }
        ToolResult.ok("${q.name} - $range: ${Market.money(first, q.currency)} se ${Market.money(last, q.currency)} (${Market.pct((last - first) / first * 100)}). " +
            "High ${Market.money(c.max(), q.currency)}, Low ${Market.money(c.min(), q.currency)}.\nBeech ke points: $samples")
    }

    private val getStockIndicators = tool("get_stock_indicators", "Technical indicators for a stock: SMA20/50, RSI, MACD, Bollinger bands, 52-week range.",
        p("symbol", "Company name or ticker", true)) { _, a ->
        val (s, e) = load(a.optString("symbol"), "1y")
        if (s == null) return@tool ToolResult.fail(e ?: "Data nahi mila.")
        ToolResult.ok("${Market.line(s.quote)}\n${Market.indicatorsText(s)}")
    }

    private val compareStocks = tool("compare_stocks", "Compare two or more stocks side by side: price, today's change, 3-month change and RSI.",
        p("symbols", "Comma-separated names or tickers", true)) { _, a ->
        val names = a.optString("symbols").split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(5)
        if (names.size < 2) return@tool ToolResult.fail("Kam se kam do stocks batao, comma se alag.")
        val rows = names.map { n ->
            val (s, e) = load(n, "3mo")
            if (s == null || s.closes.size < 2) "$n: ${e ?: "data nahi mila"}"
            else { val c = s.closes; val q = s.quote
                "${q.name} (${q.symbol}): ${Market.money(q.price, q.currency)}, aaj ${Market.pct(q.changePct)}, 3 mahine ${Market.pct((c.last() - c.first()) / c.first() * 100)}" + (Market.rsi(c)?.let { ", RSI %.0f".format(it) } ?: "") }
        }
        ToolResult.ok(rows.joinToString("\n"))
    }

    private val getTrending = tool("get_trending_stocks", "Stocks that are trending right now.", p("region", "IN (default) or US")) { _, a ->
        val region = if (a.optString("region").uppercase() == "US") "US" else "IN"
        val r = Http.get("https://query1.finance.yahoo.com/v1/finance/trending/$region?count=10")
        if (!r.ok) return@tool ToolResult.fail(if (r.code == -1) r.body else "Trending list nahi mili (HTTP ${r.code}).")
        val arr = JSONObject(r.body).optJSONObject("finance")?.optJSONArray("result")?.optJSONObject(0)?.optJSONArray("quotes")
        val syms = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it)?.optString("symbol") }.take(8)
        if (syms.isEmpty()) return@tool ToolResult.ok("Abhi trending list khaali hai.")
        ToolResult.ok(syms.joinToString("\n") { sym -> Market.quote(sym).first?.let { Market.line(it) } ?: sym })
    }

    private val getMarketSnapshot = tool("get_market_snapshot", "Quick look at the markets: Nifty, Sensex, Bank Nifty, S&P 500, Nasdaq, Dow, USD/INR, gold and crude oil.") { _, _ ->
        val items = listOf("^NSEI" to "Nifty 50", "^BSESN" to "Sensex", "^NSEBANK" to "Bank Nifty", "^GSPC" to "S&P 500", "^IXIC" to "Nasdaq", "^DJI" to "Dow", "USDINR=X" to "USD/INR", "GC=F" to "Gold (USD/oz)", "CL=F" to "Crude oil (USD)")
        val lines = items.map { (sym, label) ->
            val q = Market.quote(sym).first
            if (q == null) "$label: data nahi mila" else "$label: ${"%,.2f".format(q.price)} ${Market.pct(q.changePct)}"
        }
        ToolResult.ok(lines.joinToString("\n"))
    }

    private val getMarketNews = tool("get_market_news", "Latest stock-market / business headlines, optionally about one company or topic.", p("topic", "Company or topic; empty = Indian market")) { _, a ->
        val topic = a.optString("topic").trim().ifEmpty { "stock market India Sensex Nifty" }
        val r = Http.get("https://news.google.com/rss/search?q=${Http.enc(topic)}&hl=en-IN&gl=IN&ceid=IN:en")
        if (!r.ok) return@tool ToolResult.fail(if (r.code == -1) r.body else "News nahi mili.")
        val items = Regex("(?s)<item>.*?<title>(.*?)</title>").findAll(r.body).take(8).map { it.groupValues[1].replace("<![CDATA[", "").replace("]]>", "").replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").trim() }.toList()
        if (items.isEmpty()) ToolResult.ok("Abhi koi khabar nahi mili.") else ToolResult.ok(items.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n"))
    }

    private val getCrypto = tool("get_crypto_price", "Current price of a cryptocurrency in INR and USD with 24-hour change (bitcoin, ethereum, solana, dogecoin ...).",
        p("coin", "Coin name or symbol", true)) { _, a ->
        val q = a.optString("coin").trim().lowercase().ifEmpty { return@tool ToolResult.fail("Kaunsa coin?") }
        val common = mapOf("btc" to "bitcoin", "eth" to "ethereum", "sol" to "solana", "doge" to "dogecoin", "xrp" to "ripple", "bnb" to "binancecoin", "ada" to "cardano", "matic" to "matic-network", "usdt" to "tether", "ltc" to "litecoin")
        var id = common[q] ?: q.replace(' ', '-')
        var r = Http.get("https://api.coingecko.com/api/v3/simple/price?ids=${Http.enc(id)}&vs_currencies=inr,usd&include_24hr_change=true")
        var j = if (r.ok) JSONObject(r.body).optJSONObject(id) else null
        if (j == null && (r.ok || r.code == 200)) {
            val s = Http.get("https://api.coingecko.com/api/v3/search?query=${Http.enc(q)}")
            val coin = if (s.ok) JSONObject(s.body).optJSONArray("coins")?.optJSONObject(0) else null
            if (coin != null) {
                id = coin.optString("id")
                r = Http.get("https://api.coingecko.com/api/v3/simple/price?ids=${Http.enc(id)}&vs_currencies=inr,usd&include_24hr_change=true")
                j = if (r.ok) JSONObject(r.body).optJSONObject(id) else null
            }
        }
        if (j == null) return@tool ToolResult.fail(if (r.code == 429) "Crypto data limit lag gayi - thodi der baad poochho." else if (r.code == -1) r.body else "'$q' naam ka coin nahi mila.")
        ToolResult.ok("$id: ₹%,.2f / $%,.2f (24 ghante: %s)".format(j.optDouble("inr"), j.optDouble("usd"), Market.pct(j.optDouble("inr_24h_change", 0.0))))
    }

    private val getForex = tool("get_forex_rate", "Currency exchange rate, optionally converting an amount (USD to INR, EUR to INR ...).",
        p("from", "Currency code, e.g. USD", true), p("to", "Currency code, default INR"), p("amount", "Amount to convert, default 1", type = "NUMBER")) { _, a ->
        val from = a.optString("from").trim().uppercase(); val to = a.optString("to").trim().uppercase().ifEmpty { "INR" }
        val amount = if (a.has("amount")) a.optDouble("amount", 1.0) else 1.0
        if (!Regex("^[A-Z]{3}$").matches(from) || !Regex("^[A-Z]{3}$").matches(to)) return@tool ToolResult.fail("Currency 3-letter code me batao (USD, INR, EUR...).")
        val r = Http.get("https://open.er-api.com/v6/latest/$from")
        if (!r.ok) return@tool ToolResult.fail(if (r.code == -1) r.body else "Rate nahi mila (HTTP ${r.code}).")
        val rate = JSONObject(r.body).optJSONObject("rates")?.optDouble(to, Double.NaN) ?: Double.NaN
        if (rate.isNaN()) return@tool ToolResult.fail("$to currency nahi mili.")
        ToolResult.ok("%,.2f $from = %,.2f $to (1 $from = %.4f $to)".format(amount, amount * rate, rate))
    }

    // ---------------- watchlist / portfolio / alerts
    private val addToWatchlist = tool("add_to_watchlist", "Add a stock to the watchlist.", p("symbol", "Company name or ticker", true)) { ctx, a ->
        val (s, e) = load(a.optString("symbol"), "5d")
        if (s == null) return@tool ToolResult.fail(e ?: "Stock nahi mila.")
        FinanceStore.setWatchlist(ctx.context, FinanceStore.watchlist(ctx.context) + s.quote.symbol)
        ToolResult.ok("${s.quote.name} (${s.quote.symbol}) watchlist me add ho gaya.")
    }

    private val removeFromWatchlist = tool("remove_from_watchlist", "Remove a stock from the watchlist.", p("symbol", "Name or ticker", true)) { ctx, a ->
        val q = a.optString("symbol").trim().lowercase()
        val l = FinanceStore.watchlist(ctx.context); val hit = l.filter { it.lowercase().startsWith(q) || it.lowercase().contains(q) }
        if (hit.isEmpty()) ToolResult.fail("Watchlist me '$q' nahi hai.") else { FinanceStore.setWatchlist(ctx.context, l - hit.toSet()); ToolResult.ok("${hit.joinToString()} watchlist se hata diya.") }
    }

    private val getWatchlist = tool("get_watchlist", "Show the watchlist with live prices.") { ctx, _ ->
        val l = FinanceStore.watchlist(ctx.context)
        if (l.isEmpty()) ToolResult.ok("Watchlist khaali hai.") else ToolResult.ok(l.joinToString("\n") { sym -> Market.quote(sym).first?.let { Market.line(it) } ?: "$sym: data nahi mila" })
    }

    private val addHolding = tool("add_holding", "Record shares the user owns (replaces the entry for that stock).",
        p("symbol", "Company name or ticker", true), p("quantity", "Number of shares", true, "NUMBER"), p("avg_price", "Average buy price per share", true, "NUMBER")) { ctx, a ->
        val qty = a.optDouble("quantity", 0.0); val avg = a.optDouble("avg_price", 0.0)
        if (qty <= 0 || avg <= 0) return@tool ToolResult.fail("Quantity aur average price dono positive hone chahiye.")
        val (s, e) = load(a.optString("symbol"), "5d")
        if (s == null) return@tool ToolResult.fail(e ?: "Stock nahi mila.")
        FinanceStore.setHoldings(ctx.context, FinanceStore.holdings(ctx.context).filter { it.symbol != s.quote.symbol } + FinanceStore.Holding(s.quote.symbol, qty, avg))
        ToolResult.ok("${s.quote.name}: ${"%.2f".format(qty)} shares @ ${Market.money(avg, s.quote.currency)} portfolio me save.")
    }

    private val removeHolding = tool("remove_holding", "Remove a stock from the portfolio.", p("symbol", "Name or ticker", true)) { ctx, a ->
        val q = a.optString("symbol").trim().lowercase()
        val l = FinanceStore.holdings(ctx.context); val hit = l.filter { it.symbol.lowercase().contains(q) }
        if (hit.isEmpty()) ToolResult.fail("Portfolio me '$q' nahi hai.") else { FinanceStore.setHoldings(ctx.context, l - hit.toSet()); ToolResult.ok("${hit.joinToString { it.symbol }} portfolio se hata diya.") }
    }

    private suspend fun portfolioLines(ctx: ToolContext): Triple<List<String>, Double, Double> {
        var invested = 0.0; var current = 0.0; val lines = ArrayList<String>()
        for (h in FinanceStore.holdings(ctx.context)) {
            val q = Market.quote(h.symbol).first
            if (q == null) { lines += "${h.symbol}: price nahi mila"; continue }
            val inv = h.qty * h.avg; val cur = h.qty * q.price
            if (q.currency == "INR") { invested += inv; current += cur }
            val qtyText = "%.2f".format(h.qty)
            lines += "${q.name}: $qtyText x ${Market.money(q.price, q.currency)} = ${Market.money(cur, q.currency)} (P&L ${Market.money(cur - inv, q.currency)}, ${Market.pct((cur - inv) / inv * 100)})"
        }
        return Triple(lines, invested, current)
    }

    private val getPortfolio = tool("get_portfolio", "Show the user's holdings with live value and profit/loss.") { ctx, _ ->
        if (FinanceStore.holdings(ctx.context).isEmpty()) return@tool ToolResult.ok("Portfolio khaali hai - add_holding se shares jodo.")
        val (lines, inv, cur) = portfolioLines(ctx)
        ToolResult.ok(lines.joinToString("\n") + if (inv > 0) "\nINR holdings total: ${Market.money(cur, "INR")} (invested ${Market.money(inv, "INR")}, ${Market.pct((cur - inv) / inv * 100)})" else "")
    }

    private val setPriceAlert = tool("set_price_alert", "Notify the user when a stock goes above or below a price (checked about every 15 minutes).",
        p("symbol", "Company name or ticker", true), p("price", "Trigger price", true, "NUMBER"), p("direction", "above or below", true)) { ctx, a ->
        val price = a.optDouble("price", 0.0); val dir = a.optString("direction").lowercase()
        if (price <= 0 || dir !in setOf("above", "below")) return@tool ToolResult.fail("Price positive do aur direction 'above' ya 'below' batao.")
        val (s, e) = load(a.optString("symbol"), "5d")
        if (s == null) return@tool ToolResult.fail(e ?: "Stock nahi mila.")
        FinanceStore.addAlert(ctx.context, s.quote.symbol, dir == "above", price)
        ToolResult.ok("Alert set: ${s.quote.name} ${if (dir == "above") "upar" else "neeche"} ${Market.money(price, s.quote.currency)} jaate hi notification aayegi (abhi ${Market.money(s.quote.price, s.quote.currency)}). Check lagbhag har 15 minute me hota hai.")
    }

    // ---------------- AI analysis
    private const val ANALYST = "You are a careful market analyst. You are given real numbers fetched just now. Write a balanced, plain-language analysis in short Hinglish: " +
        "trend, momentum (RSI/MACD), where price sits vs moving averages and 52-week range, what could go either way, and the main risks. " +
        "Use ONLY the supplied numbers and headlines; do not invent figures. Do NOT tell the reader to buy, sell or hold; present facts and both sides. End with one line that this is information, not financial advice. Max 8 sentences."

    private val analyzeStock = tool("analyze_stock", "A balanced AI analysis of a stock using live price, indicators and recent headlines. Information only, not advice.",
        p("symbol", "Company name or ticker", true)) { ctx, a ->
        val (s, e) = load(a.optString("symbol"), "1y")
        if (s == null) return@tool ToolResult.fail(e ?: "Data nahi mila.")
        val q = s.quote
        val news = Http.get("https://news.google.com/rss/search?q=${Http.enc(q.name)}&hl=en-IN&gl=IN&ceid=IN:en")
        val heads = if (news.ok) Regex("(?s)<item>.*?<title>(.*?)</title>").findAll(news.body).take(5).map { it.groupValues[1].replace("<![CDATA[", "").replace("]]>", "") }.joinToString("\n") else "(headlines nahi mili)"
        val ans = SubAgent.ask(ctx.app, ANALYST, "${Market.line(q)}\n${Market.indicatorsText(s)}\n3-6 month change: ${Market.pct((s.closes.last() - s.closes.first()) / s.closes.first() * 100)}\n\nHeadlines:\n$heads", temperature = 0.3)
        if (ans.text != null) ToolResult.ok(ans.text.trim()) else ToolResult.ok("AI analysis nahi ban paayi (${ans.error}). Raw data:\n${Market.line(q)}\n${Market.indicatorsText(s)}")
    }

    private val scanWatchlist = tool("scan_watchlist", "Scan the watchlist and flag the big movers and overbought/oversold stocks.") { ctx, _ ->
        val l = FinanceStore.watchlist(ctx.context)
        if (l.isEmpty()) return@tool ToolResult.ok("Watchlist khaali hai.")
        val flagged = ArrayList<String>(); val calm = ArrayList<String>()
        for (sym in l) {
            val (s, _) = Market.series(sym, "6mo", "1d")
            if (s == null) { calm += "$sym: data nahi mila"; continue }
            val q = s.quote; val rsi = Market.rsi(s.closes)
            val notes = ArrayList<String>()
            if (kotlin.math.abs(q.changePct) >= 3) notes += "aaj ${Market.pct(q.changePct)}"
            if (rsi != null && rsi >= 70) notes += "RSI %.0f overbought".format(rsi) else if (rsi != null && rsi <= 30) notes += "RSI %.0f oversold".format(rsi)
            if (!q.high52.isNaN() && q.price >= q.high52 * 0.98) notes += "52-hafte ke high ke paas"
            if (!q.low52.isNaN() && q.price <= q.low52 * 1.02) notes += "52-hafte ke low ke paas"
            (if (notes.isNotEmpty()) flagged else calm) += "${q.name}: ${Market.money(q.price, q.currency)} ${Market.pct(q.changePct)}" + if (notes.isNotEmpty()) " - ${notes.joinToString(", ")}" else ""
        }
        ToolResult.ok((if (flagged.isNotEmpty()) "Dhyan dene layak:\n" + flagged.joinToString("\n") + "\n\n" else "Koi khaas hulchul nahi.\n\n") + "Baaki:\n" + calm.joinToString("\n"))
    }

    private val reviewPortfolio = tool("review_portfolio", "Review the portfolio: P&L, biggest positions, concentration and what moved today. Information only.") { ctx, _ ->
        val h = FinanceStore.holdings(ctx.context)
        if (h.isEmpty()) return@tool ToolResult.ok("Portfolio khaali hai - add_holding se shares jodo.")
        val (lines, inv, cur) = portfolioLines(ctx)
        val data = lines.joinToString("\n") + "\nTotal (INR holdings): value ${Market.money(cur, "INR")}, invested ${Market.money(inv, "INR")}"
        val ans = SubAgent.ask(ctx.app, "You review a retail investor's portfolio from the real numbers given. In short Hinglish: overall P&L, biggest winners/losers, concentration risk (one stock or sector too large), and 2-3 neutral points to think about. Do NOT tell them to buy/sell/hold; no invented numbers. End with one line: information, not financial advice.", data, temperature = 0.3)
        ToolResult.ok(if (ans.text != null) data + "\n\n" + ans.text.trim() else data)
    }
}
