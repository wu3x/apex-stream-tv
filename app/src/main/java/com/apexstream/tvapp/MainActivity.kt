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
    private val navThrottleMs = 180L

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

                function getFocusable() {
                    var selector = 'a, button, input, select, textarea, [onclick], [role="button"], .clickable, [tabindex]';
                    return Array.prototype.slice.call(document.querySelectorAll(selector))
                        .filter(function(el) {
                            var rect = el.getBoundingClientRect();
                            var style = window.getComputedStyle(el);
                            return rect.width > 0 && rect.height > 0 &&
                                   style.visibility !== 'hidden' && style.display !== 'none';
                        });
                }

                function currentFocused() {
                    var el = document.activeElement;
                    if (el && el !== document.body) return el;
                    var list = getFocusable();
                    return list.length ? list[0] : null;
                }

                function highlight(el) {
                    var prev = document.querySelector('.__apex_tv_focus');
                    if (prev) prev.classList.remove('__apex_tv_focus');
                    if (el) {
                        el.classList.add('__apex_tv_focus');
                        el.scrollIntoView({block: 'nearest', inline: 'nearest', behavior: 'auto'});
                    }
                }

                var style = document.createElement('style');
                style.innerHTML = '.__apex_tv_focus { outline: 4px solid #00c8ff !important; outline-offset: 2px !important; box-shadow: 0 0 12px #00c8ff !important; }';
                document.head.appendChild(style);

                function moveFocus(direction) {
                    var current = currentFocused();
                    var list = getFocusable();
                    if (!list.length) return;
                    if (!current || list.indexOf(current) === -1) {
                        current = list[0];
                        current.focus();
                        highlight(current);
                        return;
                    }
                    var cRect = current.getBoundingClientRect();
                    var cx = cRect.left + cRect.width / 2;
                    var cy = cRect.top + cRect.height / 2;

                    var best = null, bestScore = Infinity;
                    list.forEach(function(el) {
                        if (el === current) return;
                        var r = el.getBoundingClientRect();
                        var ex = r.left + r.width / 2;
                        var ey = r.top + r.height / 2;
                        var dx = ex - cx, dy = ey - cy;

                        var valid = false;
                        if (direction === 'right' && dx > 5) valid = true;
                        if (direction === 'left' && dx < -5) valid = true;
                        if (direction === 'down' && dy > 5) valid = true;
                        if (direction === 'up' && dy < -5) valid = true;
                        if (!valid) return;

                        var mainAxis = (direction === 'left' || direction === 'right') ? Math.abs(dx) : Math.abs(dy);
                        var crossAxis = (direction === 'left' || direction === 'right') ? Math.abs(dy) : Math.abs(dx);
                        var score = mainAxis + crossAxis * 2;
                        if (score < bestScore) { bestScore = score; best = el; }
                    });

                    if (best) {
                        best.focus();
                        highlight(best);
                    }
                }

                window.__apexMove = moveFocus;
                window.__apexClick = function() {
                    var el = currentFocused();
                    if (el) {
                        el.click();
                        if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') el.focus();
                    }
                };

                var first = getFocusable()[0];
                if (first) { first.focus(); highlight(first); }
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
