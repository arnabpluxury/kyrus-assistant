package com.kyrus.assistant.runtime

import android.content.Context
import java.io.File

/**
 * Private app "prefix" — mirrors Termux's $PREFIX idea without requiring Termux.
 * Uninstalling the app deletes this tree (models, chats DB is separate under databases/).
 */
object RuntimePaths {
    lateinit var root: File
        private set
    lateinit var models: File
        private set
    lateinit var workspace: File
        private set
    lateinit var notes: File
        private set
    lateinit var memory: File
        private set
    lateinit var bin: File
        private set
    lateinit var tmp: File
        private set
    lateinit var images: File
        private set
    lateinit var cache: File
        private set
    lateinit var exports: File
        private set

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        root = File(context.filesDir, "kyrus-prefix").also { it.mkdirs() }
        models = File(root, "models").also { it.mkdirs() }
        workspace = File(root, "workspace").also { it.mkdirs() }
        notes = File(root, "notes").also { it.mkdirs() }
        memory = File(root, "memory.json")
        bin = File(root, "bin").also { it.mkdirs() }
        tmp = File(root, "tmp").also { it.mkdirs() }
        images = File(root, "images").also { it.mkdirs() }
        cache = File(root, "cache").also { it.mkdirs() }
        exports = File(root, "exports").also { it.mkdirs() }
        initialized = true
    }

    fun ensureInit(context: Context) {
        if (!initialized) init(context)
    }

    val modelFile: File get() = File(models, "kyrus-low.gguf")
}
