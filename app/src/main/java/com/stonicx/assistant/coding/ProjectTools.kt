package com.stonicx.assistant.coding

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.stonicx.assistant.coding.agent.CodeProjects
import com.stonicx.assistant.coding.agent.SiteServer
import com.stonicx.assistant.tools.*
import com.stonicx.assistant.tools.coding.GitHubApi
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Tools of the coding agent: build websites/code projects on the phone, change them, look at them, share them. */
object ProjectTools {
    private val PINNED = setOf("create_website", "update_website", "coding_status", "cancel_coding", "create_code_project")

    fun all(): List<MayraTool> = listOf(
        createWebsite, createCodeProject, updateWebsite, codingStatus, cancelCoding, resumeCoding, listWebsites, listProjectFiles, readWebsiteCode,
        openProject, deleteWebsite, projectHistory, rateProject, shareProject, exportProjectFiles, importProjectZip, pushProjectToGithub
    ).map { if (it.name in PINNED) it.pin() else it }

    private fun fmt(t: Long) = SimpleDateFormat("d MMM, hh:mm a", Locale.getDefault()).format(Date(t))
    private fun projects(ctx: ToolContext) = CodeProjects(ctx.context)
    private fun missing(name: String) = ToolResult.fail("'$name' naam ka koi project nahi mila. list_websites se dekho.")

    private val createWebsite = tool("create_website",
        "Build a complete website on the phone with the coding agent (plans, writes the files, checks them and looks at the page). It runs in the BACKGROUND with a floating 'Mayra is coding' window, so this returns at once - then use coding_status. The user is not a coder: pass what they want, add sensible detail yourself.",
        p("project", "Short project name, e.g. 'portfolio' or 'birthday card'", true),
        p("prompt", "Detailed description of the website to build: purpose, sections, colours/mood, language, any text the user gave", true)) { ctx, a ->
        val name = a.optString("project").trim().ifEmpty { return@tool ToolResult.fail("Project ka naam batao.") }
        val prompt = a.optString("prompt").trim().ifEmpty { return@tool ToolResult.fail("Kaisi website chahiye wo batao.") }
        val msg = CodeJobs.start(ctx.context, name, prompt, "website", prompt, update = false)
        if (msg.startsWith("Coding shuru")) ToolResult.ok(msg) else ToolResult.fail(msg)
    }

    private val createCodeProject = tool("create_code_project",
        "Build a code project in any language (script, bot, tool, game, API...) on the phone with the coding agent. Runs in the background with the floating coding window; use coding_status for progress.",
        p("project", "Short project name", true), p("prompt", "Detailed description of what the program should do", true),
        p("language", "Language or stack, e.g. python, node, kotlin; default: best fit")) { ctx, a ->
        val name = a.optString("project").trim().ifEmpty { return@tool ToolResult.fail("Project ka naam batao.") }
        val prompt = a.optString("prompt").trim().ifEmpty { return@tool ToolResult.fail("Kya banana hai wo batao.") }
        val full = prompt + a.optString("language").takeIf { it.isNotBlank() }?.let { "\n\nLanguage/stack: $it" }.orEmpty() + "\n\n(Ye code project hai, website nahi - README.md me chalane ka tareeka likho.)"
        val msg = CodeJobs.start(ctx.context, name, prompt, "code", full, update = false)
        if (msg.startsWith("Coding shuru")) ToolResult.ok(msg) else ToolResult.fail(msg)
    }

    private val updateWebsite = tool("update_website",
        "Change an existing project (website or code): new section, theme change, bug fix, make it mobile friendly... Runs in the background with the floating coding window.",
        p("project", "The project name (whatever the user called it)", true), p("prompt", "Exactly what to change, in full detail", true)) { ctx, a ->
        val name = a.optString("project").trim().ifEmpty { return@tool ToolResult.fail("Kaunsa project badalna hai?") }
        val prompt = a.optString("prompt").trim().ifEmpty { return@tool ToolResult.fail("Kya badalna hai wo batao.") }
        val msg = CodeJobs.start(ctx.context, name, "", "website", prompt, update = true)
        if (msg.startsWith("Coding shuru")) ToolResult.ok(msg) else ToolResult.fail(msg)
    }

    private val codingStatus = tool("coding_status", "How the current (or last) coding job is going: running, done, asked a question, or failed.") { ctx, _ ->
        ToolResult.ok(CodeJobs.statusText(ctx.context)) { put("state", CodeJobs.current?.status ?: "idle") }
    }

    private val cancelCoding = tool("cancel_coding", "Stop the coding work that's running right now. Whatever was already written stays.") { _, _ -> ToolResult.ok(CodeJobs.cancel()) }

