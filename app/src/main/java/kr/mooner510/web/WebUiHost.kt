package kr.mooner510.web

import android.annotation.SuppressLint
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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kr.mooner510.BiometricLock
import kr.mooner510.BuildConfig
import kr.mooner510.appGraph
import kr.mooner510.ui.NativeScreenActivity
import org.json.JSONObject

private const val ASSET_HOST = "appassets.androidplatform.net"
private const val START_URL = "https://$ASSET_HOST/assets/ui/index.html"

/** Hosts the React UI (apps/ui) in a WebView that only ever loads the packaged bundle. */
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

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            // Never navigate the main frame away from our own bundle; external links are not part of this UI.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != ASSET_HOST

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
            openNative = { screen -> context.startActivity(NativeScreenActivity.intent(context, screen)) },
            requestBiometricEnroll = { enrollLauncher.launch(BiometricLock.enrollIntent()) },
        )
        webView.addJavascriptInterface(bridge, "NodeMapHost")
        webView.loadUrl(START_URL)

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> webView.onResume()
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
        context.appGraph.repository.changes
            .conflate()
            .onEach { webView.evaluateJavascript("window.__nm&&window.__nm.emit('data-changed','')", null) }
            .launchIn(this)
    }

    AndroidView(factory = { webView }, modifier = modifier)
}
