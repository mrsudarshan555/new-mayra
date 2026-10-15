package com.stonicx.assistant.tools.coding

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs shell commands inside Termux through its RUN_COMMAND intent and waits for the result.
 * One-time setup the user must do: install Termux (F-Droid / GitHub build), put
 * `allow-external-apps=true` in ~/.termux/termux.properties, and grant this app the
 * "Run commands in Termux environment" permission.
 */
object TermuxBridge {
    const val PKG = "com.termux"
    const val PERM = "com.termux.permission.RUN_COMMAND"
    const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    const val HOME = "/data/data/com.termux/files/home"

    data class Result(val stdout: String, val stderr: String, val exitCode: Int?, val err: Int?, val errmsg: String, val timedOut: Boolean = false)

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Result>>()
    private val counter = AtomicInteger(1000)

    val SETUP_HELP = "Termux setup: 1) Termux F-Droid ya GitHub se install karo (Play Store wala purana hai). " +
        "2) Termux me ye chalao: mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties, phir Termux band karke dobara kholo. " +
        "3) Mayra ki App permissions me 'Run commands in Termux environment' allow karo."

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PKG, 0); true
    } catch (e: Exception) { false }

    fun hasPermission(ctx: Context): Boolean = ctx.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED

    /** Single-quote a string for safe use inside a shell command. */
    fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun deliver(id: Int, r: Result) { pending.remove(id)?.complete(r) }

    /** Returns null only when the intent could not even be sent; otherwise a Result (check err/exitCode). */
    suspend fun run(ctx: Context, script: String, timeoutSec: Int = 60, workdir: String = HOME, background: Boolean = true): Result {
        val id = counter.incrementAndGet()
        val deferred = CompletableDeferred<Result>()
        pending[id] = deferred
        try {
            val back = Intent(ctx, TermuxResultReceiver::class.java).putExtra("exec_id", id)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, back, flags)
            val wrapped = if (background) "timeout $timeoutSec bash -c ${shq(script)}" else script
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                setClassName(PKG, "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", BASH)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", wrapped))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", workdir)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
                if (!background) putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
                if (background) putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
            }
            try {
                // Use startService: Termux handles its own foreground state internally.
                // startForegroundService() on another app's service is restricted on Android 14+ (API 34)
                // and unnecessary since Termux's RunCommandService promotes itself.
                ctx.startService(intent)
            } catch (e: SecurityException) {
                return Result("", "", null, -1, "Termux ne permission nahi di. $SETUP_HELP")
            } catch (e: Exception) {
                return Result("", "", null, -1, "Termux ko command nahi bhej paayi: ${e.message}")
            }
            if (!background) return Result("", "", 0, null, "Termux me session khol diya.")
            return withTimeoutOrNull(timeoutSec * 1000L + 8000L) { deferred.await() }
                ?: Result("", "", null, null, "Termux se jawab nahi aaya (timeout).", timedOut = true)
        } finally {
            pending.remove(id)
        }
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("exec_id", -1)
        if (id < 0) return
        val b = intent.getBundleExtra("result")
        val r = if (b == null) TermuxBridge.Result("", "", null, -1, "Termux ne khaali result bheja.")
        else TermuxBridge.Result(
            stdout = b.getString("stdout").orEmpty(),
            stderr = b.getString("stderr").orEmpty(),
            exitCode = if (b.containsKey("exitCode")) b.getInt("exitCode") else null,
            err = if (b.containsKey("err")) b.getInt("err") else null,
            errmsg = b.getString("errmsg").orEmpty()
        )
        TermuxBridge.deliver(id, r)
    }
}
