package com.stonicx.assistant.coding.agent

import com.stonicx.assistant.StonicXApp
import com.stonicx.assistant.coding.overlay.CodingOverlayView.Kind
import com.stonicx.assistant.tools.Http
import com.stonicx.assistant.tools.SubAgent
import com.stonicx.assistant.tools.coding.TermuxBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * One coding agent that plans, writes, patches, checks and looks at its own work - modelled on the original
 * "PLAN / WRITE / SEARCH-REPLACE / READ / GREP / LOOK / RUN / DONE" verb loop. Everything it does is streamed to [Ui]
 * so the floating coding window can type it out live.
 */
class CodeAgent(private val app: StonicXApp, private val projects: CodeProjects, private val ui: Ui) {

    interface Ui { fun emit(text: String, kind: Kind); fun status(s: String); fun turn(n: Int) }
    class Outcome(val state: String, val message: String, val question: String? = null, val files: List<String> = emptyList())

    private class Verb(val name: String, val arg: String)
    private class Write(val path: String, val code: String, val closed: Boolean)
    private class Patch(val path: String, val search: String, val replace: String)
    private class Parsed(val writes: List<Write>, val patches: List<Patch>, val verbs: List<Verb>, val plan: List<String>?)

    companion object {
        const val MAX_TURNS = 26
        private val VERB = Regex("^(?:[-*>]\\s*)?(?:[0-9]+[.)]\\s*)?\\**\\s*(LIST|READ|LINES|GREP|WRITE|DELETE|NOTE|REMEMBER|ASK|DONE|SEARCH_WEB|READ_URL|RUN|LOOK|PLAN|STEP_DONE)\\b\\**:?\\s*(.*)$")
        private val SEARCH_M = Regex("^<{3,}\\s*SEARCH\\s*$"); private val EQ_M = Regex("^={3,}\\s*$"); private val REPL_M = Regex("^>{3,}\\s*REPLACE\\s*$")
        private val STEP = Regex("^\\s*(?:[-*]\\s*)?(\\d+)[.)]\\s+(.+)$")

        const val SYSTEM = """Tum Mayra ho - ek akeli, tez coding agent jo phone par websites aur code banati hai. Tum khud plan banati ho, khud code likhti ho, khud check karti ho. Koi doosra agent nahi hai - poora kaam tumhara hai.

User CODER NAHI hai. Wo batata hai use KYA chahiye ("acchha dikhe", "mobile pe sahi chale", "orange theme"). KAISE banana hai wo poori tarah tumhara faisla hai - font, layout, colour, file structure, sab. Technical sawaal user se mat poochho; sensible default lo aur bana do.

=== HAR JAWAB IN VERBS SE (har ek apni line par) ===

PLAN                     - kaam ko 2-8 step me todo, phir neeche numbered list (1. 2. 3.). Sirf pehli baar, ya jab plan sach me galat nikle.
STEP_DONE <n>            - plan ka step n poora ho gaya.
WRITE <path>             - poori nayi file. Agli line ```lang, phir poora code, phir ```. (Nayi file ke liye yahi use karo.)
<SEARCH/REPLACE block>   - pehle se bani file me chhota change:
                             style.css
                             <<<<<<< SEARCH
                             .hero { padding: 40px; }
                             =======
                             .hero { padding: clamp(20px,6vw,56px); }
                             >>>>>>> REPLACE
                           SEARCH me lines HU-BA-HU wahi jo file me hain.
READ <path>              - file dekhni hai (badi file ke liye LINES <path> a b).
GREP <text>              - poore project me dhoondo.
DELETE <path>            - file hatao.
LOOK <kya dekhna hai>    - page ko asli phone screen par KHOL kar dekho. UI/mobile kaam me likhne ke BAAD ek baar zaroor.
SEARCH_WEB <query>       - koi asli bahar ka fact chahiye (API ka exact naam).
READ_URL <url>           - ek page poora padho.
RUN <command>            - phone par command chalao (git, python3, node). Termux chahiye.
NOTE <baat>              - is project ke baare me yaad rakhne wali baat.
REMEMBER <baat>          - USER ki pasand jo har project pe kaam aaye.
ASK <sawaal?>            - SIRF tab jab user ki marzi ka asli tukda missing ho.
DONE <ek line>           - poora kaam ho gaya.

Verb BADE akshar me hi likho. "Done", "Run", "Note" jaisi chhote akshar wali line normal baat samjhi jayegi, command nahi.

Ek turn me 1-3 file change karo, zyada nahi - bada jawab beech me kat jaata hai aur aadhi file likh jaati hai. Badi nayi file: pehle WRITE se dhaancha, agle turn me bharo.

=== WEBSITE KABHI ADHURI DIKHNE NA PAAYE ===

- POORA PAGE EK SA: ek accent colour, ek tarah ke card, ek spacing scale, ek font. :root me variables, phir HAR jagah wahi. Do accent ya do card style = toota hua page.
- PHONE PEHLE: viewport meta, flex/grid, %/rem/clamp - koi fixed px width nahi, koi side scroll nahi. Button/link 44px+ (par icon ke andar ki cheez nahi). Body text 16px+. Ek @media breakpoint. img max-width:100%.
- Sab khud likho - CDN/external font/external image nahi, offline bhi khule. Icon inline SVG. Content ASLI likho, "Lorem ipsum" nahi.
- Jo pehle se theek hai use mat todo. Jo ALAG hai use baaki jaisa karo.

=== PAGE KO KUCH YAAD RAKHNA HO (score, like, guestbook, todo) ===

- Sirf tab jab user ne aisi cheez maangi ho. localStorage use karo (sab try/catch me) - data sirf usi phone/browser me rahega, ye baat page par ek chhoti line me likh do. Visitor ka likha text textContent se dikhao, innerHTML se kabhi nahi.
- Password, OTP, PIN, card number KABHI mat maango ya save karo, aur kisi asli company/bank/app jaisa login page mat banao.

=== SACH ===

- Jo nahi bana uske liye DONE mat bolo. Adhoora kaam "ho gaya" bolna sabse buri galti hai.
- Patch fail ho to output me wajah likhi hoti hai - "snippet mila hi nahi" = galat lines quote ki, dobara READ karke EXACT lo. Do-teen baar try karna normal hai.
- REMEMBER me kabhi API key/password/token nahi - sirf pasand aur style.

Markdown headings ya explanation ki zaroorat nahi - seedha verbs aur code."""

        const val PLAYBOOK = """- Pehle ek chhota design system socho: 1 accent colour + 1 dark/light base, 1 font stack (system-ui), spacing 4/8/16/24/40, radius 12-16, ek shadow. Ye sab :root variables me.
- Page ka flow: hero (ek line ka wada + ek bada button) -> 3-4 sections (kya/kaise/kyun) -> proof ya numbers -> ek saaf call-to-action -> chhota footer. Har section me ek hi idea.
- Hero me text kam, spacing zyada. Buttons bade aur gol. Cards ek jaise: same padding, same radius, same shadow.
- Hindi/English dono me padhne layak: line-height 1.55, max-width 60ch paragraph ke liye.
- Animation halki: transition 200ms, fade-in on scroll CSS se; prefers-reduced-motion ka dhyan.
- Accessibility: alt text, contrast 4.5:1, focus-visible outline, button asli <button>.
- Code: ek index.html + style.css + script.js (chhota). Inline SVG icons. Koi external cheez nahi."""

        const val VIEWER = """Tum Mayra ki coding team ki VIEWER ho. Tumhe ek website ke screenshot diye jayenge, jaise wo asli phone par dikh rahi hai. Tum code nahi dekhti, sirf SCREEN dekhti ho.

Jo dikh raha hai wahi batao, points me:
- TOOTA hua kya hai: khaali/safed jagah, aadha rendered section, ek doosre ke upar chadhe elements, screen se bahar nikla content, aisa text jo padha na ja sake, image jo aayi hi nahi.
- PHONE par kya galat lag raha hai: bahut chhota text, button jo ungli se tap na ho sake, cheezein kinare se chipki hui, side me scroll karna pad raha ho.
- DIKHNE me kya kamzor hai: aisa contrast jisme text background me kho jaye, do rang jo alag hi na lagein, layout jisme aankh ko pata na chale pehle kya padhna hai.
- Kya ACCHHA hai. Ye bhi zaroori hai - warna writer wo cheez bhi badal dega jo sahi thi.

Har baat ke saath jagah batao ("hero ke neeche wale teen cards") taaki writer usse code me dhoondh sake.
SABSE ZAROORI: sirf wahi likho jo screenshot me DIKH raha hai. Andaza mat lagao. Agar screen khaali/safed hai to yahi saaf-saaf likho - uska matlab page chal hi nahi raha. 15 line se zyada mat likho."""

        const val VERDICT = """Ye page BAN CHUKA hai aur user ko dene wali hoon. Batao ye dene layak hai ya nahi.

Sirf ASLI kharabi dekho - wo cheezein jo koi bhi dekh kar kahega "ye toota hua hai":
- koi cheez doosri ke UPAR chadhi hui (menu heading par, text image par)
- screen se BAHAR nikla content, ya side me scroll karna pad raha ho
- khaali/safed page, ya aadha rendered section
- text jo padha na ja sake (background me kho gaya, ya bahut chhota)
- ek hi page par do-do accent colour ya do-do tarah ke card
- button/link jo dikh hi nahi rahe ya ek doosre se chipke hain

Chhoti pasand-napasand (thoda zyada spacing hota) ko kharabi NA maano - wo theek hai.

SABSE AAKHRI line me se ek exactly aise likho:
VERDICT: THEEK
VERDICT: TOOTA - <kya toota hai, ek line me, jagah ke saath>"""
    }

