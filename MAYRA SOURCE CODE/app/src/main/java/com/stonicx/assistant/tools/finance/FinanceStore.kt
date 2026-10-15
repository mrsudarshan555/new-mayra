package com.stonicx.assistant.tools.finance

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.stonicx.assistant.tools.phone.ReminderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Watchlist, holdings and price alerts, kept in a small prefs file on the phone. */
object FinanceStore {
    private const val PREFS = "mayra_finance"

    data class Holding(val symbol: String, val qty: Double, val avg: Double)
    data class Alert(val id: Int, val symbol: String, val above: Boolean, val price: Double)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun arr(ctx: Context, key: String) = try { JSONArray(prefs(ctx).getString(key, "[]")) } catch (e: Exception) { JSONArray() }

    fun watchlist(ctx: Context): List<String> = arr(ctx, "watch").let { a -> (0 until a.length()).map { a.optString(it) } }
    fun setWatchlist(ctx: Context, l: List<String>) = prefs(ctx).edit().putString("watch", JSONArray(l.distinct()).toString()).apply()

    fun holdings(ctx: Context): List<Holding> = arr(ctx, "hold").let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Holding(it.optString("s"), it.optDouble("q"), it.optDouble("a")) } }
    fun setHoldings(ctx: Context, l: List<Holding>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("s", it.symbol).put("q", it.qty).put("a", it.avg)) }
        prefs(ctx).edit().putString("hold", a.toString()).apply()
    }

    fun alerts(ctx: Context): List<Alert> = arr(ctx, "alerts").let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Alert(it.optInt("id"), it.optString("s"), it.optBoolean("up"), it.optDouble("p")) } }
    private fun setAlerts(ctx: Context, l: List<Alert>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("id", it.id).put("s", it.symbol).put("up", it.above).put("p", it.price)) }
        prefs(ctx).edit().putString("alerts", a.toString()).apply()
    }

    fun addAlert(ctx: Context, symbol: String, above: Boolean, price: Double) {
        val l = alerts(ctx).toMutableList(); l += Alert(((System.currentTimeMillis() / 1000) % 900_000).toInt() + 100_000 + l.size, symbol, above, price)
        setAlerts(ctx, l); schedule(ctx)
    }

    private fun checkIntent(ctx: Context) = PendingIntent.getBroadcast(ctx, 7001, Intent(ctx, PriceAlertReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (alerts(ctx).isEmpty()) { am.cancel(checkIntent(ctx)); return }
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + AlarmManager.INTERVAL_FIFTEEN_MINUTES, AlarmManager.INTERVAL_FIFTEEN_MINUTES, checkIntent(ctx))
    }

    suspend fun checkAlerts(ctx: Context) {
        val remaining = ArrayList<Alert>()
        for (al in alerts(ctx)) {
            val q = Market.quote(al.symbol).first
            if (q == null) { remaining += al; continue }
            val hit = if (al.above) q.price >= al.price else q.price <= al.price
            if (hit) ReminderStore.notify(ctx, al.id, "${q.name}: ${Market.money(q.price, q.currency)} - ${if (al.above) "upar" else "neeche"} ${Market.money(al.price, q.currency)} ka alert lag gaya")
            else remaining += al
        }
        setAlerts(ctx, remaining); schedule(ctx)
    }
}

class PriceAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { FinanceStore.checkAlerts(context.applicationContext) } catch (_: Throwable) { } finally { pending.finish() }
        }
    }
}
