package com.collabcode.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Instant

const val REPO_NAME = "collabcode-app"
const val REPO_DESCRIPTION = "Created by CollabCode"
private const val API_HOST = "api.github.com"

private val LOG_TIMESTAMP = Regex("^\uFEFF?\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z ?")
private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")

enum class RepoState { CREATED, MINE, OTHER }

data class GhRun(
    val id: Long,
    val number: Int,
    val status: String,
    val conclusion: String?,
    val createdAt: Long,
    val url: String,
)

data class GhStep(val number: Int, val name: String, val status: String, val conclusion: String?)

data class GhJob(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val steps: List<GhStep>,
)

data class GhArtifact(val id: Long, val name: String, val sizeBytes: Long, val expired: Boolean)

class GitHub(private val token: String) {

    private suspend fun call(method: String, path: String, body: String? = null): HttpResult =
        withContext(Dispatchers.IO) {
            Http.call(
                method = method,
                url = "https://$API_HOST$path",
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "Accept" to "application/vnd.github+json",
                    "X-GitHub-Api-Version" to "2022-11-28",
                ),
                body = body,
                allowedHost = API_HOST,
            )
        }

    private fun fail(r: HttpResult): Nothing {
        val detail = runCatching { JSONObject(r.body).optString("message") }.getOrDefault("")
        val msg = when (r.code) {
            401 -> "GitHub token is wrong or expired. Sign out and enter a new token."
            403 ->
                if (detail.contains("rate limit", ignoreCase = true)) {
                    "GitHub rate limit reached. Wait a few minutes and try again."
                } else {
                    "GitHub denied access. Use a classic token with repo and workflow scopes."
                }
            404 -> "Not found on GitHub. $detail"
            422 -> "GitHub rejected the request. $detail"
            429 -> "GitHub rate limit reached. Wait a few minutes and try again."
            else -> "GitHub error ${r.code}. $detail"
        }
        throw ApiException(r.code, msg.trim())
    }

    private fun optStr(o: JSONObject, key: String): String? = if (o.isNull(key)) null else o.getString(key)

    suspend fun login(): String {
        val r = call("GET", "/user")
        if (r.code != 200) fail(r)
        return JSONObject(r.body).getString("login")
    }

    /**
     * Opens the project repo, or creates it (private, with an initial commit).
     * MINE means CollabCode created it earlier. OTHER means the name was already taken
     * by a repo CollabCode did not create, so the caller must ask the user first.
     */
    suspend fun ensureRepo(login: String): RepoState {
        val r = call("GET", "/repos/$login/$REPO_NAME")
        return when (r.code) {
            200 -> {
                val o = JSONObject(r.body)
                val desc = if (o.isNull("description")) "" else o.getString("description")
                if (desc == REPO_DESCRIPTION) RepoState.MINE else RepoState.OTHER
            }
            404 -> {
                val body = JSONObject()
                    .put("name", REPO_NAME)
                    .put("private", true)
                    .put("auto_init", true)
                    .put("description", REPO_DESCRIPTION)
                val c = call("POST", "/user/repos", body.toString())
                if (c.code != 200 && c.code != 201) fail(c)
                RepoState.CREATED
            }
            else -> fail(r)
        }
    }

    suspend fun defaultBranch(repo: String): String {
        val r = call("GET", "/repos/$repo")
        if (r.code != 200) fail(r)
        return JSONObject(r.body).getString("default_branch")
    }

    /**
     * Writes and deletes many files in one commit (Git Data API):
     * blobs, a tree on top of the current one, a commit, then the branch ref.
     * Paths in [deleted] must exist in the current tree.
     */
    suspend fun commitFiles(
        repo: String,
        branch: String,
        files: Map<String, String>,
        deleted: Set<String>,
        message: String,
    ) {
        val ref = call("GET", "/repos/$repo/git/ref/heads/$branch")
        if (ref.code != 200) fail(ref)
        val headSha = JSONObject(ref.body).getJSONObject("object").getString("sha")

        val headCommit = call("GET", "/repos/$repo/git/commits/$headSha")
        if (headCommit.code != 200) fail(headCommit)
        val baseTree = JSONObject(headCommit.body).getJSONObject("tree").getString("sha")

        val entries = JSONArray()
        for ((path, text) in files) {
            // An empty blob is sent as a single newline to keep the API happy.
            val content = if (text.isEmpty()) "\n" else text
            val blobBody = JSONObject().put("content", content).put("encoding", "utf-8").toString()
            val blob = call("POST", "/repos/$repo/git/blobs", blobBody)
            if (blob.code != 201) fail(blob)
            entries.put(
                JSONObject()
                    .put("path", path)
                    .put("mode", "100644")
                    .put("type", "blob")
                    .put("sha", JSONObject(blob.body).getString("sha"))
            )
        }
        for (path in deleted) {
            entries.put(
                JSONObject()
                    .put("path", path)
                    .put("mode", "100644")
                    .put("type", "blob")
                    .put("sha", JSONObject.NULL)
            )
        }

        val treeBody = JSONObject().put("base_tree", baseTree).put("tree", entries).toString()
        val tree = call("POST", "/repos/$repo/git/trees", treeBody)
        if (tree.code != 201) fail(tree)
        val newTree = JSONObject(tree.body).getString("sha")

        val commitBody = JSONObject()
            .put("message", message)
            .put("tree", newTree)
            .put("parents", JSONArray().put(headSha))
            .toString()
        val commit = call("POST", "/repos/$repo/git/commits", commitBody)
        if (commit.code != 201) fail(commit)
        val newSha = JSONObject(commit.body).getString("sha")

        val upd = call("PATCH", "/repos/$repo/git/refs/heads/$branch", JSONObject().put("sha", newSha).toString())
        if (upd.code != 200) fail(upd)
    }

    /**
     * Starts the build. GitHub answers 204 with no run ID, so this returns the dispatch time.
     * A 404 is retried for about 30 seconds while GitHub registers a new workflow file.
     */
    suspend fun dispatch(repo: String, branch: String): Long {
        val started = System.currentTimeMillis()
        val body = JSONObject().put("ref", branch).toString()
        val waits = longArrayOf(2_000, 3_000, 5_000, 5_000, 5_000, 5_000, 5_000)
        for (attempt in 0..waits.size) {
            val r = call("POST", "/repos/$repo/actions/workflows/build.yml/dispatches", body)
            if (r.code == 204) return started
            if (r.code != 404) fail(r)
            if (attempt < waits.size) delay(waits[attempt])
        }
        throw ApiException(404, "The build workflow is not ready on GitHub yet. Try again in a moment.")
    }

    /** First workflow_dispatch run created at or after [sinceMs], allowing 10 s of clock skew. */
    suspend fun latestRunSince(repo: String, sinceMs: Long): GhRun? {
        val r = call("GET", "/repos/$repo/actions/runs?event=workflow_dispatch&per_page=10")
        if (r.code != 200) fail(r)
        val runs = JSONObject(r.body).getJSONArray("workflow_runs")
        return (0 until runs.length())
            .map { parseRun(runs.getJSONObject(it)) }
            .filter { it.createdAt >= sinceMs - 10_000 }
            .minByOrNull { it.createdAt }
    }

    suspend fun getRun(repo: String, id: Long): GhRun {
        val r = call("GET", "/repos/$repo/actions/runs/$id")
        if (r.code != 200) fail(r)
        return parseRun(JSONObject(r.body))
    }

    suspend fun listJobs(repo: String, runId: Long): List<GhJob> {
        val r = call("GET", "/repos/$repo/actions/runs/$runId/jobs?per_page=20")
        if (r.code != 200) fail(r)
        val arr = JSONObject(r.body).getJSONArray("jobs")
        return (0 until arr.length()).map { i ->
            val j = arr.getJSONObject(i)
            val stepsArr = j.optJSONArray("steps") ?: JSONArray()
            GhJob(
                id = j.getLong("id"),
                name = j.getString("name"),
                status = j.getString("status"),
                conclusion = optStr(j, "conclusion"),
                steps = (0 until stepsArr.length()).map { k ->
                    val s = stepsArr.getJSONObject(k)
                    GhStep(s.getInt("number"), s.getString("name"), s.getString("status"), optStr(s, "conclusion"))
                },
            )
        }
    }

    /** Last [maxLines] lines of a finished job's log, with timestamps and colour codes removed. */
    suspend fun jobLogTail(repo: String, jobId: Long, maxLines: Int): String {
        val r = call("GET", "/repos/$repo/actions/jobs/$jobId/logs")
        val text = if (r.code == 200) {
            r.body
        } else {
            val loc = if (r.code in 300..399) r.location else null
            if (loc == null) fail(r)
            val out = ByteArrayOutputStream()
            withContext(Dispatchers.IO) { Http.download(loc, out, 8L * 1024 * 1024) }
            out.toString("UTF-8")
        }
        return text.lines()
            .map { ANSI.replace(LOG_TIMESTAMP.replace(it, ""), "") }
            .takeLast(maxLines)
            .joinToString("\n")
    }

    suspend fun listArtifacts(repo: String, runId: Long): List<GhArtifact> {
        val r = call("GET", "/repos/$repo/actions/runs/$runId/artifacts")
        if (r.code != 200) fail(r)
        val arr = JSONObject(r.body).getJSONArray("artifacts")
        return (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            GhArtifact(a.getLong("id"), a.getString("name"), a.optLong("size_in_bytes"), a.optBoolean("expired"))
        }
    }

    /** Streams an artifact zip into [out]. The token is sent only to api.github.com. */
    suspend fun downloadArtifact(repo: String, artifactId: Long, out: OutputStream) {
        val r = call("GET", "/repos/$repo/actions/artifacts/$artifactId/zip")
        val loc = if (r.code in 300..399) r.location else null
        if (loc == null) fail(r)
        withContext(Dispatchers.IO) { Http.download(loc, out, 200L * 1024 * 1024) }
    }

    private fun parseRun(o: JSONObject) = GhRun(
        id = o.getLong("id"),
        number = o.getInt("run_number"),
        status = o.getString("status"),
        conclusion = optStr(o, "conclusion"),
        createdAt = Instant.parse(o.getString("created_at")).toEpochMilli(),
        url = o.getString("html_url"),
    )
}
