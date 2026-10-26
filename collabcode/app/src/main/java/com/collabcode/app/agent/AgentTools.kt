package com.collabcode.app.agent

import android.util.Base64
import com.collabcode.app.data.Http
import com.collabcode.app.data.HttpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object AgentToolDefs {
    val ALL: JSONArray by lazy { build() }

    private fun build(): JSONArray {
        val arr = JSONArray()
        arr.put(fn("list_files", "List files in a repository folder.", "path" to "Folder path, empty for the root"))
        arr.put(fn("read_file", "Read a text file from the repository.", "path" to "File path"))
        arr.put(
            fn(
                "write_file",
                "Create or update a file and commit it to the working branch.",
                "path" to "File path",
                "content" to "Full new file content",
                "message" to "Commit message",
            )
        )
        arr.put(fn("start_build", "Start the build workflow on the working branch."))
        arr.put(fn("latest_run", "Show the latest build run and its status."))
        arr.put(
            fn(
                "http_request",
                "Call any web API and return status and body.",
                "method" to "GET, POST, PUT, PATCH or DELETE",
                "url" to "Full https URL",
                "headers" to "(optional) JSON object of headers",
                "body" to "(optional) request body",
            )
        )
        return arr
    }

    private fun fn(name: String, desc: String, vararg props: Pair<String, String>): JSONObject {
        val p = JSONObject()
        val req = JSONArray()
        for ((k, v) in props) {
            p.put(k, JSONObject().put("type", "string").put("description", v))
            if (!v.startsWith("(optional)")) req.put(k)
        }
        val params = JSONObject().put("type", "object").put("properties", p).put("required", req)
        return JSONObject()
            .put("type", "function")
            .put("function", JSONObject().put("name", name).put("description", desc).put("parameters", params))
    }
}

/** Runs the tools. Network calls run on the IO thread. Card updates stay on the caller's thread. */
class AgentTools(private val cfg: AgentConfig) {
    private val host = "api.github.com"

    suspend fun run(name: String, a: JSONObject, card: ToolCard, ask: suspend (ToolCard, Boolean) -> Boolean): String =
        when (name) {
            "list_files" -> listFiles(a.optString("path"))
            "read_file" -> readFile(a.optString("path"))
            "write_file" -> writeFile(a, card, ask)
            "start_build" -> startBuild(card, ask)
            "latest_run" -> latestRun()
            "http_request" -> httpRequest(a, card, ask)
            else -> "Unknown tool: $name"
        }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun encPath(p: String): String = p.split("/").joinToString("/") { enc(it) }

    private fun repo(): String {
        val r = cfg.repo.trim()
        if (!Regex("^[\\w.-]+/[\\w.-]+$").matches(r)) {
            throw IOException("Set the repository as owner/name in the sidebar.")
        }
        return r
    }

    private fun gh(method: String, path: String, body: String? = null): HttpResult {
        if (cfg.gt.isBlank()) throw IOException("Add your GitHub token in Settings.")
        return Http.call(
            method,
            "https://$host$path",
            mapOf(
                "Authorization" to "Bearer ${cfg.gt}",
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to "2022-11-28",
            ),
            body,
            host,
        )
    }

    private fun ghError(r: HttpResult): String =
        "GitHub ${r.code}: " + runCatching { JSONObject(r.body).optString("message") }.getOrDefault("")

