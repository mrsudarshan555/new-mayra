package com.stonicx.assistant.tools.coding

import android.util.Base64
import com.stonicx.assistant.tools.*
import org.json.JSONObject

/** Run real shell commands on the phone through Termux (plain Termux or an Ubuntu proot inside it). */
object TermuxTools {

    fun all(): List<MayraTool> = listOf(status, run, runInUbuntu, installPackage, listRecipes, setupRecipe, writeFile, readFile, readMore, findInOutput, openSession)

    // ---- remembered outputs so long results can be paged / searched without re-running
    private val outputs = object : LinkedHashMap<String, String>(32, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 20
    }
    private var outputCounter = 0

    @Synchronized private fun store(text: String): String { val id = "out${++outputCounter}"; outputs[id] = text; return id }
    @Synchronized private fun load(id: String): String? = outputs[id.trim()]

    private val DANGEROUS = listOf(
        Regex("""rm\s+(-[a-zA-Z]*\s+)*-[a-zA-Z]*[rR][a-zA-Z]*\s+(-[a-zA-Z]+\s+)*(/|~|[$]HOME|/data|/sdcard|/storage)/?(\s|$|\*)"""),
        Regex("""\bmkfs\b"""), Regex("""\bdd\b.*\bof=/dev/"""), Regex(""":\(\)\s*\{"""), Regex(""">\s*/dev/(sd|mmc|block)""")
    )
    private fun isDangerous(cmd: String) = DANGEROUS.any { it.containsMatchIn(cmd) }

    private fun precheck(ctx: ToolContext): JSONObject? = when {
        !TermuxBridge.isInstalled(ctx.context) -> ToolResult.fail("Termux install nahi hai. ${TermuxBridge.SETUP_HELP}")
        !TermuxBridge.hasPermission(ctx.context) -> ToolResult.fail("Mayra ke paas Termux me command chalane ki permission nahi hai. ${TermuxBridge.SETUP_HELP}")
        else -> null
    }

    private fun format(label: String, r: TermuxBridge.Result): JSONObject {
        if (r.err != null && r.err != 0) {
            return ToolResult.fail(r.errmsg.ifBlank { "Termux error ${r.err}" })
        }
        if (r.timedOut) return ToolResult.fail(r.errmsg)
        val out = buildString {
            if (r.stdout.isNotBlank()) append(r.stdout.trimEnd())
            if (r.stderr.isNotBlank()) append(if (isNotEmpty()) "\n[stderr]\n" else "[stderr]\n").append(r.stderr.trimEnd())
        }
        val id = store(out)
        val code = r.exitCode ?: -1
        val head = if (out.length > 3000) out.take(3000) + "\n...(output lamba hai - termux_read_more output_id=$id offset=3000 se aage padho, ya termux_find_in_output se dhoondho)" else out
        val summary = when (code) { 0 -> "$label: theek chala (exit 0)"; 124 -> "$label: time limit pe ruk gaya (exit 124)"; else -> "$label: exit code $code" }
        val msg = summary + if (head.isNotBlank()) "\n$head" else "\n(koi output nahi)"
        return if (code == 0) ToolResult.ok(msg) { put("exit_code", code); put("output_id", id) }
        else ToolResult.fail(msg) { put("exit_code", code); put("output_id", id) }
    }

    private fun timeoutOf(a: JSONObject, def: Int = 60, max: Int = 900) = a.optInt("timeout_sec", def).coerceIn(5, max)

    private val status = tool("termux_status", "Check whether Termux is installed and connected, and which dev tools (python, node, git, ubuntu) it already has.") { ctx, _ ->
        if (!TermuxBridge.isInstalled(ctx.context)) return@tool ToolResult.fail("Termux install nahi hai. ${TermuxBridge.SETUP_HELP}")
        if (!TermuxBridge.hasPermission(ctx.context)) return@tool ToolResult.fail("Termux installed hai par Mayra ko 'Run commands in Termux environment' permission nahi mili. ${TermuxBridge.SETUP_HELP}")
        val r = TermuxBridge.run(ctx.context,
            "echo ok; for t in python node git clang java proot-distro; do if command -v \$t >/dev/null 2>&1; then echo \"\$t: yes\"; else echo \"\$t: no\"; fi; done", 25)
        if (r.err != null && r.err != 0) return@tool ToolResult.fail(r.errmsg.ifBlank { "Termux se baat nahi ho paayi. ${TermuxBridge.SETUP_HELP}" })
        if (r.timedOut || !r.stdout.startsWith("ok")) return@tool ToolResult.fail("Termux jawab nahi de raha - Termux ek baar khol ke dekho, aur allow-external-apps=true set hai ya nahi check karo. ${TermuxBridge.SETUP_HELP}")
        ToolResult.ok("Termux connected hai.\n" + r.stdout.removePrefix("ok").trim())
    }

