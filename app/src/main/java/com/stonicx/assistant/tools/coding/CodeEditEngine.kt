package com.stonicx.assistant.tools.coding

import com.stonicx.assistant.tools.SubAgent
import com.stonicx.assistant.tools.ToolContext
import com.stonicx.assistant.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Runs the coding sub-agent: gives it the relevant files, parses SEARCH/REPLACE blocks, applies them locally. */
object CodeEditEngine {

    data class Block(val path: String, val search: String, val replace: String)

    private const val SYSTEM = """You are a careful software engineer editing an existing codebase. You are given
the project's file list and the full text of a few files. Make the requested change.

Reply with ONLY edit blocks, in exactly this format, one per change:

path/to/file.ext
<<<<<<< SEARCH
the exact existing lines, copied character for character
=======
the lines that replace them
>>>>>>> REPLACE

Rules:
- The SEARCH section must be copied EXACTLY from the file shown, including its indentation. Include 2-3 surrounding lines so it appears only once.
- Do NOT rewrite a whole file. Make the smallest edit that does the job.
- Only edit files whose contents you were given.
- To create a new file, put the literal text (new file) as the SEARCH section and the file's complete content as the REPLACE section.
- Several blocks are fine, including several in the same file.
- Match the surrounding code's style, naming and comment density.
- No explanation, no markdown prose, no summary before or after the blocks.
- If the change cannot be made from what you were given, reply with one line starting NOT POSSIBLE: and say what you would need. Never invent file contents you were not shown."""

    private val STOP = setOf("the", "and", "for", "with", "this", "that", "into", "from", "please", "should", "would", "there",
        "which", "where", "change", "update", "karo", "kare", "karna", "chahiye", "hona", "hoga", "banao", "daalo", "hata",
        "wala", "wali", "file", "files", "code", "repo", "repository", "project", "function")

    /** Pick up to 6 files whose path (or name) shares words with the task text. */
    fun pickFiles(task: String, all: List<String>): List<String> {
        val words = Regex("[A-Za-z0-9_]{3,}").findAll(task.lowercase()).map { it.value }.filter { it !in STOP }.toSet()
        if (words.isEmpty()) return emptyList()
        val codeExt = setOf("kt", "java", "py", "js", "ts", "tsx", "jsx", "html", "css", "xml", "json", "yml", "yaml", "md", "gradle", "kts", "go", "rs", "c", "cpp", "h", "swift", "php", "sql", "toml", "sh")
        return all.filter { it.substringAfterLast('.', "").lowercase() in codeExt }
            .map { path ->
                val low = path.lowercase()
                val name = low.substringAfterLast('/')
                path to words.sumOf { w -> (if (name.contains(w)) 3 else 0) + (if (low.contains(w)) 1 else 0) }
            }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(6).map { it.first }
    }

