package com.stonicx.assistant.tools.coding

import android.content.Intent
import androidx.core.content.FileProvider
import com.stonicx.assistant.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Tools that work on a repository downloaded onto the phone: clone/pull/fetch, read, agent-edit, push, share. */
object GitHubLocalTools {

    fun all(): List<MayraTool> = listOf(clone, pull, fetch, localRepos, localFiles, localRead, localChanges, agentEdit, push, share, discard)

    private const val R = "Repository as owner/name, or just the name to use the linked account"

    private fun noRepo(input: String) =
        ToolResult.fail("'$input' phone pe download nahi hai. Pehle github_clone_repo bolo.")

    /** Download [branch] of owner/name into the local store, replacing the old copy atomically. */
    private suspend fun downloadInto(ctx: ToolContext, owner: String, name: String, branch: String): ToolResult2 {
        val api = GitHubApi(ctx.app)
        val store = LocalRepoStore(ctx.context)
        val sha = api.call("GET", "/repos/$owner/$name/commits/$branch", accept = "application/vnd.github.sha")
        if (!sha.ok) return ToolResult2(null, sha.errorText())
        val zip = File(ctx.context.cacheDir, "dl_${owner}_$name.zip")
        val err = api.download("/repos/$owner/$name/zipball/$branch", zip)
        if (err != null) { zip.delete(); return ToolResult2(null, err) }
        return withContext(Dispatchers.IO) {
            try {
                val repoDir = store.repoDir(owner, name).apply { mkdirs() }
                val incoming = File(repoDir, "files.incoming")
                val files = store.extractZip(zip, incoming)
                val tree = File(repoDir, "files")
                val previous = File(repoDir, "files.previous")
                previous.deleteRecursively()
                if (tree.exists()) tree.renameTo(previous)
                incoming.renameTo(tree)
                previous.deleteRecursively()
                store.saveBase(owner, name, store.snapshot(tree))
                store.writeMeta(LocalRepoStore.Meta(owner, name, branch, sha.body.trim(), System.currentTimeMillis()))
                ToolResult2(files, null)
            } catch (e: Exception) {
                File(store.repoDir(owner, name), "files.incoming").deleteRecursively()
                ToolResult2(null, "Extract fail: ${e.message}")
            } finally { zip.delete() }
        }
    }

    private class ToolResult2(val files: Int?, val error: String?)

    private val clone = tool(
        "github_clone_repo",
        "Download a whole repository onto the phone so it can be read offline, changed by an agent, and shared as a folder. Takes a while on a big project. After this use github_agent_edit to change it and github_push_changes to send it back. If it is already on the phone with local changes this REFUSES rather than overwriting them - push or discard those first.",
        p("repo", R, true), p("branch", "Branch to download; defaults to the repo's default branch")
    ) { ctx, a ->
        val api = GitHubApi(ctx.app); val store = LocalRepoStore(ctx.context)
        val (owner, name) = api.resolve(a.optString("repo")) ?: return@tool ToolResult.fail("Repo ka naam batao (owner/name).")
        store.find("$owner/$name")?.let { existing ->
            val ch = store.changes(existing)
            if (ch == null || !ch.isEmpty) return@tool ToolResult.fail(
                "${existing.full} phone pe pehle se hai aur usme ${ch?.count ?: "kuch"} local badlaav hain - overwrite nahi karungi. Pehle github_push_changes ya github_discard_clone karo."
            )
        }
        val branch = a.optString("branch").ifBlank { api.defaultBranch(owner, name) ?: return@tool ToolResult.fail("$owner/$name nahi mila (private hai to GitHub token lagao).") }
        val r = downloadInto(ctx, owner, name, branch)
        if (r.error != null) ToolResult.fail(r.error)
        else ToolResult.ok("$owner/$name ($branch) phone pe download ho gaya - ${r.files} files.") { put("repo", "$owner/$name"); put("branch", branch); put("files", r.files) }
    }

