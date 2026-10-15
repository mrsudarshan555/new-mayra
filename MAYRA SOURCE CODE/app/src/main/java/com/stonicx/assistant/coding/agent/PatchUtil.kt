package com.stonicx.assistant.coding.agent

/** Applies one SEARCH/REPLACE block to a file's text. */
object PatchUtil {
    /** Returns the new text, or an error string (second). Exact match first, then ignoring trailing spaces. */
    fun apply(original: String, search: String, replace: String): Pair<String?, String?> {
        if (search == replace) return null to "purana aur naya text same hai"
        val crlf = original.contains("\r\n")
        val lines = original.replace("\r\n", "\n").split("\n")
        val sLines = search.replace("\r\n", "\n").split("\n")
        val hits = locate(lines, sLines)
        if (hits.isEmpty()) return null to "quote kiya hua snippet file me mila hi nahi - pehle READ karo"
        if (hits.size > 1) return null to "ye snippet file me ${hits.size} baar hai - aur upar-neeche ki lines bhi quote karo"
        val r = hits[0]
        val rep = if (replace.isEmpty()) emptyList() else replace.replace("\r\n", "\n").split("\n")
        val joined = (lines.take(r.first) + rep + lines.drop(r.last + 1)).joinToString("\n")
        return (if (crlf) joined.replace("\n", "\r\n") else joined) to null
    }

    private fun locate(file: List<String>, search: List<String>): List<IntRange> {
        val hits = ArrayList<IntRange>()
        if (search.isEmpty() || search.size > file.size) return hits
        for (strict in listOf(true, false)) {
            for (s in 0..file.size - search.size) {
                var ok = true
                for (k in search.indices) {
                    val a = file[s + k]; val b = search[k]
                    if (if (strict) a != b else a.trimEnd() != b.trimEnd()) { ok = false; break }
                }
                if (ok) hits += s until s + search.size
            }
            if (hits.isNotEmpty()) return hits
        }
        return hits
    }
}
