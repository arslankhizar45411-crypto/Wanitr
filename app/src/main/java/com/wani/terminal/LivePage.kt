package com.wani.terminal

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

/** AI ka bheja hua live page: user ka jawab WaniBridge.submit() se wapas aata hai. */
class LiveRequest(val title: String, val html: String, internal val answer: CompletableDeferred<String>)

object LivePages {

    private const val TIMEOUT_MS = 15 * 60 * 1000L

    private val _current = MutableStateFlow<LiveRequest?>(null)
    val current: StateFlow<LiveRequest?> = _current.asStateFlow()

    /** Page dikhata hai aur user ke submit ka wait karta hai (max 15 minutes). */
    suspend fun open(title: String, html: String): String {
        val req = LiveRequest(title, html, CompletableDeferred())
        _current.value = req
        return try {
            withTimeout(TIMEOUT_MS) { req.answer.await() }
        } catch (e: TimeoutCancellationException) {
            "No answer: the user did not respond within 15 minutes."
        } finally {
            if (_current.value === req) _current.value = null
        }
    }

    internal fun submit(data: String) {
        _current.value?.answer?.complete(data.take(20_000))
    }

    fun close() {
        _current.value?.answer?.complete("The user closed the page without answering.")
    }
}

/** JavaScript se sirf ek function: WaniBridge.submit(string). */
class PageBridge {
    @JavascriptInterface
    fun submit(data: String) {
        LivePages.submit(data)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LivePageOverlay(req: LiveRequest) {
    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        BackHeader(title = req.title, onBack = { LivePages.close() })
        key(req) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        webViewClient = WebViewClient()
                        addJavascriptInterface(PageBridge(), "WaniBridge")
                        loadDataWithBaseURL(null, req.html, "text/html", "utf-8", null)
                    }
                }
            )
        }
    }
}
