package com.kyrus.assistant.runtime.agent

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.MediaStore
import com.google.gson.Gson
import com.kyrus.assistant.runtime.ErrorCodes
import com.kyrus.assistant.runtime.KyrusError
import com.kyrus.assistant.runtime.RuntimePaths
import com.kyrus.assistant.runtime.security.NetworkDlp
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ToolRegistry(
    private val context: Context,
    private val askPermission: suspend (PermissionRequest) -> Boolean,
    private val dlpConfirm: suspend (String) -> Boolean = { false },
) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
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
                "fetch_url" -> fetchUrl(args["url"]?.toString().orEmpty())
                "fetch_image" -> fetchImage(args["url"]?.toString().orEmpty())
                "summarize" -> summarize(args["text"]?.toString().orEmpty())
                "remember" -> remember(args["fact"]?.toString().orEmpty())
                "recall" -> recall(args["query"]?.toString().orEmpty())
                "edit_file" -> editFile(args)
                "search_code", "search_files" -> searchCode(args["query"]?.toString().orEmpty())
                "file_info" -> fileInfo(args["path"]?.toString().orEmpty())
                "copy_move" -> copyMove(args)
                "archive" -> archive(args)
                "unarchive" -> unarchive(args)
                "search_storage" -> searchStorage(args["query"]?.toString().orEmpty())
                "pkg_info" -> pkgInfo(args["package"]?.toString().orEmpty())
                "clipboard_get" -> clipboardGet()
                else -> err(ErrorCodes.TOOL_UNKNOWN, "Unknown tool: $name")
            }
        } catch (e: Exception) {
            err(ErrorCodes.TOOL_FAIL, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun ok(data: Map<String, Any?>): String =
        gson.toJson(data + mapOf("ok" to true))

    private fun err(code: String, msg: String): String =
        gson.toJson(mapOf("ok" to false, "code" to code, "error" to msg, "message" to msg))

    private suspend fun gateNetwork(detail: String, payload: String): Boolean {
        if (!askPermission(PermissionRequest(PermissionRequest.Kind.Network, detail))) {
            return false
        }
        when (val v = NetworkDlp.scanOutbound("network", payload, NetworkDlp.localRoots(listOf(RuntimePaths.root)))) {
            is NetworkDlp.DlpVerdict.Allow -> return true
            is NetworkDlp.DlpVerdict.Block -> return false
            is NetworkDlp.DlpVerdict.NeedsConfirm -> {
                return dlpConfirm(v.error.message) ||
                    askPermission(PermissionRequest(PermissionRequest.Kind.DlpConfirm, v.error.message))
            }
        }
    }

    private fun calc(expr: String): String {
        val cleaned = expr.replace("x", "*", ignoreCase = true).replace("^", "**").trim()
        if (cleaned.isEmpty()) return err(ErrorCodes.TOOL_FAIL, "Missing expression")
        if (!cleaned.matches(Regex("^[0-9.\\s+\\-*/()]+$"))) return err(ErrorCodes.TOOL_FAIL, "Invalid math")
        return try {
            val v = evalExpr(cleaned)
            ok(mapOf("expression" to cleaned, "result" to v, "message" to "$cleaned = **$v**"))
        } catch (e: Exception) {
            err(ErrorCodes.TOOL_FAIL, "Invalid math: ${e.message}")
        }
    }

    private fun evalExpr(s: String): Double {
        data class P(var i: Int)
        val p = P(0)
        fun peek(): Char? = s.getOrNull(p.i)
        fun eat() { p.i++ }
        fun parseExpr(): Double {
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

    private fun datetime(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
        val now = fmt.format(Date())
        return ok(mapOf("local" to now, "message" to now))
    }

    private fun deviceInfo(): String {
        val free = RuntimePaths.root.usableSpace
        val model = RuntimePaths.modelFile
        val modelOk = model.exists() && model.length() > 1_000_000
        return ok(
            mapOf(
                "tier" to "android-app",
                "workspace" to RuntimePaths.workspace.absolutePath,
                "model" to model.absolutePath,
                "model_ready" to modelOk,
                "model_mb" to if (modelOk) model.length() / (1024 * 1024) else 0,
                "free_disk_mb" to (free / (1024 * 1024)),
                "abi" to android.os.Build.SUPPORTED_ABIS.toList(),
                "message" to "Kyrus Android · sandbox ready · GGUF=${if (modelOk) "on disk" else "missing"}",
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
                return ok(mapOf("count" to items.size, "notes" to items, "message" to "${items.size} note(s)"))
            }
            "add", "save", "write" -> {
                if (text.isBlank()) return err(ErrorCodes.TOOL_FAIL, "Empty note")
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val f = File(dir, "note_$stamp.txt")
                f.writeText(text + "\n")
                return ok(mapOf("saved" to f.name, "message" to "Saved note → ${f.name}"))
            }
            else -> return err(ErrorCodes.TOOL_FAIL, "Unknown notes action")
        }
    }

    private fun workspaceInfo(): String {
        val root = RuntimePaths.workspace
        var files = 0
        var dirs = 0
        root.walkTopDown().maxDepth(6).forEach {
            if (it.isFile) files++ else if (it.isDirectory) dirs++
        }
        return ok(
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
            error(KyrusError.tool(ErrorCodes.TOOL_PATH_ESCAPE, "Path escape blocked: $raw").toUserString())
        }
        return candidate
    }

    private fun projectInit(args: Map<String, Any?>): String {
        val name = (args["name"]?.toString() ?: "app").replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        val kind = (args["kind"]?.toString() ?: "python").lowercase()
        val root = resolveSafe(name)
        if (root.exists() && root.list()?.isNotEmpty() == true) return err(ErrorCodes.TOOL_FAIL, "Project exists: $name")
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
        return ok(mapOf("project" to root.absolutePath, "message" to "Initialized $kind project at $name"))
    }

    private fun mkdir(path: String): String {
        val p = resolveSafe(path)
        p.mkdirs()
        return ok(mapOf("path" to p.absolutePath, "message" to "Created ${p.name}"))
    }

    private fun writeFile(args: Map<String, Any?>): String {
        val path = resolveSafe(args["path"]?.toString().orEmpty())
        val content = args["content"]?.toString().orEmpty()
        path.parentFile?.mkdirs()
        path.writeText(content)
        return ok(mapOf("path" to path.absolutePath, "bytes" to content.toByteArray().size, "message" to "Wrote ${path.name}"))
    }

    private fun readFile(args: Map<String, Any?>): String {
        val path = resolveSafe(args["path"]?.toString().orEmpty())
        if (!path.isFile) return err(ErrorCodes.STORAGE_NOT_FOUND, "Not found")
        if (path.length() > 512_000) return err(ErrorCodes.TOOL_FAIL, "File too large")
        val text = path.readText()
        return ok(mapOf("path" to path.absolutePath, "text" to text.take(20_000), "message" to "Read ${path.name}"))
    }

    private fun listDir(path: String): String {
        val p = resolveSafe(path)
        if (!p.isDirectory) return err(ErrorCodes.TOOL_FAIL, "Not a directory")
        val entries = p.listFiles()?.sortedBy { it.name }?.take(100)?.map {
            mapOf("name" to it.name, "type" to if (it.isDirectory) "dir" else "file", "size" to if (it.isFile) it.length() else 0)
        }.orEmpty()
        return ok(mapOf("path" to p.absolutePath, "entries" to entries))
    }

    private fun fileInfo(path: String): String {
        val p = resolveSafe(path)
        if (!p.exists()) return err(ErrorCodes.STORAGE_NOT_FOUND, "Not found")
        return ok(
            mapOf(
                "path" to p.absolutePath,
                "name" to p.name,
                "is_dir" to p.isDirectory,
                "size" to if (p.isFile) p.length() else 0,
                "modified" to p.lastModified(),
                "message" to "${p.name}: ${if (p.isDirectory) "directory" else "${p.length()} bytes"}",
            ),
        )
    }

    private fun copyMove(args: Map<String, Any?>): String {
        val src = resolveSafe(args["src"]?.toString().orEmpty())
        val dst = resolveSafe(args["dst"]?.toString().orEmpty())
        val move = args["move"]?.toString()?.toBoolean() == true
        if (!src.exists()) return err(ErrorCodes.STORAGE_NOT_FOUND, "Source missing")
        dst.parentFile?.mkdirs()
        if (move) {
            if (!src.renameTo(dst)) {
                src.copyTo(dst, overwrite = true)
                src.delete()
            }
        } else {
            if (src.isDirectory) return err(ErrorCodes.TOOL_FAIL, "Directory copy not supported yet")
            src.copyTo(dst, overwrite = true)
        }
        return ok(mapOf("dst" to dst.absolutePath, "message" to "${if (move) "Moved" else "Copied"} → ${dst.name}"))
    }

    private fun archive(args: Map<String, Any?>): String {
        val src = resolveSafe(args["path"]?.toString().orEmpty())
        val outName = args["out"]?.toString() ?: (src.name + ".zip")
        val out = resolveSafe(outName)
        if (!src.exists()) return err(ErrorCodes.STORAGE_NOT_FOUND, "Not found")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
            if (src.isFile) {
                zos.putNextEntry(ZipEntry(src.name))
                FileInputStream(src).use { it.copyTo(zos) }
                zos.closeEntry()
            } else {
                src.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(src).path.replace('\\', '/')
                    zos.putNextEntry(ZipEntry(rel))
                    FileInputStream(f).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        return ok(mapOf("archive" to out.absolutePath, "message" to "Archived → ${out.name}"))
    }

    private fun unarchive(args: Map<String, Any?>): String {
        val src = resolveSafe(args["path"]?.toString().orEmpty())
        val destRel = args["dest"]?.toString() ?: src.nameWithoutExtension
        val dest = resolveSafe(destRel)
        if (!src.isFile) return err(ErrorCodes.STORAGE_NOT_FOUND, "Archive not found")
        dest.mkdirs()
        ZipInputStream(BufferedInputStream(FileInputStream(src))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(dest, entry.name).canonicalFile
                if (!out.path.startsWith(dest.canonicalFile.path)) {
                    return err(ErrorCodes.TOOL_PATH_ESCAPE, "Zip slip blocked")
                }
                if (entry.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return ok(mapOf("dest" to dest.absolutePath, "message" to "Extracted → ${dest.name}"))
    }

    private suspend fun webResearch(query: String): String {
        if (query.isBlank()) return err(ErrorCodes.TOOL_FAIL, "Missing query")
        if (!gateNetwork("Search the web for: $query", query)) {
            return err(ErrorCodes.NET_DENIED, "Internet permission denied")
        }
        // Primary: DuckDuckGo lite HTML; structured fallback message on empty
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$q"
        val req = Request.Builder().url(url).header("User-Agent", "KyrusAssistant-Android/0.4").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return err(ErrorCodes.NET_HTTP, "Search HTTP ${resp.code}")
            val html = resp.body?.string().orEmpty()
            val doc = Jsoup.parse(html)
            val results = doc.select(".result").take(5).mapNotNull { el ->
                val a = el.selectFirst("a.result__a") ?: return@mapNotNull null
                val sn = el.selectFirst(".result__snippet")?.text().orEmpty()
                mapOf("title" to a.text(), "url" to a.attr("href"), "snippet" to sn)
            }
            if (results.isEmpty()) {
                // Fallback: try Instant Answer API
                val ia = "https://api.duckduckgo.com/?q=$q&format=json&no_html=1&skip_disambig=1"
                val iaReq = Request.Builder().url(ia).header("User-Agent", "KyrusAssistant-Android/0.4").build()
                http.newCall(iaReq).execute().use { iaResp ->
                    val body = iaResp.body?.string().orEmpty()
                    val abs = try {
                        com.google.gson.JsonParser.parseString(body).asJsonObject.get("AbstractText")?.asString.orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                    if (abs.isNotBlank()) {
                        return ok(mapOf("query" to query, "answer" to abs, "summary" to abs, "sources" to emptyList<Any>()))
                    }
                }
                return err(ErrorCodes.NET_HTTP, "No results")
            }
            val answer = buildString {
                append("Based on live web results for “$query”:\n")
                results.take(3).forEach { append("- ${it["title"]}. ${it["snippet"]}\n") }
            }
            return ok(mapOf("query" to query, "answer" to answer, "summary" to answer, "sources" to results))
        }
    }

    private fun summarize(text: String): String {
        if (text.isBlank()) return err(ErrorCodes.TOOL_FAIL, "Missing text")
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.length > 20 }
        val pick = sentences.take(4).ifEmpty { listOf(text.take(400)) }
        val summary = pick.joinToString(" ")
        return ok(mapOf("summary" to summary, "message" to summary))
    }

    private suspend fun fetchUrl(url: String): String {
        if (url.isBlank()) return err(ErrorCodes.TOOL_FAIL, "Missing url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) return err(ErrorCodes.TOOL_FAIL, "Only http/https")
        if (!gateNetwork("Fetch URL: $url", url)) return err(ErrorCodes.NET_DENIED, "Internet permission denied")
        val req = Request.Builder().url(url).header("User-Agent", "KyrusAssistant-Android/0.4").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return err(ErrorCodes.NET_HTTP, "HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val text = if (body.contains("<html", ignoreCase = true)) {
                Jsoup.parse(body).text()
            } else body
            val clipped = text.replace(Regex("\\s+"), " ").take(12_000)
            return ok(
                mapOf(
                    "url" to url,
                    "text" to clipped,
                    "message" to clipped.take(1500) + if (clipped.length > 1500) "…" else "",
                ),
            )
        }
    }

    private suspend fun fetchImage(url: String): String {
        if (url.isBlank()) return err(ErrorCodes.TOOL_FAIL, "Missing url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) return err(ErrorCodes.TOOL_FAIL, "Only http/https")
        if (!gateNetwork("Fetch image: $url", url)) return err(ErrorCodes.NET_DENIED, "Internet permission denied")
        val req = Request.Builder().url(url).header("User-Agent", "KyrusAssistant-Android/0.4").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return err(ErrorCodes.NET_HTTP, "HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: return err(ErrorCodes.NET_HTTP, "Empty image")
            if (bytes.size > 12_000_000) return err(ErrorCodes.TOOL_FAIL, "Image too large")
            val ext = when {
                url.contains(".png", true) -> "png"
                url.contains(".webp", true) -> "webp"
                url.contains(".gif", true) -> "gif"
                else -> "jpg"
            }
            val dest = File(RuntimePaths.images, "img_${UUID.randomUUID()}.$ext")
            dest.writeBytes(bytes)
            return ok(
                mapOf(
                    "url" to url,
                    "path" to dest.absolutePath,
                    "bytes" to bytes.size,
                    "message" to "Image saved",
                    "image_path" to dest.absolutePath,
                ),
            )
        }
    }

    private fun remember(fact: String): String {
        if (fact.isBlank()) return err(ErrorCodes.TOOL_FAIL, "Nothing to remember")
        val file = RuntimePaths.memory
        val existing = if (file.exists()) {
            try {
                JsonParserSafe.stringList(file.readText()).toMutableList()
            } catch (_: Exception) {
                mutableListOf()
            }
        } else mutableListOf()
        if (fact !in existing) existing.add(fact)
        val trimmed = existing.takeLast(200)
        file.writeText(gson.toJson(mapOf("facts" to trimmed)))
        return ok(mapOf("message" to "Remembered: $fact"))
    }

    private fun recall(query: String): String {
        val file = RuntimePaths.memory
        if (!file.exists()) return ok(mapOf("facts" to emptyList<String>(), "message" to "Nothing stored yet."))
        val facts = try {
            JsonParserSafe.stringList(file.readText())
        } catch (_: Exception) {
            emptyList()
        }
        val q = query.lowercase()
        val hits = if (q.isBlank()) facts else facts.filter { it.lowercase().contains(q) }
        return ok(
            mapOf(
                "facts" to hits,
                "message" to (hits.joinToString("\n") { "- $it" }.ifBlank { "Nothing stored yet." }),
            ),
        )
    }

    private fun editFile(args: Map<String, Any?>): String {
        val path = resolveSafe(args["path"]?.toString().orEmpty())
        val old = args["old_string"]?.toString().orEmpty()
        val neu = args["new_string"]?.toString().orEmpty()
        val all = args["replace_all"]?.toString()?.toBoolean() == true
        if (old.isEmpty()) return err(ErrorCodes.TOOL_FAIL, "old_string required")
        if (!path.isFile) return err(ErrorCodes.STORAGE_NOT_FOUND, "Not found")
        val text = path.readText()
        val count = text.split(old).size - 1
        if (count == 0) return err(ErrorCodes.TOOL_FAIL, "old_string not found")
        if (count > 1 && !all) return err(ErrorCodes.TOOL_FAIL, "old_string found $count times — set replace_all")
        path.writeText(if (all) text.replace(old, neu) else text.replaceFirst(old, neu))
        return ok(mapOf("path" to path.absolutePath, "message" to "Edited ${path.name}"))
    }

    private fun searchCode(query: String): String {
        if (query.isBlank()) return err(ErrorCodes.TOOL_FAIL, "Missing query")
        val root = RuntimePaths.workspace
        val hits = mutableListOf<Map<String, Any>>()
        val exts = setOf("py", "js", "ts", "kt", "java", "md", "json", "html", "css", "sh", "txt")
        root.walkTopDown().maxDepth(8).forEach { f ->
            if (!f.isFile || f.extension.lowercase() !in exts) return@forEach
            if (f.length() > 400_000) return@forEach
            f.readLines().forEachIndexed { i, line ->
                if (line.contains(query, ignoreCase = true)) {
                    hits.add(mapOf("file" to f.relativeTo(root).path, "line" to (i + 1), "text" to line.trim().take(200)))
                    if (hits.size >= 40) return ok(mapOf("hits" to hits, "message" to "${hits.size}+ matches"))
                }
            }
        }
        return ok(mapOf("hits" to hits, "message" to "${hits.size} match(es)"))
    }

    private suspend fun searchStorage(query: String): String {
        if (query.isBlank()) return err(ErrorCodes.TOOL_FAIL, "Missing query")
        if (!askPermission(PermissionRequest(PermissionRequest.Kind.ReadStorage, "Search device media for: $query"))) {
            return err(ErrorCodes.PERM_DENIED, "Storage permission denied")
        }
        val hits = mutableListOf<Map<String, Any?>>()
        try {
            val uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
            )
            val sel = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            context.contentResolver.query(uri, projection, sel, arrayOf("%$query%"), null)?.use { c ->
                val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val dataIdx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                val sizeIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val mimeIdx = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                while (c.moveToNext() && hits.size < 30) {
                    hits.add(
                        mapOf(
                            "name" to c.getString(nameIdx),
                            "path" to c.getString(dataIdx),
                            "size" to c.getLong(sizeIdx),
                            "mime" to c.getString(mimeIdx),
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            return err(ErrorCodes.STORAGE_NO_GRANT, "MediaStore query failed: ${e.message}")
        }
        // Also scan Downloads if readable
        try {
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (dl?.exists() == true) {
                dl.walkTopDown().maxDepth(3).forEach { f ->
                    if (f.isFile && f.name.contains(query, true) && hits.size < 40) {
                        hits.add(mapOf("name" to f.name, "path" to f.absolutePath, "size" to f.length(), "mime" to null))
                    }
                }
            }
        } catch (_: Exception) {
        }
        return ok(mapOf("hits" to hits, "message" to "${hits.size} storage hit(s)"))
    }

    private fun pkgInfo(pkg: String): String {
        val pm = context.packageManager
        return try {
            if (pkg.isBlank()) {
                val launch = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                val apps = pm.queryIntentActivities(launch, PackageManager.MATCH_ALL)
                    .take(40)
                    .map {
                        mapOf(
                            "package" to it.activityInfo.packageName,
                            "label" to it.loadLabel(pm).toString(),
                        )
                    }
                ok(mapOf("apps" to apps, "message" to "${apps.size} launchable apps (sample)"))
            } else {
                val info = pm.getPackageInfo(pkg, 0)
                ok(
                    mapOf(
                        "package" to info.packageName,
                        "version" to info.versionName,
                        "message" to "$pkg · ${info.versionName}",
                    ),
                )
            }
        } catch (e: Exception) {
            err(ErrorCodes.TOOL_FAIL, "Package lookup failed: ${e.message}")
        }
    }

    private fun clipboardGet(): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        return ok(mapOf("text" to text.take(8000), "message" to if (text.isBlank()) "Clipboard empty" else text.take(200)))
    }
}

private object JsonParserSafe {
    fun stringList(raw: String): List<String> {
        val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
        val arr = obj.getAsJsonArray("facts") ?: return emptyList()
        return arr.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
    }
}
