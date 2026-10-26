package com.collabcode.app.agent

import android.content.Context
import com.collabcode.app.data.Secrets

const val AGENT_DEFAULT_SYS =
    "You are CollabAgent, a coding assistant that works on the user's GitHub repository through tools. " +
        "Look at the repo first (list_files, read_file) before editing. Make small, correct changes and explain them briefly. " +
        "Commit with write_file only when the change is ready. Start builds with start_build and check them with latest_run. " +
        "Reply in the language the user writes in. Put code in fenced code blocks with a language tag."

/** Every setting from the design. Keys and tokens are encrypted on the device. */
data class AgentConfig(
    val key: String = "",
    val base: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o",
    val temp: Double = 0.4,
    val maxTok: Int = 2048,
    val sys: String = AGENT_DEFAULT_SYS,
    val gt: String = "",
    val repo: String = "",
    val br: String = "main",
    val wf: String = "build.yml",
    val askW: Boolean = true,
    val askB: Boolean = true,
    val askH: Boolean = true,
    val fs: Int = 15,
    val theme: String = "system",
)

class AgentConfigStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("agent_cfg", Context.MODE_PRIVATE)

    private fun secret(name: String): String =
        sp.getString(name, null)?.let { runCatching { Secrets.decrypt(it) }.getOrNull() }.orEmpty()

    private fun putSecret(name: String, value: String) {
        sp.edit().putString(name, if (value.isEmpty()) "" else Secrets.encrypt(value)).apply()
    }

    fun load(): AgentConfig {
        val d = AgentConfig()
        return AgentConfig(
            key = secret("key"),
            base = sp.getString("base", d.base) ?: d.base,
            model = sp.getString("model", d.model) ?: d.model,
            temp = sp.getFloat("temp", d.temp.toFloat()).toDouble(),
            maxTok = sp.getInt("maxTok", d.maxTok),
            sys = sp.getString("sys", d.sys) ?: d.sys,
            gt = secret("gt"),
            repo = sp.getString("repo", "") ?: "",
            br = sp.getString("br", d.br) ?: d.br,
            wf = sp.getString("wf", d.wf) ?: d.wf,
            askW = sp.getBoolean("askW", d.askW),
            askB = sp.getBoolean("askB", d.askB),
            askH = sp.getBoolean("askH", d.askH),
            fs = sp.getInt("fs", d.fs),
            theme = sp.getString("theme", d.theme) ?: d.theme,
        )
    }

    fun save(c: AgentConfig) {
        putSecret("key", c.key)
        putSecret("gt", c.gt)
        sp.edit()
            .putString("base", c.base)
            .putString("model", c.model)
            .putFloat("temp", c.temp.toFloat())
            .putInt("maxTok", c.maxTok)
            .putString("sys", c.sys)
            .putString("repo", c.repo)
            .putString("br", c.br)
            .putString("wf", c.wf)
            .putBoolean("askW", c.askW)
            .putBoolean("askB", c.askB)
            .putBoolean("askH", c.askH)
            .putInt("fs", c.fs)
            .putString("theme", c.theme)
            .apply()
    }
}