    private val resumeCoding = tool("resume_coding",
        "Continue a coding job that stopped or asked a question, from where it left off. If it asked the user something, pass the user's answer.",
        p("project", "The project name; leave out for the most recent unfinished one"), p("answer", "The user's answer to the question Mayra asked")) { ctx, a ->
        val ps = projects(ctx)
        val meta = a.optString("project").takeIf { it.isNotBlank() }?.let { ps.find(it) }
            ?: ps.list().firstOrNull { ps.loadSession(it.slug) != null }
            ?: return@tool ToolResult.fail("Koi adhura coding kaam nahi hai. Naya banwana ho to create_website bolo.")
        val msg = CodeJobs.start(ctx.context, meta.name, "", meta.kind, ps.loadSession(meta.slug)?.optString("request").orEmpty(), update = false, resume = true, answer = a.optString("answer").ifBlank { null })
        if (msg.startsWith("Coding shuru")) ToolResult.ok(msg.replace("Coding shuru", "Coding dobara shuru (wahin se aage)")) else ToolResult.fail(msg)
    }

    private val listWebsites = tool("list_websites", "List every project Mayra has built - name, what it is, when it was made and last changed.") { ctx, _ ->
        val l = projects(ctx).list()
        if (l.isEmpty()) ToolResult.ok("Abhi koi project nahi bana - create_website se shuru karo.")
        else ToolResult.ok(l.joinToString("\n") { "- ${it.name} (${if (it.kind == "website") "website" else "code"}): ${it.desc.take(70)} - bana ${fmt(it.created)}, badla ${fmt(it.updated)}${if (it.github.isNotBlank()) ", GitHub: ${it.github}" else ""}" })
    }

    private val listProjectFiles = tool("list_project_files", "List the files inside one project, with sizes.", p("project", "The project name", true)) { ctx, a ->
        val ps = projects(ctx); val m = ps.find(a.optString("project")) ?: return@tool missing(a.optString("project"))
        val fs = ps.files(m.slug)
        if (fs.isEmpty()) ToolResult.ok("${m.name} abhi khaali hai.") else ToolResult.ok(fs.joinToString("\n") { "$it (${(ps.read(m.slug, it)?.length ?: 0)} chars)" })
    }

    private val readWebsiteCode = tool("read_website_code", "Read a file of a project (default: the main file). For big files pass from/to line numbers.",
        p("project", "The project name", true), p("file", "File inside the project, e.g. 'css/style.css'; leave out for the main file"),
        p("from", "First line (1-based)", type = "INTEGER"), p("to", "Last line", type = "INTEGER")) { ctx, a ->
        val ps = projects(ctx); val m = ps.find(a.optString("project")) ?: return@tool missing(a.optString("project"))
        val file = a.optString("file").ifBlank { ps.entry(m.slug) ?: ps.files(m.slug).firstOrNull().orEmpty() }
        val t = ps.read(m.slug, file) ?: return@tool ToolResult.fail("'$file' is project me hai hi nahi. Ye files hain: ${ps.files(m.slug).joinToString()}")
        val ls = t.split("\n"); val from = a.optInt("from", 1).coerceAtLeast(1); val to = a.optInt("to", from + 199).coerceAtMost(ls.size)
        if (t.length > 14000 && !a.has("from")) return@tool ToolResult.fail("'$file' badi hai (${t.length} characters) - poori nahi padhi ja sakti. read_website_code ko 'from' aur 'to' line number ke saath chalao, thode-thode hisse me.")
        ToolResult.ok("$file lines $from-$to of ${ls.size}:\n" + ls.subList(from - 1, maxOf(from, to)).joinToString("\n"))
    }

    private val openProject = tool("open_project", "Open a website MAYRA HERSELF BUILT (from create_website) in the browser. NOT a general URL opener - for any internet site use the normal open_website tool.",
        p("project", "The project name", true)) { ctx, a ->
        val ps = projects(ctx); val m = ps.find(a.optString("project")) ?: return@tool missing(a.optString("project"))
        val entry = ps.entry(m.slug) ?: return@tool ToolResult.fail("${m.name} me koi web page nahi hai - ye code project hai, isme kholne ko kuch nahi.")
        val url = SiteServer.urlFor(m.slug, ps.filesDir(m.slug), entry) ?: return@tool ToolResult.fail("Local server start nahi ho paaya.")
        try { ctx.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ToolResult.ok("${m.name} browser me khol di (ye sirf tumhare phone pe chal rahi hai).") }
        catch (e: Exception) { ToolResult.fail("Browser nahi khul paaya.") }
    }

