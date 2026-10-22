package com.wani.terminal

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Terminal sessions (tabs). Har session ka apna alag shell hota hai.
 * Ek waqt mein ek session screen par dikhta hai, baaqi background mein chalte rehte hain.
 */
class SessionManager(context: Context) {

    private val appCtx = context.applicationContext

    private val _list = MutableStateFlow(listOf(ShellSession(appCtx)))
    val list: StateFlow<List<ShellSession>> = _list.asStateFlow()

    private val _index = MutableStateFlow(0)
    val index: StateFlow<Int> = _index.asStateFlow()

    /** Abhi dikhne wala session. */
    val current: ShellSession
        get() {
            val l = _list.value
            return l[_index.value.coerceIn(0, l.lastIndex)]
        }

    /** Naya session banata hai aur usi par chala jata hai. Limit poori ho to null. */
    fun newSession(): Int? {
        val l = _list.value
        if (l.size >= MAX_SESSIONS) return null
        _list.value = l + ShellSession(appCtx)
        _index.value = l.size
        return l.size
    }

    fun select(i: Int) {
        _index.value = i.coerceIn(0, _list.value.lastIndex)
    }

    /** Session band karta hai. Aakhri session band ho to naya khul jata hai. */
    fun close(i: Int) {
        val l = _list.value
        if (i !in l.indices) return
        l[i].dispose()
        val rest = l.filterIndexed { idx, _ -> idx != i }
        _list.value = if (rest.isEmpty()) listOf(ShellSession(appCtx)) else rest
        _index.value = i.coerceIn(0, _list.value.lastIndex)
    }

    /** Service band ho to sab shells band. */
    fun shutdownAll() {
        _list.value.forEach { it.shutdown() }
    }

    companion object {
        const val MAX_SESSIONS = 5
    }
}
