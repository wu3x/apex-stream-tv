package com.apexstream.tvapp

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.webkit.WebSettings
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
    private val navThrottleMs = 60L // زمن استجابة سريع ومثالي للريموت

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progressBar)

        // تفعيل تسريع العتاد الكامل لـ WebView
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            
            userAgentString = userAgentString + " ApexStreamTVApp/SmartTV Chromecast"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                
                // حقن السكربت الشامل والمراقب الذكي للتنقل
                injectSmartTVNavigation()
                webView.requestFocus()
            }
        }

        webView.loadUrl(targetUrl)
    }

    private fun injectSmartTVNavigation() {
        val js = """
            (function() {
                if (window.__apexNavInjected) {
                    if (window.__apexAcquire) window.__apexAcquire();
                    return;
                }
                window.__apexNavInjected = true;

                // إضافة ستايل مربع التركيز المميز وتغليفه بالكامل
                var FOCUS_CLASS = '__apex_focus';
                var style = document.createElement('style');
                style.id = '__apex_style';
                style.innerHTML =
                    '.' + FOCUS_CLASS + ' {' +
                    '  outline: 4px solid #00E5FF !important;' +
                    '  outline-offset: 3px !important;' +
                    '  transform: scale(1.06) !important;' +
                    '  transition: transform 90ms ease-out, outline 90ms ease-out !important;' +
                    '  box-shadow: 0 0 20px rgba(0, 229, 255, 0.9) !important;' +
                    '  z-index: 999999 !important;' +
                    '  position: relative !important;' +
                    '}';
                document.head.appendChild(style);

                var current = null;

                function isVisible(el) {
                    if (!el || !el.isConnected) return false;
                    var rect = el.getBoundingClientRect();
                    if (rect.width <= 0 || rect.height <= 0) return false;
                    var s = window.getComputedStyle(el);
                    return s.visibility !== 'hidden' && s.display !== 'none' && parseFloat(s.opacity) > 0;
                }

                // استخراج كافة العناصر القابلة للتركيز بما فيها الأزرار الديناميكية كأزرار "تشغيل" و "المفضلة"
                function getFocusable() {
                    var selector = 'button, a, input, select, textarea, [onclick], [role="button"], [role="tab"], [role="link"], .clickable, [tabindex]';
                    var nodes = document.querySelectorAll(selector);
                    var res = [];
                    for (var i = 0; i < nodes.length; i++) {
                        var el = nodes[i];
                        if (el.disabled) continue;
                        var tabindex = el.getAttribute('tabindex');
                        if (tabindex !== null && parseInt(tabindex, 10) < 0) continue;
                        if (isVisible(el)) res.push(el);
                    }
                    return res;
                }

                function setCurrent(el) {
                    if (current === el && el && el.classList.contains(FOCUS_CLASS)) return;
                    if (current) current.classList.remove(FOCUS_CLASS);
                    current = el;
                    if (el) {
                        el.classList.add(FOCUS_CLASS);
                        try { el.focus({preventScroll: true}); } catch (e) {}
                        el.scrollIntoView({block: 'center', inline: 'center', behavior: 'smooth'});
                    }
                }

                function acquireFirst() {
                    var list = getFocusable();
                    if (list.length) {
                        // إعطاء أولوية لأزرار التشغيل أو المفضلة في حال كانت موجودة للشاشة الحالية
                        var primaryBtn = list.find(function(el) {
                            var txt = (el.textContent || '').trim();
                            return /تشغيل|Play|المفضلة|Favorite/i.test(txt);
                        });
                        setCurrent(primaryBtn || list[0]);
                        return true;
                    }
                    return false;
                }
                window.__apexAcquire = acquireFirst;

                function pickBest(list, direction, from) {
                    var cRect = from.getBoundingClientRect();
                    var cx = cRect.left + cRect.width / 2;
                    var cy = cRect.top + cRect.height / 2;
                    var horizontal = (direction === 'left' || direction === 'right');

                    var best = null, bestScore = Infinity;
                    for (var i = 0; i < list.length; i++) {
                        var el = list[i];
                        if (el === from) continue;
                        var r = el.getBoundingClientRect();
                        var ex = r.left + r.width / 2, ey = r.top + r.height / 2;
                        var dx = ex - cx, dy = ey - cy;

                        if (direction === 'right' && dx <= 2) continue;
                        if (direction === 'left' && dx >= -2) continue;
                        if (direction === 'down' && dy <= 2) continue;
                        if (direction === 'up' && dy >= -2) continue;

                        var mainAxis = horizontal ? Math.abs(dx) : Math.abs(dy);
                        var crossAxis = horizontal ? Math.abs(dy) : Math.abs(dx);
                        var score = mainAxis + (crossAxis * 2.2);

                        if (score < bestScore) { bestScore = score; best = el; }
                    }
                    return best;
                }

                window.__apexMove = function(direction) {
                    var list = getFocusable();
                    if (!list.length) return;

                    if (!current || !isVisible(current) || list.indexOf(current) === -1) {
                        acquireFirst();
                        return;
                    }

                    var next = pickBest(list, direction, current);
                    if (next) setCurrent(next);
                };

                window.__apexClick = function() {
                    if (current && isVisible(current)) {
                        current.click();
                        // إعادة توجيه التركيز بعد الضغط للتكيف مع تغير الواجهة الديناميكي
                        setTimeout(acquireFirst, 300);
                        setTimeout(acquireFirst, 800);
                        return;
                    }
                    var v = document.querySelector('video');
                    if (v) { if (v.paused) v.play(); else v.pause(); }
                };

                window.__apexMedia = function(action) {
                    var v = document.querySelector('video');
                    if (!v) return;
                    if (action === 'playpause') { if (v.paused) v.play(); else v.pause(); }
                    if (action === 'seekf') v.currentTime = Math.min(v.duration || Infinity, v.currentTime + 10);
                    if (action === 'seekb') v.currentTime = Math.max(0, v.currentTime - 10);
                };

                // مراقب تغيرات الـ DOM المباشر: يستشعر دخول الفيلم أو تغيير القوائم فوراً بدون إجهاد المعالج
                var observer = new MutationObserver(function(mutations) {
                    if (!current || !isVisible(current)) {
                        acquireFirst();
                    }
                });
                observer.observe(document.body, { childList: true, subtree: true });

                // التفعيل الفوري
                acquireFirst();
                setTimeout(acquireFirst, 500);
                setTimeout(acquireFirst, 1200);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun tryWebPageBack(onResult: (Boolean) -> Unit) {
        val js = """
            (function() {
                // 1. خروج من وضع الشاشة الكاملة للمشغل إذا كان مفعلاً
                if (document.fullscreenElement) {
                    document.exitFullscreen();
                    return true;
                }

                // 2. إغلاق المشغل إن كان يعرض فيديو حالياً دون الرجوع للرئيسية
                var video = document.querySelector('video');
                if (video && !video.paused) {
                    video.pause();
                    // إرسال زر خروج للمشغل لتسريع إغلاق طبقة التشغيل فقط
                    var closeBtn = document.querySelector('.vjs-close-button, .close-player, [class*="close"]');
                    if (closeBtn) closeBtn.click();
                    return true;
                }

                // 3. البحث عن أزرار الرجوع/الإغلاق داخل صفحة الفيلم نفسها كي لا تخرج للرئيسية
                var candidates = Array.prototype.slice.call(
                    document.querySelectorAll('button, a, [onclick], [role="button"]')
                );
                var backBtn = candidates.find(function(el) {
                    var label = ((el.getAttribute('aria-label') || '') + ' ' + (el.title || '') + ' ' + el.textContent).toLowerCase();
                    return /رجوع|إغلاق|خروج|back|close|exit/i.test(label);
                });
                if (backBtn && backBtn.offsetWidth > 0) {
                    backBtn.click();
                    return true;
                }

                return false;
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
