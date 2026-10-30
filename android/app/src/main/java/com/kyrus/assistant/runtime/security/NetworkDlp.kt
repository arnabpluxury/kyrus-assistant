package com.kyrus.assistant.runtime.security

import com.kyrus.assistant.runtime.ErrorCodes
import com.kyrus.assistant.runtime.KyrusError
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Blocks accidental exfiltration of local secrets / private paths over the network.
 */
object NetworkDlp {
    data class AuditEntry(
        val time: Long = System.currentTimeMillis(),
        val tool: String,
        val summary: String,
        val blocked: Boolean,
        val code: String? = null,
    )

    private val audit = CopyOnWriteArrayList<AuditEntry>()

    fun auditLog(): List<AuditEntry> = audit.toList().takeLast(200)

    private val secretPatterns = listOf(
        Regex("""(?i)(api[_-]?key|secret|password|token|authorization)\s*[:=]\s*\S+"""),
        Regex("""(?i)BEGIN (RSA |OPENSSH |EC )?PRIVATE KEY"""),
        Regex("""(?i)sk-[a-zA-Z0-9]{20,}"""),
        Regex("""(?i)AIza[0-9A-Za-z\-_]{20,}"""),
    )

    fun scanOutbound(tool: String, payload: String, localPaths: List<String> = emptyList()): DlpVerdict {
        for (p in secretPatterns) {
            if (p.containsMatchIn(payload)) {
                val err = KyrusError.network(
                    ErrorCodes.NET_DLP_BLOCK,
                    "Outbound payload looks like it contains secrets — blocked",
                    retryable = false,
                )
                audit += AuditEntry(tool = tool, summary = err.message, blocked = true, code = err.code)
                return DlpVerdict.Block(err)
            }
        }
        val mentionsPrivate = localPaths.any { payload.contains(it) } ||
            payload.contains("/data/data/com.kyrus") ||
            payload.contains("memory.json") && payload.length > 400
        if (mentionsPrivate) {
            val err = KyrusError.network(
                ErrorCodes.NET_DLP_CONFIRM,
                "This request would send local/private data online — confirm to continue",
                retryable = true,
            )
            audit += AuditEntry(tool = tool, summary = err.message, blocked = false, code = err.code)
            return DlpVerdict.NeedsConfirm(err)
        }
        audit += AuditEntry(tool = tool, summary = "allowed ${payload.take(80)}", blocked = false)
        return DlpVerdict.Allow
    }

    fun localRoots(files: List<File>): List<String> = files.map { it.absolutePath }

    sealed class DlpVerdict {
        data object Allow : DlpVerdict()
        data class NeedsConfirm(val error: KyrusError) : DlpVerdict()
        data class Block(val error: KyrusError) : DlpVerdict()
    }
}
