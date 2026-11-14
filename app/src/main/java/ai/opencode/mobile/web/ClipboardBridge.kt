package ai.opencode.mobile.web

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * Name of the JavaScript object injected into the web client. Exposed as `window.OpenCodeClipboard`.
 */
const val CLIPBOARD_BRIDGE_NAME = "OpenCodeClipboard"

/**
 * `navigator.clipboard` only exists in secure contexts (https or localhost). The shell talks to
 * `http://<lan-ip>:4096`, so the async clipboard API is `undefined` inside the WebView and the web client's copy
 * buttons ("Copy path", copied code blocks, message actions) threw
 * `Cannot read properties of undefined (reading 'writeText')`.
 *
 * This bridge puts the platform clipboard behind [CLIPBOARD_BRIDGE_NAME]; [CLIPBOARD_POLYFILL_JS] then defines
 * `navigator.clipboard` on top of it. Writes are only honoured while the WebView shows the configured server, so a
 * page reached by following a link cannot silently replace the clipboard.
 */
class ClipboardBridge(private val webView: WebView, private val trustedHost: String?) {

    private val appContext = webView.context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun writeText(text: String) {
        // JavascriptInterface callbacks arrive on a WebView worker thread; touching the clipboard from the main
        // thread keeps it in line with the platform's focus rules and gives us a safe place to read the current URL.
        mainHandler.post {
            if (!isTrustedPage()) return@post
            runCatching {
                appContext.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            }
        }
    }

    private fun isTrustedPage(): Boolean {
        val host = runCatching { webView.url?.let { Uri.parse(it).host } }.getOrNull() ?: return false
        val expected = trustedHost ?: return false
        return host.equals(expected, ignoreCase = true)
    }

    private companion object {
        const val CLIP_LABEL = "OpenCode"
    }
}

/**
 * Attaches [ClipboardBridge] to this WebView. Call once, before the first page load.
 */
fun WebView.installClipboardBridge(trustedHost: String?) {
    addJavascriptInterface(ClipboardBridge(this, trustedHost), CLIPBOARD_BRIDGE_NAME)
}

/**
 * (Re)defines `navigator.clipboard` in the current document. Idempotent, so it is safe to inject on every page
 * start and finish.
 */
fun WebView.injectClipboardPolyfill() {
    evaluateJavascript(CLIPBOARD_POLYFILL_JS, null)
}

/**
 * Minimal `navigator.clipboard` implementation.
 *
 * - `writeText` prefers the native bridge and falls back to a hidden textarea plus `document.execCommand("copy")`
 *   when the bridge is missing (e.g. the page was opened outside the shell).
 * - `readText` is not bridged: reading the clipboard needs a user-visible paste gesture, and the shell has no use
 *   for it. Rejecting keeps the API shape intact so callers fail with a readable message.
 */
internal val CLIPBOARD_POLYFILL_JS = """
(function () {
  if (window.__openCodeClipboardInstalled) return;
  window.__openCodeClipboardInstalled = true;

  var bridge = window.$CLIPBOARD_BRIDGE_NAME;

  function legacyCopy(text) {
    var area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', 'readonly');
    area.style.cssText = 'position:fixed;top:-1000px;left:0;width:1px;height:1px;opacity:0;';
    document.body.appendChild(area);
    area.select();
    area.setSelectionRange(0, area.value.length);
    var copied = false;
    try { copied = document.execCommand('copy'); } catch (error) { copied = false; }
    document.body.removeChild(area);
    if (!copied) throw new Error('复制失败');
  }

  function writeText(text) {
    return new Promise(function (resolve, reject) {
      var value = String(text == null ? '' : text);
      try {
        if (bridge && typeof bridge.writeText === 'function') bridge.writeText(value);
        else legacyCopy(value);
        resolve();
      } catch (error) {
        try {
          legacyCopy(value);
          resolve();
        } catch (fallbackError) {
          reject(fallbackError);
        }
      }
    });
  }

  function readText() {
    return Promise.reject(new Error('当前客户端不支持读取剪贴板'));
  }

  var api = { writeText: writeText, readText: readText };

  // A secure context (https / localhost) already has a working clipboard; leave it untouched.
  if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') return;

  try {
    Object.defineProperty(navigator, 'clipboard', { value: api, configurable: true, writable: false });
  } catch (error) {
    try { navigator.clipboard = api; } catch (ignored) { /* immutable navigator: give up quietly */ }
  }
})();
""".trimIndent()
