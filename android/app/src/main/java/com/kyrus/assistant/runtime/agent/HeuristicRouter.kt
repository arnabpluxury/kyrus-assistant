package com.kyrus.assistant.runtime.agent

/**
 * Ultra-light intent router — same idea as the Termux heuristic LLM.
 * When a GGUF JNI runtime is linked later, it replaces this for generation;
 * tools stay the same.
 */
object HeuristicRouter {
    data class Decision(val tool: String?, val args: Map<String, Any?>, val chat: String?)

    fun route(user: String): Decision {
        val t = user.trim()
        val low = t.lowercase()

        if (low.matches(Regex("^(hi|hello|hey)\\b.*"))) {
            return Decision(
                null,
                emptyMap(),
                "Hey — I'm Kyrus on your phone.\nAsk me to research something, calculate, take notes, or scaffold a project in the sandbox.",
            )
        }
        if (low.contains("help") || low.contains("what can you do")) {
            return Decision(
                null,
                emptyMap(),
                "I run tools on-device:\n• Web research (asks permission)\n• Calculator, notes, time\n• Sandboxed coding (create projects / files)\n• Device / workspace status\nTry: research latest Android WebView CVE",
            )
        }
        if (Regex("\\b(device|phone|workspace|tier|hardware)\\b").containsMatchIn(low)) {
            return Decision("device_info", emptyMap(), null)
        }
        if (Regex("\\b(time|date|today|clock)\\b").containsMatchIn(low)) {
            return Decision("datetime", emptyMap(), null)
        }
        Regex("(?:calc(?:ulate)?|compute)\\s+(.+)$", RegexOption.IGNORE_CASE).find(t)?.let {
            return Decision("calculator", mapOf("expression" to it.groupValues[1]), null)
        }
        if (t.replace("x", "*", ignoreCase = true).matches(Regex("^[0-9.\\s+\\-*/()]+$"))) {
            return Decision("calculator", mapOf("expression" to t), null)
        }
        Regex("(?:note|save note)[:\\s]+(.+)", RegexOption.IGNORE_CASE).find(t)?.let {
            return Decision("notes", mapOf("action" to "add", "text" to it.groupValues[1]), null)
        }
        if (Regex("\\b(list notes|show notes|my notes)\\b").containsMatchIn(low)) {
            return Decision("notes", mapOf("action" to "list"), null)
        }
        Regex("(?:remember that|remember:)\\s+(.+)", RegexOption.IGNORE_CASE).find(t)?.let {
            return Decision("remember", mapOf("fact" to it.groupValues[1]), null)
        }
        if (Regex("\\b(what do you (know|remember)|recall|my memory)\\b").containsMatchIn(low)) {
            val q = t.replace(Regex(".*?(recall|remember)\\s*", RegexOption.IGNORE_CASE), "").trim()
            return Decision("recall", mapOf("query" to q), null)
        }
        Regex(
            "(?:create|scaffold|init|new)\\s+(?:a\\s+)?(python|web|node|js)?\\s*project\\s*(?:named\\s+|called\\s+)?([a-zA-Z0-9_\\-]+)?",
            RegexOption.IGNORE_CASE,
        ).find(t)?.let {
            var kind = it.groupValues[1].ifBlank { "python" }
            if (kind == "js") kind = "node"
            val name = it.groupValues[2].ifBlank { "app" }
            return Decision("project_init", mapOf("name" to name, "kind" to kind), null)
        }
        if (Regex("\\b(workspace|coding workspace)\\b").containsMatchIn(low)) {
            return Decision("workspace_info", emptyMap(), null)
        }
        Regex("(?:mkdir|create (?:folder|directory))\\s+(.+)$", RegexOption.IGNORE_CASE).find(t)?.let {
            return Decision("mkdir", mapOf("path" to it.groupValues[1].trim()), null)
        }
        Regex("(?:news(?: about)?|headlines)\\s+(.+)$", RegexOption.IGNORE_CASE).find(t)?.let {
            return Decision("web_news", mapOf("query" to it.groupValues[1]), null)
        }
        val researchHit = Regex(
            "\\b(search|research|look up|google|find out|latest|current|who is|what is|how to|tell me about)\\b",
        ).containsMatchIn(low) || t.endsWith("?")
        if (researchHit && !Regex("\\b(hi|hello|hey|thanks|bye)\\b").containsMatchIn(low)) {
            return Decision("web_research", mapOf("query" to t), null)
        }
        Regex("(?:summarize|tldr)[:\\s]+(.+)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(t)?.let {
            if (it.groupValues[1].length > 40) {
                return Decision("summarize", mapOf("text" to it.groupValues[1]), null)
            }
        }
        Regex("(?:fetch image|show image|download image)\\s+(https?://\\S+)", RegexOption.IGNORE_CASE).find(t)?.let {
            return Decision("fetch_image", mapOf("url" to it.groupValues[1]), null)
        }
        Regex("(?:search (?:storage|files|downloads|phone)|find (?:on (?:my )?(?:phone|storage)))\\s+(.+)$", RegexOption.IGNORE_CASE).find(t)?.let {
            return Decision("search_storage", mapOf("query" to it.groupValues[1].trim()), null)
        }
        Regex("(?:zip|archive)\\s+(.+)$", RegexOption.IGNORE_CASE).find(t)?.let {
            return Decision("archive", mapOf("path" to it.groupValues[1].trim()), null)
        }
        Regex("(?:unzip|unarchive|extract)\\s+(.+)$", RegexOption.IGNORE_CASE).find(t)?.let {
            return Decision("unarchive", mapOf("path" to it.groupValues[1].trim()), null)
        }
        if (Regex("\\b(apps?|packages?|installed)\\b").containsMatchIn(low) && Regex("\\b(list|show|what)\\b").containsMatchIn(low)) {
            return Decision("pkg_info", mapOf("package" to ""), null)
        }
        if (Regex("\\bclipboard\\b").containsMatchIn(low)) {
            return Decision("clipboard_get", emptyMap(), null)
        }

        return Decision(
            null,
            emptyMap(),
            "Try being specific — e.g. “research …”, “calculate …”, “note …”, “search storage …”, or “create python project named demo”.",
        )
    }
}
