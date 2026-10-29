package com.kyrus.assistant.runtime.agent

import com.kyrus.assistant.runtime.RuntimePaths
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class ToolRegistry(
    private val askNetwork: suspend (String) -> Boolean,
) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun run(name: String, args: Map<String, Any?>): String {
        return try {
            when (name) {
                "calculator" -> calc(args["expression"]?.toString().orEmpty())
                "datetime" -> datetime()
                "device_info" -> deviceInfo()
                "notes" -> notes(args)
                "workspace_info" -> workspaceInfo()
                "project_init" -> projectInit(args)
                "mkdir" -> mkdir(args["path"]?.toString().orEmpty())
                "write_file" -> writeFile(args)
                "read_file" -> readFile(args)
                "list_dir" -> listDir(args["path"]?.toString() ?: ".")
                "web_research" -> webResearch(args["query"]?.toString().orEmpty())
                "web_news" -> webResearch("news " + args["query"]?.toString().orEmpty())
                "summarize" -> summarize(args["text"]?.toString().orEmpty())
                else -> gson.toJson(mapOf("error" to "Unknown tool: $name"))
            }
        } catch (e: Exception) {
            gson.toJson(mapOf("error" to "${e.javaClass.simpleName}: ${e.message}"))
        }
    }

    private fun calc(expr: String): String {
        val cleaned = expr.replace("x", "*", ignoreCase = true).replace("^", "**").trim()
        if (cleaned.isEmpty()) return err("Missing expression")
        // Tiny safe eval: numbers and + - * / ( ) only
        if (!cleaned.matches(Regex("^[0-9.\\s+\\-*/()]+$"))) return err("Invalid math")
        return try {
            val v = evalExpr(cleaned)
            gson.toJson(mapOf("expression" to cleaned, "result" to v, "message" to "$cleaned = **$v**"))
        } catch (e: Exception) {
            err("Invalid math: ${e.message}")
        }
    }

    private fun evalExpr(s: String): Double {
        data class P(var i: Int)
        val p = P(0)
        fun peek(): Char? = s.getOrNull(p.i)
        fun eat() { p.i++ }
        fun parse(): Double {
            fun parseFactor(): Double {
                while (peek() == ' ') eat()
                if (peek() == '+') { eat(); return parseFactor() }
                if (peek() == '-') { eat(); return -parseFactor() }
                if (peek() == '(') {
                    eat()
                    val v = parseExpr()
                    if (peek() == ')') eat()
                    return v
                }
                val start = p.i
                while (peek()?.isDigit() == true || peek() == '.') eat()
                return s.substring(start, p.i).toDouble()
            }
            fun parseTerm(): Double {
                var v = parseFactor()
                while (true) {
                    while (peek() == ' ') eat()
                    when (peek()) {
                        '*' -> { eat(); v *= parseFactor() }
                        '/' -> { eat(); v /= parseFactor() }
                        else -> return v
                    }
                }
            }
            fun parseExpr(): Double {
                var v = parseTerm()
                while (true) {
                    while (peek() == ' ') eat()
                    when (peek()) {
                        '+' -> { eat(); v += parseTerm() }
                        '-' -> { eat(); v -= parseTerm() }
                        else -> return v
                    }
                }
            }
            return parseExpr()
        }
        return parse()
    }

    private fun datetime(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
        val now = fmt.format(Date())
        return gson.toJson(mapOf("local" to now, "message" to now))
    }

    private fun deviceInfo(): String {
        val free = RuntimePaths.root.usableSpace
        return gson.toJson(
            mapOf(
                "tier" to "android-app",
                "workspace" to RuntimePaths.workspace.absolutePath,
                "model" to RuntimePaths.modelFile.absolutePath,
                "model_ready" to (RuntimePaths.modelFile.exists() && RuntimePaths.modelFile.length() > 1_000_000),
                "free_disk_mb" to (free / (1024 * 1024)),
                "message" to "Kyrus Android runtime · workspace ready · model=${RuntimePaths.modelFile.exists()}",
            ),
        )
    }

    private fun notes(args: Map<String, Any?>): String {
        val action = args["action"]?.toString()?.lowercase() ?: "add"
        val text = args["text"]?.toString().orEmpty()
        val dir = RuntimePaths.notes
        when (action) {
            "list" -> {
                val files = dir.listFiles()?.sortedByDescending { it.lastModified() }?.take(20).orEmpty()
                val items = files.map {
                    mapOf("file" to it.name, "preview" to it.readText().take(120).replace("\n", " "))
                }
                return gson.toJson(mapOf("count" to items.size, "notes" to items, "message" to "${items.size} note(s)"))
            }
            "add", "save", "write" -> {
                if (text.isBlank()) return err("Empty note")
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val f = File(dir, "note_$stamp.txt")
                f.writeText(text + "\n")
                return gson.toJson(mapOf("saved" to f.name, "message" to "Saved note → ${f.name}"))
            }
            else -> return err("Unknown notes action")
        }
    }

    private fun workspaceInfo(): String {
        val root = RuntimePaths.workspace
        var files = 0
        var dirs = 0
        root.walkTopDown().maxDepth(6).forEach {
            if (it.isFile) files++ else if (it.isDirectory) dirs++
        }
        return gson.toJson(
            mapOf(
                "workspace" to root.absolutePath,
                "files_seen" to files,
                "dirs_seen" to dirs,
                "message" to "Workspace: ${root.absolutePath} ($files files)",
            ),
        )
    }

    private fun resolveSafe(raw: String): File {
        val root = RuntimePaths.workspace.canonicalFile
        val candidate = if (raw.isBlank() || raw == ".") root
        else File(root, raw).canonicalFile
        if (!candidate.path.startsWith(root.path)) {
            error("Path escape blocked: $raw")
        }
        return candidate
    }

    private fun projectInit(args: Map<String, Any?>): String {
        val name = (args["name"]?.toString() ?: "app").replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        val kind = (args["kind"]?.toString() ?: "python").lowercase()
        val root = resolveSafe(name)
        if (root.exists() && root.list()?.isNotEmpty() == true) return err("Project exists: $name")
        root.mkdirs()
        when (kind) {
            "python", "py" -> {
                File(root, "README.md").writeText("# $name\n\nScaffolded by Kyrus.\n")
                File(root, "main.py").writeText("def main():\n    print(\"hello from Kyrus\")\n\n\nif __name__ == \"__main__\":\n    main()\n")
                File(root, "requirements.txt").writeText("# add deps here\n")
            }
            "web", "html" -> {
                File(root, "index.html").writeText("<!doctype html><html><head><meta charset=utf-8><title>$name</title></head><body><h1>$name</h1></body></html>\n")
                File(root, "style.css").writeText("body{font-family:system-ui;margin:2rem;}\n")
            }
            else -> File(root, "README.md").writeText("# $name\n")
        }
        return gson.toJson(mapOf("project" to root.absolutePath, "message" to "Initialized $kind project at $name"))
    }

    private fun mkdir(path: String): String {
        val p = resolveSafe(path)
        p.mkdirs()
        return gson.toJson(mapOf("path" to p.absolutePath, "message" to "Created ${p.name}"))
    }

    private fun writeFile(args: Map<String, Any?>): String {
        val path = resolveSafe(args["path"]?.toString().orEmpty())
        val content = args["content"]?.toString().orEmpty()
        path.parentFile?.mkdirs()
        path.writeText(content)
        return gson.toJson(mapOf("path" to path.absolutePath, "bytes" to content.toByteArray().size, "message" to "Wrote ${path.name}"))
    }

    private fun readFile(args: Map<String, Any?>): String {
        val path = resolveSafe(args["path"]?.toString().orEmpty())
        if (!path.isFile) return err("Not found")
        if (path.length() > 512_000) return err("File too large")
        val text = path.readText()
        return gson.toJson(mapOf("path" to path.absolutePath, "text" to text.take(20_000), "message" to "Read ${path.name}"))
    }

    private fun listDir(path: String): String {
        val p = resolveSafe(path)
        if (!p.isDirectory) return err("Not a directory")
        val entries = p.listFiles()?.sortedBy { it.name }?.take(100)?.map {
            mapOf("name" to it.name, "type" to if (it.isDirectory) "dir" else "file", "size" to if (it.isFile) it.length() else 0)
        }.orEmpty()
        return gson.toJson(mapOf("path" to p.absolutePath, "entries" to entries))
    }

    private suspend fun webResearch(query: String): String {
        if (query.isBlank()) return err("Missing query")
        if (!askNetwork("Search the web for: $query")) {
            return err("Internet permission denied")
        }
        // DuckDuckGo HTML lite
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$q"
        val req = Request.Builder().url(url).header("User-Agent", "KyrusAssistant-Android/0.2").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return err("Search HTTP ${resp.code}")
            val html = resp.body?.string().orEmpty()
            val doc = Jsoup.parse(html)
            val results = doc.select(".result").take(5).mapNotNull { el ->
                val a = el.selectFirst("a.result__a") ?: return@mapNotNull null
                val sn = el.selectFirst(".result__snippet")?.text().orEmpty()
                mapOf("title" to a.text(), "url" to a.attr("href"), "snippet" to sn)
            }
            if (results.isEmpty()) return err("No results")
            val answer = buildString {
                append("Based on live web results for “$query”:\n")
                results.take(3).forEach { append("- ${it["title"]}. ${it["snippet"]}\n") }
            }
            return gson.toJson(mapOf("query" to query, "answer" to answer, "summary" to answer, "sources" to results))
        }
    }

    private fun summarize(text: String): String {
        if (text.isBlank()) return err("Missing text")
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.length > 20 }
        val pick = sentences.take(4).ifEmpty { listOf(text.take(400)) }
        val summary = pick.joinToString(" ")
        return gson.toJson(mapOf("summary" to summary, "message" to summary))
    }

    private fun err(msg: String) = gson.toJson(mapOf("error" to msg))
}