    private fun decode(b64: String): String =
        String(Base64.decode(b64.replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)

    private suspend fun listFiles(p: String): String {
        val r = io { gh("GET", "/repos/${repo()}/contents/${encPath(p)}?ref=${enc(cfg.br)}") }
        if (r.code == 404) return "Not found."
        if (r.code !in 200..299) throw IOException(ghError(r))
        if (!r.body.trimStart().startsWith("[")) return "Not a folder."
        val arr = JSONArray(r.body)
        return (0 until arr.length()).joinToString("\n") { i ->
            val f = arr.getJSONObject(i)
            val path = f.optString("path")
            if (f.optString("type") == "dir") "$path/" else path
        }
    }

    private suspend fun readFile(p: String): String {
        val r = io { gh("GET", "/repos/${repo()}/contents/${encPath(p)}?ref=${enc(cfg.br)}") }
        if (r.code == 404) return "File not found or not a text file."
        if (r.code !in 200..299) throw IOException(ghError(r))
        if (r.body.trimStart().startsWith("[")) return "File not found or not a text file."
        val content = JSONObject(r.body).optString("content")
        if (content.isEmpty()) return "File not found or not a text file."
        return decode(content).take(14000)
    }

    private suspend fun writeFile(a: JSONObject, card: ToolCard, ask: suspend (ToolCard, Boolean) -> Boolean): String {
        val path = a.optString("path").removePrefix("/")
        if (path.isEmpty() || !a.has("content")) throw IOException("path and content are required")
        val content = a.optString("content")
        val msg = a.optString("message").ifEmpty { "Update $path" }
        card.out = "Commit to ${cfg.br}: $msg\n\n" + content.take(3000)
        if (!ask(card, cfg.askW)) return "The user denied this commit. Ask what they want instead."
        val sha = io {
            val old = gh("GET", "/repos/${repo()}/contents/${encPath(path)}?ref=${enc(cfg.br)}")
            if (old.code == 200) JSONObject(old.body).optString("sha") else null
        }
        val body = JSONObject()
            .put("message", msg)
            .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            .put("branch", cfg.br)
        if (sha != null) body.put("sha", sha)
        val r = io { gh("PUT", "/repos/${repo()}/contents/${encPath(path)}", body.toString()) }
        if (r.code !in 200..299) throw IOException(ghError(r))
        val commit = JSONObject(r.body).optJSONObject("commit")?.optString("sha").orEmpty()
        card.out = "Committed $path (${commit.take(7)})"
        return card.out
    }

    private suspend fun startBuild(card: ToolCard, ask: suspend (ToolCard, Boolean) -> Boolean): String {
        card.out = "Run ${cfg.wf} on ${cfg.br}"
        if (!ask(card, cfg.askB)) return "The user denied the build."
        val body = JSONObject().put("ref", cfg.br).toString()
        val r = io { gh("POST", "/repos/${repo()}/actions/workflows/${enc(cfg.wf)}/dispatches", body) }
        if (r.code !in 200..299) throw IOException(ghError(r))
        card.out = "Build started. Check it with latest_run in a minute."
        return card.out
    }

    private suspend fun latestRun(): String {
        val r = io { gh("GET", "/repos/${repo()}/actions/runs?per_page=1&branch=${enc(cfg.br)}") }
        if (r.code !in 200..299) throw IOException(ghError(r))
        val run = JSONObject(r.body).optJSONArray("workflow_runs")?.optJSONObject(0) ?: return "No runs yet."
        val concl = if (run.isNull("conclusion")) "" else run.optString("conclusion")
        val status = if (concl.isEmpty()) run.optString("status") else concl
        return "${run.optString("name")}: $status\n${run.optString("html_url")}\n(APK is under Artifacts on that page)"
    }

    private suspend fun httpRequest(a: JSONObject, card: ToolCard, ask: suspend (ToolCard, Boolean) -> Boolean): String {
        val m = a.optString("method").ifEmpty { "GET" }.uppercase()
        val url = a.optString("url")
        val body = a.optString("body")
        card.out = m + " " + url + (if (body.isNotEmpty()) "\n\n" + body.take(1500) else "")
        if (m != "GET" && !ask(card, cfg.askH)) return "The user denied this API call."
        if (!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("Give a full http or https URL")
        val headers = a.optString("headers")
        val h = if (headers.isBlank()) JSONObject() else JSONObject(headers)
        val out = io {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                // HttpURLConnection has no PATCH, so PATCH goes as POST with an override header.
                val wire = if (m == "PATCH") "POST" else m
                c.requestMethod = wire
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                if (m == "PATCH") c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                val keys = h.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    c.setRequestProperty(k, h.optString(k))
                }
                if (body.isNotEmpty()) {
                    c.doOutput = true
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val stream = try {
                    if (code in 200..299) c.inputStream else c.errorStream
                } catch (e: IOException) {
                    null
                }
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                "HTTP $code\n" + text.take(6000)
            } finally {
                c.disconnect()
            }
        }
        card.out = out
        return out
    }
}
