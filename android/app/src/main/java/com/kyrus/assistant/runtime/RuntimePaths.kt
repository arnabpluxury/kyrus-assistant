package com.kyrus.assistant.runtime

import android.content.Context
import java.io.File

/**
 * Private app "prefix" — mirrors Termux's $PREFIX idea without requiring Termux.
 * Everything lives under the app sandbox; users never touch a CLI.
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

    fun init(context: Context) {
        root = File(context.filesDir, "kyrus-prefix").also { it.mkdirs() }
        models = File(root, "models").also { it.mkdirs() }
        workspace = File(root, "workspace").also { it.mkdirs() }
        notes = File(root, "notes").also { it.mkdirs() }
        memory = File(root, "memory.json")
        bin = File(root, "bin").also { it.mkdirs() }
        tmp = File(root, "tmp").also { it.mkdirs() }
    }

    val modelFile: File get() = File(models, "kyrus-low.gguf")
}