    // ------------------------------------------------------------------ parsing
    private fun cleanPath(s: String): String = s.trim().trim('`', '*', ':', ' ').removePrefix("FILE ").removePrefix("PATCH ").removePrefix("file ").trim('`', ' ')

    private fun parse(text: String): Parsed {
        val lines = text.replace("\r\n", "\n").split("\n")
        val writes = ArrayList<Write>(); val patches = ArrayList<Patch>(); val verbs = ArrayList<Verb>(); var plan: MutableList<String>? = null
        var prev = ""; var i = 0
        while (i < lines.size) {
            val t = lines[i].trim()
            if (SEARCH_M.matches(t)) {
                val s = StringBuilder(); var k = i + 1
                while (k < lines.size && !EQ_M.matches(lines[k].trim())) { s.append(lines[k]).append('\n'); k++ }
                val r = StringBuilder(); k++
                while (k < lines.size && !REPL_M.matches(lines[k].trim())) { r.append(lines[k]).append('\n'); k++ }
                patches += Patch(cleanPath(prev), s.toString().removeSuffix("\n"), r.toString().removeSuffix("\n"))
                i = k + 1; continue
            }
            val m = VERB.matchEntire(t)
            if (m != null) {
                val name = m.groupValues[1]; val arg = m.groupValues[2].trim().trim('*', '`')
                when (name) {
                    "WRITE" -> {
                        var k = i + 1; while (k < lines.size && lines[k].isBlank()) k++
                        if (k < lines.size && lines[k].trim().startsWith("```")) {
                            k++; val code = StringBuilder(); var closed = false
                            while (k < lines.size) { if (lines[k].trim() == "```") { closed = true; break }; code.append(lines[k]).append('\n'); k++ }
                            writes += Write(cleanPath(arg), code.toString().removeSuffix("\n"), closed); i = k + 1; continue
                        } else verbs += Verb("WRITE_BAD", arg)
                    }
                    "PLAN" -> {
                        val steps = ArrayList<String>(); var k = i + 1
                        while (k < lines.size) { val sm = STEP.matchEntire(lines[k]); if (sm != null) steps += sm.groupValues[2].trim() else if (lines[k].isNotBlank()) break; k++ }
                        if (steps.isNotEmpty()) plan = steps; i = k; continue
                    }
                    else -> verbs += Verb(name, arg)
                }
                i++; continue
            }
            if (t.isNotEmpty() && !t.startsWith("```")) prev = t
            i++
        }
        return Parsed(writes, patches, verbs, plan)
    }

