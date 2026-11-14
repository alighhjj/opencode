package ai.opencode.mobile.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.net.http.SslError
import android.os.Build
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
import androidx.compose.foundation.layout.padding
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
import ai.opencode.mobile.web.injectClipboardPolyfill
import ai.opencode.mobile.web.injectKeyboardComposerPolyfill
import ai.opencode.mobile.web.installClipboardBridge
import ai.opencode.mobile.web.pushKeyboardHeight

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

    // The web client pins its composer to the bottom of its own viewport. Resizing the WebView to keep the
    // composer above the keyboard forces the whole page to reflow and repaint on every IME frame; for a heavy
    // chat UI that exceeds the frame budget and reads as stutter. Instead the shell keeps the WebView full
    // height — the page never reflows — and tells the page the keyboard height; the injected polyfill lifts
    // only the bottom-pinned composer to sit on top of the keyboard. Moving one fixed element is cheap and
    // does not reflow the message list, so the keyboard show/hide stays smooth (see KeyboardBridge).
    val density = LocalDensity.current

    // Android 10 and older report no IME inset once the app draws edge to edge, so measure the visible frame
    // the way adjustResize used to. Kept off Android 11+ to avoid fighting the real inset.
    val hostView = LocalView.current
    var legacyIme by remember { mutableIntStateOf(0) }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        DisposableEffect(hostView) {
            val root = hostView.rootView
            val frame = Rect()
            val keyboardThreshold = with(density) { 100.dp.roundToPx() }
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                root.getWindowVisibleDisplayFrame(frame)
                val hidden = root.height - frame.bottom
                legacyIme = if (hidden > keyboardThreshold) hidden else 0
            }
            root.viewTreeObserver.addOnGlobalLayoutListener(listener)
            listener.onGlobalLayout()
            onDispose { root.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
        }
    }
    // Keyboard height in physical px; only one source is active per API level (modern ime, legacy fallback).
    val kbPx = WindowInsets.ime.getBottom(density).coerceAtLeast(legacyIme)
    val currentKbPx = rememberUpdatedState(kbPx)

    // Push the height into the page whenever it changes (and once the WebView is ready).
    LaunchedEffect(kbPx, webView) {
        webView?.pushKeyboardHeight(kbPx)
    }

    Box(Modifier.fillMaxSize().background(OpenCodeColors.Background)) {
        Column(Modifier.fillMaxSize()) {
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
                            webView.injectKeyboardComposerPolyfill()
                            webView.pushKeyboardHeight(currentKbPx.value)
                        }

                        override fun onPageCommitVisible(webView: WebView, url: String?) {
                            // The first frame of the new document is up: guaranteed to hit the right JS context.
                            webView.injectClipboardPolyfill()
                            webView.injectKeyboardComposerPolyfill()
                            webView.pushKeyboardHeight(currentKbPx.value)
                        }

                        override fun onPageFinished(webView: WebView, url: String?) {
                            loading = false
                            progress = 1f
                            // Defensive second pass: an SPA may have replaced the document during hydration.
                            webView.injectClipboardPolyfill()
                            webView.injectKeyboardComposerPolyfill()
                            webView.pushKeyboardHeight(currentKbPx.value)
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
