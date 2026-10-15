package com.stonicx.assistant.tools.coding

import android.util.Base64
import com.stonicx.assistant.tools.ToolContext
import com.stonicx.assistant.tools.ToolResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Pushes all local changes as ONE commit using the Git Data API (blobs -> tree -> commit -> ref). */
object GitPusher {

    suspend fun push(
        ctx: ToolContext, api: GitHubApi, store: LocalRepoStore, m: LocalRepoStore.Meta,
        ch: LocalRepoStore.Changes, message: String, targetBranch: String, newBranch: Boolean
    ): JSONObject {
        val base = "/repos/${m.owner}/${m.name}"
        if (ch.added.size + ch.modified.size > 200) return ToolResult.fail("200 se zyada files ek saath push nahi karungi - laptop se karo.")

        // 1. Which commit are we building on?
        val sourceBranch = if (newBranch) m.branch else targetBranch
        val refResp = api.get("$base/git/ref/heads/$sourceBranch")
        if (!refResp.ok) return ToolResult.fail(refResp.errorText())
        val headSha = refResp.obj()?.optJSONObject("object")?.optString("sha").orEmpty()
        if (headSha.isBlank()) return ToolResult.fail("Branch '$sourceBranch' ka head nahi mila.")
        if (!newBranch && sourceBranch == m.branch && headSha != m.commit) {
            return ToolResult.fail("Download ke baad kisi aur ne '${m.branch}' pe push kiya hai - tumhare kaam ko overwrite nahi karungi. github_push_changes ko new_branch=true ke saath naye branch naam pe chalao, ya local badlaav push karke merge karo.")
        }
        if (newBranch) {
            val exists = api.get("$base/git/ref/heads/$targetBranch")
            if (exists.ok) return ToolResult.fail("Branch '$targetBranch' pehle se hai - koi aur naam do ya new_branch ke bina push karo.")
        }

        // 2. Base tree + existing file modes (so executable bits survive)
        val commit = api.get("$base/git/commits/$headSha")
        if (!commit.ok) return ToolResult.fail(commit.errorText())
        val baseTree = commit.obj()?.optJSONObject("tree")?.optString("sha").orEmpty()
        val modes = HashMap<String, String>()
        api.get("$base/git/trees/$baseTree?recursive=1").obj()?.optJSONArray("tree")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { if (it.optString("type") == "blob") modes[it.optString("path")] = it.optString("mode") }
        }

        // 3. Blobs for new/changed files
        val entries = JSONArray()
        for (path in ch.added + ch.modified) {
            val f: File = store.resolveInTree(m, path) ?: continue
            val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
            val blob = api.post("$base/git/blobs", JSONObject().put("content", b64).put("encoding", "base64"))
            if (!blob.ok) return ToolResult.fail("'$path' upload nahi hui: " + blob.errorText())
            entries.put(JSONObject().put("path", path).put("mode", modes[path] ?: "100644").put("type", "blob").put("sha", blob.obj()?.optString("sha")))
        }
        for (path in ch.deleted) {
            entries.put(JSONObject().put("path", path).put("mode", modes[path] ?: "100644").put("type", "blob").put("sha", JSONObject.NULL))
        }

        // 4. Tree -> commit -> ref
        val tree = api.post("$base/git/trees", JSONObject().put("base_tree", baseTree).put("tree", entries))
        if (!tree.ok) return ToolResult.fail(tree.errorText())
        val newCommit = api.post("$base/git/commits", JSONObject()
            .put("message", message).put("tree", tree.obj()?.optString("sha")).put("parents", JSONArray().put(headSha)))
        if (!newCommit.ok) return ToolResult.fail(newCommit.errorText())
        val newSha = newCommit.obj()?.optString("sha").orEmpty()
        val refUpdate = if (newBranch)
            api.post("$base/git/refs", JSONObject().put("ref", "refs/heads/$targetBranch").put("sha", newSha))
        else
            api.patch("$base/git/refs/heads/$targetBranch", JSONObject().put("sha", newSha).put("force", false))
        if (!refUpdate.ok) return ToolResult.fail(refUpdate.errorText())

        // 5. Local copy now equals remote
        store.saveBase(m.owner, m.name, store.snapshot(store.treeDir(m.owner, m.name)))
        store.writeMeta(m.copy(branch = targetBranch, commit = newSha))
        return ToolResult.ok(
            "${ch.count} file ka badlaav ek commit me ${m.full} ke '$targetBranch' pe push ho gaya (${newSha.take(7)})" +
                if (newBranch) " - naya branch ban gaya." else "."
        ) { put("commit", newSha); put("branch", targetBranch) }
    }
}
