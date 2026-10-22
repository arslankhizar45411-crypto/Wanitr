package com.collabcode.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Keeps the project files on the phone so they survive closing the app. */
class ProjectStore(context: Context) {
    private val dir = File(context.filesDir, "project").apply { mkdirs() }
    private val committedFile = File(context.filesDir, "committed.json")

    fun loadFiles(): Map<String, String>? {
        val list = dir.listFiles()?.filter { it.isFile } ?: return null
        if (list.isEmpty()) return null
        return list.sortedBy { it.name }.associate { it.name to it.readText() }
    }

    fun save(name: String, text: String) {
        if (isValidName(name)) File(dir, name).writeText(text)
    }

    fun delete(name: String) {
        if (isValidName(name)) File(dir, name).delete()
    }

    /** What was last pushed to GitHub, by repo path. Used to send only what changed. */
    fun loadCommitted(): Map<String, String> {
        if (!committedFile.exists()) return emptyMap()
        return try {
            val o = JSONObject(committedFile.readText())
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveCommitted(map: Map<String, String>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        committedFile.writeText(o.toString())
    }

    companion object {
        private val NAME = Regex("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}$")

        /** Flat names only. No slashes, so a name can never point outside the project folder. */
        fun isValidName(name: String): Boolean = NAME.matches(name)
    }
}
