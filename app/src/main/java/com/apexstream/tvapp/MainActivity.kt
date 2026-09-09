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

                // ---------- floating focus ring (independent of the page's own DOM/CSS) ----------
                var ring = document.createElement('div');
                ring.id = '__apex_focus_ring';
                ring.style.cssText = [
                    'position:fixed', 'left:0', 'top:0', 'width:0', 'height:0',
                    'border:4px solid #00c8ff', 'border-radius:8px',
                    'box-shadow:0 0 16px 2px rgba(0,200,255,0.9)',
                    'pointer-events:none', 'z-index:2147483647',
                    'transition:left 90ms ease-out, top 90ms ease-out, width 90ms ease-out, height 90ms ease-out, opacity 90ms',
                    'opacity:0'
                ].join(';');
                document.documentElement.appendChild(ring);

                var current = null;

                function isVisible(el) {
                    if (!el || !el.isConnected) return false;
                    var rect = el.getBoundingClientRect();
                    if (rect.width <= 0 || rect.height <= 0) return false;
                    var style = window.getComputedStyle(el);
                    if (style.visibility === 'hidden' || style.display === 'none') return false;
                    if (parseFloat(style.opacity) === 0) return false;
                    if (style.pointerEvents === 'none') return false;
                    return true;
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

                function updateRing() {
                    if (current && isVisible(current)) {
                        var r = current.getBoundingClientRect();
                        ring.style.left = (r.left - 4) + 'px';
                        ring.style.top = (r.top - 4) + 'px';
                        ring.style.width = (r.width) + 'px';
                        ring.style.height = (r.height) + 'px';
                        ring.style.opacity = '1';
                    } else {
                        ring.style.opacity = '0';
                    }
                }

                function setCurrent(el, scroll) {
                    current = el;
                    if (el) {
                        try { el.focus({preventScroll: true}); } catch (e) { el.focus(); }
                        if (scroll !== false) {
                            el.scrollIntoView({block: 'nearest', inline: 'nearest', behavior: 'auto'});
                        }
                    }
                    updateRing();
                }

                // Keep the ring glued to its target even during animations/scroll/layout shifts.
                (function ringLoop() {
                    updateRing();
                    requestAnimationFrame(ringLoop);
                })();

                // If the page mutates (e.g. player controls fade in/out), make sure our
                // tracked element is still valid; otherwise silently reacquire the nearest one.
                var mo = new MutationObserver(function() {
                    if (current && !isVisible(current)) {
                        var list = getFocusable();
                        if (list.length) setCurrent(list[0], false);
                        else updateRing();
                    }
                });
                mo.observe(document.body, { attributes: true, childList: true, subtree: true });

                // Many players hide their controls after inactivity and only reveal them on
                // mouse movement. Simulate that so the remote can always reach them.
                function wake() {
                    var video = document.querySelector('video');
                    var target = (video && video.getBoundingClientRect().width > 0) ? video : document.body;
                    var rect = target.getBoundingClientRect();
                    var x = rect.left + rect.width / 2, y = rect.top + rect.height / 2;
                    ['pointermove', 'mousemove', 'mouseover'].forEach(function(type) {
                        try {
                            target.dispatchEvent(new MouseEvent(type, {
                                bubbles: true, cancelable: true, clientX: x, clientY: y
                            }));
                        } catch (e) {}
                    });
                }

                function activeVideo() {
                    var video = document.querySelector('video');
                    return video || null;
                }

                function moveFocus(direction) {
                    wake();

                    var list = getFocusable();

                    if (!current || !isVisible(current) || list.indexOf(current) === -1) {
                        if (list.length) { setCurrent(list[0]); return; }
                        current = null;
                        updateRing();
                        // no interactive controls found at all -> fall back to controlling the video directly
                        fallbackVideoAction(direction);
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
                        if (direction === 'right' && dx > 4) valid = true;
                        if (direction === 'left' && dx < -4) valid = true;
                        if (direction === 'down' && dy > 4) valid = true;
                        if (direction === 'up' && dy < -4) valid = true;
                        if (!valid) return;

                        var mainAxis = (direction === 'left' || direction === 'right') ? Math.abs(dx) : Math.abs(dy);
                        var crossAxis = (direction === 'left' || direction === 'right') ? Math.abs(dy) : Math.abs(dx);
                        // heavily penalize candidates far off the main axis so movement stays predictable
                        if (crossAxis > mainAxis * 2.5 + 40) return;
                        var score = mainAxis + crossAxis * 1.6;
                        if (score < bestScore) { bestScore = score; best = el; }
                    });

                    if (best) {
                        setCurrent(best);
                    } else {
                        // nothing focusable further in that direction -> treat as a media control
                        fallbackVideoAction(direction);
                    }
                }

                function fallbackVideoAction(direction) {
                    var video = activeVideo();
                    if (!video) return;
                    if (direction === 'left') video.currentTime = Math.max(0, video.currentTime - 10);
                    if (direction === 'right') video.currentTime = Math.min(video.duration || Infinity, video.currentTime + 10);
                    if (direction === 'up') video.volume = Math.min(1, video.volume + 0.1);
                    if (direction === 'down') video.volume = Math.max(0, video.volume - 0.1);
                }

                window.__apexMove = moveFocus;

                window.__apexClick = function() {
                    wake();
                    if (current && isVisible(current)) {
                        current.click();
                        if (current.tagName === 'INPUT' || current.tagName === 'TEXTAREA') current.focus();
                        return;
                    }
                    var video = activeVideo();
                    if (video) {
                        if (video.paused) video.play(); else video.pause();
                    }
                };

                window.__apexMedia = function(action) {
                    wake();
                    var video = activeVideo();
                    if (!video) return;
                    if (action === 'playpause') { if (video.paused) video.play(); else video.pause(); }
                    if (action === 'play') video.play();
                    if (action === 'pause') video.pause();
                    if (action === 'seekf') video.currentTime = Math.min(video.duration || Infinity, video.currentTime + 10);
                    if (action === 'seekb') video.currentTime = Math.max(0, video.currentTime - 10);
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
