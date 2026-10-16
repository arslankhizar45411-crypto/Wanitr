package com.wani.terminal

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Phone se file chunne ka dialog (Upload button). Activity ke launcher ke zariye. */
object FilePicker {

    @Volatile
    internal var launcher: ((String) -> Unit)? = null

    private var pending: CompletableDeferred<Uri?>? = null

    internal fun onResult(uri: Uri?) {
        pending?.complete(uri)
        pending = null
    }

    /** User file chune to Uri, cancel kare to null. */
    suspend fun pick(): Uri? {
        val launch = launcher ?: return null
        val result = CompletableDeferred<Uri?>()
        pending = result
        withContext(Dispatchers.Main) { launch("*/*") }
        return result.await()
    }
}
