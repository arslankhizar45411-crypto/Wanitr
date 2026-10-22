package com.collabcode.app.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves chat text (not diffs) on the phone, so history survives closing the app. */
class ChatStore(context: Context) {
    private val file = File(context.filesDir, "chat.json")

    fun load(): List<ChatItem> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            val out = mutableListOf<ChatItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val text = o.optString("text")
                when (o.optString("role")) {
                    "user" -> out.add(UserMsg(text))
                    "assistant" -> out.add(AssistantMsg(null, null, text, emptyList()))
                }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(items: List<ChatItem>) {
        val entries = mutableListOf<Pair<String, String>>()
        for (item in items) {
            when (item) {
                is UserMsg -> entries.add("user" to item.text)
                is AssistantMsg -> entries.add("assistant" to item.text)
                is NoticeMsg -> Unit
            }
        }
        val arr = JSONArray()
        for ((role, text) in entries.takeLast(MAX)) {
            arr.put(JSONObject().put("role", role).put("text", text))
        }
        file.writeText(arr.toString())
    }

    fun clear() {
        file.delete()
    }

    companion object {
        const val MAX = 200
    }
}