    // ------------------------------------------------------------------ prompt
    private fun listFilesText(slug: String): String {
        val fs = projects.files(slug)
        return if (fs.isEmpty()) "(khaali - naya project)" else fs.joinToString("\n") { "- $it (${projects.read(slug, it)?.length ?: 0} chars)" }
    }

    private fun codeBlock(slug: String): String {
        var budget = 110_000; val sb = StringBuilder()
        for (p in projects.files(slug)) {
            val t = projects.read(slug, p) ?: continue
            val body = if (t.length > budget) t.take(maxOf(0, budget)) + "\n...(file aur lambi hai - READ/LINES se dekho)" else t
            sb.append("--- ").append(p).append(" ---\n```\n").append(body).append("\n```\n"); budget -= body.length
            if (budget <= 0) { sb.append("...(baaki files budget ki wajah se chhodi - READ se dekho)\n"); break }
        }
        return sb.toString()
    }

    private fun buildPrompt(slug: String, meta: CodeProjects.Meta, request: String, plan: List<String>, done: Set<Int>, feedback: String, answered: String): String {
        val sb = StringBuilder()
        sb.append("PROJECT: ${meta.name} - ${meta.desc.take(300)}\n\nFILES ABHI:\n").append(listFilesText(slug)).append("\n\n")
        sb.append("=== DESIGN & BUILD PLAYBOOK (isko follow karo) ===\n").append(PLAYBOOK).append("\n=== PLAYBOOK KHATAM ===\n\n")
        val code = codeBlock(slug)
        if (code.isNotEmpty()) sb.append("=== POORA ASLI CODE (jaisa ABHI disk par hai) ===\n").append(code).append("=== CODE KHATAM ===\nUpar poora project hai. SEARCH me jo quote karo wo isi me se HU-BA-HU copy karo.\n\n")
        val notes = projects.notes(slug); if (notes.isNotEmpty()) sb.append("PROJECT NOTES:\n").append(notes.takeLast(12).joinToString("\n") { "- $it" }).append("\n\n")
        val rated = projects.history(slug).filter { it.optString("kind") == "feedback" }.takeLast(4)
        if (rated.isNotEmpty()) sb.append("USER NE PEHLE YE KAHA THA (ye wapas mat lao jo unhe pasand nahi aaya):\n").append(rated.joinToString("\n") { "- " + it.optString("text") }).append("\n\n")
        sb.append("USER NE YE MAANGA HAI:\n").append(request).append("\n\n")
        if (answered.isNotBlank()) sb.append(answered).append("\n\n")
        if (plan.isEmpty()) sb.append("ABHI KOI PLAN NAHI HAI. Pehle `PLAN` bhejo (2-8 step), phir kaam shuru karo.\n")
        else {
            sb.append("PLAN:\n"); plan.forEachIndexed { i, s -> sb.append("${i + 1}. ${if ((i + 1) in done) "[ho gaya] " else ""}$s\n") }
            val next = plan.indices.firstOrNull { (it + 1) !in done }
            if (next != null) sb.append("\nABHI YE STEP: ${next + 1} - ${plan[next]}\n") else sb.append("\nPlan ke saare step ho gaye - check karo aur DONE bhejo.\n")
        }
        if (feedback.isNotBlank()) sb.append("\nAB TAK:\n").append(feedback.takeLast(9000)).append("\n")
        sb.append("\nAgla kadam. Zaroori file(s) likho, step ho jaye to STEP_DONE, poora kaam ho jaye to DONE.")
        return sb.toString()
    }

