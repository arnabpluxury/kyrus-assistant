package com.kyrus.assistant.runtime.llm

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.kyrus.assistant.runtime.ErrorCodes
import com.kyrus.assistant.runtime.KyrusError
import com.kyrus.assistant.runtime.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

enum class LlmBackend { Local, OpenAI, Anthropic, Google, Groq, OpenRouter, CustomOpenAI }

data class LlmStreamEvent(
    val textDelta: String? = null,
    val done: Boolean = false,
    val error: KyrusError? = null,
)

/**
 * Unified local + BYOK cloud client.
 */
class LlmClient(
    private val local: LlamaRuntime,
    private val prefs: Prefs,
) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .build()

    val localRuntime: LlamaRuntime get() = local

    fun activeBackend(): LlmBackend = prefs.llmBackend()

    suspend fun completeStream(
        system: String,
        user: String,
        maxTokens: Int = 512,
        temperature: Float = 0.25f,
        clearKv: Boolean = true,
        onEvent: (LlmStreamEvent) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        when (val backend = activeBackend()) {
            LlmBackend.Local -> {
                val sb = StringBuilder()
                val out = local.completeStream(system, user, maxTokens, temperature, clearKv) { piece ->
                    sb.append(piece)
                    onEvent(LlmStreamEvent(textDelta = piece))
                    true
                }
                onEvent(LlmStreamEvent(done = true))
                out.ifBlank { sb.toString() }
            }
            else -> cloudChat(backend, system, user, maxTokens, temperature, onEvent)
        }
    }

    private fun cloudChat(
        backend: LlmBackend,
        system: String,
        user: String,
        maxTokens: Int,
        temperature: Float,
        onEvent: (LlmStreamEvent) -> Unit,
    ): String {
        val key = prefs.apiKey(backend.name)
        if (key.isNullOrBlank()) {
            val err = KyrusError.provider(ErrorCodes.PROVIDER_NO_KEY, "No API key for $backend")
            onEvent(LlmStreamEvent(error = err, done = true))
            return ""
        }
        return try {
            when (backend) {
                LlmBackend.Anthropic -> anthropic(key, system, user, maxTokens, temperature, onEvent)
                LlmBackend.Google -> google(key, system, user, maxTokens, temperature, onEvent)
                else -> openAiCompatible(backend, key, system, user, maxTokens, temperature, onEvent)
            }
        } catch (e: Exception) {
            val err = KyrusError.provider(ErrorCodes.PROVIDER_HTTP, "Provider call failed", e.message)
            onEvent(LlmStreamEvent(error = err, done = true))
            ""
        }
    }

    private fun openAiCompatible(
        backend: LlmBackend,
        key: String,
        system: String,
        user: String,
        maxTokens: Int,
        temperature: Float,
        onEvent: (LlmStreamEvent) -> Unit,
    ): String {
        val base = when (backend) {
            LlmBackend.Groq -> "https://api.groq.com/openai/v1"
            LlmBackend.OpenRouter -> "https://openrouter.ai/api/v1"
            LlmBackend.CustomOpenAI -> prefs.customBaseUrl().ifBlank { "https://api.openai.com/v1" }
            else -> "https://api.openai.com/v1"
        }
        val model = prefs.cloudModel().ifBlank {
            when (backend) {
                LlmBackend.Groq -> "llama-3.1-8b-instant"
                LlmBackend.OpenRouter -> "openai/gpt-4o-mini"
                else -> "gpt-4o-mini"
            }
        }
        val body = gson.toJson(
            mapOf(
                "model" to model,
                "temperature" to temperature,
                "max_tokens" to maxTokens,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to system),
                    mapOf("role" to "user", "content" to user),
                ),
            ),
        )
        val req = Request.Builder()
            .url("$base/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .apply {
                if (backend == LlmBackend.OpenRouter) {
                    header("HTTP-Referer", "https://github.com/arnabpluxury/kyrus-assistant")
                    header("X-Title", "Kyrus Assistant")
                }
            }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403) {
                val err = KyrusError.provider(ErrorCodes.PROVIDER_AUTH, "Auth failed HTTP ${resp.code}", raw.take(200))
                onEvent(LlmStreamEvent(error = err, done = true))
                return ""
            }
            if (!resp.isSuccessful) {
                val err = KyrusError.provider(ErrorCodes.PROVIDER_HTTP, "HTTP ${resp.code}", raw.take(300), true)
                onEvent(LlmStreamEvent(error = err, done = true))
                return ""
            }
            val text = JsonParser.parseString(raw).asJsonObject
                .getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString
                .orEmpty()
            onEvent(LlmStreamEvent(textDelta = text))
            onEvent(LlmStreamEvent(done = true))
            return text
        }
    }

    private fun anthropic(
        key: String,
        system: String,
        user: String,
        maxTokens: Int,
        temperature: Float,
        onEvent: (LlmStreamEvent) -> Unit,
    ): String {
        val model = prefs.cloudModel().ifBlank { "claude-3-5-haiku-latest" }
        val body = gson.toJson(
            mapOf(
                "model" to model,
                "max_tokens" to maxTokens,
                "temperature" to temperature,
                "system" to system,
                "messages" to listOf(mapOf("role" to "user", "content" to user)),
            ),
        )
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val code = if (resp.code == 401) ErrorCodes.PROVIDER_AUTH else ErrorCodes.PROVIDER_HTTP
                onEvent(LlmStreamEvent(error = KyrusError.provider(code, "Anthropic HTTP ${resp.code}", raw.take(300)), done = true))
                return ""
            }
            val arr = JsonParser.parseString(raw).asJsonObject.getAsJsonArray("content")
            val text = arr?.joinToString("") {
                it.asJsonObject.get("text")?.asString.orEmpty()
            }.orEmpty()
            onEvent(LlmStreamEvent(textDelta = text))
            onEvent(LlmStreamEvent(done = true))
            return text
        }
    }

    private fun google(
        key: String,
        system: String,
        user: String,
        maxTokens: Int,
        temperature: Float,
        onEvent: (LlmStreamEvent) -> Unit,
    ): String {
        val model = prefs.cloudModel().ifBlank { "gemini-2.0-flash" }
        val body = gson.toJson(
            mapOf(
                "system_instruction" to mapOf("parts" to listOf(mapOf("text" to system))),
                "contents" to listOf(mapOf("parts" to listOf(mapOf("text" to user)))),
                "generationConfig" to mapOf(
                    "temperature" to temperature,
                    "maxOutputTokens" to maxTokens,
                ),
            ),
        )
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                onEvent(
                    LlmStreamEvent(
                        error = KyrusError.provider(ErrorCodes.PROVIDER_HTTP, "Gemini HTTP ${resp.code}", raw.take(300)),
                        done = true,
                    ),
                )
                return ""
            }
            val text = JsonParser.parseString(raw).asJsonObject
                .getAsJsonArray("candidates")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("content")
                ?.getAsJsonArray("parts")
                ?.get(0)?.asJsonObject
                ?.get("text")?.asString
                .orEmpty()
            onEvent(LlmStreamEvent(textDelta = text))
            onEvent(LlmStreamEvent(done = true))
            return text
        }
    }
}
