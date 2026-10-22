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
            prefs.edit().putString(K_BASE_URL, value.trim().trimEnd('/').removeSuffix("/chat/completions").trimEnd('/')).apply()
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

    /** true = dark theme (default). */
    var darkTheme: Boolean
        get() = prefs.getBoolean(K_DARK, true)
        set(value) {
            prefs.edit().putBoolean(K_DARK, value).apply()
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

    /** AI ko extra hidayat (system prompt ke saath jaati hai). Max 4000 characters. */
    var aiCustomInstructions: String
        get() = prefs.getString(K_CUSTOM, "") ?: ""
        set(value) {
            prefs.edit().putString(K_CUSTOM, value.trim().take(4000)).apply()
        }

    /** On ho to AI ke commands aur unka output terminal mein bhi dikhte hain. */
    var aiShowInTerminal: Boolean
        get() = prefs.getBoolean(K_SHOW_TERM, true)
        set(value) {
            prefs.edit().putBoolean(K_SHOW_TERM, value).apply()
        }

    /** Shortcut bar mein dikhne wale buttons (ID ki list, isi order mein). */
    var shortcutKeys: List<String>
        get() {
            val saved = prefs.getString(K_SHORTCUTS, null) ?: return ShortcutKeys.DEFAULT
            val ids = saved.split(',').map { it.trim() }.filter { ShortcutKeys.isKnown(it) }.distinct()
            return if (ids.isEmpty()) ShortcutKeys.DEFAULT else ids
        }
        set(value) {
            prefs.edit().putString(K_SHORTCUTS, value.joinToString(",")).apply()
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        const val DEFAULT_MODEL = "openrouter/free"

        private const val K_BASE_URL = "ai_base_url_v2"
        private const val K_MODEL = "ai_model_v2"
        private const val K_API_KEY = "ai_api_key"
        private const val K_ALLOW_COMMANDS = "ai_allow_commands"
        private const val K_AUTO_APPROVE = "ai_auto_approve"
        private const val K_DARK = "dark_theme"
        private const val K_GH_TOKEN = "gh_token"
        private const val K_GH_REPO = "gh_repo"
        private const val K_GH_WORKFLOW = "gh_workflow"
        private const val K_GH_BRANCH = "gh_branch"
        private const val K_CUSTOM = "ai_custom_instructions"
        private const val K_SHOW_TERM = "ai_show_in_terminal"
        private const val K_SHORTCUTS = "shortcut_keys_v2"
    }
}
