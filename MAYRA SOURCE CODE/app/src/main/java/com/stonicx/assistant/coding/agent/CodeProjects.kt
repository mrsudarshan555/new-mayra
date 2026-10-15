package com.stonicx.assistant.coding.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Projects Mayra builds on the phone: filesDir/projects/<slug>/{files/, meta.json, session.json, history.json, notes.json}. */
class CodeProjects(context: Context) {
    data class Meta(val slug: String, val name: String, val desc: String, val kind: String, val created: Long, val updated: Long, val github: String)

    private val root = File(context.filesDir, "projects").apply { mkdirs() }

    companion object {
        private val ALLOWED_EXT = setOf("html", "htm", "css", "js", "mjs", "json", "md", "txt", "svg", "xml", "py", "ts", "tsx", "jsx", "java", "kt",
            "go", "rs", "c", "cpp", "h", "hpp", "sh", "yml", "yaml", "toml", "sql", "php", "rb", "cs", "swift", "csv", "ini", "env", "gradle", "vue")
        const val MAX_FILE = 400_000
        fun slug(name: String): String = name.trim().lowercase().replace(Regex("[^a-z0-9\\u0900-\\u097f]+"), "-").trim('-').take(40).ifEmpty { "project" }
    }

    fun dir(slug: String) = File(root, slug)
    fun filesDir(slug: String) = File(dir(slug), "files").apply { mkdirs() }

    fun list(): List<Meta> = (root.listFiles()?.filter { it.isDirectory } ?: emptyList()).mapNotNull { meta(it.name) }.sortedByDescending { it.updated }

    fun meta(slug: String): Meta? = try {
        val j = JSONObject(File(dir(slug), "meta.json").readText())
        Meta(slug, j.optString("name", slug), j.optString("desc"), j.optString("kind", "website"), j.optLong("created"), j.optLong("updated"), j.optString("github"))
    } catch (e: Exception) { null }

    /** Find by exact slug, or by the name the user said (case-insensitive, partial). */
    fun find(nameOrSlug: String): Meta? {
        val q = nameOrSlug.trim().lowercase(); if (q.isEmpty()) return null
        val all = list()
        return all.firstOrNull { it.slug == slug(q) } ?: all.firstOrNull { it.name.lowercase() == q }
            ?: all.firstOrNull { it.name.lowercase().contains(q) || it.slug.contains(slug(q)) }
    }

    fun create(name: String, desc: String, kind: String): Meta {
        var s = slug(name); var n = 2
        while (File(dir(s), "meta.json").exists() && meta(s)?.name?.lowercase() != name.trim().lowercase()) s = slug(name) + "-" + n++
        val now = System.currentTimeMillis()
        val m = Meta(s, name.trim(), desc, kind, meta(s)?.created ?: now, now, meta(s)?.github.orEmpty())
        save(m); filesDir(s); return m
    }

    fun save(m: Meta) {
        dir(m.slug).mkdirs()
        File(dir(m.slug), "meta.json").writeText(JSONObject().put("name", m.name).put("desc", m.desc).put("kind", m.kind).put("created", m.created).put("updated", m.updated).put("github", m.github).toString())
    }

    fun touch(slug: String) { meta(slug)?.let { save(it.copy(updated = System.currentTimeMillis())) } }

    fun delete(slug: String) = dir(slug).deleteRecursively()

    // ---------------- files
    /** A safe relative path inside the project, or null. */
    fun safePath(rel: String): String? {
        val p = rel.trim().replace('\\', '/').trimStart('/')
        if (p.isEmpty() || p.length > 120 || p.contains("..") || p.contains("//") || !Regex("^[A-Za-z0-9._\\-/ ]+$").matches(p)) return null
        if (p.split('/').size > 5 || p.split('/').any { it.startsWith(".") && it != ".gitignore" }) return null
        val ext = p.substringAfterLast('.', "").lowercase()
        if (ext !in ALLOWED_EXT && p.substringAfterLast('/') !in setOf("Dockerfile", "Makefile", ".gitignore", "LICENSE")) return null
        return p
    }

    fun file(slug: String, rel: String): File? {
        val p = safePath(rel) ?: return null
        val base = filesDir(slug); val f = File(base, p)
        return if (f.canonicalPath.startsWith(base.canonicalPath + File.separator)) f else null
    }

    fun files(slug: String): List<String> {
        val base = filesDir(slug); val prefix = base.canonicalPath + File.separator
        return base.walkTopDown().filter { it.isFile }.map { it.canonicalPath.removePrefix(prefix).replace(File.separatorChar, '/') }.sorted().toList()
    }

    fun read(slug: String, rel: String): String? = file(slug, rel)?.takeIf { it.isFile }?.readText()

    /** Returns null on success or the reason it failed. */
    fun write(slug: String, rel: String, content: String): String? {
        val f = file(slug, rel) ?: return "ye path allowed nahi hai (project ke andar simple relative path do)"
        if (content.length > MAX_FILE) return "file bahut badi hai (${content.length} characters) - chhoti files me todo"
        return try { f.parentFile?.mkdirs(); f.writeText(content); touch(slug); null } catch (e: Exception) { "save nahi hui: ${e.message}" }
    }

    fun deleteFile(slug: String, rel: String): Boolean = file(slug, rel)?.takeIf { it.isFile }?.delete() ?: false

    /** The page to open for a website: index.html, else the first .html. */
    fun entry(slug: String): String? { val fs = files(slug); return fs.firstOrNull { it == "index.html" } ?: fs.firstOrNull { it.endsWith(".html") } }

    // ---------------- session / history / notes
    fun sessionFile(slug: String) = File(dir(slug), "session.json")
    fun saveSession(slug: String, j: JSONObject) { try { sessionFile(slug).writeText(j.toString()) } catch (_: Exception) { } }
    fun loadSession(slug: String): JSONObject? = try { JSONObject(sessionFile(slug).readText()) } catch (e: Exception) { null }
    fun clearSession(slug: String) { sessionFile(slug).delete() }

    private fun arrFile(slug: String, name: String) = File(dir(slug), name)
    private fun readArr(f: File): JSONArray = try { JSONArray(f.readText()) } catch (e: Exception) { JSONArray() }

    fun addHistory(slug: String, kind: String, text: String) {
        val f = arrFile(slug, "history.json"); val a = readArr(f)
        a.put(JSONObject().put("t", System.currentTimeMillis()).put("kind", kind).put("text", text.take(800)))
        while (a.length() > 150) a.remove(0)
        try { f.writeText(a.toString()) } catch (_: Exception) { }
    }
    fun history(slug: String): List<JSONObject> = readArr(arrFile(slug, "history.json")).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }

    fun addNote(slug: String, text: String) {
        val f = arrFile(slug, "notes.json"); val a = readArr(f); a.put(text.take(400))
        while (a.length() > 40) a.remove(0)
        try { f.writeText(a.toString()) } catch (_: Exception) { }
    }
    fun notes(slug: String): List<String> = readArr(arrFile(slug, "notes.json")).let { a -> (0 until a.length()).map { a.optString(it) } }
}