    private val deleteWebsite = tool("delete_website", "Delete a project from the phone for good. Say which project will be deleted and ask yes/no first; confirmed only after a clear yes.",
        p("project", "The project name", true), p("confirmed", "Only true after the user heard which project would be deleted and said yes", type = "BOOLEAN")) { ctx, a ->
        val ps = projects(ctx); val m = ps.find(a.optString("project")) ?: return@tool missing(a.optString("project"))
        if (!a.optBoolean("confirmed", false)) return@tool ToolResult.fail("CONFIRM_REQUIRED - '${m.name}' (${ps.files(m.slug).size} files) hamesha ke liye delete hone wala hai. User ko ye naam bolkar haan-na poochho; haan bole tabhi confirmed=true ke saath dobara chalao.")
        if (ps.delete(m.slug)) ToolResult.ok("'${m.name}' delete ho gaya.") else ToolResult.fail("Delete nahi ho paaya.")
    }

    private val projectHistory = tool("project_history", "What happened to a project over time: created, every change asked for, what the agent did, what worked and failed, and what the user said about it.",
        p("project", "The project name", true)) { ctx, a ->
        val ps = projects(ctx); val m = ps.find(a.optString("project")) ?: return@tool missing(a.optString("project"))
        val h = ps.history(m.slug)
        if (h.isEmpty()) ToolResult.ok("${m.name} ki koi history nahi hai.")
        else ToolResult.ok("${m.name} (bana ${fmt(m.created)}):\n" + h.takeLast(25).joinToString("\n") { "${fmt(it.optLong("t"))} [${it.optString("kind")}] ${it.optString("text").take(160)}" })
    }

    private val rateProject = tool("rate_project", "Record what the user thought of a project, in their own words. Call this whenever they react to something built ('mast bana hai', 'ye colour achha nahi lag raha'). The agent reads it before the next change.",
        p("project", "The project name, exactly as the user said it", true), p("feedback", "The user's reaction, in their words", true)) { ctx, a ->
        val ps = projects(ctx); val m = ps.find(a.optString("project")) ?: return@tool missing(a.optString("project"))
        ps.addHistory(m.slug, "feedback", a.optString("feedback")); ToolResult.ok("Note kar liya - agli baar ye dhyan me rahega.")
    }

    private fun zipProject(ctx: ToolContext, m: CodeProjects.Meta): File? {
        val ps = projects(ctx); val files = ps.files(m.slug); if (files.isEmpty()) return null
        val out = File(ctx.context.cacheDir, "share").apply { mkdirs() }.let { File(it, "${m.slug}.zip") }
        ZipOutputStream(out.outputStream().buffered()).use { z -> files.forEach { f -> ps.file(m.slug, f)?.let { src -> z.putNextEntry(ZipEntry("${m.slug}/$f")); src.inputStream().use { it.copyTo(z) }; z.closeEntry() } } }
        return out
    }

