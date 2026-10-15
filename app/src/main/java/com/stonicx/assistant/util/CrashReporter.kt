package com.stonicx.assistant.util

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash on disk so it can be read inside the app on the next launch.
 *  - Java/Kotlin crashes: the stack trace is saved by the default uncaught-exception handler.
 *  - Silent exits (native crash, memory kill): a marker file is created while the voice service
 *    runs and removed on a clean stop; if it is still there at launch, the last run ended abnormally.
 */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val REPORT = "last_crash.txt"
    private const val MARKER = "voice_session_running"

    private fun file(ctx: Context, name: String) = File(ctx.applicationContext.filesDir, name)

    private fun deviceInfo(ctx: Context): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()
        return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "ABI: ${Build.SUPPORTED_ABIS.joinToString()}, lowRamDevice=${am?.isLowRamDevice}\n" +
            "RAM total=${mi.totalMem / 1048576}MB free=${mi.availMem / 1048576}MB, app heap max=${rt.maxMemory() / 1048576}MB used=${(rt.totalMemory() - rt.freeMemory()) / 1048576}MB"
    }

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun write(ctx: Context, title: String, t: Throwable?) {
        try {
            val sw = StringWriter()
            t?.printStackTrace(PrintWriter(sw))
            file(ctx, REPORT).writeText("$title\n${stamp()}\n${deviceInfo(ctx)}\n\n$sw")
        } catch (_: Throwable) { }
    }

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            write(app, "CRASH on thread '${thread.name}'", t)
            file(app, MARKER).delete() // a Java crash is already explained by the report
            previous?.uncaughtException(thread, t)
        }
        // Last run ended without a clean stop and without a Java crash report -> native crash / killed.
        val marker = file(app, MARKER)
        if (marker.exists()) {
            marker.delete()
            val report = file(app, REPORT)
            if (!report.exists() || report.lastModified() < marker.lastModified()) {
                write(app, "SILENT EXIT: voice session ended without a clean stop (native crash or Android killed the app, usually low memory).", null)
            }
        }
    }

    /** An exception that was caught and survived (e.g. in a coroutine); kept for diagnosis, app keeps running. */
    fun recordHandled(ctx: Context, t: Throwable, where: String) {
        Log.e(TAG, "Handled exception in $where", t)
        try {
            val f = file(ctx, "handled_errors.txt")
            if (f.length() > 20_000) f.delete()
            val sw = StringWriter(); t.printStackTrace(PrintWriter(sw))
            f.appendText("[${stamp()}] $where\n${sw.toString().lineSequence().take(14).joinToString("\n")}\n\n")
        } catch (_: Throwable) { }
    }

    fun markSessionStart(ctx: Context) { try { file(ctx, MARKER).writeText(stamp()) } catch (_: Throwable) { } }
    fun markSessionEnd(ctx: Context) { try { file(ctx, MARKER).delete() } catch (_: Throwable) { } }

    /** Shows the last crash (if any) with Copy / Share / Delete. Call from the main activity. */
    fun showPendingReport(activity: AppCompatActivity) {
        val report = file(activity, REPORT)
        if (!report.exists()) return
        val handled = file(activity, "handled_errors.txt").takeIf { it.exists() }?.readText().orEmpty()
        val text = report.readText().take(6000) + if (handled.isNotBlank()) "\n--- chhoti errors jo app ne sambhal li ---\n" + handled.takeLast(2500) else ""
        MaterialAlertDialogBuilder(activity)
            .setTitle("Pichhli baar app band hui thi")
            .setMessage(text)
            .setPositiveButton("Copy") { _, _ ->
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Mayra crash", text))
                report.delete()
            }
            .setNeutralButton("Share") { _, _ ->
                activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                }, "Crash report bhejo"))
                report.delete()
            }
            .setNegativeButton("Delete") { _, _ -> report.delete(); file(activity, "handled_errors.txt").delete() }
            .show()
    }
}
