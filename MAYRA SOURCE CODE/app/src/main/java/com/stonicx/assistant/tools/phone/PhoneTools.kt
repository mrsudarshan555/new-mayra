package com.stonicx.assistant.tools.phone

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.stonicx.assistant.tools.*
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Everyday phone tools: alarms, timers, reminders, calendar, contacts, settings, apps, files, sharing, memory. */
object PhoneTools {
    private val PINNED = setOf("set_alarm", "set_timer", "add_reminder", "find_contact")

    fun all(): List<MayraTool> = listOf(
        getCurrentTime, setAlarm, cancelAlarm, setTimer, cancelTimer, addReminder, listReminders, completeReminder, addCalendarEvent,
        findContact, saveContact, editContact, answerCall, openSettings, listInstalledApps, shareText, shareFile, findFiles, openFile,
        showOnMap, rememberFact, recallMemory, forgetMemory
    ).map { if (it.name in PINNED) it.pin() else it }

    private fun launch(ctx: ToolContext, i: Intent): Boolean = try {
        ctx.context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { false }

    private fun has(ctx: ToolContext, perm: String) = ctx.context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    /** "2026-10-15T18:30", "2026-10-15 18:30" or "18:30" (next occurrence) -> epoch millis. */
    fun parseWhen(text: String): Long? {
        val t = text.trim().replace(' ', 'T')
        val zone = ZoneId.systemDefault()
        return try { LocalDateTime.parse(t).atZone(zone).toInstant().toEpochMilli() } catch (e: Exception) {
            try {
                val time = LocalTime.parse(t)
                var dt = LocalDateTime.of(LocalDate.now(), time)
                if (dt.atZone(zone).toInstant().toEpochMilli() <= System.currentTimeMillis()) dt = dt.plusDays(1)
                dt.atZone(zone).toInstant().toEpochMilli()
            } catch (e2: Exception) { null }
        }
    }

    private val getCurrentTime = tool("get_current_time", "Current date, time and weekday on the phone.") { _, _ ->
        ToolResult.ok(SimpleDateFormat("EEEE, d MMMM yyyy, hh:mm a (zzz)", Locale.getDefault()).format(Date()))
    }

    private val DAYS = mapOf("sun" to Calendar.SUNDAY, "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY,
        "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY)

    private val setAlarm = tool("set_alarm", "Set a real alarm in the phone's clock app for a time of day. Optionally repeating on given weekdays.",
        p("hour", "Hour 0-23", true, "INTEGER"), p("minute", "Minute 0-59", true, "INTEGER"),
        p("label", "Alarm label"), p("days", "Repeat days, comma separated like mon,tue,fri; empty = once")) { ctx, a ->
        val h = a.optInt("hour", -1); val m = a.optInt("minute", -1)
        if (h !in 0..23 || m !in 0..59) return@tool ToolResult.fail("Time galat hai - hour 0-23 aur minute 0-59 batao.")
        val days = a.optString("days").lowercase().split(",", " ").mapNotNull { DAYS[it.trim().take(3)] }
        val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label")).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (days.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
        if (launch(ctx, i)) ToolResult.ok("Alarm set: %02d:%02d%s%s.".format(h, m, if (days.isNotEmpty()) " (repeat)" else "", a.optString("label").takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""))
        else ToolResult.fail("Clock app alarm set nahi kar paaya (koi clock app nahi mili ya permission nahi hai).")
    }

    private val cancelAlarm = tool("cancel_alarm", "Dismiss an alarm: by label, or by its time.",
        p("label", "Label of the alarm"), p("hour", "Hour 0-23", type = "INTEGER"), p("minute", "Minute 0-59", type = "INTEGER")) { ctx, a ->
        val i = Intent(AlarmClock.ACTION_DISMISS_ALARM).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        when {
            a.optString("label").isNotBlank() -> i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL).putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label"))
            a.has("hour") -> i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME).putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour")).putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute", 0))
            else -> return@tool ToolResult.fail("Kaunsa alarm - label ya time batao.")
        }
        if (launch(ctx, i)) ToolResult.ok("Alarm cancel karne ki request clock app ko bhej di. (Clock app ne kya kiya wo main dekh nahi sakti.)") else ToolResult.fail("Clock app ne request nahi li.")
    }

    private val setTimer = tool("set_timer", "Start a countdown timer in the clock app.",
        p("seconds", "Length in seconds", true, "INTEGER"), p("label", "Timer label")) { ctx, a ->
        val s = a.optInt("seconds", 0)
        if (s !in 1..86400) return@tool ToolResult.fail("Timer 1 second se 24 ghante ke beech hona chahiye.")
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s).putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label")).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (launch(ctx, i)) ToolResult.ok("Timer shuru: ${if (s >= 60) "${s / 60} min ${s % 60} sec" else "$s sec"}.") else ToolResult.fail("Clock app timer nahi chala paaya.")
    }

    private val cancelTimer = tool("cancel_timer", "Dismiss the running timer in the clock app.") { ctx, _ ->
        if (launch(ctx, Intent(AlarmClock.ACTION_DISMISS_TIMER))) ToolResult.ok("Timer band karne ki request clock app ko bhej di.") else ToolResult.fail("Clock app ne request nahi li.")
    }

    private val addReminder = tool("add_reminder", "Remind the user of something with a notification. Give either in_minutes, or at as 'YYYY-MM-DDTHH:MM' (or just 'HH:MM' for the next such time).",
        p("text", "What to remind about", true), p("in_minutes", "Minutes from now", type = "INTEGER"), p("at", "Exact local time")) { ctx, a ->
        val text = a.optString("text").trim().ifEmpty { return@tool ToolResult.fail("Kis cheez ka reminder?") }
        val at = when {
            a.optInt("in_minutes", 0) > 0 -> System.currentTimeMillis() + a.optInt("in_minutes") * 60_000L
            a.optString("at").isNotBlank() -> parseWhen(a.optString("at")) ?: return@tool ToolResult.fail("Time samajh nahi aaya - 'YYYY-MM-DDTHH:MM' ya 'HH:MM' me do.")
            else -> return@tool ToolResult.fail("Kab yaad dilana hai - in_minutes ya at batao.")
        }
        if (at <= System.currentTimeMillis()) return@tool ToolResult.fail("Wo time to nikal chuka hai.")
        val item = ReminderStore.add(ctx.context, text, at)
        ToolResult.ok("Reminder set: '$text' - ${SimpleDateFormat("EEE d MMM, hh:mm a", Locale.getDefault()).format(Date(at))}.") { put("id", item.id) }
    }

    private val listReminders = tool("list_reminders", "List the pending reminders.") { ctx, _ ->
        val l = ReminderStore.list(ctx.context)
        if (l.isEmpty()) ToolResult.ok("Koi pending reminder nahi hai.")
        else ToolResult.ok(l.joinToString("\n") { "- ${it.text} (${SimpleDateFormat("EEE d MMM, hh:mm a", Locale.getDefault()).format(Date(it.at))})" })
    }

    private val completeReminder = tool("complete_reminder", "Cancel/complete a pending reminder, matched by part of its text.", p("text", "Part of the reminder text", true)) { ctx, a ->
        val r = ReminderStore.remove(ctx.context, a.optString("text"))
        if (r.isEmpty()) ToolResult.fail("Aisa koi reminder nahi mila.") else ToolResult.ok("Reminder hata diya: " + r.joinToString { it.text })
    }

    private val addCalendarEvent = tool("add_calendar_event", "Open the calendar with a new event filled in (the user taps Save).",
        p("title", "Event title", true), p("start", "Local start 'YYYY-MM-DDTHH:MM'", true), p("duration_minutes", "Length, default 60", type = "INTEGER"),
        p("location", "Place"), p("description", "Notes")) { ctx, a ->
        val start = parseWhen(a.optString("start")) ?: return@tool ToolResult.fail("Start time samajh nahi aaya - 'YYYY-MM-DDTHH:MM' me do.")
        val end = start + a.optInt("duration_minutes", 60).coerceIn(5, 1440) * 60_000L
        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, a.optString("title")).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end).putExtra(CalendarContract.Events.EVENT_LOCATION, a.optString("location"))
            .putExtra(CalendarContract.Events.DESCRIPTION, a.optString("description"))
        if (launch(ctx, i)) ToolResult.ok("Calendar khul gaya '${a.optString("title")}' ke saath - Save dabana hoga, wo main nahi kar sakti jab tak tum na bolo.") else ToolResult.fail("Calendar app nahi mili.")
    }

    // ---- contacts
    private val findContact = tool("find_contact", "Look up a contact's phone number by name.", p("name", "Name or part of it", true)) { ctx, a ->
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return@tool ToolResult.fail("Contacts ki permission nahi hai - App permissions me allow karo.")
        val q = a.optString("name").trim().ifEmpty { return@tool ToolResult.fail("Kiska naam?") }
        val rows = ArrayList<String>()
        ctx.context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"), "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")?.use { c ->
            while (c.moveToNext() && rows.size < 8) rows += "${c.getString(0)}: ${c.getString(1)}"
        }
        if (rows.isEmpty()) ToolResult.ok("'$q' naam ka koi contact nahi mila.") else ToolResult.ok(rows.distinct().joinToString("\n"))
    }

    private val saveContact = tool("save_contact", "Open the new-contact screen with a name and number filled in (the user taps Save).",
        p("name", "Contact name", true), p("number", "Phone number", true)) { ctx, a ->
        val i = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
            .putExtra(ContactsContract.Intents.Insert.NAME, a.optString("name")).putExtra(ContactsContract.Intents.Insert.PHONE, a.optString("number"))
        if (launch(ctx, i)) ToolResult.ok("Contact screen khul gayi - Save tum dabana.") else ToolResult.fail("Contacts app nahi khul payi.")
    }

    private val editContact = tool("edit_contact", "Open an existing contact for editing.", p("name", "Contact name", true)) { ctx, a ->
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return@tool ToolResult.fail("Contacts ki permission nahi hai.")
        var uri: Uri? = null
        ctx.context.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY),
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?", arrayOf("%${a.optString("name")}%"), null)?.use { c ->
            if (c.moveToFirst()) uri = ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1))
        }
        val u = uri ?: return@tool ToolResult.fail("'${a.optString("name")}' contact nahi mila.")
        if (launch(ctx, Intent(Intent.ACTION_EDIT).setDataAndType(u, ContactsContract.Contacts.CONTENT_ITEM_TYPE))) ToolResult.ok("Contact edit screen khul gayi.") else ToolResult.fail("Edit screen nahi khul payi.")
    }

    private val answerCall = tool("answer_call", "Answer the phone call that is ringing right now.") { ctx, _ ->
        if (!has(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) return@tool ToolResult.fail("Call uthane ki permission (Phone) nahi hai - App permissions me allow karo.")
        try {
            (ctx.context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).acceptRingingCall()
            ToolResult.ok("Call uthane ka command bhej diya.")
        } catch (e: Exception) { ToolResult.fail("Call nahi utha paayi: ${e.message}") }
    }

    // ---- system / apps / files
    private val openSettings = tool("open_settings", "Open a phone settings page: wifi, bluetooth, display, sound, battery, location, apps, accessibility, hotspot, airplane, notifications, storage, date, language, or main.",
        p("page", "Which settings page", true)) { ctx, a ->
        val pkg = ctx.context.packageName
        val i = when (a.optString("page").lowercase().trim()) {
            "wifi", "wi-fi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "display", "brightness" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "sound", "volume" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "location", "gps" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "apps" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "hotspot", "tethering" -> Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
            "airplane", "flight" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            "storage" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            "date", "time" -> Intent(Settings.ACTION_DATE_SETTINGS)
            "language" -> Intent(Settings.ACTION_LOCALE_SETTINGS)
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        if (launch(ctx, i) || launch(ctx, Intent(Settings.ACTION_WIRELESS_SETTINGS)) || launch(ctx, Intent(Settings.ACTION_SETTINGS))) ToolResult.ok("Settings khol di: ${a.optString("page")}.") else ToolResult.fail("Settings nahi khul paayi.")
    }

    private val listInstalledApps = tool("list_installed_apps", "List apps installed on the phone, optionally filtered by name.", p("query", "Part of an app name")) { ctx, a ->
        val pm = ctx.context.packageManager
        val q = a.optString("query").trim().lowercase()
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.distinct().filter { q.isEmpty() || it.first.lowercase().contains(q) }.sortedBy { it.first.lowercase() }
        if (apps.isEmpty()) ToolResult.ok("Koi app nahi mili.")
        else ToolResult.ok(apps.take(60).joinToString("\n") { "${it.first} (${it.second})" } + if (apps.size > 60) "\n...(aur ${apps.size - 60})" else "")
    }

    private val shareText = tool("share_text", "Open the share sheet with some text so the user can send it anywhere.", p("text", "Text to share", true)) { ctx, a ->
        val i = Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a.optString("text")), "Share")
        if (launch(ctx, i)) ToolResult.ok("Share sheet khul gayi.") else ToolResult.fail("Share sheet nahi khul payi.")
    }

    private fun uriFor(ctx: ToolContext, f: File): Uri? = try { FileProvider.getUriForFile(ctx.context, ctx.context.packageName + ".fileprovider", f) } catch (e: Exception) { null }
    private fun mimeOf(f: File) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

    private val shareFile = tool("share_file", "Share a file from the phone through the share sheet.", p("path", "Full path of the file", true)) { ctx, a ->
        val f = File(a.optString("path"))
        if (!f.isFile) return@tool ToolResult.fail("File nahi mili: ${a.optString("path")}")
        val u = uriFor(ctx, f) ?: return@tool ToolResult.fail("Ye file share nahi ho sakti (location allowed nahi).")
        val i = Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mimeOf(f)).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share ${f.name}")
        if (launch(ctx, i)) ToolResult.ok("${f.name} ki share sheet khul gayi.") else ToolResult.fail("Share sheet nahi khul payi.")
    }

    private val findFiles = tool("find_files", "Find files by name in Downloads, Documents, DCIM, Pictures, Movies, Music. Newest first.",
        p("query", "Part of the file name", true)) { ctx, a ->
        if (!has(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) && android.os.Build.VERSION.SDK_INT < 33) return@tool ToolResult.fail("Storage ki permission nahi hai - App permissions me allow karo.")
        val q = a.optString("query").trim().lowercase().ifEmpty { return@tool ToolResult.fail("File ka naam batao.") }
        val root = Environment.getExternalStorageDirectory()
        val found = ArrayList<File>(); var visited = 0
        fun walk(d: File, depth: Int) {
            if (depth > 4 || visited > 6000) return
            d.listFiles()?.forEach { f ->
                visited++
                if (f.isDirectory) { if (!f.name.startsWith(".")) walk(f, depth + 1) } else if (f.name.lowercase().contains(q)) found += f
            }
        }
        listOf("Download", "Documents", "DCIM", "Pictures", "Movies", "Music").forEach { walk(File(root, it), 0) }
        if (found.isEmpty()) ToolResult.ok("'$q' naam ki koi file nahi mili.")
        else ToolResult.ok(found.sortedByDescending { it.lastModified() }.take(12).joinToString("\n") { "${it.absolutePath} (${it.length() / 1024} KB)" })
    }

    private val openFile = tool("open_file", "Open a file with a suitable app.", p("path", "Full path of the file", true)) { ctx, a ->
        val f = File(a.optString("path"))
        if (!f.isFile) return@tool ToolResult.fail("File nahi mili: ${a.optString("path")}")
        val u = uriFor(ctx, f) ?: return@tool ToolResult.fail("Ye file open nahi ho sakti (location allowed nahi).")
        if (launch(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(u, mimeOf(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))) ToolResult.ok("${f.name} khol di.") else ToolResult.fail("Is file ko kholne wali koi app nahi mili.")
    }

    private val showOnMap = tool("show_on_map", "Show a place, or places of a kind near the user (e.g. 'petrol pump', 'Taj Mahal'), in the maps app.", p("query", "Place or kind of place", true)) { ctx, a ->
        val q = a.optString("query").trim().ifEmpty { return@tool ToolResult.fail("Kya dhoondhna hai?") }
        if (launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))) ToolResult.ok("Maps me '$q' khol diya.") else ToolResult.fail("Maps app nahi mili.")
    }

    // ---- memory (uses the app's existing memory system)
    private val rememberFact = tool("remember_fact", "Remember something about the user for the future (birthday, preference, routine...).", p("text", "The fact, in the user's own words", true)) { ctx, a ->
        ToolResult.ok(ctx.app.memoryManager.rememberFact(a.optString("text")))
    }

    private val recallMemory = tool("recall_memory", "Say what is remembered about the user.") { ctx, _ -> ToolResult.ok(ctx.app.memoryManager.getWhatDoYouRemember()) }

    private val forgetMemory = tool("forget_memory", "Forget something that was remembered about the user.", p("query", "What to forget", true)) { ctx, a ->
        ToolResult.ok(ctx.app.memoryManager.forgetFact(a.optString("query")))
    }
}
