package com.bilicraft.handheld.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 快捷工具二级浏览器页。
 * 纯 UI 配置：只配置 WebView 展示能力，不参与登录、协议、插件或数据存储逻辑。
 */
class WebViewActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var pendingWebViewState: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingWebViewState = savedInstanceState?.getBundle(KEY_WEBVIEW_STATE)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "快捷工具" }
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    WebToolPage(
                        title = title,
                        url = url,
                        onClose = { finish() },
                        createWebView = { progress, onRendererGone ->
                            buildWebView(
                                url = url,
                                savedState = pendingWebViewState,
                                onProgress = progress,
                                onRendererGone = onRendererGone
                            ).also {
                                webView = it
                                pendingWebViewState = null
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.let { current ->
            val webViewState = Bundle()
            current.saveState(webViewState)
            outState.putBundle(KEY_WEBVIEW_STATE, webViewState)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        webView?.onPause()
        webView?.pauseTimers()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.resumeTimers()
        webView?.onResume()
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(
        url: String,
        savedState: Bundle?,
        onProgress: (Int) -> Unit,
        onRendererGone: () -> Unit
    ): WebView = WebView(this).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgress(newProgress)
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                onProgress(0)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val target = url?.takeIf { it.isNotBlank() } ?: return false
                view?.loadUrl(target) ?: return false
                return true
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                view.loadUrl(request.url.toString())
                return true
            }

            @RequiresApi(Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                onProgress(100)
                (view.parent as? ViewGroup)?.removeView(view)
                if (webView === view) webView = null
                view.destroy()
                onRendererGone()
                return true
            }
        }
        if (savedState != null) restoreState(savedState) else if (url.isNotBlank()) loadUrl(url)
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_URL = "url"
        private const val KEY_WEBVIEW_STATE = "webview_state"

        fun intent(context: Context, title: String, url: String): Intent =
            Intent(context, WebViewActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_URL, url)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebToolPage(
    title: String,
    url: String,
    onClose: () -> Unit,
    createWebView: ((Int) -> Unit, () -> Unit) -> WebView
) {
    var progress by remember { mutableIntStateOf(0) }
    var rendererGone by remember { mutableStateOf(false) }
    var webViewKey by remember { mutableIntStateOf(0) }
    val webView = remember(url, webViewKey) {
        createWebView(
            { progress = it },
            { rendererGone = true }
        )
    }

    BackHandler(enabled = true) {
        if (!rendererGone && webView.canGoBack()) webView.goBack() else onClose()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            actions = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }
        )
        if (!rendererGone && progress in 0..99) {
            LinearProgressIndicator(progress = { progress / 100f })
        }
        Box(Modifier.fillMaxSize()) {
            if (rendererGone) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("网页渲染进程已被系统回收，页面没有崩溃。")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        rendererGone = false
                        progress = 0
                        webViewKey += 1
                    }) {
                        Text("重新加载")
                    }
                }
            } else {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}