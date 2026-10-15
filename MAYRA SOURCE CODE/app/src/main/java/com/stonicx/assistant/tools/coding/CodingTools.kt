package com.stonicx.assistant.tools.coding

import com.stonicx.assistant.tools.MayraTool
import com.stonicx.assistant.tools.pin

/** Everything under the "coding" family: GitHub (remote + local clones) and Termux. */
object CodingTools {
    /** Declared to the live model up front; the rest are reachable through find_tool / run_tool. */
    private val PINNED = setOf(
        "github_clone_repo", "github_pull", "github_agent_edit", "github_local_changes", "github_push_changes",
        "github_read_file", "github_list_prs", "termux_run"
    )

    fun all(): List<MayraTool> =
        (GitHubRemoteTools.all() + GitHubLocalTools.all() + TermuxTools.all()).map { if (it.name in PINNED) it.pin() else it }
}
