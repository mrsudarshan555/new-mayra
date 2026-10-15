package com.stonicx.assistant.tools.phone

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

/** Reminders kept in a small prefs file and scheduled with AlarmManager. Re-armed every time the app starts. */
object ReminderStore {
    private const val PREFS = "mayra_reminders"
    private const val KEY = "items"
    const val CHANNEL = "mayra_reminders"

    data class Item(val id: Int, val text: String, val at: Long)

    private fun load(ctx: Context): MutableList<Item> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Item(it.optInt("id"), it.optString("text"), it.optLong("at")) } }.toMutableList()
    }

    private fun save(ctx: Context, items: List<Item>) {
        val arr = JSONArray(); items.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.at)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    private fun pending(ctx: Context, item: Item): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("id", item.id).putExtra("text", item.text)
        return PendingIntent.getBroadcast(ctx, item.id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun arm(ctx: Context, item: Item) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx, item)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.at, pi)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.at, pi)
        }
    }

    fun add(ctx: Context, text: String, at: Long): Item {
        val items = load(ctx)
        val item = Item(((System.currentTimeMillis() / 1000) % 1_000_000).toInt() + items.size, text, at)
        items += item; save(ctx, items); arm(ctx, item)
        return item
    }

    fun list(ctx: Context): List<Item> = load(ctx).filter { it.at > System.currentTimeMillis() - 60_000 }.sortedBy { it.at }

    /** Remove by id or by (part of) the text. Returns what was removed. */
    fun remove(ctx: Context, query: String): List<Item> {
        val q = query.trim().lowercase()
        val items = load(ctx)
        val hit = items.filter { it.id.toString() == q || it.text.lowercase().contains(q) }
        if (hit.isEmpty()) return emptyList()
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        hit.forEach { am.cancel(pending(ctx, it)) }
        save(ctx, items - hit.toSet())
        return hit
    }

    fun rearm(ctx: Context) {
        val now = System.currentTimeMillis()
        val items = load(ctx).filter { it.at > now }
        save(ctx, items)
        items.forEach { arm(ctx, it) }
    }

    fun notify(ctx: Context, id: Int, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mayra reminders", NotificationManager.IMPORTANCE_HIGH))
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Mayra reminder").setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setDefaults(NotificationCompat.DEFAULT_ALL).build()
        nm.notify(id, n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val text = intent.getStringExtra("text").orEmpty().ifBlank { "Reminder" }
        ReminderStore.notify(context, id, text)
        ReminderStore.remove(context, id.toString())
    }
}
