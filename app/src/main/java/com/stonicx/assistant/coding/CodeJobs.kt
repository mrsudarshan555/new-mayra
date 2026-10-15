package com.stonicx.assistant.coding

import android.content.Context
import com.stonicx.assistant.StonicXApp
import com.stonicx.assistant.coding.agent.CodeAgent
import com.stonicx.assistant.coding.agent.CodeProjects
import com.stonicx.assistant.coding.overlay.CodingOverlay
import com.stonicx.assistant.coding.overlay.CodingOverlayView.Kind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Runs one coding job at a time in the background, drives the floating window and reports the result. */
object CodeJobs {
    class State(val slug: String, val name: String, val request: String, val started: Long) {
        @Volatile var status: String = "running"   // running | done | failed | cancelled | asked
        @Volatile var message: String = "Coding shuru…"
        @Volatile var turn: Int = 0
        @Volatile var step: String = "Planning…"
        @Volatile var question: String? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile var current: State? = null
        private set

    fun isRunning(): Boolean = job?.isActive == true

    /** Starts (or resumes) a coding job. Returns a short message for the tool result. */
    @Synchronized
    fun start(ctx: Context, name: String, desc: String, kind: String, request: String, update: Boolean, resume: Boolean = false, answer: String? = null): String {
        if (isRunning()) return "Ek coding kaam pehle se chal raha hai (${current?.name}) - dobara start nahi kiya. Usse poora hone do ya cancel_coding bolo."
        val app = ctx.applicationContext as StonicXApp
        val projects = CodeProjects(app)
        val meta = if (update || resume) (projects.find(name) ?: return "'$name' naam ka koi project nahi mila. list_websites se dekho, ya naya banwao.") else projects.create(name, desc.ifBlank { request }.take(200), kind)
        val st = State(meta.slug, meta.name, request, System.currentTimeMillis()); current = st
        CodingService.start(app)
        val realistic = app.preferences.codingRealisticTyping
        CodingOverlay.onStopRequested = { cancel() }
        CodingOverlay.show(app, "Mayra is coding", realistic)
        CodingOverlay.append("${if (update) "Update" else if (resume) "Resume" else "New"}: ${meta.name}", Kind.STATUS)

        val ui = object : CodeAgent.Ui {
            override fun emit(text: String, kind: Kind) { CodingOverlay.append(text, kind) }
            override fun status(s: String) { st.step = s; CodingOverlay.setStatus(s.take(24)) }
            override fun turn(n: Int) { st.turn = n }
        }
        job = scope.launch {
            val outcome = try {
                CodeAgent(app, projects, ui).run(meta.slug, request, resume, answer)
            } catch (e: CancellationException) {
                CodeAgent.Outcome("cancelled", "Coding rok di. Jo ban gaya tha wo safe hai.")
            } catch (e: Throwable) {
                com.stonicx.assistant.util.CrashReporter.recordHandled(app, e, "coding agent")
                CodeAgent.Outcome("failed", "Coding task fail ho gaya: ${e.message ?: e.javaClass.simpleName}")
            }
            st.status = outcome.state; st.message = outcome.message; st.question = outcome.question
            if (outcome.state == "cancelled") projects.addHistory(meta.slug, "cancelled", "user ne roka")
            CodingOverlay.append(
                when (outcome.state) { "done" -> "✓ ${outcome.message}"; "asked" -> "? ${outcome.message}"; "cancelled" -> "■ rok di"; else -> "✗ ${outcome.message}" },
                when (outcome.state) { "done" -> Kind.OK; "asked" -> Kind.WAIT; "cancelled" -> Kind.WAIT; else -> Kind.ERR }
            )
            CodingOverlay.finish(outcome.state == "done", if (outcome.state == "done") "Ho gaya ✓" else "Ruk gayi", if (outcome.state == "asked") 60000L else 9000L)
            CodingService.stop(app)
            notifyFinished(app, st, outcome)
        }
        return "Coding shuru: '${meta.name}'. Kaam peeche chal raha hai aur screen pe floating window me dikh raha hai. Jab tak coding_status 'done' na bataye tab tak user ko ye mat bolo ki ban gaya."
    }

    private fun notifyFinished(app: StonicXApp, st: State, o: CodeAgent.Outcome) {
        try {
            com.stonicx.assistant.tools.phone.ReminderStore.notify(app, 880_001, when (o.state) {
                "done" -> "'${st.name}' ban gaya ✓ - open_project bolo dekhne ke liye"
                "asked" -> "'${st.name}': ${o.question}"
                "cancelled" -> "'${st.name}' ki coding rok di"
                else -> "'${st.name}' ruk gayi: ${o.message.take(80)}"
            })
        } catch (_: Throwable) { }
        try {
            com.stonicx.assistant.background.StonicXConversationService.sendTextMessage(
                "[CODING UPDATE] Project '${st.name}': ${o.state.uppercase()}. ${o.message.take(300)}" +
                    (if (o.state == "done") " User ko ek chhoti line me batao ki ban gaya; dekhna ho to open_project." else if (o.state == "asked") " User se ye sawaal poochho aur jawab milne par resume_coding (answer ke saath) chalao." else " User ko seedhi saaf baat batao.")
            )
        } catch (_: Throwable) { }
    }

    fun cancel(): String {
        val j = job
        if (j == null || !j.isActive) return "Abhi koi coding kaam nahi chal raha."
        j.cancel(); return "Coding rok di. Jo ban gaya tha wo safe hai."
    }

    fun statusText(ctx: Context): String {
        val s = current ?: return "Abhi koi coding kaam nahi chal raha."
        val files = try { CodeProjects(ctx).files(s.slug) } catch (e: Exception) { emptyList() }
        return when (s.status) {
            "running" -> "'${s.name}' pe coding chal rahi hai - turn ${s.turn}, ${s.step}. Files abhi tak: ${files.joinToString().ifEmpty { "(abhi koi nahi)" }}."
            "done" -> "'${s.name}' DONE: ${s.message} Files: ${files.joinToString()}."
            "asked" -> "'${s.name}' ruki hui hai - Mayra ka sawaal: ${s.question}. User ka jawab milne par resume_coding chalao (answer ke saath)."
            "cancelled" -> "'${s.name}' ki coding roki gayi. Jo bana wo safe hai; resume_coding se aage badh sakti hai."
            else -> "'${s.name}' fail/ruk gayi: ${s.message}. resume_coding se wahin se aage badhaungi."
        }
    }
}
