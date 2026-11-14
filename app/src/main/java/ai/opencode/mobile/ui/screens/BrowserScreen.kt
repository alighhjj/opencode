package ai.opencode.mobile.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.net.http.SslError
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.opencode.mobile.BuildConfig
import ai.opencode.mobile.data.Server
import ai.opencode.mobile.ui.theme.OpenCodeColors
import kotlinx.coroutines.delay
import ai.opencode.mobile.web.injectClipboardPolyfill
import ai.opencode.mobile.web.installClipboardBridge

/**
 * Full-screen host for the OpenCode web client served by `opencode serve`.
 *
 * The web app already ships a mobile layout, a sign-in screen and the server pairing flow, so the Android side
 * only supplies what a browser would: a configured WebView, basic-auth credentials, back navigation and
 * permission bridging for the camera (QR pairing) and the microphone (voice input).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    server: Server,
    initialPath: String?,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0f) }
    var failure by remember { mutableStateOf<String?>(null) }
    var showExitDialog by remember { mutableStateOf(false) }
    var askPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf(server.password) }

    val currentPassword = rememberUpdatedState(password)
    val startUrl = remember(server, initialPath) { server.url + (initialPath ?: "/") }

    // <input type="file"> from the web composer.
    val fileCallback = remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        fileCallback.value?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback.value = null
    }

    // getUserMedia: camera for the QR pairing scanner, microphone for voice input.
    val pendingPermission = remember { mutableStateOf<PermissionRequest?>(null) }
    val permissionPrompt =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val request = pendingPermission.value
            pendingPermission.value = null
            if (request == null) return@rememberLauncherForActivityResult
            val needed = request.resources.mapNotNull { it.toAndroidPermission() }
            val granted = needed.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
            if (granted) request.grant(request.resources) else request.deny()
        }

    fun openExternally(uri: Uri) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else showExitDialog = true
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> webView?.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                    webView?.onPause()
                    // Persist the 30 day session cookie across process death.
                    CookieManager.getInstance().flush()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The web client lays its composer out as the last row of a 100dvh flex column — it is NOT a fixed
    // element, so lifting it from JS would mean re-laying out the page anyway. What a browser does is shrink
    // the viewport: 100dvh follows the visible area and the composer stays above the keyboard by itself.
    // So the shell does the same — the WebView keeps its width and loses exactly the IME height at the
    // bottom, no JS patching involved (see the 0.2.4 regression where a DOM polyfill could not find the
    // composer and the input sat under the keyboard).
    val density = LocalDensity.current

    // Some ROMs do not report the IME inset while drawing edge to edge, and Android 10 and older never do.
    // Measure the visible display frame the way adjustResize used to and take the larger of the two; when
    // the real inset is present both agree, so max() cannot double count.
    val hostView = LocalView.current
    var frameIme by remember { mutableIntStateOf(0) }
    DisposableEffect(hostView) {
        val root = hostView.rootView
        val frame = Rect()
        val keyboardThreshold = with(density) { 80.dp.roundToPx() }
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            root.getWindowVisibleDisplayFrame(frame)
            val hidden = root.height - frame.bottom
            frameIme = if (hidden > keyboardThreshold) hidden else 0
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        listener.onGlobalLayout()
        onDispose { root.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }

    // Bottom inset while the keyboard is closed: gesture navigation bar (or 3-button bar) height.
    val navPx = WindowInsets.navigationBars.getBottom(density)
    val imePx = WindowInsets.ime.getBottom(density)
    // max() of the three sources: IME when open, navigation bar when closed, measured frame as fallback.
    val bottomInset = with(density) { maxOf(imePx, navPx, frameIme).toDp() }

    // The activity draws edge to edge, so the WebView's own origin is under the status bar and the web
    // client's header (tab bar, session title) ended up behind the clock and battery icons. safeDrawing
    // carries the status bar height and, on notched devices, the taller cutout — the page's 100dvh then
    // re-flows into the remaining box, exactly like the WebView resize used for the keyboard.
    val topInset = with(density) { WindowInsets.safeDrawing.getTop(density).toDp() }

    // The page uses height:100dvh, so shrinking the WebView already re-lays it out. This is the safety net:
    // once the keyboard stops moving, scroll the focused editor back into view (a no-op when the page
    // already placed it above the keyboard). 180 ms of quiet also debounces the per-frame IME animation.
    LaunchedEffect(bottomInset) {
        if (bottomInset > 0.dp) {
            delay(180)
            webView?.evaluateJavascript(ENSURE_EDITOR_VISIBLE_JS, null)
        }
    }

    Box(Modifier.fillMaxSize().background(OpenCodeColors.Background)) {
        Column(Modifier.fillMaxSize().padding(top = topInset, bottom = bottomInset)) {
            if (loading) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = OpenCodeColors.Accent,
                    trackColor = Color.Transparent,
                )
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val view = WebView(ctx)
                    view.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    view.setBackgroundColor(0xFF080808.toInt())

                    view.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        javaScriptCanOpenWindowsAutomatically = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        cacheMode = WebSettings.LOAD_DEFAULT
                        userAgentString = "$userAgentString OpenCodeMobile/${BuildConfig.VERSION_NAME}"
                    }

                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(view, true)
                    }

                    // http:// origins are not secure contexts, so the web client's navigator.clipboard is
                    // undefined and "Copy path" failed with "reading 'writeText'". Bridge the platform clipboard.
                    view.installClipboardBridge(runCatching { Uri.parse(server.url).host }.getOrNull())

                    view.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            webView: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val uri = request.url
                            val scheme = uri.scheme?.lowercase()
                            if (scheme != "http" && scheme != "https") {
                                openExternally(uri)
                                return true
                            }
                            return false
                        }

                        override fun onReceivedHttpAuthRequest(
                            webView: WebView,
                            handler: HttpAuthHandler,
                            host: String,
                            realm: String,
                        ) {
                            val secret = currentPassword.value
                            if (secret.isNotEmpty()) {
                                handler.proceed("opencode", secret)
                            } else {
                                handler.cancel()
                                askPassword = true
                            }
                        }

                        override fun onPageStarted(webView: WebView, url: String?, favicon: Bitmap?) {
                            loading = true
                            failure = null
                            // Get navigator.clipboard and the composer-lift helper in place before the page's own bundles run.
                            webView.injectClipboardPolyfill()
                        }

                        override fun onPageCommitVisible(webView: WebView, url: String?) {
                            // The first frame of the new document is up: guaranteed to hit the right JS context.
                            webView.injectClipboardPolyfill()
                        }

                        override fun onPageFinished(webView: WebView, url: String?) {
                            loading = false
                            progress = 1f
                            // Defensive second pass: an SPA may have replaced the document during hydration.
                            webView.injectClipboardPolyfill()
                        }

                        override fun onReceivedError(
                            webView: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) {
                                loading = false
                                failure = error.description?.toString() ?: "无法连接服务器"
                            }
                        }

                        override fun onReceivedSslError(
                            webView: WebView,
                            handler: SslErrorHandler,
                            error: SslError,
                        ) {
                            handler.cancel()
                            failure = "服务器证书校验失败（${error.url}）"
                        }
                    }

                    view.webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(webView: WebView, newProgress: Int) {
                            progress = newProgress / 100f
                            if (newProgress >= 100) loading = false
                        }

                        override fun onPermissionRequest(request: PermissionRequest) {
                            val wanted = request.resources.filter {
                                it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                                    it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                            }
                            if (wanted.isEmpty()) {
                                request.deny()
                                return
                            }
                            val androidPermissions = wanted.mapNotNull { it.toAndroidPermission() }
                            val granted = androidPermissions.all {
                                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                            }
                            if (granted) {
                                request.grant(wanted.toTypedArray())
                            } else {
                                pendingPermission.value = request
                                permissionPrompt.launch(androidPermissions.toTypedArray())
                            }
                        }

                        override fun onShowFileChooser(
                            webView: WebView?,
                            callback: ValueCallback<Array<Uri>>?,
                            params: FileChooserParams?,
                        ): Boolean {
                            fileCallback.value?.onReceiveValue(null)
                            fileCallback.value = callback
                            return runCatching {
                                filePicker.launch(arrayOf("*/*"))
                                true
                            }.getOrElse {
                                fileCallback.value = null
                                false
                            }
                        }
                    }

                    view.setDownloadListener { url, _, _, _, _ -> openExternally(Uri.parse(url)) }

                    view.loadUrl(startUrl)
                    webView = view
                    view
                },
                onRelease = {
                    it.stopLoading()
                    it.destroy()
                },
            )
        }

        val currentFailure = failure
        if (currentFailure != null) {
            FailureOverlay(
                message = currentFailure,
                url = startUrl,
                onRetry = {
                    failure = null
                    loading = true
                    progress = 0f
                    webView?.reload()
                },
                onBack = onDisconnect,
            )
        } else if (loading && progress < 0.05f) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = OpenCodeColors.Accent, strokeWidth = 2.dp)
            }
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            containerColor = OpenCodeColors.SurfaceRaised,
            title = { Text("离开当前会话？", color = OpenCodeColors.TextPrimary) },
            text = {
                Text("会话仍保留在服务器上，可以随时重新连接。", color = OpenCodeColors.TextSecondary)
            },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    onDisconnect()
                }) { Text("切换服务器", color = OpenCodeColors.Accent) }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("继续", color = OpenCodeColors.TextSecondary)
                }
            },
        )
    }

    if (askPassword) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askPassword = false },
            containerColor = OpenCodeColors.SurfaceRaised,
            title = { Text("服务器需要密码", color = OpenCodeColors.TextPrimary) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    placeholder = { Text("opencode serve 输出的 password") },
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OpenCodeColors.Accent,
                        unfocusedBorderColor = OpenCodeColors.Border,
                        focusedTextColor = OpenCodeColors.TextPrimary,
                        unfocusedTextColor = OpenCodeColors.TextPrimary,
                        cursorColor = OpenCodeColors.Accent,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    password = input.trim()
                    askPassword = false
                    webView?.reload()
                }) { Text("连接", color = OpenCodeColors.Accent) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askPassword = false
                    onDisconnect()
                }) { Text("返回", color = OpenCodeColors.TextSecondary) }
            },
        )
    }
}

