package com.stonicx.assistant.tools

import android.util.Log
import com.stonicx.assistant.tools.coding.CodingTools
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Central place for every "extra" tool (coding, marketing, social, ...).
 * Only pinned tools plus find_tool / run_tool are declared to Gemini Live, which keeps the live session
 * setup small; every other tool is discovered with find_tool and called with run_tool (or by its own name).
 */
object ToolRegistry {
    private const val TAG = "ToolRegistry"
    private val tools = LinkedHashMap<String, MayraTool>()

    init {
        register(findTool.pin())
        register(runTool.pin())
        CodingTools.all().forEach { register(it) }
    }

    @Synchronized
    fun register(tool: MayraTool) {
        if (tools.containsKey(tool.name)) Log.w(TAG, "Tool '${tool.name}' registered twice, replacing")
        tools[tool.name] = tool
    }

    @Synchronized fun has(name: String): Boolean = tools.containsKey(name)

    @Synchronized fun names(): List<String> = tools.keys.toList()

    /** What goes into the live session setup. */
    @Synchronized fun declarations(): List<JSONObject> = tools.values.filter { it.pinned }.map { it.toDeclaration() }

    /** How long the service should wait for a tool before giving up. */
    fun timeoutMs(name: String): Long = when {
        name == "run_tool" -> 960_000L
        name.startsWith("termux_") && name !in setOf("termux_status", "termux_read_more", "termux_find_in_output", "termux_read_file", "termux_list_recipes") -> 960_000L
        name == "github_clone_repo" || name == "github_pull" -> 240_000L
        name == "github_agent_edit" || name == "github_push_changes" || name == "github_share_repo" -> 120_000L
        else -> 60_000L
    }

    suspend fun execute(ctx: ToolContext, name: String, args: JSONObject): JSONObject {
        val t = synchronized(this) { tools[name] } ?: return ToolResult.fail("Unknown tool: $name. find_tool se sahi naam dhoondho.")
        return try {
            t.execute(ctx, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Tool $name crashed", e)
            ToolResult.fail("$name fail ho gaya: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun describe(t: MayraTool): String = buildString {
        append("${t.name} - ${t.description}")
        if (t.params.isNotEmpty()) append("\n  params: ").append(t.params.joinToString(", ") { "${it.name}${if (it.required) "*" else ""} (${it.type.lowercase()}: ${it.description.take(90)})" })
    }

    private val findTool: MayraTool get() = tool(
        "find_tool",
        "Search the extra tools (GitHub, Termux/coding, and more) by keyword and return their exact names and parameters. Use this when the user wants something the declared tools do not cover, then call the tool by its name with run_tool.",
        p("query", "What you want to do, in a few words, e.g. 'github clone', 'termux python', 'pull request'", true)
    ) { _, a ->
        val words = a.optString("query").lowercase().split(Regex("[^a-z0-9_]+")).filter { it.length >= 2 }
        val all = synchronized(this) { tools.values.toList() }.filter { it.name != "find_tool" && it.name != "run_tool" }
        val scored = all.map { t ->
            val hay = (t.name + " " + t.description).lowercase()
            t to words.sumOf { w -> (if (t.name.contains(w)) 3 else 0) + (if (hay.contains(w)) 1 else 0) }
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(8)
        if (scored.isEmpty()) ToolResult.ok("Is naam ka koi extra tool nahi mila. Doosre keywords try karo.")
        else ToolResult.ok(scored.joinToString("\n\n") { describe(it.first) })
    }

    private val runTool: MayraTool get() = tool(
        "run_tool",
        "Run an extra tool found with find_tool. name is the exact tool name; arguments is a JSON object string with its parameters, e.g. {\"repo\":\"owner/name\"}.",
        p("name", "Exact tool name from find_tool", true),
        p("arguments", "JSON object string of the tool's parameters; {} if none")
    ) { ctx, a ->
        val n = a.optString("name").trim()
        if (n.isEmpty() || n == "run_tool" || n == "find_tool") return@tool ToolResult.fail("Tool ka exact naam batao (find_tool se milega).")
        val args = try { JSONObject(a.optString("arguments").ifBlank { "{}" }) } catch (e: Exception) { return@tool ToolResult.fail("arguments valid JSON object hona chahiye.") }
        execute(ctx, n, args)
    }
}
