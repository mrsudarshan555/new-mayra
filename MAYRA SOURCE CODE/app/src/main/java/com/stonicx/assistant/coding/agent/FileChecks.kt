package com.stonicx.assistant.coding.agent

/** Cheap structural checks that catch the usual "model cut off halfway" damage before a file is called finished. */
object FileChecks {
    /** Returns a list of problem descriptions (empty = looks fine). */
    fun check(path: String, text: String): List<String> {
        val ext = path.substringAfterLast('.', "").lowercase()
        val out = ArrayList<String>()
        if (text.isBlank()) { out += "file 0 characters ki hai - kuch likha hi nahi gaya"; return out }
        val lower = text.lowercase()
        if (ext == "html" || ext == "htm") {
            if (lower.contains("<html") && !lower.contains("</html>")) out += "</html> kahin nahi hai - file adhuri kati hui lagti hai"
            if (lower.contains("<body") && !lower.contains("</body>")) out += "</body> kahin nahi hai - file adhuri kati hui lagti hai"
        }
        if (ext in setOf("html", "htm", "css", "js", "mjs", "json", "ts", "tsx", "jsx", "java", "kt", "go", "rs", "c", "cpp", "php", "cs", "swift")) {
            val open = text.count { it == '{' }; val close = text.count { it == '}' }
            if (open != close && (ext != "html" || lower.contains("<script") || lower.contains("<style"))) out += "$open bar '{' khula aur $close bar '}' band hua"
        }
        val lastLine = text.trimEnd().lines().lastOrNull().orEmpty()
        if (ext in setOf("js", "mjs", "ts", "css", "json", "py", "kt", "java") && lastLine.count { it == '"' } % 2 == 1 && !lastLine.trimStart().startsWith("//") && !lastLine.contains("'")) {
            out += "aakhri line pe quote khula reh gaya: ${lastLine.take(60)}"
        }
        if (ext in setOf("html", "htm", "css", "js", "mjs") && text.length > 400 && lastLine.isNotEmpty() && lastLine.last() in ",(+=:&|" ) out += "file adhuri lag rahi hai - aakhri line: ${lastLine.take(60)}"
        return out
    }

    /** Extra checks for pages that must look right on a phone. */
    fun mobile(files: Map<String, String>): List<String> {
        val out = ArrayList<String>()
        files.filter { it.key.endsWith(".html") }.forEach { (p, t) ->
            if (!Regex("<meta[^>]+name=[\"']viewport[\"']", RegexOption.IGNORE_CASE).containsMatchIn(t)) out += "$p: viewport meta tag nahi hai"
            if (Regex("(src|href)=[\"']https?://(?!getmaya)[^\"']+\\.(js|css)", RegexOption.IGNORE_CASE).containsMatchIn(t)) out += "$p: external CDN script/css hai - offline nahi khulega, sab khud likho"
            if (Regex("lorem ipsum", RegexOption.IGNORE_CASE).containsMatchIn(t)) out += "$p: 'Lorem ipsum' bacha hai - asli content likho"
        }
        files.filter { it.key.endsWith(".css") || it.key.endsWith(".html") }.forEach { (p, t) ->
            if (Regex("(?<![-\\w])width:\\s*(5[5-9]\\d|[6-9]\\d\\d|\\d{4,})px").containsMatchIn(t)) out += "$p: fixed badi px width hai - phone pe side scroll aayega (%, rem, clamp, max-width use karo)"
        }
        return out
    }
}
