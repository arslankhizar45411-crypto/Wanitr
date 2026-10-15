package com.wani.terminal

import android.content.Context

/**
 * App settings, sirf is phone par (SharedPreferences, app ke private folder mein).
 * Keys/tokens kisi aur jagah nahi bheje jaate, sirf us API ko jo user ne set kiya ho.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("wani_settings", Context.MODE_PRIVATE)

    var aiBaseUrl: String
        get() = prefs.getString(K_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) {
            prefs.edit().putString(K_BASE_URL, value.trim().trimEnd('/')).apply()
        }

    var aiModel: String
        get() = prefs.getString(K_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) {
            prefs.edit().putString(K_MODEL, value.trim()).apply()
        }

    var aiApiKey: String
        get() = prefs.getString(K_API_KEY, "") ?: ""
        set(value) {
            prefs.edit().putString(K_API_KEY, value.trim()).apply()
        }

    /** Off ho to AI shell commands nahi chala sakta. */
    var aiAllowCommands: Boolean
        get() = prefs.getBoolean(K_ALLOW_COMMANDS, true)
        set(value) {
            prefs.edit().putBoolean(K_ALLOW_COMMANDS, value).apply()
        }

    /** On ho to AI har command bina poochhe chalata hai. Off (default) = har command par Yes/No. */
    var aiAutoApprove: Boolean
        get() = prefs.getBoolean(K_AUTO_APPROVE, false)
        set(value) {
            prefs.edit().putBoolean(K_AUTO_APPROVE, value).apply()
        }

    var githubToken: String
        get() = prefs.getString(K_GH_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(K_GH_TOKEN, value.trim()).apply()
        }

    var githubRepo: String
        get() = prefs.getString(K_GH_REPO, "") ?: ""
        set(value) {
            prefs.edit().putString(K_GH_REPO, value.trim()).apply()
        }

    var githubWorkflow: String
        get() = prefs.getString(K_GH_WORKFLOW, "build.yml") ?: "build.yml"
        set(value) {
            prefs.edit().putString(K_GH_WORKFLOW, value.trim()).apply()
        }

    var githubBranch: String
        get() = prefs.getString(K_GH_BRANCH, "main") ?: "main"
        set(value) {
            prefs.edit().putString(K_GH_BRANCH, value.trim()).apply()
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"

        private const val K_BASE_URL = "ai_base_url"
        private const val K_MODEL = "ai_model"
        private const val K_API_KEY = "ai_api_key"
        private const val K_ALLOW_COMMANDS = "ai_allow_commands"
        private const val K_AUTO_APPROVE = "ai_auto_approve"
        private const val K_GH_TOKEN = "gh_token"
        private const val K_GH_REPO = "gh_repo"
        private const val K_GH_WORKFLOW = "gh_workflow"
        private const val K_GH_BRANCH = "gh_branch"
    }
}