    // ------------------------------------------------------------------ main loop
    suspend fun run(slug: String, request: String, resume: Boolean, answer: String?): Outcome {
        val meta = projects.meta(slug) ?: return Outcome("failed", "Project nahi mila.")
        var plan: MutableList<String> = ArrayList(); val done = HashSet<Int>(); val feedback = StringBuilder(); var turn = 0
        var req = request
        var answered = ""
        if (resume) projects.loadSession(slug)?.let { s ->
            req = s.optString("request", request).ifBlank { request }
            s.optJSONArray("plan")?.let { a -> for (i in 0 until a.length()) plan.add(a.optString(i)) }
            s.optJSONArray("done")?.let { a -> for (i in 0 until a.length()) done.add(a.optInt(i)) }
            feedback.append(s.optString("feedback")); turn = s.optInt("turn", 0)
            val asked = s.optString("asked")
            if (asked.isNotBlank() && !answer.isNullOrBlank()) answered = "Pichhli baar tumne poochha tha: \"$asked\"\nUser ka jawab: \"$answer\"\nYe jawab mil chuka hai - isi pe kaam karo, yahi sawaal dobara mat poochho."
            ui.emit("Resuming - ${done.size} steps already done.", Kind.WAIT)
        }
        projects.addHistory(slug, if (resume) "resume" else "request", req)
        var idle = 0; var rateRetries = 0; var doneRejects = 0; var looked = false
        val touched = LinkedHashSet<String>()

        while (turn < MAX_TURNS) {
            coroutineContext.ensureActive()
            turn++; ui.turn(turn); ui.status(if (plan.isEmpty()) "Planning…" else "Step ${(plan.indices.firstOrNull { (it + 1) !in done } ?: plan.size - 1) + 1}/${plan.size}")
            ui.emit("[turn $turn] ${if (plan.isEmpty()) "Planning…" else "Working…"}", Kind.STATUS)

            val prompt = buildPrompt(slug, meta, req, plan, done, feedback.toString(), answered); answered = ""
            val emitter = LineEmitter(ui)
            val res = GeminiStream.run(app, SYSTEM, prompt, onDelta = { emitter.feed(it) })
            emitter.flush()
            if (res.text == null) {
                if (res.retryAfterSec > 0 && rateRetries < 4) {
                    rateRetries++; turn--
                    ui.emit("Rate limit - ${res.retryAfterSec}s ruk kar phir koshish karungi (free tier).", Kind.WAIT)
                    delay((res.retryAfterSec.coerceIn(5, 90)) * 1000L); continue
                }
                save(slug, req, plan, done, feedback, turn, "")
                return Outcome("failed", (res.error ?: "Coding brain se jawab nahi aaya") + ". Thodi der baad bolo, ya resume_coding se wahin se aage badhaungi.")
            }
            rateRetries = 0
            val parsed = parse(res.text)
            val fb = StringBuilder(); var progressed = false; var failures = 0; var wantDone: String? = null; var asked: String? = null
            if (res.truncated) fb.append("[SYSTEM] Jawab beech me kat gaya - ek turn me kam aur chhoti files likho.\n")

            parsed.plan?.let { plan = it.toMutableList(); done.clear(); progressed = true; ui.emit("Plan: ${plan.size} step", Kind.OK); fb.append("plan set: ${plan.size} steps\n") }

            for (w in parsed.writes) {
                if (!w.closed) { failures++; fb.append("${w.path}: jawab beech me kat gaya (closing ``` nahi mili) - file chhoti rakho aur dobara WRITE karo\n"); ui.emit("✗ ${w.path}: kat gaya", Kind.ERR); continue }
                val err = projects.write(slug, w.path, w.code)
                if (err != null) { failures++; fb.append("${w.path}: $err\n"); ui.emit("✗ ${w.path}: $err", Kind.ERR) }
                else {
                    progressed = true; touched += w.path; ui.emit("✓ wrote ${w.path} (${w.code.length} chars)", Kind.OK); fb.append("wrote ${w.path}\n")
                    FileChecks.check(w.path, w.code).takeIf { it.isNotEmpty() }?.let { fb.append("FILE CHECK (${w.path}):\n").append(it.joinToString("\n") { x -> "  - $x" }).append("\n"); failures++ }
                }
            }
            for (pt in parsed.patches) {
                val path = pt.path
                if (projects.safePath(path) == null) { failures++; fb.append("$path: ye path allowed nahi hai (project ke andar simple relative path do)\n"); continue }
                if (pt.search.trim() == "(new file)") {
                    if (projects.read(slug, path) != null) { failures++; fb.append("$path: file pehle se hai - naye file ke liye (new file) nahi chalega\n"); continue }
                    val err = projects.write(slug, path, pt.replace)
                    if (err != null) { failures++; fb.append("$path: $err\n"); ui.emit("✗ $path: $err", Kind.ERR) } else { progressed = true; touched += path; ui.emit("✓ naya $path", Kind.OK); fb.append("edit $path: naya\n") }
                    continue
                }
                val cur = projects.read(slug, path)
                if (cur == null) { failures++; fb.append("$path: file exist hi nahi karti - naye file ke liye SEARCH section me (new file) likho\n"); ui.emit("✗ $path: file nahi hai", Kind.ERR); continue }
                val (newText, err) = PatchUtil.apply(cur, pt.search, pt.replace)
                if (newText == null) { failures++; fb.append("$path: $err\n"); ui.emit("✗ $path: $err", Kind.ERR); continue }
                val werr = projects.write(slug, path, newText)
                if (werr != null) { failures++; fb.append("$path: $werr\n"); ui.emit("✗ $path: $werr", Kind.ERR) }
                else {
                    progressed = true; touched += path; ui.emit("✓ edit $path", Kind.OK); fb.append("edit $path: ho gaya\n")
                    FileChecks.check(path, newText).takeIf { it.isNotEmpty() }?.let { fb.append("FILE CHECK ($path):\n").append(it.joinToString("\n") { x -> "  - $x" }).append("\n"); failures++ }
                }
            }
            if (failures > 0 && parsed.patches.isNotEmpty()) fb.append("\n[YE EDITS APPLY NAHI HUI] (upar wajah likhi hai)\n")

            for (v in parsed.verbs) {
                coroutineContext.ensureActive()
                when (v.name) {
                    "STEP_DONE" -> v.arg.trim().toIntOrNull()?.let { n -> if (n in 1..plan.size) { done.add(n); progressed = true; ui.emit("✓ step $n ho gaya", Kind.OK) } }
                    "READ" -> {
                        val t = projects.read(slug, v.arg.trim())
                        if (t == null) fb.append("READ ${v.arg}: (nahi mili)\nFiles: ${projects.files(slug).joinToString()}\n")
                        else fb.append("FILE ${v.arg}:\n```\n${t.take(18000)}${if (t.length > 18000) "\n...(badi file - LINES se hisson me padho)" else ""}\n```\n")
                    }
                    "LINES" -> {
                        val a = v.arg.split(Regex("\\s+")); val t = projects.read(slug, a.getOrElse(0) { "" })
                        if (t == null) fb.append("LINES ${v.arg}: (file nahi mili)\n") else {
                            val ls = t.split("\n"); val from = a.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1; val to = (a.getOrNull(2)?.toIntOrNull() ?: (from + 120)).coerceAtMost(ls.size)
                            fb.append("FILE ${a[0]} lines $from-$to of ${ls.size}:\n```\n${ls.subList(from - 1, maxOf(from, to)).joinToString("\n")}\n```\n")
                        }
                    }
                    "LIST" -> fb.append("FILES:\n${listFilesText(slug)}\n")
                    "GREP" -> {
                        val q = v.arg.trim(); val hits = ArrayList<String>()
                        if (q.isNotEmpty()) for (p in projects.files(slug)) projects.read(slug, p)?.lines()?.forEachIndexed { i, l -> if (hits.size < 40 && l.contains(q, true)) hits += "$p:${i + 1}: ${l.trim().take(140)}" }
                        fb.append("GREP '$q':\n${if (hits.isEmpty()) "(kahin nahi mila)" else hits.joinToString("\n")}\n")
                    }
                    "DELETE" -> if (projects.deleteFile(slug, v.arg.trim())) { progressed = true; touched.remove(v.arg.trim()); ui.emit("✓ delete ${v.arg}", Kind.OK); fb.append("delete ${v.arg}: ho gaya\n") } else fb.append("delete ${v.arg}: file nahi mili\n")
                    "NOTE" -> { projects.addNote(slug, v.arg); ui.emit("note: ${v.arg.take(60)}", Kind.THOUGHT) }
                    "REMEMBER" -> if (!Regex("(?i)AIza|ghp_|github_pat|sk-|password|token|secret|api[ _-]?key").containsMatchIn(v.arg)) runCatching { app.memoryManager.rememberFact(v.arg) } else fb.append("REMEMBER: secret jaisa lagta hai - save nahi kiya\n")
                    "SEARCH_WEB" -> {
                        ui.emit("web: ${v.arg.take(70)}", Kind.TOOL)
                        val a = SubAgent.ask(app, "Answer with concrete, current facts (exact names, versions, endpoints) in under 120 words, naming sources.", v.arg, googleSearch = true)
                        fb.append("SEARCH '${v.arg}':\n${a.text?.trim() ?: "[SYSTEM] Internet search available nahi hai."}\n")
                    }
                    "READ_URL" -> {
                        ui.emit("read: ${v.arg.take(70)}", Kind.TOOL)
                        val r = Http.get(v.arg.trim()); fb.append("PAGE ${v.arg}\n${if (r.ok) Http.htmlToText(r.body).take(6000) else "(khul nahi paaya)"}\n")
                    }
                    "RUN" -> { ui.emit("$ ${v.arg.take(80)}", Kind.TOOL); fb.append("run: ${v.arg}\n").append(runShell(slug, v.arg)).append("\n") }
                    "LOOK" -> { ui.emit("Dekh rahi hoon page kaisa lagta hai…", Kind.WAIT); fb.append("\n[DEKHA - asli screen se]\n").append(look(slug, v.arg.ifBlank { "poora page" })).append("\n"); looked = true }
                    "ASK" -> asked = v.arg
                    "DONE" -> wantDone = v.arg.ifBlank { "Kaam ho gaya." }
                    "WRITE_BAD" -> { failures++; fb.append("WRITE ${v.arg}: agli line me ```lang code fence nahi mili - format: WRITE path, phir ```lang, code, ```\n") }
                }
            }

            if (asked != null) {
                ui.emit("Ek sawaal: $asked", Kind.WAIT)
                save(slug, req, plan, done, feedback.append(fb), turn, asked)
                return Outcome("asked", "Mayra ek cheez poochh rahi hai: $asked", question = asked)
            }

            if (wantDone != null) {
                if (failures > 0) { fb.append("\nDONE roka - ${failures} edit/file check apply nahi hui ya fail hui. Pehle unhe theek karo.\n"); ui.emit("DONE roka — edit apply nahi hui", Kind.ERR) }
                else {
                    val problems = ArrayList<String>()
                    val all = projects.files(slug).associateWith { projects.read(slug, it).orEmpty() }
                    all.forEach { (p, t) -> FileChecks.check(p, t).forEach { problems += "$p: $it" } }
                    if (meta.kind == "website") problems += FileChecks.mobile(all)
                    if (problems.isNotEmpty() && doneRejects < 2) {
                        doneRejects++; fb.append("\n[MOBILE CHECK - DONE se pehle theek karo]\n").append(problems.take(10).joinToString("\n") { "  - $it" }).append("\n"); ui.emit("mobile check: ${problems.size} baat theek karni hai", Kind.ERR)
                    } else {
                        val verdict = if (meta.kind == "website" && !looked) finalLook(slug) else null
                        if (verdict != null && doneRejects < 3) {
                            doneRejects = 3; looked = true
                            fb.append("\nAsli phone screen par ye dikkatein DIKH rahi hain - SIRF inhe theek karo, baaki kuch mat badlo:\n$verdict\n"); ui.emit("Page par dikkat: ${verdict.take(80)}", Kind.ERR)
                        } else {
                            projects.addHistory(slug, "done", "$wantDone (files: ${touched.joinToString()})"); projects.clearSession(slug); projects.touch(slug)
                            ui.emit("Ho gaya. Files: ${projects.files(slug).joinToString()}", Kind.OK)
                            return Outcome("done", wantDone, files = projects.files(slug))
                        }
                    }
                }
            }

            if (progressed) idle = 0 else { idle++; if (fb.isEmpty()) fb.append("[SYSTEM] Is turn me kuch nahi hua. Ab code likho - WRITE ya SEARCH/REPLACE bhejo.\n") }
            if (idle >= 3) { save(slug, req, plan, done, feedback, turn, ""); return Outcome("failed", "Kaam aage nahi badh raha tha. Jo bana wo safe hai - dobara bologe to wahin se aage badhaungi.") }
            feedback.setLength(0); feedback.append(fb)
            save(slug, req, plan, done, feedback, turn, "")
        }
        save(slug, req, plan, done, feedback, turn, "")
        return Outcome("failed", "Bahut der ho gayi (${MAX_TURNS} turns). Jo bana wo safe hai - dobara bologe to wahin se aage badhaungi.")
    }

