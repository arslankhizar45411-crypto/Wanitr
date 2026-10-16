package com.wani.terminal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Phone storage (Downloads) ki permission. Activity ke launcher ke zariye mangi jaati hai. */
object StoragePermission {

    // Android 11+ par sirf READ maangte hain (WRITE legacy hai aur manifest mein 28 tak hi hai).
    private val perms: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }

    private var pending: CompletableDeferred<Boolean>? = null

    @Volatile
    internal var launcher: ((Array<String>) -> Unit)? = null

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    internal fun onResult(granted: Boolean) {
        pending?.complete(granted)
        pending = null
    }

    /** Permission dialog dikhata hai aur user ka jawab (true/false) return karta hai. */
    suspend fun request(context: Context): Boolean {
        if (isGranted(context)) return true
        val launch = launcher ?: return false
        val result = CompletableDeferred<Boolean>()
        pending = result
        withContext(Dispatchers.Main) { launch(perms) }
        return result.await()
    }
}

/** Ek approval jo UI mein Yes/No dialog ban kar dikhta hai. */
class ApprovalRequest(
    val title: String,
    val detail: String,
    internal val answer: CompletableDeferred<Boolean>
)

/** AI ke risky kaam (command chalana, storage padhna) se pehle user ki ijazat. */
object Approvals {

    private val _current = MutableStateFlow<ApprovalRequest?>(null)
    val current: StateFlow<ApprovalRequest?> = _current.asStateFlow()

    suspend fun ask(title: String, detail: String): Boolean {
        val req = ApprovalRequest(title, detail, CompletableDeferred())
        _current.value = req
        return try {
            req.answer.await()
        } finally {
            if (_current.value === req) _current.value = null
        }
    }

    fun respond(yes: Boolean) {
        _current.value?.answer?.complete(yes)
    }
}
