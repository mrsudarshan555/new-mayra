package com.stonicx.assistant.tools.coding

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Repositories downloaded onto the phone. Layout per repo:
 *   filesDir/github/<owner>__<name>/files        working tree (what agents edit)
 *   filesDir/github/<owner>__<name>/meta.json    owner, name, branch, commit, clonedAt
 *   filesDir/github/<owner>__<name>/base.json    path -> git blob sha at download time
 * "Changes" are found by re-hashing the working tree against base.json, so no second copy is kept.
 */
class LocalRepoStore(context: Context) {

    data class Meta(val owner: String, val name: String, val branch: String, val commit: String, val clonedAt: Long) {
        val full: String get() = "$owner/$name"
    }

    data class Changes(val added: List<String>, val modified: List<String>, val deleted: List<String>) {
        val isEmpty: Boolean get() = added.isEmpty() && modified.isEmpty() && deleted.isEmpty()
        val count: Int get() = added.size + modified.size + deleted.size
    }

    private val root = File(context.filesDir, "github").apply { mkdirs() }

    fun repoDir(owner: String, name: String) = File(root, "${safe(owner)}__${safe(name)}")
    fun treeDir(owner: String, name: String) = File(repoDir(owner, name), "files")

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun writeMeta(m: Meta) {
        val dir = repoDir(m.owner, m.name).apply { mkdirs() }
        File(dir, "meta.json").writeText(JSONObject().apply {
            put("owner", m.owner); put("name", m.name); put("branch", m.branch)
            put("commit", m.commit); put("clonedAt", m.clonedAt)
        }.toString())
    }

    private fun readMeta(dir: File): Meta? = try {
        val j = JSONObject(File(dir, "meta.json").readText())
        Meta(j.getString("owner"), j.getString("name"), j.getString("branch"), j.optString("commit"), j.optLong("clonedAt"))
    } catch (e: Exception) { null }

    fun listRepos(): List<Meta> =
        (root.listFiles()?.filter { it.isDirectory } ?: emptyList()).mapNotNull { readMeta(it) }.sortedBy { it.full.lowercase() }

    /** Match "owner/name" exactly, or just "name" (case-insensitive) if unambiguous. */
    fun find(input: String): Meta? {
        val q = input.trim().removeSuffix("/").removeSuffix(".git").substringAfter("github.com/").lowercase()
        if (q.isBlank()) return null
        val all = listRepos()
        all.firstOrNull { it.full.lowercase() == q }?.let { return it }
        val byName = all.filter { it.name.lowercase() == q.substringAfterLast('/') }
        return byName.singleOrNull() ?: byName.firstOrNull()
    }

    fun delete(m: Meta): Boolean = repoDir(m.owner, m.name).deleteRecursively()

    /** Extract a GitHub zipball into [dest], dropping the "<owner>-<repo>-<sha>/" top folder. Zip-slip safe. */
    fun extractZip(zip: File, dest: File, maxFiles: Int = 20000): Int {
        dest.deleteRecursively(); dest.mkdirs()
        val destCanon = dest.canonicalPath + File.separator
        var count = 0
        ZipFile(zip).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty() || e.isDirectory) continue
                if (++count > maxFiles) throw IllegalStateException("Repo bahut bada hai (>$maxFiles files).")
                val out = File(dest, rel)
                if (!out.canonicalPath.startsWith(destCanon)) continue // zip-slip
                out.parentFile?.mkdirs()
                zf.getInputStream(e).use { i -> out.outputStream().use { i.copyTo(it) } }
            }
        }
        return count
    }

    fun gitBlobSha(f: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${f.length()}\u0000".toByteArray(Charsets.UTF_8))
        f.inputStream().use { i ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun snapshot(tree: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val base = tree.canonicalPath + File.separator
        tree.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.canonicalPath.removePrefix(base).replace(File.separatorChar, '/')
            if (!rel.startsWith(".git/")) out[rel] = gitBlobSha(f)
        }
        return out
    }

    fun saveBase(owner: String, name: String, snap: Map<String, String>) {
        File(repoDir(owner, name), "base.json").writeText(JSONObject().apply { snap.forEach { (k, v) -> put(k, v) } }.toString())
    }

    private fun loadBase(owner: String, name: String): Map<String, String>? = try {
        val j = JSONObject(File(repoDir(owner, name), "base.json").readText())
        j.keys().asSequence().associateWith { j.getString(it) }
    } catch (e: Exception) { null }

    /** null = the tree is too big to compare reliably. */
    fun changes(m: Meta, maxFiles: Int = 8000): Changes? {
        val tree = treeDir(m.owner, m.name)
        val base = loadBase(m.owner, m.name) ?: return Changes(emptyList(), emptyList(), emptyList())
        val now = LinkedHashMap<String, String>()
        var n = 0
        val prefix = tree.canonicalPath + File.separator
        for (f in tree.walkTopDown().filter { it.isFile }) {
            if (++n > maxFiles) return null
            val rel = f.canonicalPath.removePrefix(prefix).replace(File.separatorChar, '/')
            if (rel.startsWith(".git/")) continue
            now[rel] = gitBlobSha(f)
        }
        val added = now.keys.filter { it !in base }.sorted()
        val modified = now.keys.filter { base[it] != null && base[it] != now[it] }.sorted()
        val deleted = base.keys.filter { it !in now }.sorted()
        return Changes(added, modified, deleted)
    }

    /** Resolve a repo-relative path inside the working tree; null if it escapes the tree. */
    fun resolveInTree(m: Meta, rel: String): File? {
        val tree = treeDir(m.owner, m.name)
        val f = File(tree, rel.trim().trimStart('/'))
        return if (f.canonicalPath.startsWith(tree.canonicalPath + File.separator)) f else null
    }

    fun looksBinary(f: File): Boolean = try {
        f.inputStream().use { i ->
            val buf = ByteArray(8000); val n = i.read(buf)
            (0 until maxOf(n, 0)).any { buf[it] == 0.toByte() }
        }
    } catch (e: Exception) { true }

    fun listFiles(m: Meta): List<String> {
        val tree = treeDir(m.owner, m.name)
        val prefix = tree.canonicalPath + File.separator
        return tree.walkTopDown().filter { it.isFile }
            .map { it.canonicalPath.removePrefix(prefix).replace(File.separatorChar, '/') }
            .filter { !it.startsWith(".git/") }.sorted().toList()
    }
}
