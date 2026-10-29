package com.kyrus.assistant.runtime.agent

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background agent loop — tools-first, same philosophy as the Termux CLI,
 * but driven entirely from the app (no visible terminal).
 */
class KyrusEngine(
    private val tools: ToolRegistry,
) {
    private val gson = Gson()

    data class TurnResult(
        val reply: String,
        val toolTrace: String? = null,
    )

    suspend fun ask(userText: String): TurnResult = withContext(Dispatchers.Default) {
        val decision = HeuristicRouter.route(userText)
        if (decision.tool == null) {
            return@withContext TurnResult(decision.chat ?: "")
        }
        val trace = "${decision.tool}(${gson.toJson(decision.args)})"
        val raw = tools.run(decision.tool, decision.args)
        val pretty = prettyTool(decision.tool, raw)
        TurnResult(pretty, trace)
    }

    private fun prettyTool(name: String, raw: String): String {
        return try {
            val obj = JsonParser.parseString(raw).asJsonObject
            when {
                obj.has("error") -> "**$name** failed: ${obj.get("error").asString}"
                obj.has("message") -> obj.get("message").asString
                obj.has("answer") -> obj.get("answer").asString
                obj.has("summary") -> obj.get("summary").asString
                obj.has("result") -> "${obj.get("expression")?.asString ?: "?"} = **${obj.get("result")}**"
                else -> raw
            }
        } catch (_: Exception) {
            raw
        }
    }
}