    private val pull = tool(
        "github_pull",
        "Bring a repo cloned on the phone up to date with GitHub. REFUSES when there are local changes it would overwrite, and says what they are - push those first. Use for 'repo update kar do', 'naya code le aao'.",
        p("repo", R, true)
    ) { ctx, a ->
        val api = GitHubApi(ctx.app); val store = LocalRepoStore(ctx.context)
        val m = store.find(a.optString("repo")) ?: return@tool noRepo(a.optString("repo"))
        val ch = store.changes(m)
        if (ch == null || !ch.isEmpty) return@tool ToolResult.fail(
            "${m.full} me ${ch?.count ?: "kuch"} local badlaav hain jo pull se mit jaate - pehle github_push_changes karo."
        )
        val head = api.call("GET", "/repos/${m.owner}/${m.name}/commits/${m.branch}", accept = "application/vnd.github.sha")
        if (!head.ok) return@tool ToolResult.fail(head.errorText())
        if (head.body.trim() == m.commit) return@tool ToolResult.ok("${m.full} pehle se latest hai.")
        val r = downloadInto(ctx, m.owner, m.name, m.branch)
        if (r.error != null) ToolResult.fail(r.error) else ToolResult.ok("${m.full} update ho gaya (${r.files} files).")
    }

    private val fetch = tool(
        "github_fetch",
        "Check whether GitHub has anything newer than the copy on the phone, without changing anything. Use for 'kuch naya aaya hai kya?'.",
        p("repo", R, true)
    ) { ctx, a ->
        val api = GitHubApi(ctx.app); val store = LocalRepoStore(ctx.context)
        val m = store.find(a.optString("repo")) ?: return@tool noRepo(a.optString("repo"))
        val head = api.call("GET", "/repos/${m.owner}/${m.name}/commits/${m.branch}", accept = "application/vnd.github.sha")
        if (!head.ok) return@tool ToolResult.fail(head.errorText())
        if (head.body.trim() == m.commit) ToolResult.ok("Kuch naya nahi - ${m.full} (${m.branch}) latest hai.") { put("up_to_date", true) }
        else {
            val cmp = api.get("/repos/${m.owner}/${m.name}/compare/${m.commit}...${m.branch}").obj()
            val ahead = cmp?.optInt("ahead_by", -1) ?: -1
            ToolResult.ok("GitHub pe ${m.branch} me naya code hai${if (ahead > 0) " ($ahead naye commits)" else ""}. github_pull se le aao.") { put("up_to_date", false) }
        }
    }

    private val localRepos = tool(
        "github_local_repos",
        "List the repositories already downloaded onto this phone, with how many local changes each is carrying."
    ) { ctx, _ ->
        val store = LocalRepoStore(ctx.context)
        val repos = store.listRepos()
        if (repos.isEmpty()) return@tool ToolResult.ok("Abhi koi repo phone pe download nahi hai - github_clone_repo bolo.")
        val sb = StringBuilder("Phone pe ye repos hain:\n")
        repos.forEach { m ->
            val ch = store.changes(m)
            sb.append("- ${m.full} (${m.branch}) - ").append(
                when { ch == null -> "badlaav check nahi ho paaye"; ch.isEmpty -> "koi local badlaav nahi"; else -> "${ch.count} file badli hui" }
            ).append('\n')
        }
        ToolResult.ok(sb.toString().trim())
    }

    private val localFiles = tool(
        "github_local_files",
        "List the files in a downloaded repository.",
        p("repo", "The downloaded repository", true), p("folder", "Only inside this folder"), p("query", "Only paths containing this text")
    ) { ctx, a ->
        val store = LocalRepoStore(ctx.context)
        val m = store.find(a.optString("repo")) ?: return@tool noRepo(a.optString("repo"))
        val folder = a.optString("folder").trim().trim('/')
        val q = a.optString("query").trim().lowercase()
        var files = store.listFiles(m)
        if (folder.isNotEmpty()) files = files.filter { it.startsWith("$folder/") }
        if (q.isNotEmpty()) files = files.filter { it.lowercase().contains(q) }
        if (files.isEmpty()) return@tool ToolResult.ok("${m.full} me aisi koi file nahi mili.")
        val shown = files.take(150)
        ToolResult.ok(shown.joinToString("\n") + if (files.size > shown.size) "\n...(aur ${files.size - shown.size} files)" else "") { put("total", files.size) }
    }