@Composable
private fun FailureOverlay(
    message: String,
    url: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(OpenCodeColors.Background).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "连接失败",
                style = MaterialTheme.typography.titleLarge,
                color = OpenCodeColors.TextPrimary,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = OpenCodeColors.Danger,
                textAlign = TextAlign.Center,
            )
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = OpenCodeColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "请确认电脑已运行 opencode serve --hostname 0.0.0.0，且手机与电脑在同一局域网。",
                style = MaterialTheme.typography.bodySmall,
                color = OpenCodeColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = OpenCodeColors.Accent,
                    contentColor = Color(0xFF06122E),
                ),
            ) { Text("重试") }
            TextButton(onClick = onBack) {
                Text("切换服务器", color = OpenCodeColors.TextSecondary)
            }
        }
    }
}

private fun String.toAndroidPermission(): String? = when (this) {
    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
    else -> null
}

/**
 * Scrolls the focused editor back into the visible area after the keyboard settles. The page's own layout
 * (height:100dvh + `interactive-widget=resizes-content`) is what normally keeps the composer above the IME;
 * this only covers the case where the editor was pushed below the fold, e.g. a draft session whose content
 * is taller than the shrunken viewport. `block: "nearest"` avoids jumping when nothing is clipped.
 */
private val ENSURE_EDITOR_VISIBLE_JS = """
(function () {
  var a = document.activeElement;
  if (!a || !a.matches) return;
  if (!a.matches('textarea, input:not([readonly]), [contenteditable="true"]')) return;
  var r = a.getBoundingClientRect();
  var vh = window.visualViewport ? window.visualViewport.height : window.innerHeight;
  if (r.bottom <= vh && r.top >= 0) return;
  a.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
})();
""".trimIndent()