    private fun shareZip(ctx: ToolContext, zip: File, label: String): Boolean = try {
        val uri = FileProvider.getUriForFile(ctx.context, ctx.context.packageName + ".fileprovider", zip)
        ctx.context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), label).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { false }

    private val shareProject = tool("share_project", "Zip a project and open the share sheet so it can go to WhatsApp, Drive, a laptop...", p("project", "The project name", true)) { ctx, a ->
        val ps = projects(ctx); val m = ps.find(a.optString("project")) ?: return@tool missing(a.optString("project"))
        val zip = zipProject(ctx, m) ?: return@tool ToolResult.fail("${m.name} khaali hai - share karne ko kuch nahi.")
        if (shareZip(ctx, zip, "${m.name} bhejo")) ToolResult.ok("${m.name} ka zip taiyaar (${zip.length() / 1024} KB) - share sheet khol di.") else ToolResult.ok("Zip ban gaya: ${zip.absolutePath} (share sheet nahi khul payi).")
    }

    private val exportProjectFiles = tool("export_project_files", "Save a project as a zip on the phone and return where it is (and open the share sheet).", p("project", "The project name", true)) { ctx, a ->
        val ps = projects(ctx); val m = ps.find(a.optString("project")) ?: return@tool missing(a.optString("project"))
        val zip = zipProject(ctx, m) ?: return@tool ToolResult.fail("${m.name} khaali hai.")
        val dest = File((ctx.context.getExternalFilesDir(null) ?: ctx.context.filesDir).apply { mkdirs() }, "${m.slug}.zip"); zip.copyTo(dest, overwrite = true)
        shareZip(ctx, dest, "${m.name} export")
        ToolResult.ok("Export ho gaya: ${dest.absolutePath}") { put("path", dest.absolutePath) }
    }

    private val importProjectZip = tool("import_project_zip", "Bring an existing project (a .zip on the phone) into Mayra so it can be changed with update_website.",
        p("path", "Full path of the zip file", true), p("project", "Name to give the project; default: the zip's name")) { ctx, a ->
        val zip = File(a.optString("path")); if (!zip.isFile) return@tool ToolResult.fail("Zip nahi mili: ${a.optString("path")}")
        val ps = projects(ctx); val name = a.optString("project").ifBlank { zip.nameWithoutExtension }
        val m = ps.create(name, "Imported from ${zip.name}", if (zip.name.contains("site", true)) "website" else "code")
        var n = 0; var skipped = 0
        ZipFile(zip).use { zf ->
            val entries = zf.entries().toList().filter { !it.isDirectory }
            val top = entries.map { it.name.substringBefore('/', "") }.distinct().singleOrNull()?.takeIf { t -> t.isNotEmpty() && entries.all { it.name.startsWith("$t/") } }
            for (e in entries) {
                if (n >= 300) { skipped++; continue }
                val rel = if (top != null) e.name.removePrefix("$top/") else e.name
                val text = try { zf.getInputStream(e).use { it.readBytes() }.toString(Charsets.UTF_8) } catch (x: Exception) { null }
                if (text == null || text.contains('\u0000') || ps.write(m.slug, rel, text) != null) skipped++ else n++
            }
        }
        ps.addHistory(m.slug, "import", "$n files imported from ${zip.name}")
        ToolResult.ok("'${m.name}' me $n files aa gayi" + (if (skipped > 0) " ($skipped chhodi - binary/bahut badi/allowed nahi)" else "") + ". Ab update_website se badal sakte ho.")
    }

    private val pushProjectToGithub = tool("push_project_to_github", "Upload a project to a GitHub repository (creates the repo if missing). Needs the GitHub token.",
        p("project", "The project name", true), p("repo", "Repository name; default: the project name"), p("private", "true for a private repo (default false)", type = "BOOLEAN"),
        p("message", "Commit message")) { ctx, a ->
        val api = GitHubApi(ctx.app)
        if (!api.hasToken) return@tool ToolResult.fail("Iske liye GitHub token chahiye - Settings me token daalo.")
        val ps = projects(ctx); val m = ps.find(a.optString("project")) ?: return@tool missing(a.optString("project"))
        val files = ps.files(m.slug); if (files.isEmpty()) return@tool ToolResult.fail("${m.name} khaali hai.")
        if (files.size > 60) return@tool ToolResult.fail("60 se zyada files hain - share_project se zip bhejo.")
        val owner = api.username() ?: return@tool ToolResult.fail("GitHub username nahi mila - token dobara daalo.")
        val repo = a.optString("repo").ifBlank { m.slug }.replace(Regex("[^A-Za-z0-9._-]"), "-")
        var branch = "main"
        val have = api.get("/repos/$owner/$repo")
        if (!have.ok) {
            if (have.code != 404) return@tool ToolResult.fail(have.errorText())
            val c = api.post("/user/repos", JSONObject().put("name", repo).put("private", a.optBoolean("private", false)).put("description", m.desc.take(200)).put("auto_init", true))
            if (!c.ok) return@tool ToolResult.fail(c.errorText())
            branch = c.obj()?.optString("default_branch").orEmpty().ifBlank { "main" }
        } else branch = have.obj()?.optString("default_branch").orEmpty().ifBlank { "main" }
        val msg = a.optString("message").ifBlank { "Update from Mayra" }
        var pushed = 0
        for (f in files) {
            val text = ps.read(m.slug, f) ?: continue
            val path = f.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            val existing = api.get("/repos/$owner/$repo/contents/$path?ref=$branch")
            val body = JSONObject().put("message", msg).put("content", android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP)).put("branch", branch)
            if (existing.ok) existing.obj()?.optString("sha")?.takeIf { it.isNotBlank() }?.let { body.put("sha", it) }
            val r = api.put("/repos/$owner/$repo/contents/$path", body)
            if (!r.ok) return@tool ToolResult.fail("'$f' upload nahi hui ($pushed pehle ho chuki thin): " + r.errorText())
            pushed++
        }
        ps.save(m.copy(github = "$owner/$repo")); ps.addHistory(m.slug, "github", "pushed $pushed files to $owner/$repo")
        ToolResult.ok("$pushed files $owner/$repo ($branch) pe push ho gayi - https://github.com/$owner/$repo")
    }
}
