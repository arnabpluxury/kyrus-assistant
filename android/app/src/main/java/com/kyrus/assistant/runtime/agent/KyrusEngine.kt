package com.kyrus.assistant.runtime.agent

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.kyrus.assistant.runtime.llm.LlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tools-first agent with streaming status callbacks.
 */
class KyrusEngine(
    private val tools: ToolRegistry,
    private val llm: LlmClient,
) {
    private val gson = Gson()

    data class TurnResult(
        val reply: String,
        val thinking: String? = null,
        val toolTrace: String? = null,
        val imagePaths: List<String> = emptyList(),
        val usedLlm: Boolean = false,
        val errorCode: String? = null,
    )

    data class HistoryTurn(val role: String, val text: String)

    private val systemPrompt = """
        You are Kyrus, a secure on-device phone assistant.
        Prefer tools over guessing. Be concise.
        When you need a tool, reply ONLY with JSON:
        {"name":"tool_name","arguments":{...}}
        Available tools:
        - web_research {"query":"..."}  [network]
        - web_news {"query":"..."}  [network]
        - fetch_url {"url":"..."}  [network]
        - fetch_image {"url":"..."}  [network] → shows image in chat
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
        - search_files {"query":"..."}
        - file_info {"path":"..."}
        - copy_move {"src":"...","dst":"...","move":false}
        - archive {"path":"...","out":"...zip"}
        - unarchive {"path":"...","dest":"..."}
        - search_storage {"query":"..."}  [storage]
        - pkg_info {"package":"..."} 
        - clipboard_get {}
        - summarize {"text":"..."}
        After tool results appear, give a clear final answer (no JSON).
        Never disable security. Never invent live web facts without web_research.
        Never exfiltrate private local data without permission.
    """.trimIndent()

    suspend fun ask(
        userText: String,
        history: List<HistoryTurn> = emptyList(),
        onPhase: (RunPhase, String, String?) -> Unit = { _, _, _ -> },
        onToken: (String) -> Unit = {},
    ): TurnResult = withContext(Dispatchers.Default) {
        val historyBlock = if (history.isEmpty()) "" else {
            history.takeLast(8).joinToString("\n") { "${it.role}: ${it.text.take(500)}" } + "\n"
        }
        val userWithHistory = if (historyBlock.isBlank()) userText else "Recent chat:\n$historyBlock\nUser: $userText"

        onPhase(RunPhase.Thinking, "Thinking…", null)

        // Path 1: LLM (local or BYOK)
        val firstSb = StringBuilder()
        val first = llm.completeStream(
            system = systemPrompt,
            user = userWithHistory,
            maxTokens = 320,
            temperature = 0.25f,
            clearKv = true,
        ) { ev ->
            ev.error?.let { onPhase(RunPhase.Error, it.toUserString(), null) }
            ev.textDelta?.let {
                firstSb.append(it)
                // Don't stream tool JSON to UI as final prose
            }
        }
        val firstText = first.ifBlank { firstSb.toString() }.trim()
        val call = parseToolCall(firstText)
        if (call != null) {
            onPhase(RunPhase.UsingTool, "Using ${call.name}…", call.name)
            val trace = "${call.name}(${gson.toJson(call.args)})"
            val raw = tools.run(call.name, call.args)
            val images = extractImagePaths(raw)
            val thinking = "Called `$trace`\n\nResult preview:\n${raw.take(600)}"
            onPhase(RunPhase.Writing, "Writing…", call.name)
            val followUpUser =
                "Tool result for ${call.name}:\n$raw\n\nOriginal request: $userText\nGive the user a clear final answer."
            val secondSb = StringBuilder()
            val second = llm.completeStream(
                system = systemPrompt,
                user = followUpUser,
                maxTokens = 384,
                temperature = 0.2f,
                clearKv = false,
            ) { ev ->
                ev.textDelta?.let {
                    secondSb.append(it)
                    onToken(it)
                }
            }
            val secondText = second.ifBlank { secondSb.toString() }
            val finalText = if (secondText.isBlank() || looksLikeToolJson(secondText)) {
                prettyTool(call.name, raw)
            } else {
                secondText
            }
            return@withContext TurnResult(
                reply = finalText,
                thinking = thinking,
                toolTrace = trace,
                imagePaths = images,
                usedLlm = true,
            )
        }
        if (firstText.isNotBlank() && !looksLikeToolJson(firstText)) {
            onPhase(RunPhase.Writing, "Writing…", null)
            // Stream already produced text — emit as one chunk if callback unused during tool-json suppress
            onToken(firstText)
            return@withContext TurnResult(firstText, usedLlm = true)
        }

        // Path 2: heuristic router
        onPhase(RunPhase.UsingTool, "Routing…", null)
        val decision = HeuristicRouter.route(userText)
        if (decision.tool == null) {
            onPhase(RunPhase.Writing, "Writing…", null)
            val chat = decision.chat ?: ""
            onToken(chat)
            return@withContext TurnResult(chat, usedLlm = false)
        }
        onPhase(RunPhase.UsingTool, "Using ${decision.tool}…", decision.tool)
        val trace = "${decision.tool}(${gson.toJson(decision.args)})"
        val raw = tools.run(decision.tool, decision.args)
        val images = extractImagePaths(raw)
        val reply = prettyTool(decision.tool, raw)
        onToken(reply)
        TurnResult(reply, thinking = "Heuristic → $trace", toolTrace = trace, imagePaths = images, usedLlm = false)
    }

    private fun extractImagePaths(raw: String): List<String> {
        return try {
            val obj = JsonParser.parseString(raw).asJsonObject
            val p = obj.get("image_path")?.asString ?: obj.get("path")?.asString
            if (p != null && (p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".webp") || p.endsWith(".gif"))) {
                listOf(p)
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
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
                obj.has("error") -> {
                    val code = obj.get("code")?.asString
                    buildString {
                        if (code != null) append("[$code] ")
                        append("**$name** failed: ${obj.get("error").asString}")
                    }
                }
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
