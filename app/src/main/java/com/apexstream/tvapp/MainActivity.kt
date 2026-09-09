package com.apexstream.tvapp

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var dPad: DPadIndicatorView

    private val targetUrl = "https://web.apex-stream.com"

    private var backPressedOnce = false
    private val backHandler = Handler(Looper.getMainLooper())

    private var lastNavTime = 0L
    private val navThrottleMs = 90L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progressBar)

        // Force a hardware-accelerated layer for smoother scrolling/animation while the
        // video decoder is also busy — helps a lot on weaker boxes like base Chromecast.
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString + " ApexStreamTVApp"
        }

        // Small semi-transparent D-pad overlay, bottom-left, that flashes the pressed
        // direction — pure native visual feedback, independent of the website.
        dPad = DPadIndicatorView(this)
        val dPadSizePx = dpToPx(96)
        val dPadParams = FrameLayout.LayoutParams(dPadSizePx, dPadSizePx).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = dpToPx(24)
            bottomMargin = dpToPx(24)
        }
        (findViewById<View>(android.R.id.content) as ViewGroup).addView(dPad, dPadParams)

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = ProgressBar.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectContentReadyWatcher()
                // Safety net: if our "is it ready?" heuristic never fires for some reason,
                // don't leave the user staring at a spinner forever.
                backHandler.postDelayed({ revealContent() }, 6000)
            }
        }

        webView.loadUrl(targetUrl)
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    private inner class AndroidBridge {
        @JavascriptInterface
        fun contentReady() {
            runOnUiThread { revealContent() }
        }
    }

    private fun revealContent() {
        if (progressBar.visibility == ProgressBar.GONE) return
        progressBar.visibility = ProgressBar.GONE
        injectSpatialNavigation()
        webView.requestFocus()
    }

    /**
     * The site is a single-page app: the raw HTML finishes loading almost instantly, long
     * before it has actually fetched and rendered real content. Injecting our navigation (or
     * hiding the loading screen) before that point means grabbing an empty/half-built page.
     * This waits for real interactive content to show up, then tells Android it's safe to go.
     */
    private fun injectContentReadyWatcher() {
        val js = """
            (function() {
                if (window.__apexReadyWatcherStarted) return;
                window.__apexReadyWatcherStarted = true;
                var attempts = 0;
                function looksReady() {
                    var interactive = document.querySelectorAll('a, button, [role="button"], [tabindex]').length;
                    var media = document.querySelectorAll('img[src], video').length;
                    return interactive > 3 || media > 0;
                }
                function check() {
                    attempts++;
                    if (looksReady() || attempts > 40) {
                        if (window.AndroidBridge && window.AndroidBridge.contentReady) {
                            window.AndroidBridge.contentReady();
                        }
                        return;
                    }
                    setTimeout(check, 150);
                }
                check();
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectSpatialNavigation() {
        val js = """
            (function() {
                if (window.__apexNavInjected) return;
                window.__apexNavInjected = true;

                // Lightweight, event-driven navigation only — no per-frame loops and no
                // whole-document MutationObservers, since those are what made the previous
                // version sluggish on weaker hardware (fighting the video decoder for CPU).

                var FOCUS_CLASS = '__apex_focus';
                var style = document.createElement('style');
                style.innerHTML =
                    '.' + FOCUS_CLASS + ' {' +
                    '  transform: scale(1.08) !important;' +
                    '  transition: transform 120ms ease-out, box-shadow 120ms ease-out !important;' +
                    '  box-shadow: 0 0 0 3px rgba(255,255,255,0.95), 0 10px 26px rgba(0,0,0,0.55) !important;' +
                    '  z-index: 3 !important;' +
                    '  position: relative !important;' +
                    '}';
                document.head.appendChild(style);

                var current = null;

                function clearCurrent() {
                    if (current) current.classList.remove(FOCUS_CLASS);
                    current = null;
                }

                function isVisible(el) {
                    if (!el || !el.isConnected) return false;
                    var rect = el.getBoundingClientRect();
                    if (rect.width <= 0 || rect.height <= 0) return false;
                    var s = window.getComputedStyle(el);
                    if (s.visibility === 'hidden' || s.display === 'none') return false;
                    if (parseFloat(s.opacity) === 0) return false;
                    if (s.pointerEvents === 'none') return false;
                    return true;
                }

                // Cheap "is this actually on top" check (only ever called on a handful of
                // elements per keypress, never on a timer/observer).
                function isTopmost(el) {
                    var r = el.getBoundingClientRect();
                    var x = r.left + r.width / 2, y = r.top + r.height / 2;
                    var hit = document.elementFromPoint(x, y);
                    return !!hit && (hit === el || el.contains(hit) || hit.contains(el));
                }

                function getFocusable() {
                    var selector = 'a, button, input, select, textarea, [onclick], [role="button"], [role="tab"], [role="link"], .clickable, [tabindex]';
                    return Array.prototype.slice.call(document.querySelectorAll(selector))
                        .filter(function(el) {
                            if (el.disabled) return false;
                            if (el.getAttribute('aria-hidden') === 'true') return false;
                            var tabindex = el.getAttribute('tabindex');
                            if (tabindex !== null && parseInt(tabindex, 10) < 0) return false;
                            return isVisible(el);
                        });
                }

                function setCurrent(el) {
                    if (current === el) return;
                    if (current) current.classList.remove(FOCUS_CLASS);
                    current = el;
                    if (el) {
                        el.classList.add(FOCUS_CLASS);
                        try { el.focus({preventScroll: true}); } catch (e) {}
                        el.scrollIntoView({block: 'nearest', inline: 'nearest', behavior: 'auto'});
                    }
                }

                function video() { return document.querySelector('video'); }

                function wake() {
                    var v = video();
                    var target = (v && v.getBoundingClientRect().width > 0) ? v : document.body;
                    var rect = target.getBoundingClientRect();
                    var x = rect.left + rect.width / 2, y = rect.top + rect.height / 2;
                    ['pointermove', 'mousemove', 'mouseover'].forEach(function(type) {
                        try {
                            target.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, clientX: x, clientY: y }));
                        } catch (e) {}
                    });
                }

                function fallbackVideoAction(direction) {
                    var v = video();
                    if (!v) return;
                    if (direction === 'left') v.currentTime = Math.max(0, v.currentTime - 10);
                    if (direction === 'right') v.currentTime = Math.min(v.duration || Infinity, v.currentTime + 10);
                    if (direction === 'up') v.volume = Math.min(1, v.volume + 0.1);
                    if (direction === 'down') v.volume = Math.max(0, v.volume - 0.1);
                }

                // Real spatial-navigation scoring (the same idea LG/Samsung/Chromium's own TV
                // navigation uses): prefer candidates that overlap the current element along
                // the cross-axis (i.e. stay in the same visual column/row) over ones that are
                // merely close by raw distance. This is what makes movement feel aligned and
                // predictable instead of "jumping" to the nearest diagonal neighbor.
                function overlap(aStart, aEnd, bStart, bEnd) {
                    return Math.max(0, Math.min(aEnd, bEnd) - Math.max(aStart, bStart));
                }

                function candidateScore(from, r, direction) {
                    var horizontal = (direction === 'left' || direction === 'right');
                    var primary, ov, span;

                    if (direction === 'down') {
                        primary = r.top - from.bottom;
                        ov = overlap(from.left, from.right, r.left, r.right);
                        span = Math.max(from.right - from.left, r.right - r.left);
                    } else if (direction === 'up') {
                        primary = from.top - r.bottom;
                        ov = overlap(from.left, from.right, r.left, r.right);
                        span = Math.max(from.right - from.left, r.right - r.left);
                    } else if (direction === 'right') {
                        primary = r.left - from.right;
                        ov = overlap(from.top, from.bottom, r.top, r.bottom);
                        span = Math.max(from.bottom - from.top, r.bottom - r.top);
                    } else {
                        primary = from.left - r.right;
                        ov = overlap(from.top, from.bottom, r.top, r.bottom);
                        span = Math.max(from.bottom - from.top, r.bottom - r.top);
                    }

                    if (primary < -4) return null; // not actually in that direction

                    var overlapRatio = span > 0 ? Math.min(1, ov / span) : 0;
                    // Fully aligned candidates keep their raw distance as the score; poorly
                    // aligned ones get penalized in proportion to how misaligned they are.
                    return Math.max(primary, 1) * (1 + (1 - overlapRatio) * 2.2);
                }

                function pickBest(list, direction, fromEl) {
                    var from = fromEl.getBoundingClientRect();
                    var scored = [];
                    for (var i = 0; i < list.length; i++) {
                        var el = list[i];
                        if (el === fromEl) continue;
                        var s = candidateScore(from, el.getBoundingClientRect(), direction);
                        if (s !== null) scored.push({ el: el, score: s });
                    }
                    scored.sort(function(a, b) { return a.score - b.score; });

                    // Only verify "is this actually clickable/on top" for the best few — far
                    // cheaper than checking every single candidate on every keypress.
                    for (var k = 0; k < Math.min(5, scored.length); k++) {
                        if (isTopmost(scored[k].el)) return scored[k].el;
                    }
                    return scored.length ? scored[0].el : null;
                }

                function firstTopmost(list) {
                    for (var i = 0; i < Math.min(list.length, 30); i++) {
                        if (isTopmost(list[i])) return list[i];
                    }
                    return list.length ? list[0] : null;
                }

                function acquireFirst() {
                    var list = getFocusable();
                    var pick = firstTopmost(list);
                    if (pick) { setCurrent(pick); return true; }
                    clearCurrent();
                    return false;
                }

                function scrollableAncestor(el) {
                    var node = el;
                    while (node && node !== document.body && node !== document.documentElement) {
                        var s = window.getComputedStyle(node);
                        var canY = /(auto|scroll)/.test(s.overflowY) && node.scrollHeight > node.clientHeight + 2;
                        var canX = /(auto|scroll)/.test(s.overflowX) && node.scrollWidth > node.clientWidth + 2;
                        if (canY || canX) return node;
                        node = node.parentElement;
                    }
                    return document.scrollingElement || document.documentElement;
                }

                function nudgeScroll(direction, from) {
                    var container = scrollableAncestor(from);
                    var step = 260;
                    var dx = (direction === 'right') ? step : (direction === 'left') ? -step : 0;
                    var dy = (direction === 'down') ? step : (direction === 'up') ? -step : 0;
                    try { container.scrollBy({ left: dx, top: dy, behavior: 'auto' }); }
                    catch (e) { container.scrollLeft += dx; container.scrollTop += dy; }
                }

                function moveFocus(direction) {
                    wake();

                    var list = getFocusable();
                    if (!list.length) {
                        clearCurrent();
                        fallbackVideoAction(direction);
                        return;
                    }

                    if (!current || !isVisible(current) || list.indexOf(current) === -1) {
                        var pick = firstTopmost(list);
                        if (pick) setCurrent(pick);
                        return;
                    }

                    var next = pickBest(list, direction, current);
                    if (next) {
                        setCurrent(next);
                        return;
                    }

                    // Nothing focusable that way yet — the site may lazily render more
                    // content as you scroll (common in card carousels). Nudge the scroll
                    // and take one more look before giving up, instead of forcing the user
                    // to mash the button several times for the same effect.
                    var fromEl = current;
                    nudgeScroll(direction, fromEl);
                    setTimeout(function() {
                        var list2 = getFocusable();
                        var again = pickBest(list2, direction, fromEl);
                        if (again) setCurrent(again);
                        else fallbackVideoAction(direction);
                    }, 160);
                }

                window.__apexMove = moveFocus;

                window.__apexClick = function() {
                    wake();
                    if (current && isVisible(current) && isTopmost(current)) {
                        current.click();
                        if (current.tagName === 'INPUT' || current.tagName === 'TEXTAREA') current.focus();
                        // the click likely navigated to a new screen/route; drop the stale
                        // reference and grab whatever is focusable there shortly after.
                        clearCurrent();
                        setTimeout(acquireFirst, 250);
                        return;
                    }
                    var v = video();
                    if (v) { if (v.paused) v.play(); else v.pause(); }
                };

                window.__apexMedia = function(action) {
                    wake();
                    var v = video();
                    if (!v) return;
                    if (action === 'playpause') { if (v.paused) v.play(); else v.pause(); }
                    if (action === 'play') v.play();
                    if (action === 'pause') v.pause();
                    if (action === 'seekf') v.currentTime = Math.min(v.duration || Infinity, v.currentTime + 10);
                    if (action === 'seekb') v.currentTime = Math.max(0, v.currentTime - 10);
                };

                // The site is an SPA: it changes "pages" via the History API without a real
                // reload. popstate alone only fires on back/forward — most in-app navigation
                // (clicking into a movie, opening a player) calls pushState/replaceState
                // directly, which never fires popstate. Patch both so we always notice and
                // re-grab focus on whatever the new screen actually shows.
                if (!window.__apexHistoryPatched) {
                    window.__apexHistoryPatched = true;
                    var onNav = function() {
                        clearCurrent();
                        setTimeout(acquireFirst, 250);
                    };
                    ['pushState', 'replaceState'].forEach(function(fn) {
                        var orig = history[fn];
                        history[fn] = function() {
                            var ret = orig.apply(this, arguments);
                            onNav();
                            return ret;
                        };
                    });
                    window.addEventListener('popstate', onNav);
                }

                acquireFirst();
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    /**
     * Tries to let the web page handle the back action itself:
     * - exits native fullscreen (common for <video> fullscreen)
     * - dispatches an Escape keydown (common convention to close players/modals)
     * - pauses any playing <video>
     * Returns (via callback) whether the page reported that something was closed.
     */
    private fun tryWebPageBack(onResult: (Boolean) -> Unit) {
        val js = """
            (function() {
                var handled = false;

                if (document.fullscreenElement) {
                    document.exitFullscreen();
                    handled = true;
                }

                // Prefer the app's own visible back/close control (if any) so ITS router
                // decides where "back" goes, instead of guessing from browser history —
                // this is what correctly lands back on the movie/series page instead of Home.
                function isTopmostEl(el) {
                    var r = el.getBoundingClientRect();
                    var x = r.left + r.width / 2, y = r.top + r.height / 2;
                    var hit = document.elementFromPoint(x, y);
                    return !!hit && (hit === el || el.contains(hit) || hit.contains(el));
                }

                var candidates = Array.prototype.slice.call(
                    document.querySelectorAll('a, button, [onclick], [role="button"], [tabindex]')
                );
                var backBtn = candidates.find(function(el) {
                    var label = ((el.getAttribute('aria-label') || '') + ' ' + (el.title || '') + ' ' + el.textContent).toLowerCase();
                    return /رجوع|خروج|إغلاق|back|close|exit/.test(label);
                });
                if (!backBtn) {
                    backBtn = candidates.find(function(el) {
                        var r = el.getBoundingClientRect();
                        return r.top >= 0 && r.top < 70 && r.width > 0 && r.width < 90 && r.height < 90;
                    });
                }
                if (backBtn && isTopmostEl(backBtn)) {
                    backBtn.click();
                    handled = true;
                }

                if (!handled) {
                    var evt = new KeyboardEvent('keydown', {
                        key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true
                    });
                    document.dispatchEvent(evt);
                }

                var video = document.querySelector('video');
                if (video && !video.paused) {
                    video.pause();
                    handled = true;
                }

                return handled;
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            onResult(result == "true")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            else -> null
        }
        if (direction != null) {
            dPad.flash(direction)
            val now = SystemClock.elapsedRealtime()
            val isRepeat = (event?.repeatCount ?: 0) > 0
            if (!isRepeat || now - lastNavTime >= navThrottleMs) {
                lastNavTime = now
                webView.evaluateJavascript("window.__apexMove && window.__apexMove('$direction');", null)
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            webView.evaluateJavascript("window.__apexClick && window.__apexClick();", null)
            return true
        }
        val mediaAction = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "playpause"
            KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "seekf"
            KeyEvent.KEYCODE_MEDIA_REWIND -> "seekb"
            else -> null
        }
        if (mediaAction != null) {
            webView.evaluateJavascript("window.__apexMedia && window.__apexMedia('$mediaAction');", null)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            tryWebPageBack { handled ->
                if (!handled) {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else if (backPressedOnce) {
                        finish()
                    } else {
                        backPressedOnce = true
                        Toast.makeText(this, "اضغط رجوع مرة ثانية للخروج", Toast.LENGTH_SHORT).show()
                        backHandler.postDelayed({ backPressedOnce = false }, 2000)
                    }
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
