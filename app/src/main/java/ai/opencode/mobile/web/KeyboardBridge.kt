package ai.opencode.mobile.web

import android.webkit.WebView

/**
 * Keeps the web client's composer above the soft keyboard WITHOUT resizing the WebView.
 *
 * Resizing the WebView on every IME frame forces the whole page to reflow and repaint, which is what reads
 * as stutter while the keyboard shows/hides (the opencode chat UI is heavy, so each reflow exceeds the frame
 * budget). Instead the shell keeps the WebView at full height — the page never reflows — and tells the page
 * the keyboard height via [pushKeyboardHeight]. [KEYBOARD_COMPOSER_JS] then lifts the bottom-pinned composer
 * to sit on top of the keyboard. Moving a single fixed element is cheap and does not reflow the message list.
 */
fun WebView.injectKeyboardComposerPolyfill() {
    evaluateJavascript(KEYBOARD_COMPOSER_JS, null)
}

/**
 * Pushes the current keyboard height (in physical px) into the page. `window.__opencodeKeyboard` is installed
 * by [injectKeyboardComposerPolyfill]; the optional call keeps this a no-op until the page is ready.
 */
fun WebView.pushKeyboardHeight(kbPx: Int) {
    evaluateJavascript("window.__opencodeKeyboard?.(${kbPx})", null)
}

/**
 * Installs `window.__opencodeKeyboard(hPx)` which moves the composer so it clears the keyboard.
 *
 * The composer is found by a small heuristic (the bottom-pinned ancestor of the message input), cached, and
 * re-looked-up only when it leaves the DOM (SPA re-render). The cached element makes the per-frame update a
 * single cheap style write instead of a layout-thrashing query. `bottom` is cleared when the keyboard closes
 * so the page's own positioning (including its safe-area padding) takes over again.
 */
internal val KEYBOARD_COMPOSER_JS = """
(function () {
  if (window.__opencodeKeyboardInstalled) return;
  window.__opencodeKeyboardInstalled = true;

  var cachedComposer = null;

  function findComposer() {
    var known = document.querySelector('[data-composer], .composer, form.composer');
    if (known) return known;
    var inputs = document.querySelectorAll(
      'textarea, [contenteditable="true"], input[type="text"], input[type="search"], input[type="url"]'
    );
    for (var i = 0; i < inputs.length; i++) {
      var n = inputs[i];
      while (n && n !== document.body && n !== document.documentElement) {
        var s = getComputedStyle(n);
        var pos = s.position;
        if ((pos === 'fixed' || pos === 'absolute' || pos === 'sticky') && parseFloat(s.bottom) === 0) {
          return n;
        }
        n = n.parentElement;
      }
    }
    return null;
  }

  function getComposer() {
    if (cachedComposer && document.documentElement.contains(cachedComposer)) return cachedComposer;
    cachedComposer = findComposer();
    return cachedComposer;
  }

  window.__opencodeKeyboard = function (hPx) {
    var composer = getComposer();
    if (!composer) return;
    var t = composer.style.transition;
    if (!t || t.indexOf('bottom') === -1) {
      composer.style.transition = t ? t + ', bottom 0.1s ease' : 'bottom 0.1s ease';
    }
    var h = (Number(hPx) || 0) / (window.devicePixelRatio || 1);
    composer.style.bottom = h > 0 ? (h + 'px') : '';
  };
})();
""".trimIndent()
