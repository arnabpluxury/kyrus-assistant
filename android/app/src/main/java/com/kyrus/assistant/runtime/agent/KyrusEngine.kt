package com.kyrus.assistant.runtime.agent

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.kyrus.assistant.runtime.llm.LlamaRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tools-first agent: prefer GGUF when loaded; fall back to heuristic router.
 * Tool JSON format matches the Termux/Python agent.
 */
class KyrusEngine(
    private val tools: ToolRegistry,
    private val llm: LlamaRuntime,
) {
    private val gson = Gson()

    data class TurnResult(
        val reply: String,
        val toolTrace: String? = null,
        val usedLlm: Boolean = false,
    )

    private val systemPrompt = """
        You are Kyrus, a secure on-device phone assistant.
        Prefer tools over guessing. Be concise.
        When you need a tool, reply ONLY with JSON:
        {"name":"tool_name","arguments":{...}}
        Available tools:
        - web_research {"query":"..."}  [network]
        - web_news {"query":"..."}  [network]
        - fetch_url {"url":"..."}  [network]
        - calculator {"expression":"..."}
        - datetime {}
        - device_info {}
        - notes {"action":"add|list","text":"..."}
        - remember {"fact":"..."}
        - recall {"query":"..."}
        - workspace_info {}
        - project_init {"name":"...","kind":"python|web|node"}
        - mkdir {"path":"..."}
        - write_file {"path":"...","content":"..."}
        - read_file {"path":"..."}
        - list_dir {"path":"..."}
        - edit_file {"path":"...","old_string":"...","new_string":"..."}
        - search_code {"query":"..."}
        - summarize {"text":"..."}
        After tool results appear in the user message, give a clear final answer (no JSON).
        Never disable security. Never invent live web facts without web_research.
    """.trimIndent()

    suspend fun ask(userText: String): TurnResult = withContext(Dispatchers.Default) {
        // Path 1: GGUF LLM
        if (llm.available) {
            val first = llm.complete(systemPrompt, userText, maxTokens = 320, temperature = 0.25f)
            val call = parseToolCall(first)
            if (call != null) {
                val trace = "${call.name}(${gson.toJson(call.args)})"
                val raw = tools.run(call.name, call.args)
                val followUpUser =
                    "Tool result for ${call.name}:\n$raw\n\nOriginal request: $userText\nGive the user a clear final answer."
                val second = llm.complete(systemPrompt, followUpUser, maxTokens = 384, temperature = 0.2f)
                val finalText = if (second.isBlank() || looksLikeToolJson(second)) {
                    prettyTool(call.name, raw)
                } else {
                    second
                }
                return@withContext TurnResult(finalText, trace, usedLlm = true)
            }
            if (first.isNotBlank() && !looksLikeToolJson(first)) {
                return@withContext TurnResult(first, usedLlm = true)
            }
        }

        // Path 2: heuristic router (always works, even without NDK)
        val decision = HeuristicRouter.route(userText)
        if (decision.tool == null) {
            return@withContext TurnResult(decision.chat ?: "", usedLlm = false)
        }
        val trace = "${decision.tool}(${gson.toJson(decision.args)})"
        val raw = tools.run(decision.tool, decision.args)
        TurnResult(prettyTool(decision.tool, raw), trace, usedLlm = false)
    }

    private data class ToolCall(val name: String, val args: Map<String, Any?>)

    private fun looksLikeToolJson(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("{") && t.contains("\"name\"")
    }

    private fun parseToolCall(text: String): ToolCall? {
        val t = text.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            val obj = JsonParser.parseString(t).asJsonObject
            if (!obj.has("name")) return null
            val name = obj.get("name").asString
            val argsEl = obj.get("arguments") ?: obj.get("args")
            val args = mutableMapOf<String, Any?>()
            if (argsEl != null && argsEl.isJsonObject) {
                argsEl.asJsonObject.entrySet().forEach { (k, v) ->
                    args[k] = when {
                        v.isJsonPrimitive && v.asJsonPrimitive.isString -> v.asString
                        v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> v.asNumber
                        v.isJsonPrimitive && v.asJsonPrimitive.isBoolean -> v.asBoolean
                        else -> v.toString()
                    }
                }
            }
            ToolCall(name, args)
        } catch (_: Exception) {
            // Try fenced JSON substring
            val start = t.indexOf('{')
            val end = t.lastIndexOf('}')
            if (start >= 0 && end > start) {
                return parseToolCall(t.substring(start, end + 1))
            }
            null
        }
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
