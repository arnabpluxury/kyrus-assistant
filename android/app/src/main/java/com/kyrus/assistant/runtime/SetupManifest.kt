package com.kyrus.assistant.runtime

/**
 * Transparent first-run sizes — shown before anything downloads.
 * APK stays lean; heavy pieces install into the private prefix after consent.
 */
object SetupManifest {
    data class Component(
        val id: String,
        val title: String,
        val detail: String,
        val bytes: Long,
        val required: Boolean = true,
        val url: String? = null,
        val destName: String? = null,
    )

    const val RELEASE_TAG = "v0.2.0"
    const val GITHUB_REPO = "arnabpluxury/kyrus-assistant"

    val modelUrl =
        "https://github.com/$GITHUB_REPO/releases/download/$RELEASE_TAG/kyrus-low.gguf"

    /** Approx. model size from release (~379 MiB). */
    const val MODEL_BYTES = 397_807_648L

    /** On-device runtime reserved for workspace, notes, caches (not a network download). */
    const val LOCAL_PREFIX_BYTES = 32L * 1024 * 1024

    fun components(): List<Component> = listOf(
        Component(
            id = "prefix",
            title = "Private runtime prefix",
            detail = "Sandbox workspace, notes, memory — like Termux \$PREFIX, inside the app only.",
            bytes = LOCAL_PREFIX_BYTES,
            required = true,
        ),
        Component(
            id = "model",
            title = "Kyrus low model (GGUF)",
            detail = "Fine-tuned on-device brain (Q4_K_M). Downloaded from GitHub Releases.",
            bytes = MODEL_BYTES,
            required = true,
            url = modelUrl,
            destName = "kyrus-low.gguf",
        ),
    )

    fun totalDownloadBytes(): Long = components().filter { it.url != null }.sumOf { it.bytes }

    fun totalDiskBytes(): Long = components().sumOf { it.bytes }

    fun formatBytes(n: Long): String {
        val mb = n / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format("%.2f GB", mb / 1024.0)
        else String.format("%.0f MB", mb)
    }
}