    private val run = tool("termux_run", "Run a shell command in Termux on the phone and return its output (stdout+stderr). For python/node/git/build tasks. Long output is saved: use termux_read_more / termux_find_in_output.",
        p("command", "Shell command (bash)", true), p("timeout_sec", "Max seconds, default 60, max 900", type = "INTEGER"),
        p("workdir", "Working directory, default Termux home"), p("confirmed", "true only after the user approved a risky command", type = "BOOLEAN")) { ctx, a ->
        precheck(ctx)?.let { return@tool it }
        val cmd = a.optString("command").trim().ifEmpty { return@tool ToolResult.fail("Command batao.") }
        if (isDangerous(cmd) && !a.optBoolean("confirmed", false))
            return@tool ToolResult.fail("CONFIRM_REQUIRED - ye command khatarnak lag raha hai (files mit sakti hain): $cmd\nUser ko batao aur haan-na poochho; haan bole tabhi confirmed=true ke saath dobara chalao.")
        format("termux_run", TermuxBridge.run(ctx.context, cmd, timeoutOf(a), a.optString("workdir").ifBlank { TermuxBridge.HOME }))
    }

    private fun ubuntuWrap(cmd: String) = "proot-distro login ubuntu --shared-tmp -- bash -lc ${TermuxBridge.shq(cmd)}"

    private val runInUbuntu = tool("termux_run_in_ubuntu", "Run a command inside the Ubuntu environment (proot-distro) in Termux, for tools that need a full Linux (apt, gcc, etc.). Needs the 'ubuntu' recipe installed first.",
        p("command", "Shell command (bash) to run in Ubuntu", true), p("timeout_sec", "Max seconds, default 120, max 900", type = "INTEGER"),
        p("confirmed", "true only after the user approved a risky command", type = "BOOLEAN")) { ctx, a ->
        precheck(ctx)?.let { return@tool it }
        val cmd = a.optString("command").trim().ifEmpty { return@tool ToolResult.fail("Command batao.") }
        if (isDangerous(cmd) && !a.optBoolean("confirmed", false))
            return@tool ToolResult.fail("CONFIRM_REQUIRED - ye command khatarnak lag raha hai: $cmd\nUser se haan-na poochho; haan bole tabhi confirmed=true ke saath dobara chalao.")
        format("termux_run_in_ubuntu", TermuxBridge.run(ctx.context, ubuntuWrap(cmd), timeoutOf(a, 120)))
    }

    private val PKG_OK = Regex("""^[A-Za-z0-9][A-Za-z0-9.+_-]*$""")

    private val installPackage = tool("termux_install_package", "Install one or more packages in Termux (pkg) or in Ubuntu (apt).",
        p("package", "Package name(s), space separated, e.g. python or 'git openssh'", true), p("ubuntu", "true to install with apt inside Ubuntu", type = "BOOLEAN")) { ctx, a ->
        precheck(ctx)?.let { return@tool it }
        val pkgs = a.optString("package").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (pkgs.isEmpty() || pkgs.any { !PKG_OK.matches(it) }) return@tool ToolResult.fail("Package ka naam galat hai - sirf letters, numbers, . + _ - allowed hain.")
        val ubuntu = a.optBoolean("ubuntu", false)
        val cmd = if (ubuntu) ubuntuWrap("apt-get update -y && apt-get install -y ${pkgs.joinToString(" ")}") else "pkg install -y ${pkgs.joinToString(" ")}"
        format("install ${pkgs.joinToString(" ")}", TermuxBridge.run(ctx.context, cmd, 600))
    }

    private val RECIPES = linkedMapOf(
        "python" to ("Python 3 + pip" to "pkg install -y python"),
        "node" to ("Node.js + npm" to "pkg install -y nodejs"),
        "git" to ("Git + SSH" to "pkg install -y git openssh"),
        "build" to ("C/C++ build tools (clang, make, cmake)" to "pkg install -y clang make cmake"),
        "java" to ("OpenJDK 17" to "pkg install -y openjdk-17"),
        "ubuntu" to ("Ubuntu Linux inside Termux (proot-distro)" to "pkg install -y proot-distro && proot-distro install ubuntu")
    )

    private val listRecipes = tool("termux_list_recipes", "List the ready-made setup recipes termux_setup_recipe can install.") { _, _ ->
        ToolResult.ok(RECIPES.entries.joinToString("\n") { "- ${it.key}: ${it.value.first}" })
    }

