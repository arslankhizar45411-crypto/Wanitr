package com.collabcode.app.data

import android.content.Context

const val DEFAULT_AI_BASE = "https://openrouter.ai/api/v1"
const val DEFAULT_AI_MODEL = "openrouter/free"

/** Tokens and keys are encrypted with an Android Keystore key. Other values are plain. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("collabcode", Context.MODE_PRIVATE)

    init {
        // Migrate values saved by v0.1 (plain text) into encrypted storage.
        sp.getString("gh_token", null)?.let { writeSecret("gh_token_enc", it) }
        sp.getString("ai_key", null)?.let { writeSecret("ai_key_enc", it) }
        sp.edit().remove("gh_token").remove("ai_key").apply()
    }

    private fun read(key: String): String? = sp.getString(key, null)

    private fun write(key: String, value: String?) {
        sp.edit().putString(key, value).apply()
    }

    private fun readSecret(key: String): String? =
        sp.getString(key, null)?.let { runCatching { Secrets.decrypt(it) }.getOrNull() }

    private fun writeSecret(key: String, value: String?) {
        sp.edit().putString(key, value?.let { Secrets.encrypt(it) }).apply()
    }

    var githubToken: String?
        get() = readSecret("gh_token_enc")
        set(v) { writeSecret("gh_token_enc", v) }

    var aiKey: String?
        get() = readSecret("ai_key_enc")
        set(v) { writeSecret("ai_key_enc", v) }

    var githubLogin: String?
        get() = read("gh_login")
        set(v) { write("gh_login", v) }

    var aiBaseUrl: String
        get() = read("ai_base") ?: DEFAULT_AI_BASE
        set(v) { write("ai_base", v) }

    var aiModel: String
        get() = read("ai_model") ?: DEFAULT_AI_MODEL
        set(v) { write("ai_model", v) }

    val repo: String?
        get() = githubLogin?.let { "$it/$REPO_NAME" }

    val isSetUp: Boolean
        get() = !githubToken.isNullOrBlank() && !githubLogin.isNullOrBlank() && !aiKey.isNullOrBlank()

    fun clear() {
        sp.edit().clear().apply()
    }
}