    private fun save(slug: String, req: String, plan: List<String>, done: Set<Int>, fb: CharSequence, turn: Int, asked: String) {
        projects.saveSession(slug, JSONObject().put("request", req).put("plan", JSONArray(plan)).put("done", JSONArray(done.toList()))
            .put("feedback", fb.toString().takeLast(6000)).put("turn", turn).put("asked", asked))
    }

    // ------------------------------------------------------------------ eyes & hands
    private suspend fun look(slug: String, what: String): String {
        val shot = screenshot(slug) ?: return "[SYSTEM] Page is phone par dekha nahi ja sakta - code se hi judge karo."
        val a = SubAgent.ask(app, VIEWER, "Dekho: $what", temperature = 0.2, imagesJpeg = listOf(shot))
        return a.text?.trim() ?: "[SYSTEM] Page is phone par dekha nahi ja sakta - code se hi judge karo."
    }

    /** Returns problems text, or null when the page looks fine / cannot be viewed. */
    private suspend fun finalLook(slug: String): String? {
        ui.emit("Build finished — dekh rahi hoon asli page.", Kind.WAIT)
        val shot = screenshot(slug) ?: return null
        val t = SubAgent.ask(app, VERDICT, "Ye final page hai.", temperature = 0.1, imagesJpeg = listOf(shot)).text ?: return null
        val last = t.lines().lastOrNull { it.contains("VERDICT") }.orEmpty()
        return if (last.contains("TOOTA", true)) last.substringAfter("TOOTA").trimStart(' ', '-', ':').ifBlank { t.take(200) } else null
    }