    private val setupRecipe = tool("termux_setup_recipe", "Install a ready-made dev environment in Termux (python, node, git, build, java, ubuntu). Takes minutes.",
        p("recipe", "One of: ${RECIPES.keys.joinToString(", ")}", true)) { ctx, a ->
        precheck(ctx)?.let { return@tool it }
        val key = a.optString("recipe").trim().lowercase()
        val rec = RECIPES[key] ?: return@tool ToolResult.fail("'$key' naam ka recipe nahi hai. Ye hain: ${RECIPES.keys.joinToString(", ")}")
        format("setup $key", TermuxBridge.run(ctx.context, "pkg update -y >/dev/null 2>&1; ${rec.second}", 900))
    }

    private val writeFile = tool("termux_write_file", "Write a text file inside Termux's file system (overwrites). Max ~60 KB.",
        p("path", "Absolute path or relative to Termux home", true), p("content", "File content", true)) { ctx, a ->
        precheck(ctx)?.let { return@tool it }
        val content = a.optString("content")
        if (content.toByteArray().size > 60_000) return@tool ToolResult.fail("Content 60 KB se bada hai - chhote hisson me likho.")
        val path = a.optString("path").trim().ifEmpty { return@tool ToolResult.fail("Path batao.") }
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val q = TermuxBridge.shq(path)
        val r = TermuxBridge.run(ctx.context, "mkdir -p \"\$(dirname $q)\" && echo '$b64' | base64 -d > $q && wc -c < $q", 30)
        val res = format("termux_write_file", r)
        if (res.optBoolean("success")) ToolResult.ok("$path likh di (${content.length} chars).") else res
    }

    private val readFile = tool("termux_read_file", "Read a text file from Termux's file system.",
        p("path", "Absolute path or relative to Termux home", true), p("from_line", "First line (1-based)", type = "INTEGER"), p("max_lines", "How many lines, default 200", type = "INTEGER")) { ctx, a ->
        precheck(ctx)?.let { return@tool it }
        val path = a.optString("path").trim().ifEmpty { return@tool ToolResult.fail("Path batao.") }
        val from = a.optInt("from_line", 1).coerceAtLeast(1); val n = a.optInt("max_lines", 200).coerceIn(1, 1000)
        format("termux_read_file", TermuxBridge.run(ctx.context, "sed -n '${from},${from + n - 1}p' ${TermuxBridge.shq(path)}", 20))
    }

    private val readMore = tool("termux_read_more", "Read further into a long output saved by an earlier termux call.",
        p("output_id", "output_id from the earlier result", true), p("offset", "Character offset to start from", type = "INTEGER"), p("limit", "How many characters, default 3000", type = "INTEGER")) { _, a ->
        val text = load(a.optString("output_id")) ?: return@tool ToolResult.fail("Wo output ab yaad nahi - command dobara chalao.")
        val off = a.optInt("offset", 0).coerceIn(0, text.length); val lim = a.optInt("limit", 3000).coerceIn(100, 6000)
        val chunk = text.substring(off, minOf(text.length, off + lim))
        ToolResult.ok(chunk + if (off + lim < text.length) "\n...(offset=${off + lim} se aage aur hai, total ${text.length})" else "\n(output yahin khatam)")
    }

    private val findInOutput = tool("termux_find_in_output", "Search for text inside a long saved termux output and show matching lines.",
        p("output_id", "output_id from the earlier result", true), p("query", "Text to look for (case-insensitive)", true)) { _, a ->
        val text = load(a.optString("output_id")) ?: return@tool ToolResult.fail("Wo output ab yaad nahi - command dobara chalao.")
        val q = a.optString("query").trim().ifEmpty { return@tool ToolResult.fail("Kya dhoondhna hai?") }
        val hits = text.lines().withIndex().filter { it.value.contains(q, ignoreCase = true) }
        if (hits.isEmpty()) ToolResult.ok("'$q' output me kahin nahi mila.")
        else ToolResult.ok(hits.take(40).joinToString("\n") { "${it.index + 1}: ${it.value.take(200)}" } + if (hits.size > 40) "\n...(aur ${hits.size - 40} matches)" else "")
    }

    private val openSession = tool("termux_open_session", "Open the Termux terminal on screen (optionally running a command in it) so the user can watch or type.",
        p("command", "Optional command to start in the visible session")) { ctx, a ->
        precheck(ctx)?.let { return@tool it }
        val cmd = a.optString("command").trim().ifEmpty { "exec bash -l" }
        val r = TermuxBridge.run(ctx.context, cmd, 30, background = false)
        if (r.err == -1) ToolResult.fail(r.errmsg) else ToolResult.ok("Termux session khol diya.")
    }
}
