package com.wani.terminal

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Ek workflow run ki halat. */
data class RunInfo(val status: String, val conclusion: String?, val url: String, val createdAt: String)

/**
 * GitHub REST API (blocking calls, IO thread par chalayein).
 * Token sirf user ka diya hua hota hai, aur sirf GitHub ko bheja jata hai.
 */
object GitHubApi {

    private const val BASE = "https://api.github.com"

    /** Repo ka naam: owner/repo. */
    val REPO_PATTERN = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
    /** Workflow file ka naam, jaise build.yml. */
    val WORKFLOW_PATTERN = Regex("[A-Za-z0-9_.-]+")
    /** Branch ka naam. */
    val BRANCH_PATTERN = Regex("[A-Za-z0-9._/-]+")

    private fun call(method: String, path: String, token: String, body: JSONObject? = null): Pair<Int, String> {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "WaniTerminal")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(text: String): String =
        runCatching { JSONObject(text).optString("message") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: text.take(200)

    /** Token check karta hai aur GitHub username return karta hai. */
    fun login(token: String): String {
        val (code, text) = call("GET", "/user", token)
        check(code == 200) { "Token check failed (HTTP $code): ${errorMessage(text)}" }
        return JSONObject(text).getString("login")
    }

    /** workflow_dispatch se build shuru karta hai. */
    fun triggerBuild(token: String, repo: String, workflow: String, branch: String) {
        val body = JSONObject().put("ref", branch)
        val (code, text) = call("POST", "/repos/$repo/actions/workflows/$workflow/dispatches", token, body)
        check(code == 204) { "Build not started (HTTP $code): ${errorMessage(text)}" }
    }

    /** Workflow ka sab se naya run. */
    fun latestRun(token: String, repo: String, workflow: String): RunInfo? {
        val (code, text) = call("GET", "/repos/$repo/actions/workflows/$workflow/runs?per_page=1", token)
        check(code == 200) { "Could not read runs (HTTP $code): ${errorMessage(text)}" }
        val runs = JSONObject(text).optJSONArray("workflow_runs") ?: return null
        if (runs.length() == 0) return null
        val r = runs.getJSONObject(0)
        return RunInfo(
            status = r.optString("status"),
            conclusion = if (r.isNull("conclusion")) null else r.optString("conclusion"),
            url = r.optString("html_url"),
            createdAt = r.optString("created_at")
        )
    }
}