    private val localRead = tool(
        "github_local_read",
        "Read a file from a downloaded repository - no network, so it is instant.",
        p("repo", "The downloaded repository", true), p("path", "File path inside the repo", true),
        p("from_line", "First line (1-based)", type = "INTEGER"), p("max_lines", "How many lines; defaults to 400", type = "INTEGER")
    ) { ctx, a ->
        val store = LocalRepoStore(ctx.context)
        val m = store.find(a.optString("repo")) ?: return@tool noRepo(a.optString("repo"))
        val path = a.optString("path")
        val f = store.resolveInTree(m, path)
        if (f == null || !f.isFile || store.looksBinary(f)) return@tool ToolResult.fail("'$path' local copy me nahi mili (ya binary hai).")
        val lines = f.readText().replace("\r\n", "\n").split("\n")
        val from = a.optInt("from_line", 1).coerceAtLeast(1)
        val max = a.optInt("max_lines", 400).coerceIn(1, 1500)
        if (from > lines.size) return@tool ToolResult.fail("'$path' me sirf ${lines.size} line hain.")
        val slice = lines.drop(from - 1).take(max)
        val end = from + slice.size - 1
        ToolResult.ok("$path lines $from-$end of ${lines.size}:\n\n" + slice.joinToString("\n") +
            if (end < lines.size) "\n...(from_line=${end + 1} se aage)" else "")
    }

    private val localChanges = tool(
        "github_local_changes",
        "What has been changed in a downloaded repository since it was downloaded, and what the changed lines are. ALWAYS use this after github_agent_edit and read it out before pushing.",
        p("repo", "The downloaded repository", true), p("with_code", "Include the changed lines. Defaults to true", type = "BOOLEAN")
    ) { ctx, a ->
        val store = LocalRepoStore(ctx.context)
        val m = store.find(a.optString("repo")) ?: return@tool noRepo(a.optString("repo"))
        val ch = store.changes(m) ?: return@tool ToolResult.fail("${m.full} me itni files hain ki main theek se compare nahi kar paayi - isliye main ye NAHI keh sakti ki kuch badla ya nahi badla.")
        if (ch.isEmpty) return@tool ToolResult.ok("${m.full} me koi local badlaav nahi hai.") { put("changed", 0) }
        val withCode = if (a.has("with_code")) a.optBoolean("with_code", true) else true
        val sb = StringBuilder("${m.full} me ${ch.count} badlaav:\n")
        if (ch.added.isNotEmpty()) sb.append("nayi: ").append(ch.added.joinToString(", ")).append('\n')
        if (ch.modified.isNotEmpty()) sb.append("badli: ").append(ch.modified.joinToString(", ")).append('\n')
        if (ch.deleted.isNotEmpty()) sb.append("hatai: ").append(ch.deleted.joinToString(", ")).append('\n')
        if (withCode) {
            var budget = 12000
            for (path in ch.added + ch.modified) {
                val f = store.resolveInTree(m, path) ?: continue
                if (store.looksBinary(f)) continue
                val body = f.readText().take(minOf(4000, budget))
                budget -= body.length
                sb.append("\n--- $path ---\n").append(body)
                if (f.length() > body.length) sb.append("\n...(file aur lambi hai)")
                if (budget <= 0) { sb.append("\n...(baaki files chhod di)"); break }
            }
            sb.append("\n\n(Ye files ka abhi ka roop hai - poora line-by-line diff GitHub pe push ke baad dikhega.)")
        }
        ToolResult.ok(sb.toString()) { put("changed", ch.count) }
    }

    private val agentEdit = tool(
        "github_agent_edit",
        "Hand a coding job to a sub-agent, which reads the relevant files in a downloaded repository and edits them locally. Nothing is pushed - the changes sit on the phone until github_push_changes. Describe the job the way you would to a developer: what should change and where. Takes 10-30 seconds.",
        p("repo", "The downloaded repository", true),
        p("task", "What to change, in full. Be specific about the behaviour wanted", true),
        p("files", "Comma-separated paths the agent should work on. Omit and the relevant files are found from the task text")
    ) { ctx, a ->
        val store = LocalRepoStore(ctx.context)
        val m = store.find(a.optString("repo")) ?: return@tool noRepo(a.optString("repo"))
        val task = a.optString("task").trim()
        if (task.isEmpty()) return@tool ToolResult.fail("Kaam kya karna hai wo to batao.")
        val all = store.listFiles(m)
        val wanted: List<String> = a.optString("files").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { w -> all.firstOrNull { it == w } ?: all.firstOrNull { it.endsWith("/$w") || it.substringAfterLast('/') == w } }
            .ifEmpty { CodeEditEngine.pickFiles(task, all) }
        if (wanted.isEmpty()) return@tool ToolResult.fail("Is kaam ke liye kaunsi file chhedni hai wo pata nahi chala. File ka naam bata do (files parameter me), ya pehle github_local_files se dhoondh lo.")
        CodeEditEngine.run(ctx, m, store, task, wanted, all)
    }