    private suspend fun screenshot(slug: String): ByteArray? {
        val entry = projects.entry(slug) ?: return null
        val url = SiteServer.urlFor(slug, projects.filesDir(slug), entry) ?: return null
        return PageShot.capture(app, url)
    }

    private suspend fun runShell(slug: String, cmd: String): String {
        if (!TermuxBridge.isInstalled(app) || !TermuxBridge.hasPermission(app)) return "[SYSTEM] Termux is phone par use nahi ho sakta."
        val files = projects.files(slug); var total = 0
        val sync = StringBuilder("mkdir -p ~/mayra/${slug} && cd ~/mayra/${slug}\n")
        for (f in files) {
            val t = projects.read(slug, f) ?: continue; total += t.length; if (total > 180_000) break
            val b64 = android.util.Base64.encodeToString(t.toByteArray(), android.util.Base64.NO_WRAP)
            sync.append("mkdir -p \"\$(dirname ${TermuxBridge.shq(f)})\" && echo '$b64' | base64 -d > ${TermuxBridge.shq(f)}\n")
        }
        val r = TermuxBridge.run(app, sync.toString() + cmd, 120)
        val out = (r.stdout + (if (r.stderr.isNotBlank()) "\n[stderr]\n" + r.stderr else "")).trim()
        return "[SHELL] exit=${r.exitCode ?: -1}\n" + (out.take(3500).ifEmpty { "(koi output nahi)" })
    }

    /** Classifies streamed text line by line for the overlay: code in fences green, verbs blue, the rest pale text. */
    private class LineEmitter(val ui: Ui) {
        private val buf = StringBuilder(); private var inFence = false
        fun feed(d: String) { buf.append(d); while (true) { val i = buf.indexOf("\n"); if (i < 0) break; val l = buf.substring(0, i); buf.delete(0, i + 1); line(l) } }
        fun flush() { if (buf.isNotEmpty()) { line(buf.toString()); buf.setLength(0) } }
        private fun line(l: String) {
            val t = l.trim()
            if (t.startsWith("```")) { inFence = !inFence; return }
            val kind = when {
                inFence -> Kind.CODE
                SEARCH_M.matches(t) || EQ_M.matches(t) || REPL_M.matches(t) -> Kind.CODE
                t.startsWith("DONE") -> Kind.OK
                VERB.matches(t) -> Kind.TOOL
                else -> Kind.TEXT
            }
            if (t.isNotEmpty() || inFence) ui.emit(l, kind)
        }
    }
}
