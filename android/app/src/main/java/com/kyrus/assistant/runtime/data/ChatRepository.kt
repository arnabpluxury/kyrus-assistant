package com.kyrus.assistant.runtime.data

import android.content.Context
import androidx.room.Room
import com.google.gson.Gson
import com.kyrus.assistant.runtime.ErrorCodes
import com.kyrus.assistant.runtime.KyrusError
import com.kyrus.assistant.runtime.RuntimePaths
import com.kyrus.assistant.runtime.agent.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ChatRepository(context: Context) {
    private val app = context.applicationContext
    private val gson = Gson()
    private val db = Room.databaseBuilder(app, KyrusDatabase::class.java, "kyrus.db")
        .fallbackToDestructiveMigration()
        .build()
    private val dao = db.chatDao()

    fun observeConversations(): Flow<List<ConversationEntity>> = dao.observeConversations()
    fun observeMessages(cid: String): Flow<List<MessageEntity>> = dao.observeMessages(cid)
    fun observeProjects(): Flow<List<ProjectEntity>> = dao.observeProjects()

    suspend fun getConversation(id: String): ConversationEntity? = dao.getConversation(id)

    suspend fun ensureDefaultConversation(): ConversationEntity {
        val existing = dao.listConversations().firstOrNull()
        if (existing != null) return existing
        val c = ConversationEntity(
            id = UUID.randomUUID().toString(),
            title = "New chat",
        )
        dao.upsertConversation(c)
        val welcome = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = c.id,
            role = ChatMessage.Role.Assistant.name,
            text = "I'm Kyrus — on-device, sandboxed, and ready. Ask me anything; I'll use tools when needed and ask before going online.",
        )
        dao.upsertMessage(welcome)
        return c
    }

    suspend fun newConversation(projectId: String? = null): ConversationEntity {
        val c = ConversationEntity(
            id = UUID.randomUUID().toString(),
            title = "New chat",
            projectId = projectId,
        )
        dao.upsertConversation(c)
        return c
    }

    suspend fun deleteConversation(id: String) {
        dao.deleteMessages(id)
        dao.deleteConversation(id)
    }

    suspend fun touchTitle(id: String, title: String) {
        val c = dao.getConversation(id) ?: return
        dao.upsertConversation(c.copy(title = title.take(80), updatedAt = System.currentTimeMillis()))
    }

    suspend fun saveMessage(m: MessageEntity) = dao.upsertMessage(m)

    suspend fun listMessages(cid: String) = dao.listMessages(cid)

    suspend fun upsertProject(p: ProjectEntity) = dao.upsertProject(p)

    suspend fun deleteProject(id: String) = dao.deleteProject(id)

    suspend fun listProjects() = dao.listProjects()

    /**
     * Export chats + memory + prefs (not the GGUF by default) into a zip under exports/.
     */
    suspend fun exportData(includeModel: Boolean = false): Result<File> = withContext(Dispatchers.IO) {
        try {
            RuntimePaths.init(app)
            val outDir = File(RuntimePaths.root, "exports").also { it.mkdirs() }
            val out = File(outDir, "kyrus-export-${System.currentTimeMillis()}.zip")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
                fun putFile(pathInZip: String, file: File) {
                    if (!file.exists() || !file.isFile) return
                    zos.putNextEntry(ZipEntry(pathInZip))
                    FileInputStream(file).use { it.copyTo(zos) }
                    zos.closeEntry()
                }

                val dump = buildString {
                    appendLine("{")
                    appendLine("\"conversations\": ${gson.toJson(dao.listConversations())},")
                    appendLine("\"messages\": ${gson.toJson(dao.listConversations().flatMap { dao.listMessages(it.id) })},")
                    appendLine("\"projects\": ${gson.toJson(dao.listProjects())}")
                    appendLine("}")
                }
                zos.putNextEntry(ZipEntry("chats.json"))
                zos.write(dump.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                putFile("memory.json", RuntimePaths.memory)
                val prefsFile = File(app.filesDir.parentFile, "shared_prefs/kyrus.xml")
                putFile("prefs/kyrus.xml", prefsFile)

                if (includeModel) {
                    putFile("models/${RuntimePaths.modelFile.name}", RuntimePaths.modelFile)
                }
            }
            Result.success(out)
        } catch (e: Exception) {
            Result.failure(
                Exception(
                    KyrusError.data(ErrorCodes.DATA_EXPORT, "Export failed", e.message).toUserString(),
                ),
            )
        }
    }

    suspend fun importData(zipFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    when {
                        entry.name == "chats.json" -> {
                            val text = zis.readBytes().toString(Charsets.UTF_8)
                            // Best-effort: replace DB contents from export blob
                            val root = com.google.gson.JsonParser.parseString(text).asJsonObject
                            dao.clearMessages()
                            dao.clearConversations()
                            root.getAsJsonArray("conversations")?.forEach { el ->
                                dao.upsertConversation(gson.fromJson(el, ConversationEntity::class.java))
                            }
                            root.getAsJsonArray("messages")?.forEach { el ->
                                dao.upsertMessage(gson.fromJson(el, MessageEntity::class.java))
                            }
                            root.getAsJsonArray("projects")?.forEach { el ->
                                dao.upsertProject(gson.fromJson(el, ProjectEntity::class.java))
                            }
                        }
                        entry.name == "memory.json" -> {
                            RuntimePaths.memory.parentFile?.mkdirs()
                            FileOutputStream(RuntimePaths.memory).use { zis.copyTo(it) }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(
                Exception(
                    KyrusError.data(ErrorCodes.DATA_IMPORT, "Import failed", e.message).toUserString(),
                ),
            )
        }
    }

    fun toUiMessage(m: MessageEntity): ChatMessage = ChatMessage(
        id = m.id,
        role = runCatching { ChatMessage.Role.valueOf(m.role) }.getOrDefault(ChatMessage.Role.Assistant),
        text = m.text,
        thinking = m.thinking,
        toolTrace = m.toolTrace,
        imagePaths = m.imagePathsJson?.let {
            runCatching {
                gson.fromJson(it, Array<String>::class.java)?.toList().orEmpty()
            }.getOrDefault(emptyList())
        }.orEmpty(),
        attachmentPaths = m.attachmentPathsJson?.let {
            runCatching {
                gson.fromJson(it, Array<String>::class.java)?.toList().orEmpty()
            }.getOrDefault(emptyList())
        }.orEmpty(),
        status = m.status,
    )
}
