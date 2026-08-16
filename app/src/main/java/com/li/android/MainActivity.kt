package com.li.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 主界面：全屏 WebView 加载 LI（assets/index.html）。
 *
 * 关键机制：
 *   1. 注入 AndroidBridge（JS → Native 桥），让网页能回传"用户发消息"事件
 *   2. 页面加载完成后注入 MutationObserver 脚本，监听 .chat-bubble--user 出现
 *   3. 注册 WorkManager 周期任务（推送调度）
 *   4. 请求通知权限 + 电池优化豁免
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: AndroidBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 注册后台推送任务（幂等，重复调用只替换）
        PushScheduler.enqueue(this)

        val prefs = AppPreferences(this)
        bridge = AndroidBridge { AppPreferences(this) }

        webView = findViewById(R.id.webview)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        // 注入 JS → Native 桥接对象（网页端通过 window.AndroidBridge.onUserMessage() 调用）
        webView.addJavascriptInterface(bridge, "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            // 页面加载完毕后注入 DOM 观察器脚本（不修改 LI 源码）
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectChatObserver(view)
            }
        }

        // LI 单文件产物放 assets/index.html，离线加载，不联网
        webView.loadUrl("file:///android_asset/index.html")

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        requestPostNotificationPermission()
        requestIgnoreBatteryOptimizations()
    }

    /**
     * 向 LI 网页注入一段不可见的 JS 脚本。
     * 脚本功能：用 MutationObserver 监听 DOM 变更，
     * 当检测到新的 .chat-bubble--user（用户消息气泡）出现时，
     * 调用 window.AndroidBridge.onUserMessage() 通知原生层"用户刚发了消息"。
     *
     * 为什么用注入而非改 LI 源码：
     *   - LI 的 index.html 是构建产物，每次重新构建会覆盖手动修改
     *   - 注入方式与 LI 版本解耦——只要气泡类名不变就能工作
     *   - 用户不需要维护两份 HTML（一份给浏览器、一份给 App）
     */
    private fun injectChatObserver(webView: WebView) {
        // language=JavaScript
        val js = """
            (function() {
                if (window.__liBridgeInjected) return;
                window.__liBridgeInjected = true;

                // 防抖：同一秒内多次 DOM 变更只触发一次桥接调用
                var lastReported = 0;
                function reportIfNew() {
                    var now = Date.now();
                    if (now - lastReported < 1000) return;
                    lastReported = now;
                    try {
                        window.AndroidBridge.onUserMessage();
                    } catch(e) {
                        // AndroidBridge 不可用时静默失败（如在普通浏览器中打开）
                    }
                }

                // 策略1：MutationObserver 监听全局 DOM 新增节点
                var observer = new MutationObserver(function(mutations) {
                    for (var i = 0; i < mutations.length; i++) {
                        var added = mutations[i].addedNodes;
                        for (var j = 0; j < added.length; j++) {
                            if (added[j].nodeType === 1 && // Element node
                                (added[j].matches && added[j].matches('.chat-bubble--user') ||
                                 added[j].querySelector && added[j].querySelector('.chat-bubble--user'))) {
                                reportIfNew();
                            }
                        }
                    }
                });
                observer.observe(document.body || document.documentElement, { childList: true, subtree: true });

                // 策略2（兜底）：拦截 fetch 请求中的聊天发送
                // LI 用 sendMessage() → fetch(post /v1/chat/completions) 发送消息
                // 通过监听 fetch 的 POST 请求来捕获用户主动发起的对话
                var origFetch = window.fetch;
                window.fetch = function() {
                    var args = arguments;
                    var url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url) || '';
                    // 只关心 POST 到 LLM 接口的请求（说明用户在发消息）
                    if (args[1] && args[1].method === 'POST' &&
                        (url.indexOf('/chat/completions') !== -1 ||
                         url.indexOf('/completions') !== -1)) {
                        reportIfNew();
                    }
                    return origFetch.apply(this, args);
                };
            })();
        """
        webView.evaluateJavascript(js, null)
    }

    // Android 13+ 必须用户点允许才能弹通知
    private fun requestPostNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
                )
            }
        }
    }

    // 关键：不申请电池豁免，国产手机分分钟把后台任务杀掉，推送就失灵
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    @Deprecated("使用 OnBackPressedDispatcher")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
