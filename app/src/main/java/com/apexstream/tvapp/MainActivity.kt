package com.apexstream.tvapp

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    private val targetUrl = "https://web.apex-stream.com"

    private var backPressedOnce = false
    private val backHandler = Handler(Looper.getMainLooper())

    private var lastNavTime = 0L
    private val navThrottleMs = 120L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progressBar)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = userAgentString + " ApexStreamTVApp"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = ProgressBar.GONE
                injectSpatialNavigation()
                webView.requestFocus()
            }
        }

        webView.loadUrl(targetUrl)
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

                // While something is actually playing full-screen-ish, don't fight it with
                // grid navigation — just control the video directly, unless a real, visible,
                // on-top control (progress bar, settings button, etc.) shows up after waking it.
                function playerActive() {
                    var v = video();
                    if (!v) return false;
                    var r = v.getBoundingClientRect();
                    if (r.width <= 0 || r.height <= 0) return false;
                    var coverage = (r.width * r.height) / (window.innerWidth * window.innerHeight);
                    return coverage > 0.45;
                }

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

                function pickBest(list, direction, from) {
                    var cRect = from.getBoundingClientRect();
                    var cx = cRect.left + cRect.width / 2;
                    var cy = cRect.top + cRect.height / 2;
                    var horizontal = (direction === 'left' || direction === 'right');

                    // two passes: a strict cone first, then a looser one so we don't ever get
                    // "stuck" needing many presses before something finally matches.
                    var tolerances = [1.4, 3.5];
                    for (var t = 0; t < tolerances.length; t++) {
                        var best = null, bestScore = Infinity;
                        for (var i = 0; i < list.length; i++) {
                            var el = list[i];
                            if (el === from) continue;
                            var r = el.getBoundingClientRect();
                            var ex = r.left + r.width / 2, ey = r.top + r.height / 2;
                            var dx = ex - cx, dy = ey - cy;

                            var valid = false;
                            if (direction === 'right' && dx > 4) valid = true;
                            if (direction === 'left' && dx < -4) valid = true;
                            if (direction === 'down' && dy > 4) valid = true;
                            if (direction === 'up' && dy < -4) valid = true;
                            if (!valid) continue;

                            var mainAxis = horizontal ? Math.abs(dx) : Math.abs(dy);
                            var crossAxis = horizontal ? Math.abs(dy) : Math.abs(dx);
                            if (crossAxis > mainAxis * tolerances[t] + 60) continue;

                            var score = mainAxis + crossAxis * 1.3;
                            if (score < bestScore) { bestScore = score; best = el; }
                        }
                        if (best) return best;
                    }
                    return null;
                }

                function moveFocus(direction) {
                    wake();

                    if (playerActive()) {
                        var onTopControls = getFocusable().filter(isTopmost);
                        if (!onTopControls.length) {
                            clearCurrent();
                            fallbackVideoAction(direction);
                            return;
                        }
                        if (!current || onTopControls.indexOf(current) === -1) {
                            setCurrent(onTopControls[0]);
                            return;
                        }
                        var nextInPlayer = pickBest(onTopControls, direction, current);
                        if (nextInPlayer) setCurrent(nextInPlayer);
                        else fallbackVideoAction(direction);
                        return;
                    }

                    var list = getFocusable();
                    if (!list.length) { clearCurrent(); return; }

                    if (!current || !isVisible(current) || list.indexOf(current) === -1) {
                        setCurrent(list[0]);
                        return;
                    }

                    var next = pickBest(list, direction, current);
                    if (next) setCurrent(next);
                }

                window.__apexMove = moveFocus;

                window.__apexClick = function() {
                    wake();
                    if (current && isVisible(current) && (!playerActive() || isTopmost(current))) {
                        current.click();
                        if (current.tagName === 'INPUT' || current.tagName === 'TEXTAREA') current.focus();
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

                var firstList = getFocusable();
                if (firstList.length) setCurrent(firstList[0]);
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

                var evt = new KeyboardEvent('keydown', {
                    key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true
                });
                document.dispatchEvent(evt);

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
            val now = SystemClock.elapsedRealtime()
            if (now - lastNavTime >= navThrottleMs) {
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
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }

            tryWebPageBack { handled ->
                if (!handled) {
                    if (backPressedOnce) {
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