    private val push = tool(
        "github_push_changes",
        "Push every local change in a downloaded repository to GitHub as ONE commit. Read the changes out to the user first. If someone else pushed to the branch since it was downloaded, this refuses rather than overwriting their work.",
        p("repo", "The downloaded repository", true), p("message", "Commit message", true),
        p("branch", "Branch to push to; defaults to the one downloaded. A new name plus new_branch=true opens a fresh branch instead"),
        p("new_branch", "Create the branch instead of updating an existing one", type = "BOOLEAN")
    ) { ctx, a ->
        val api = GitHubApi(ctx.app); val store = LocalRepoStore(ctx.context)
        if (!api.hasToken) return@tool ToolResult.fail("Push ke liye GitHub token chahiye - Settings me token daalo.")
        val m = store.find(a.optString("repo")) ?: return@tool noRepo(a.optString("repo"))
        val msg = a.optString("message").trim().ifEmpty { return@tool ToolResult.fail("Commit message batao.") }
        val ch = store.changes(m) ?: return@tool ToolResult.fail("Itni files hain ki changes pakad nahi paayi - ye repo laptop se push karo.")
        if (ch.isEmpty) return@tool ToolResult.ok("${m.full} me push karne layak koi badlaav nahi hai.")
        val newBranch = a.optBoolean("new_branch", false)
        val target = a.optString("branch").ifBlank { m.branch }
        GitPusher.push(ctx, api, store, m, ch, msg, target, newBranch)
    }

    private val share = tool(
        "github_share_repo",
        "Zip a downloaded repository and open the share sheet, so the whole project can go to a laptop, Drive, WhatsApp or Bluetooth.",
        p("repo", "The downloaded repository", true)
    ) { ctx, a ->
        val store = LocalRepoStore(ctx.context)
        val m = store.find(a.optString("repo")) ?: return@tool noRepo(a.optString("repo"))
        withContext(Dispatchers.IO) {
            try {
                val tree = store.treeDir(m.owner, m.name)
                val outDir = File(ctx.context.cacheDir, "share").apply { mkdirs() }
                val zip = File(outDir, "${m.name}.zip")
                ZipOutputStream(zip.outputStream().buffered()).use { z ->
                    val prefix = tree.canonicalPath + File.separator
                    tree.walkTopDown().filter { it.isFile }.forEach { f ->
                        z.putNextEntry(ZipEntry("${m.name}/" + f.canonicalPath.removePrefix(prefix).replace(File.separatorChar, '/')))
                        f.inputStream().use { it.copyTo(z) }
                        z.closeEntry()
                    }
                }
                if (zip.length() == 0L) return@withContext ToolResult.fail("${m.name} ka zip nahi ban paya - folder khali to nahi hai?")
                val uri = FileProvider.getUriForFile(ctx.context, ctx.context.packageName + ".fileprovider", zip)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "${m.name} bhejo")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, "${m.name} bhejo").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    ctx.context.startActivity(chooser)
                    ToolResult.ok("${m.name} ka zip taiyaar hai (${zip.length() / 1024} KB) - share sheet khol diya, jahan bhejna hai chun lo.")
                } catch (e: Exception) {
                    ToolResult.ok("Zip ban gaya hai yahan: ${zip.absolutePath} - share sheet nahi khul payi.")
                }
            } catch (e: Exception) {
                ToolResult.fail("${m.name} ka zip nahi ban paya: ${e.message}")
            }
        }
    }

    private val discard = tool(
        "github_discard_clone",
        "Delete a downloaded repository from the phone, throwing away any local changes that were never pushed. If there are unpushed changes, ask the user first and call again with confirmed=true.",
        p("repo", "The downloaded repository", true), p("confirmed", "Set true only after the user said yes", type = "BOOLEAN")
    ) { ctx, a ->
        val store = LocalRepoStore(ctx.context)
        val m = store.find(a.optString("repo")) ?: return@tool noRepo(a.optString("repo"))
        val ch = store.changes(m)
        if ((ch == null || !ch.isEmpty) && !a.optBoolean("confirmed", false)) {
            return@tool ToolResult.fail("CONFIRM_REQUIRED - ${m.full} me ${ch?.count ?: "kuch"} unpushed badlaav hain jo delete ke saath chale jaayenge. User se haan-na poochho, haan bole tabhi confirmed=true ke saath dobara call karo.")
        }
        if (store.delete(m)) ToolResult.ok("${m.full} phone se hata diya." + if (ch != null && !ch.isEmpty) " Local badlaav bhi chale gaye." else "")
        else ToolResult.fail("${m.full} hataya nahi ja saka.")
    }
}
