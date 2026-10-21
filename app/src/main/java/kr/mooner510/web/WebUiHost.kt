package kr.mooner510.web

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.mooner510.BiometricLock
import kr.mooner510.BuildConfig
import kr.mooner510.appGraph
import org.json.JSONObject

private const val ASSET_HOST = "appassets.androidplatform.net"
private const val START_URL = "https://$ASSET_HOST/assets/ui/index.html"

// Debug builds load the Vite dev server (HMR) when it is reachable; release builds have this empty.
private val DEV_URL: String? = BuildConfig.WEB_DEV_URL.takeIf { BuildConfig.DEBUG && it.isNotEmpty() }
private val DEV_HOST: String? = DEV_URL?.let { android.net.Uri.parse(it).host }

/** Turns an activity-result callback into a suspending call. A newer request supersedes a pending one. */
private class ResultSlot<T>(private val cancelled: T) {
    private var pending: CompletableDeferred<T>? = null

    suspend fun await(launch: () -> Unit): T {
        val deferred = CompletableDeferred<T>()
        withContext(Dispatchers.Main) {
            pending?.complete(cancelled)
            pending = deferred
            launch()
        }
        return deferred.await()
    }

    fun complete(value: T) {
        pending?.complete(value)
        pending = null
    }
}

/** Hosts the whole UI (apps/ui) in a WebView that only ever loads the packaged bundle. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebUiHost(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = isSystemInDarkTheme()
    var pageLoads by remember { mutableIntStateOf(0) }
    val enrollLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (BiometricLock.isAvailable(context)) {
            scope.launch { context.appGraph.preferences.setBiometricLockEnabled(true) }
        }
    }
    val permissionSlot = remember { ResultSlot(Unit) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionSlot.complete(Unit)
    }
    val createDocumentSlot = remember { ResultSlot<Uri?>(null) }
    val createDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        createDocumentSlot.complete(it)
    }
    val openDocumentSlot = remember { ResultSlot<Uri?>(null) }
    val openDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        openDocumentSlot.complete(it)
    }

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            overScrollMode = WebView.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportZoom(false)
                builtInZoomControls = false
            }
        }
    }
    webView.setBackgroundColor(if (dark) 0xFF101216.toInt() else 0xFFF4F5F7.toInt())

    DisposableEffect(webView) {
        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(ASSET_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()

        val tileCache = context.appGraph.tileCache

        webView.webViewClient = object : WebViewClient() {
            // Map styles/tiles go through the on-device cache so saved regions render offline.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url) ?: tileCache.intercept(request.url, request.method)

            // Never navigate the main frame away from our own bundle; external links are not part of this UI.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != ASSET_HOST && request.url.host != DEV_HOST

            // Dev server not running: fall back to the packaged bundle.
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame && DEV_URL != null && request.url.toString().startsWith(DEV_URL)) view.loadUrl(START_URL)
            }

            // Insets are pushed before the page has installed window.__nm; this makes the effect below re-send them.
            override fun onPageFinished(view: WebView, url: String?) {
                pageLoads++
            }
        }

        val bridge = WebUiBridge(
            context = context,
            scope = scope,
            reply = { id, ok, json ->
                webView.post {
                    webView.evaluateJavascript("window.__nm&&window.__nm.resolve($id,$ok,${JSONObject.quote(json)})", null)
                }
            },
            host = object : WebUiHostActions {
                override suspend fun requestPermissions(permissions: Array<String>) =
                    permissionSlot.await { permissionLauncher.launch(permissions) }

                override suspend fun createDocument(fileName: String): Uri? =
                    createDocumentSlot.await { createDocumentLauncher.launch(fileName) }

                override suspend fun openDocument(): Uri? =
                    openDocumentSlot.await { openDocumentLauncher.launch(arrayOf("application/octet-stream", "*/*")) }

                override fun startActivity(intent: Intent) = context.startActivity(intent)

                override fun requestBiometricEnroll() = enrollLauncher.launch(BiometricLock.enrollIntent())

                override fun emit(name: String, json: String) = webView.emit(name, json)
            },
        )
        webView.addJavascriptInterface(bridge, "NodeMapHost")
        webView.loadUrl(DEV_URL ?: START_URL)

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    webView.onResume()
                    // Permissions may have been granted in system settings meanwhile.
                    webView.emit("resumed", "")
                }
                Lifecycle.Event.ON_PAUSE -> webView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            webView.removeJavascriptInterface("NodeMapHost")
            webView.stopLoading()
            webView.destroy()
        }
    }

    // Hand the system-bar sizes to CSS (--sat/--sab …) in CSS pixels; the keyboard counts as bottom inset.
    val density = LocalDensity.current
    val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val top = with(density) { bars.getTop(density).toDp().value }
    val left = with(density) { bars.getLeft(density, LayoutDirection.Ltr).toDp().value }
    val right = with(density) { bars.getRight(density, LayoutDirection.Ltr).toDp().value }
    val bottom = with(density) { maxOf(bars.getBottom(density), WindowInsets.ime.getBottom(density)).toDp().value }
    LaunchedEffect(top, bottom, left, right, pageLoads) {
        webView.evaluateJavascript("window.__nm&&window.__nm.setInsets($top,$bottom,$left,$right)", null)
    }

    // New track points / events → tell the page to refresh (it throttles on its own side too).
    LaunchedEffect(webView) {
        val graph = context.appGraph
        graph.repository.changes
            .conflate()
            .onEach { webView.emit("data-changed", "") }
            .launchIn(this)
        // Settings can change outside the page (biometric enrollment result, boot receiver…).
        graph.preferences.settings
            .drop(1)
            .onEach { webView.emit("settings-changed", it.toJson().toString()) }
            .launchIn(this)
        graph.offlineMapManager.downloadState
            .onEach { webView.emit("offline-changed", it.toJson().toString()) }
            .launchIn(this)
    }

    AndroidView(factory = { webView }, modifier = modifier)
}

private fun WebView.emit(name: String, json: String) {
    post { evaluateJavascript("window.__nm&&window.__nm.emit(${JSONObject.quote(name)},${JSONObject.quote(json)})", null) }
}
