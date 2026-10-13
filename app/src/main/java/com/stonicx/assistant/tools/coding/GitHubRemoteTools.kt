package com.stonicx.assistant.tools.coding

import android.util.Base64
import com.stonicx.assistant.tools.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Tools that talk straight to GitHub (no local clone): browse, read, search, branches, PRs, issues, Actions, gists. */
object GitHubRemoteTools {

    fun all(): List<MayraTool> = listOf(
        findRepo, listRepos, repoOverview, browseTree, readFile, searchCode, recentCommits, readCommit, compareBranches,
        listBranches, createBranch, listPrs, readPr, createPr, mergePr, listIssues, readIssue, createIssue, commentIssue,
        closeIssue, listReleases, createGist, myWork, actionsStatus, actionsLog, editFile, writeFile, deleteFile, explainRepo
    )

    private const val R = "Repository as owner/name, or just the name to use the linked account"
    private const val NO_TOKEN = "Iske liye GitHub token chahiye - Settings me token daalo."

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun encPath(p: String) = p.trim().trim('/').split("/").joinToString("/") { enc(it).replace("+", "%20") }
    private fun cut(s: String, n: Int) = if (s.length > n) s.take(n) + "\n...(aur bhi hai)" else s
    private fun JSONArray.objs(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    private suspend fun withRepo(ctx: ToolContext, a: JSONObject, block: suspend (GitHubApi, String, String) -> JSONObject): JSONObject {
        val api = GitHubApi(ctx.app)
        val pair = api.resolve(a.optString("repo")) ?: return ToolResult.fail("Repo ka naam owner/name ke roop me batao (ya GitHub token lagao taaki sirf naam se chale).")
        return block(api, pair.first, pair.second)
    }

    private fun refQ(a: JSONObject) = a.optString("branch").takeIf { it.isNotBlank() }?.let { "?ref=${enc(it)}" } ?: ""

    // ---------------------------------------------------------------- discovery / reading

    private val findRepo = tool("github_find_repo", "Search GitHub for repositories by name or topic.",
        p("query", "What to search for", true)) { ctx, a ->
        val r = GitHubApi(ctx.app).get("/search/repositories?q=${enc(a.optString("query"))}&per_page=6")
        if (!r.ok) return@tool ToolResult.fail(r.errorText())
        val items = r.obj()?.optJSONArray("items")?.objs().orEmpty()
        if (items.isEmpty()) ToolResult.ok("Is naam ka koi repo nahi mila.")
        else ToolResult.ok(items.joinToString("\n") { "- ${it.optString("full_name")} (${it.optInt("stargazers_count")}★, ${it.optString("language", "?")}) ${it.optString("description", "").take(100)}" })
    }

    private val listRepos = tool("github_list_repos", "List the linked GitHub account's own repositories, most recently updated first.",
        p("limit", "How many, default 15", type = "INTEGER")) { ctx, a ->
        val api = GitHubApi(ctx.app)
        if (!api.hasToken) return@tool ToolResult.fail(NO_TOKEN)
        val n = a.optInt("limit", 15).coerceIn(1, 50)
        val r = api.get("/user/repos?sort=updated&per_page=$n&affiliation=owner,collaborator")
        if (!r.ok) return@tool ToolResult.fail(r.errorText())
        val items = r.arr()?.objs().orEmpty()
        ToolResult.ok(if (items.isEmpty()) "Account me koi repo nahi hai." else items.joinToString("\n") {
            "- ${it.optString("full_name")}${if (it.optBoolean("private")) " (private)" else ""} - ${it.optString("description", "").take(80)}"
        })
    }

    private val repoOverview = tool("github_repo_overview", "Summary of a repo: description, languages, size, last update and the start of the README.",
        p("repo", R, true)) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            val r = api.get("/repos/$o/$n")
            if (!r.ok) return@withRepo ToolResult.fail(r.errorText())
            val j = r.obj() ?: return@withRepo ToolResult.fail("Repo ka data samajh nahi aaya.")
            val langs = api.get("/repos/$o/$n/languages").obj()?.keys()?.asSequence()?.take(6)?.joinToString(", ").orEmpty()
            val readme = api.call("GET", "/repos/$o/$n/readme", accept = "application/vnd.github.raw")
            ToolResult.ok(buildString {
                append("$o/$n - ${j.optString("description", "(koi description nahi)")}\n")
                append("Default branch: ${j.optString("default_branch")}, ${j.optInt("stargazers_count")}★, ${j.optInt("open_issues_count")} open issues, ")
                append("last push ${j.optString("pushed_at")}, ${if (j.optBoolean("private")) "private" else "public"}\n")
                if (langs.isNotBlank()) append("Languages: $langs\n")
                if (readme.ok) append("\nREADME:\n").append(cut(readme.body, 1500))
            })
        }
    }

    private val browseTree = tool("github_browse_tree", "List the files and folders at a path in a GitHub repo (no clone needed).",
        p("repo", R, true), p("path", "Folder path; empty for the root"), p("branch", "Branch, defaults to the default branch")) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            val r = api.get("/repos/$o/$n/contents/${encPath(a.optString("path"))}${refQ(a)}")
            if (!r.ok) return@withRepo ToolResult.fail(r.errorText())
            val arr = r.arr()
            if (arr == null) { val f = r.obj(); ToolResult.ok("Ye ek file hai: ${f?.optString("path")} (${f?.optInt("size")} bytes)") }
            else ToolResult.ok(cut(arr.objs().sortedBy { if (it.optString("type") == "dir") 0 else 1 }
                .joinToString("\n") { (if (it.optString("type") == "dir") "[dir] " else "") + it.optString("path") + if (it.optString("type") == "file") " (${it.optInt("size")} B)" else "" }, 4000))
        }
    }

    private val readFile = tool("github_read_file", "Read a file straight from GitHub (no clone needed).",
        p("repo", R, true), p("path", "File path", true), p("branch", "Branch, defaults to the default branch"),
        p("from_line", "First line (1-based)", type = "INTEGER"), p("max_lines", "How many lines, default 300", type = "INTEGER")) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            val path = a.optString("path")
            val r = api.call("GET", "/repos/$o/$n/contents/${encPath(path)}${refQ(a)}", accept = "application/vnd.github.raw")
            if (!r.ok) return@withRepo ToolResult.fail(r.errorText())
            val lines = r.body.replace("\r\n", "\n").split("\n")
            val from = a.optInt("from_line", 1).coerceAtLeast(1)
            val max = a.optInt("max_lines", 300).coerceIn(1, 1200)
            if (from > lines.size) return@withRepo ToolResult.fail("File me sirf ${lines.size} lines hain.")
            val slice = lines.drop(from - 1).take(max); val end = from + slice.size - 1
            ToolResult.ok("$path lines $from-$end of ${lines.size}:\n\n" + slice.joinToString("\n") + if (end < lines.size) "\n...(from_line=${end + 1} se aage)" else "")
        }
    }

    private val searchCode = tool("github_search_code", "Search for code text inside a repo (or all of the user's repos if repo is empty). Needs a GitHub token.",
        p("query", "Text to look for", true), p("repo", "Limit to this repo (owner/name or name)")) { ctx, a ->
        val api = GitHubApi(ctx.app)
        if (!api.hasToken) return@tool ToolResult.fail(NO_TOKEN)
        val scope = a.optString("repo").takeIf { it.isNotBlank() }?.let { api.resolve(it) }?.let { " repo:${it.first}/${it.second}" }
            ?: api.username()?.let { " user:$it" }.orEmpty()
        val r = api.get("/search/code?q=${enc(a.optString("query") + scope)}&per_page=10")
        if (!r.ok) return@tool ToolResult.fail(r.errorText())
        val items = r.obj()?.optJSONArray("items")?.objs().orEmpty()
        if (items.isEmpty()) ToolResult.ok("Kahin nahi mila.") else ToolResult.ok(items.joinToString("\n") { "- ${it.optJSONObject("repository")?.optString("full_name")}: ${it.optString("path")}" })
    }

    private val recentCommits = tool("github_recent_commits", "Latest commits on a branch.",
        p("repo", R, true), p("branch", "Branch, defaults to default"), p("limit", "How many, default 8", type = "INTEGER")) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            val sha = a.optString("branch").takeIf { it.isNotBlank() }?.let { "&sha=${enc(it)}" } ?: ""
            val r = api.get("/repos/$o/$n/commits?per_page=${a.optInt("limit", 8).coerceIn(1, 30)}$sha")
            if (!r.ok) return@withRepo ToolResult.fail(r.errorText())
            ToolResult.ok(r.arr()?.objs().orEmpty().joinToString("\n") {
                val c = it.optJSONObject("commit")
                "- ${it.optString("sha").take(7)} ${c?.optString("message", "")?.lineSequence()?.firstOrNull().orEmpty()} (${c?.optJSONObject("author")?.optString("name")}, ${c?.optJSONObject("author")?.optString("date")?.take(10)})"
            }.ifBlank { "Koi commit nahi mila." })
        }
    }

    private val readCommit = tool("github_read_commit", "Read one commit: message, author and the changed files with their patches.",
        p("repo", R, true), p("sha", "Commit sha (short is fine)", true)) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            val r = api.get("/repos/$o/$n/commits/${enc(a.optString("sha"))}")
            if (!r.ok) return@withRepo ToolResult.fail(r.errorText())
            val j = r.obj()!!; val c = j.optJSONObject("commit")
            val files = j.optJSONArray("files")?.objs().orEmpty()
            ToolResult.ok(cut(buildString {
                append("${j.optString("sha").take(7)} - ${c?.optString("message")}\nAuthor: ${c?.optJSONObject("author")?.optString("name")}\n")
                append("${files.size} files:\n")
                files.take(15).forEach { append("\n--- ${it.optString("filename")} (+${it.optInt("additions")}/-${it.optInt("deletions")}) ---\n${cut(it.optString("patch", ""), 700)}") }
            }, 6000))
        }
    }

    private val compareBranches = tool("github_compare_branches", "Compare two branches/commits: how far ahead/behind, commits and changed files.",
        p("repo", R, true), p("base", "Base branch or sha", true), p("head", "Head branch or sha", true)) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            val r = api.get("/repos/$o/$n/compare/${enc(a.optString("base"))}...${enc(a.optString("head"))}")
            if (!r.ok) return@withRepo ToolResult.fail(r.errorText())
            val j = r.obj()!!
            val commits = j.optJSONArray("commits")?.objs().orEmpty(); val files = j.optJSONArray("files")?.objs().orEmpty()
            ToolResult.ok(cut(buildString {
                append("${a.optString("head")} is ${j.optInt("ahead_by")} ahead, ${j.optInt("behind_by")} behind ${a.optString("base")} (${j.optString("status")}).\n")
                commits.takeLast(10).forEach { append("- ${it.optString("sha").take(7)} ${it.optJSONObject("commit")?.optString("message")?.lineSequence()?.firstOrNull()}\n") }
                append("${files.size} files badli:\n"); files.take(20).forEach { append("  ${it.optString("filename")} (+${it.optInt("additions")}/-${it.optInt("deletions")})\n") }
            }, 3500))
        }
    }

    private val listBranches = tool("github_list_branches", "List branches of a repo.", p("repo", R, true)) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            val r = api.get("/repos/$o/$n/branches?per_page=50")
            if (!r.ok) return@withRepo ToolResult.fail(r.errorText())
            ToolResult.ok(r.arr()?.objs().orEmpty().joinToString("\n") { "- " + it.optString("name") + if (it.optBoolean("protected")) " (protected)" else "" })
        }
    }

    private val createBranch = tool("github_create_branch", "Create a new branch on GitHub from an existing one.",
        p("repo", R, true), p("branch", "Name of the new branch", true), p("from", "Branch to start from; defaults to the default branch")) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            if (!api.hasToken) return@withRepo ToolResult.fail(NO_TOKEN)
            val name = a.optString("branch").trim().ifEmpty { return@withRepo ToolResult.fail("Branch ka naam to batao.") }
            val from = a.optString("from").ifBlank { api.defaultBranch(o, n) ?: return@withRepo ToolResult.fail("Default branch nahi mili.") }
            val sha = api.get("/repos/$o/$n/git/ref/heads/${encPath(from)}").obj()?.optJSONObject("object")?.optString("sha").orEmpty()
            if (sha.isBlank()) return@withRepo ToolResult.fail("'$from' branch ka sha nahi mila.")
            val r = api.post("/repos/$o/$n/git/refs", JSONObject().put("ref", "refs/heads/$name").put("sha", sha))
            if (r.ok) ToolResult.ok("$o/$n pe branch '$name' ban gayi, '$from' se (${sha.take(7)}).") else ToolResult.fail(r.errorText())
        }
    }

    // ---------------------------------------------------------------- pull requests

    private val listPrs = tool("github_list_prs", "List pull requests of a repo.",
        p("repo", R, true), p("state", "open, closed or all; default open")) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            val st = a.optString("state").ifBlank { "open" }
            val r = api.get("/repos/$o/$n/pulls?state=${enc(st)}&per_page=25")
            if (!r.ok) return@withRepo ToolResult.fail(r.errorText())
            val prs = r.arr()?.objs().orEmpty()
            if (prs.isEmpty()) ToolResult.ok("$o/$n me koi $st pull request nahi hai.")
            else ToolResult.ok("$o/$n ke $st pull requests:\n" + prs.joinToString("\n") {
                "#${it.optInt("number")} ${it.optString("title")}${if (it.optBoolean("draft")) " [draft]" else ""} (${it.optJSONObject("head")?.optString("ref")} → ${it.optJSONObject("base")?.optString("ref")})"
            })
        }
    }

    private val readPr = tool("github_read_pr", "Read one pull request: description, state, mergeability and optionally the changed files.",
        p("repo", R, true), p("number", "PR number", true, "INTEGER"), p("with_files", "Also list changed files", type = "BOOLEAN")) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            val num = a.optInt("number", -1)
            val r = api.get("/repos/$o/$n/pulls/$num")
            if (!r.ok) return@withRepo ToolResult.fail(r.errorText())
            val j = r.obj()!!
            val merged = j.optBoolean("merged")
            val mergeable = if (j.isNull("mergeable")) null else j.optBoolean("mergeable")
            ToolResult.ok(cut(buildString {
                append("#$num ${j.optString("title")} - ${if (merged) "merged" else j.optString("state")}${if (j.optBoolean("draft")) ", draft" else ""}\n")
                append("${j.optJSONObject("head")?.optString("ref")} → ${j.optJSONObject("base")?.optString("ref")}, by ${j.optJSONObject("user")?.optString("login")}, ")
                append("${j.optInt("changed_files")} files, ${j.optInt("commits")} commits\n")
                append(when { merged -> "Merge ho chuka hai."; mergeable == null -> "GitHub abhi check kar raha hai ki merge ho payega ya nahi."; mergeable -> "Cleanly merge ho jayega."; else -> "Conflict hai - merge nahi hoga jab tak resolve na ho." })
                j.optString("body").takeIf { it.isNotBlank() && it != "null" }?.let { append("\n\n").append(cut(it, 1200)) }
                if (a.optBoolean("with_files", false)) {
                    val files = api.get("/repos/$o/$n/pulls/$num/files?per_page=40").arr()?.objs().orEmpty()
                    append("\n\nFiles:\n"); files.forEach { append("${it.optString("status")} ${it.optString("filename")} (+${it.optInt("additions")}/-${it.optInt("deletions")})\n") }
                }
            }, 4500))
        }
    }

    private val createPr = tool("github_create_pr", "Open a pull request.",
        p("repo", R, true), p("title", "PR title", true), p("head", "Branch with the changes", true),
        p("base", "Branch to merge into; default branch if empty"), p("body", "Description")) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            if (!api.hasToken) return@withRepo ToolResult.fail(NO_TOKEN)
            val base = a.optString("base").ifBlank { api.defaultBranch(o, n) ?: return@withRepo ToolResult.fail("Default branch nahi mili.") }
            val r = api.post("/repos/$o/$n/pulls", JSONObject().put("title", a.optString("title")).put("head", a.optString("head")).put("base", base).put("body", a.optString("body")))
            if (r.ok) { val j = r.obj()!!; ToolResult.ok("Pull request #${j.optInt("number")} khul gaya - ${j.optString("html_url")}") } else ToolResult.fail(r.errorText())
        }
    }

    private val mergePr = tool("github_merge_pr", "Merge a pull request. First call WITHOUT confirmed: it returns what will be merged. Read that to the user, ask yes/no, and only on yes call again with confirmed=true.",
        p("repo", R, true), p("number", "PR number", true, "INTEGER"),
        p("merge_method", "merge, squash or rebase; default merge"), p("commit_title", "Optional merge commit title"),
        p("confirmed", "true only after the user said yes", type = "BOOLEAN")) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            if (!api.hasToken) return@withRepo ToolResult.fail(NO_TOKEN)
            val num = a.optInt("number", -1)
            val method = a.optString("merge_method").ifBlank { "merge" }.lowercase()
            if (method !in setOf("merge", "squash", "rebase")) return@withRepo ToolResult.fail("Merge method '$method' hota hi nahi - merge, squash ya rebase bolo.")
            val pr = api.get("/repos/$o/$n/pulls/$num")
            if (!pr.ok) return@withRepo ToolResult.fail(pr.errorText())
            val j = pr.obj()!!
            if (j.optBoolean("merged")) return@withRepo ToolResult.ok("#$num pehle se merge ho chuka hai.")
            if (j.optString("state") != "open") return@withRepo ToolResult.fail("#$num band hai, merge nahi ho sakta.")
            if (!j.isNull("mergeable") && !j.optBoolean("mergeable")) return@withRepo ToolResult.fail("#$num me conflict hai - pehle resolve karna padega, tabhi merge hoga.")
            if (!a.optBoolean("confirmed", false)) {
                return@withRepo ToolResult.fail("CONFIRM_REQUIRED - ye merge hone wala hai:\n#$num ${j.optString("title")} (${j.optJSONObject("head")?.optString("ref")} → ${j.optJSONObject("base")?.optString("ref")}, $method, ${j.optInt("changed_files")} files)\n\nUser ko ye padh ke sunao aur haan-na poochho. Haan bole tabhi github_merge_pr ko confirmed=true ke saath dobara call karo.")
            }
            val body = JSONObject().put("merge_method", method)
            a.optString("commit_title").takeIf { it.isNotBlank() }?.let { body.put("commit_title", it) }
            val r = api.put("/repos/$o/$n/pulls/$num/merge", body)
            if (r.ok) ToolResult.ok("#$num $method se merge ho gaya (${j.optJSONObject("head")?.optString("ref")} → ${j.optJSONObject("base")?.optString("ref")}).") else ToolResult.fail(r.errorText())
        }
    }

    // ---------------------------------------------------------------- issues

    private val listIssues = tool("github_list_issues", "List issues of a repo (pull requests are excluded).",
        p("repo", R, true), p("state", "open, closed or all; default open")) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            val st = a.optString("state").ifBlank { "open" }
            val r = api.get("/repos/$o/$n/issues?state=${enc(st)}&per_page=30")
            if (!r.ok) return@withRepo ToolResult.fail(r.errorText())
            val items = r.arr()?.objs().orEmpty().filter { !it.has("pull_request") }
            ToolResult.ok(if (items.isEmpty()) "$o/$n me koi $st issue nahi hai." else items.joinToString("\n") { "#${it.optInt("number")} ${it.optString("title")} (${it.optInt("comments")} comments)" })
        }
    }

    private val readIssue = tool("github_read_issue", "Read one issue with its latest comments.",
        p("repo", R, true), p("number", "Issue number", true, "INTEGER")) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            val num = a.optInt("number", -1)
            val r = api.get("/repos/$o/$n/issues/$num")
            if (!r.ok) return@withRepo ToolResult.fail(r.errorText())
            val j = r.obj()!!
            val comments = api.get("/repos/$o/$n/issues/$num/comments?per_page=30").arr()?.objs().orEmpty().takeLast(5)
            ToolResult.ok(cut(buildString {
                append("#$num ${j.optString("title")} - ${j.optString("state")}, by ${j.optJSONObject("user")?.optString("login")}\n")
                j.optString("body").takeIf { it.isNotBlank() && it != "null" }?.let { append("\n").append(cut(it, 1500)) }
                comments.forEach { append("\n\n${it.optJSONObject("user")?.optString("login")}: ").append(cut(it.optString("body"), 500)) }
            }, 4500))
        }
    }

    private val createIssue = tool("github_create_issue", "Open a new issue.",
        p("repo", R, true), p("title", "Issue title", true), p("body", "Details")) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            if (!api.hasToken) return@withRepo ToolResult.fail(NO_TOKEN)
            val r = api.post("/repos/$o/$n/issues", JSONObject().put("title", a.optString("title")).put("body", a.optString("body")))
            if (r.ok) ToolResult.ok("Issue #${r.obj()?.optInt("number")} ban gaya - ${r.obj()?.optString("html_url")}") else ToolResult.fail(r.errorText())
        }
    }

    private val commentIssue = tool("github_comment_issue", "Add a comment to an issue or pull request.",
        p("repo", R, true), p("number", "Issue/PR number", true, "INTEGER"), p("body", "Comment text", true)) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            if (!api.hasToken) return@withRepo ToolResult.fail(NO_TOKEN)
            val r = api.post("/repos/$o/$n/issues/${a.optInt("number", -1)}/comments", JSONObject().put("body", a.optString("body")))
            if (r.ok) ToolResult.ok("Comment #${a.optInt("number")} pe daal diya.") else ToolResult.fail(r.errorText())
        }
    }

    private val closeIssue = tool("github_close_issue", "Close an issue (optionally with a closing comment).",
        p("repo", R, true), p("number", "Issue number", true, "INTEGER"), p("comment", "Optional closing comment")) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            if (!api.hasToken) return@withRepo ToolResult.fail(NO_TOKEN)
            val num = a.optInt("number", -1)
            a.optString("comment").takeIf { it.isNotBlank() }?.let { api.post("/repos/$o/$n/issues/$num/comments", JSONObject().put("body", it)) }
            val r = api.patch("/repos/$o/$n/issues/$num", JSONObject().put("state", "closed"))
            if (r.ok) ToolResult.ok("Issue #$num band kar diya.") else ToolResult.fail(r.errorText())
        }
    }

    // ---------------------------------------------------------------- releases, gists, my work

    private val listReleases = tool("github_list_releases", "List a repo's releases with download counts.",
        p("repo", R, true), p("limit", "How many, default 5", type = "INTEGER")) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            val r = api.get("/repos/$o/$n/releases?per_page=${a.optInt("limit", 5).coerceIn(1, 20)}")
            if (!r.ok) return@withRepo ToolResult.fail(r.errorText())
            val rel = r.arr()?.objs().orEmpty()
            if (rel.isEmpty()) ToolResult.ok("$o/$n pe koi release nahi hai.")
            else ToolResult.ok("$o/$n ke releases:\n" + rel.joinToString("\n") { x ->
                val dl = x.optJSONArray("assets")?.objs().orEmpty().sumOf { it.optInt("download_count") }
                "- ${x.optString("tag_name")}${if (x.optBoolean("prerelease")) " [pre-release]" else ""} (${x.optString("published_at").take(10)}, $dl downloads)"
            })
        }
    }

    private val createGist = tool("github_create_gist", "Create a GitHub gist from some text/code. Secret by default.",
        p("content", "The text or code", true), p("filename", "File name, default maya.txt"), p("description", "Gist description"),
        p("public", "true to make it public", type = "BOOLEAN")) { ctx, a ->
        val api = GitHubApi(ctx.app)
        if (!api.hasToken) return@tool ToolResult.fail(NO_TOKEN)
        val body = JSONObject().put("description", a.optString("description").ifBlank { "Mayra se bheja gaya" })
            .put("public", a.optBoolean("public", false))
            .put("files", JSONObject().put(a.optString("filename").ifBlank { "mayra.txt" }, JSONObject().put("content", a.optString("content"))))
        val r = api.post("/gists", body)
        if (r.ok) ToolResult.ok("Gist ban gaya (${if (a.optBoolean("public")) "public" else "secret"}):\n${r.obj()?.optString("html_url")}") else ToolResult.fail(r.errorText())
    }

    private val myWork = tool("github_my_work", "What is waiting on the user on GitHub: PRs needing their review, issues assigned to them, their own open PRs.") { ctx, _ ->
        val api = GitHubApi(ctx.app)
        val user = api.username() ?: return@tool ToolResult.fail("GitHub username pata nahi chal raha - token dobara daalo.")
        val queries = listOf("Tumhara review chahiye" to "is:open is:pr review-requested:$user",
            "Tumpe assign" to "is:open assignee:$user", "Tumhare khud ke PR" to "is:open is:pr author:$user")
        val sb = StringBuilder(); var failed = 0; var any = false
        for ((label, q) in queries) {
            val r = api.get("/search/issues?q=${enc(q)}&per_page=8")
            if (!r.ok) { failed++; continue }
            val items = r.obj()?.optJSONArray("items")?.objs().orEmpty()
            if (items.isNotEmpty()) { any = true; sb.append("$label:\n"); items.forEach { sb.append("  ${it.optString("repository_url").substringAfter("repos/")}#${it.optInt("number")} ${it.optString("title")}\n") } }
        }
        when {
            failed == queries.size -> ToolResult.fail("GitHub se jawab nahi aaya (net ya token ka issue lag raha hai), isliye pending kaam ka pata nahi chala - thodi der baad dobara poochho.")
            !any && failed == 0 -> ToolResult.ok("GitHub pe abhi kuch pending nahi hai - sab clear.")
            !any -> ToolResult.ok("Jo lists mil payi unme kuch pending nahi; $failed list GitHub se aayi nahi, isliye poora 'sab clear' nahi keh sakti.")
            else -> ToolResult.ok(sb.toString().trim())
        }
    }

    // ---------------------------------------------------------------- Actions

    private val actionsStatus = tool("github_actions_status", "Latest GitHub Actions runs of a repo (success/failed/running).",
        p("repo", R, true), p("branch", "Only this branch")) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            val br = a.optString("branch").takeIf { it.isNotBlank() }?.let { "&branch=${enc(it)}" } ?: ""
            val r = api.get("/repos/$o/$n/actions/runs?per_page=5$br")
            if (!r.ok) return@withRepo ToolResult.fail(r.errorText())
            val runs = r.obj()?.optJSONArray("workflow_runs")?.objs().orEmpty()
            if (runs.isEmpty()) ToolResult.ok("$o/$n pe koi GitHub Actions run nahi hai.")
            else ToolResult.ok(runs.joinToString("\n") {
                "- run ${it.optLong("id")}: ${it.optString("name")} on ${it.optString("head_branch")} - ${it.optString("status")}${it.optString("conclusion").takeIf { c -> c.isNotBlank() && c != "null" }?.let { c -> "/$c" } ?: ""}"
            })
        }
    }

    private val actionsLog = tool("github_actions_log", "Why did a GitHub Actions run fail? Returns the failing job/step and the tail of its log. Defaults to the latest failed run.",
        p("repo", R, true), p("run_id", "Run id from github_actions_status; omit for the latest failed run")) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            var runId = a.optString("run_id").toLongOrNull() ?: a.optLong("run_id", 0L)
            if (runId == 0L) {
                val runs = api.get("/repos/$o/$n/actions/runs?per_page=10").obj()?.optJSONArray("workflow_runs")?.objs().orEmpty()
                runId = (runs.firstOrNull { it.optString("conclusion") == "failure" } ?: runs.firstOrNull())?.optLong("id") ?: 0L
            }
            if (runId == 0L) return@withRepo ToolResult.ok("$o/$n pe koi GitHub Actions run nahi hai.")
            val jobs = api.get("/repos/$o/$n/actions/runs/$runId/jobs").obj()?.optJSONArray("jobs")?.objs().orEmpty()
            val job = jobs.firstOrNull { it.optString("conclusion") == "failure" } ?: jobs.firstOrNull()
                ?: return@withRepo ToolResult.fail("Run $runId ke jobs nahi mile.")
            val step = job.optJSONArray("steps")?.objs().orEmpty().firstOrNull { it.optString("conclusion") == "failure" }?.optString("name")
            val log = api.get("/repos/$o/$n/actions/jobs/${job.optLong("id")}/logs")
            val tail = if (log.ok) log.body.takeLast(3500) else "(log nahi mila: ${log.errorText()})"
            ToolResult.ok("Run $runId - job '${job.optString("name")}' ${job.optString("conclusion")}${step?.let { ", step '$it' fail hua" } ?: ""}.\n\nLog ka aakhri hissa:\n$tail")
        }
    }

    // ---------------------------------------------------------------- direct file writes (no clone)

    private fun branchOf(a: JSONObject) = a.optString("branch").trim().takeIf { it.isNotEmpty() }

    private suspend fun fileMeta(api: GitHubApi, o: String, n: String, path: String, branch: String?): GitHubApi.Resp =
        api.get("/repos/$o/$n/contents/${encPath(path)}" + (branch?.let { "?ref=${enc(it)}" } ?: ""))

    private fun decode(b64: String) = String(Base64.decode(b64.replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)
    private fun encode(s: String) = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private val editFile = tool("github_edit_file", "Change one spot in a file directly on GitHub (one commit, no clone). old_text must appear exactly once.",
        p("repo", R, true), p("path", "File path", true), p("old_text", "Exact existing text to replace", true), p("new_text", "Replacement text", true),
        p("message", "Commit message", true), p("branch", "Branch, defaults to the default branch")) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            if (!api.hasToken) return@withRepo ToolResult.fail(NO_TOKEN)
            val path = a.optString("path"); val branch = branchOf(a)
            val meta = fileMeta(api, o, n, path, branch)
            if (!meta.ok) return@withRepo ToolResult.fail(meta.errorText())
            val mj = meta.obj() ?: return@withRepo ToolResult.fail("'$path' file nahi hai.")
            val text = decode(mj.optString("content")); val old = a.optString("old_text")
            if (old.isEmpty()) return@withRepo ToolResult.fail("old_text batao - kya badalna hai.")
            val count = Regex(Regex.escape(old)).findAll(text).count()
            if (count == 0) return@withRepo ToolResult.fail("old_text '$path' me nahi mila.")
            if (count > 1) return@withRepo ToolResult.fail("old_text '$path' me $count jagah hai - aur context ke saath batao taaki sirf ek match ho.")
            val body = JSONObject().put("message", a.optString("message")).put("content", encode(text.replace(old, a.optString("new_text")))).put("sha", mj.optString("sha"))
            branch?.let { body.put("branch", it) }
            val r = api.put("/repos/$o/$n/contents/${encPath(path)}", body)
            if (r.ok) ToolResult.ok("$path me badlaav commit ho gaya (${r.obj()?.optJSONObject("commit")?.optString("sha")?.take(7)}).") else ToolResult.fail(r.errorText())
        }
    }

    private val writeFile = tool("github_write_file", "Create a file or overwrite it with full new content directly on GitHub (one commit, no clone).",
        p("repo", R, true), p("path", "File path", true), p("content", "Full file content", true), p("message", "Commit message", true), p("branch", "Branch, defaults to the default branch")) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            if (!api.hasToken) return@withRepo ToolResult.fail(NO_TOKEN)
            val path = a.optString("path"); val branch = branchOf(a)
            val existing = fileMeta(api, o, n, path, branch)
            val body = JSONObject().put("message", a.optString("message")).put("content", encode(a.optString("content")))
            if (existing.ok) existing.obj()?.optString("sha")?.takeIf { it.isNotBlank() }?.let { body.put("sha", it) }
            branch?.let { body.put("branch", it) }
            val r = api.put("/repos/$o/$n/contents/${encPath(path)}", body)
            if (r.ok) ToolResult.ok("$path ${if (existing.ok) "update" else "ban"} ho gayi (${r.obj()?.optJSONObject("commit")?.optString("sha")?.take(7)}).") else ToolResult.fail(r.errorText())
        }
    }

    private val deleteFile = tool("github_delete_file", "Delete a file on GitHub (one commit). First call without confirmed to get a confirmation prompt; call again with confirmed=true only after the user says yes.",
        p("repo", R, true), p("path", "File path", true), p("message", "Commit message", true), p("branch", "Branch, defaults to the default branch"),
        p("confirmed", "true only after the user said yes", type = "BOOLEAN")) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            if (!api.hasToken) return@withRepo ToolResult.fail(NO_TOKEN)
            val path = a.optString("path"); val branch = branchOf(a)
            val meta = fileMeta(api, o, n, path, branch)
            if (!meta.ok) return@withRepo ToolResult.fail(meta.errorText())
            if (!a.optBoolean("confirmed", false)) return@withRepo ToolResult.fail("CONFIRM_REQUIRED - $o/$n se '$path' delete hone wali hai. User se haan-na poochho, haan bole tabhi github_delete_file ko confirmed=true ke saath dobara call karo.")
            val body = JSONObject().put("message", a.optString("message").ifBlank { "Delete $path" }).put("sha", meta.obj()?.optString("sha"))
            branch?.let { body.put("branch", it) }
            val r = api.delete("/repos/$o/$n/contents/${encPath(path)}", body)
            if (r.ok) ToolResult.ok("$path delete ho gayi.") else ToolResult.fail(r.errorText())
        }
    }

    // ---------------------------------------------------------------- explain

    private val explainRepo = tool("github_explain_repo", "Gather what is needed to explain a repo to the user: description, languages, README, top-level layout and key config files. Then explain it in simple words.",
        p("repo", R, true)) { ctx, a ->
        withRepo(ctx, a) { api, o, n ->
            val meta = api.get("/repos/$o/$n")
            if (!meta.ok) return@withRepo ToolResult.fail(meta.errorText())
            val j = meta.obj()!!
            val top = api.get("/repos/$o/$n/contents/").arr()?.objs().orEmpty()
            val readme = api.call("GET", "/repos/$o/$n/readme", accept = "application/vnd.github.raw")
            val keyNames = setOf("package.json", "build.gradle", "build.gradle.kts", "requirements.txt", "pyproject.toml", "Cargo.toml", "go.mod", "pom.xml", "AndroidManifest.xml")
            val keys = StringBuilder()
            top.filter { it.optString("name") in keyNames }.take(3).forEach {
                val f = api.call("GET", "/repos/$o/$n/contents/${encPath(it.optString("path"))}", accept = "application/vnd.github.raw")
                if (f.ok) keys.append("\n--- ${it.optString("name")} ---\n").append(cut(f.body, 1200))
            }
            ToolResult.ok(cut(buildString {
                append("$o/$n - ${j.optString("description", "")}\nLanguage: ${j.optString("language")}, ${j.optInt("stargazers_count")}★\n")
                append("\nTop-level:\n").append(top.joinToString("\n") { (if (it.optString("type") == "dir") "[dir] " else "") + it.optString("name") })
                if (readme.ok) append("\n\nREADME:\n").append(cut(readme.body, 2500))
                append(keys)
                append("\n\n(Is material se user ko simple Hinglish me samjhao: ye kya hai, kaise bana hai, kahan se shuru karein.)")
            }, 8000))
        }
    }
}