    fun parse(text: String): List<Block> {
        val lines = text.replace("\r\n", "\n").split("\n")
        val out = ArrayList<Block>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].trim() == "<<<<<<< SEARCH") {
                var j = i - 1
                while (j >= 0 && (lines[j].isBlank() || lines[j].trim().startsWith("```"))) j--
                val path = if (j >= 0) lines[j].trim().trim('`', '*', ':', ' ') else ""
                val s = StringBuilder(); var k = i + 1
                while (k < lines.size && lines[k].trim() != "=======") { s.append(lines[k]).append('\n'); k++ }
                val r = StringBuilder(); k++
                while (k < lines.size && lines[k].trim() != ">>>>>>> REPLACE") { r.append(lines[k]).append('\n'); k++ }
                out += Block(path, s.toString().removeSuffix("\n"), r.toString().removeSuffix("\n"))
                i = k + 1
            } else i++
        }
        return out
    }

    /** Exact match first, then a match that ignores trailing whitespace. Returns line range or null. */
    private fun locate(fileLines: List<String>, searchLines: List<String>): List<IntRange> {
        val hits = ArrayList<IntRange>()
        if (searchLines.isEmpty() || searchLines.size > fileLines.size) return hits
        for (strict in listOf(true, false)) {
            for (s in 0..fileLines.size - searchLines.size) {
                var ok = true
                for (k in searchLines.indices) {
                    val a = fileLines[s + k]; val b = searchLines[k]
                    if (if (strict) a != b else a.trimEnd() != b.trimEnd()) { ok = false; break }
                }
                if (ok) hits += s until s + searchLines.size
            }
            if (hits.isNotEmpty()) return hits
        }
        return hits
    }

    suspend fun run(
        ctx: ToolContext, m: LocalRepoStore.Meta, store: LocalRepoStore,
        task: String, wanted: List<String>, all: List<String>
    ): JSONObject {
        val sb = StringBuilder("PROJECT FILES (${all.size}):\n").append(all.take(400).joinToString("\n"))
        if (all.size > 400) sb.append("\n...(aur ${all.size - 400} files)")
        sb.append("\n\n=== FILES TO EDIT ===\n")
        var budget = 150_000
        for (path in wanted) {
            val f = store.resolveInTree(m, path) ?: continue
            if (!f.isFile || store.looksBinary(f)) continue
            val body = f.readText().take(minOf(60_000, budget))
            budget -= body.length
            sb.append("\n--- $path ---\n").append(body).append('\n')
            if (budget <= 0) break
        }
        sb.append("\n=== KAAM ===\n").append(task)

        val ans = SubAgent.ask(ctx.app, SYSTEM, sb.toString(), temperature = 0.1)
        if (ans.text == null) return ToolResult.fail(ans.error ?: "Sub-agent ne jawab nahi diya - thodi der me phir bolna.")
        val text = ans.text
        if (text.trimStart().startsWith("NOT POSSIBLE")) return ToolResult.fail("Sub-agent ye kaam nahi kar paya: " + text.trim().take(300))
        val blocks = parse(text)
        if (blocks.isEmpty()) return ToolResult.fail("Sub-agent ne koi edit block nahi bheja. Usne ye kaha:\n" + text.trim().take(300) + "\n\nKaam aur specific batao, ya file ka naam de do.")

        return withContext(Dispatchers.IO) {
            val applied = ArrayList<String>(); val failed = ArrayList<String>()
            for (b in blocks) {
                val f = store.resolveInTree(m, b.path)
                if (f == null || b.path.isBlank()) { failed += "'${b.path}' valid path nahi"; continue }
                if (b.search.trim() == "(new file)") {
                    if (f.exists()) { failed += "${b.path}: pehle se hai, naya nahi bana sakte"; continue }
                    f.parentFile?.mkdirs(); f.writeText(b.replace + "\n"); applied += "${b.path} (nayi)"; continue
                }
                if (!f.isFile || store.looksBinary(f)) { failed += "${b.path}: file nahi mili"; continue }
                val raw = f.readText(); val crlf = raw.contains("\r\n")
                val lines = raw.replace("\r\n", "\n").split("\n")
                val hits = locate(lines, b.search.split("\n"))
                when {
                    hits.isEmpty() -> failed += "${b.path}: purana text file me match nahi hua"
                    hits.size > 1 -> failed += "${b.path}: ek se zyada jagah match hua (${hits.size}) - aur context chahiye"
                    else -> {
                        val r = hits[0]
                        val out = lines.take(r.first) + (if (b.replace.isEmpty() && b.search.isNotEmpty()) emptyList() else b.replace.split("\n")) + lines.drop(r.last + 1)
                        val joined = out.joinToString("\n")
                        f.writeText(if (crlf) joined.replace("\n", "\r\n") else joined)
                        applied += b.path
                    }
                }
            }
            val files = applied.distinct()
            val msg = StringBuilder()
            if (files.isNotEmpty()) msg.append("Sub-agent ne ${applied.size} edit lagaye: ${files.joinToString(", ")}. Abhi sirf phone pe hain - github_local_changes se dekh ke user ko batao, phir push karo.")
            if (failed.isNotEmpty()) msg.append(if (msg.isNotEmpty()) "\n" else "").append("Ye edit nahi lag paye: ").append(failed.joinToString("; "))
            if (applied.isEmpty()) ToolResult.fail(msg.toString()) else ToolResult.ok(msg.toString()) { put("applied", applied.size); put("failed", failed.size) }
        }
    }
}
