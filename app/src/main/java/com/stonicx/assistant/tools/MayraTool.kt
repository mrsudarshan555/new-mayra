package com.stonicx.assistant.tools

import android.content.Context
import com.stonicx.assistant.StonicXApp
import org.json.JSONArray
import org.json.JSONObject

/** One parameter of a tool, in Gemini function-declaration terms. */
data class ToolParam(
    val name: String,
    val type: String = "STRING", // STRING | INTEGER | BOOLEAN | NUMBER
    val description: String,
    val required: Boolean = false
)

class ToolContext(val context: Context) {
    val app: StonicXApp get() = context.applicationContext as StonicXApp
}

/** A tool Mayra (Gemini Live) can call. Implementations must never throw: return ToolResult.fail instead. */
interface MayraTool {
    val name: String
    val description: String
    val params: List<ToolParam> get() = emptyList()
    /** Pinned tools are declared to Gemini Live up front; the rest are found with find_tool and run with run_tool. */
    val pinned: Boolean get() = false
    suspend fun execute(ctx: ToolContext, args: JSONObject): JSONObject
}

/** Small lambda-based tool so simple tools need no boilerplate class. */
class FnTool(
    override val name: String,
    override val description: String,
    override val params: List<ToolParam>,
    private val fn: suspend (ToolContext, JSONObject) -> JSONObject
) : MayraTool {
    override suspend fun execute(ctx: ToolContext, args: JSONObject): JSONObject = fn(ctx, args)
}

fun tool(
    name: String,
    description: String,
    vararg params: ToolParam,
    fn: suspend (ToolContext, JSONObject) -> JSONObject
): MayraTool = FnTool(name, description, params.toList(), fn)

fun p(name: String, description: String, required: Boolean = false, type: String = "STRING") =
    ToolParam(name, type, description, required)

object ToolResult {
    fun ok(message: String, extra: (JSONObject.() -> Unit)? = null): JSONObject =
        JSONObject().apply {
            put("success", true)
            put("message", message)
            extra?.invoke(this)
        }

    fun fail(message: String, extra: (JSONObject.() -> Unit)? = null): JSONObject =
        JSONObject().apply {
            put("success", false)
            put("message", message)
            extra?.invoke(this)
        }
}

fun MayraTool.toDeclaration(): JSONObject = JSONObject().apply {
    put("name", name)
    put("description", description)
    put("parameters", JSONObject().apply {
        put("type", "OBJECT")
        put("properties", JSONObject().apply {
            params.forEach { prm ->
                put(prm.name, JSONObject().apply {
                    put("type", prm.type)
                    put("description", prm.description)
                })
            }
        })
        val req = params.filter { it.required }
        if (req.isNotEmpty()) put("required", JSONArray().apply { req.forEach { put(it.name) } })
    })
}

/** Wrapper that marks any tool as pinned (always declared to the live model). */
class PinnedTool(private val inner: MayraTool) : MayraTool by inner {
    override val pinned: Boolean get() = true
}

fun MayraTool.pin(): MayraTool = PinnedTool(this)
