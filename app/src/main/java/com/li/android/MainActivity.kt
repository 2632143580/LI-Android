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
import org.json.JSONObject
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
        bridge = AndroidBridge(this) { AppPreferences(this) }

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
                injectConfigScript(view)
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

    /**
     * 向 LI 网页注入「配置同步 + 数据面板」脚本（不修改 LI 源码，与版本解耦）。
     *
     * 做四件事：
     *   1) 统计 LI 的 localStorage 占用 / 聊天节点数，回传给原生（数据面板显示用）。
     *   2) 执行 pendingWebAction（clear_chat / reset_all / export）—— 设置页写入，这里落地。
     *   3) 若开启「填一次注入两边」，把推送 LLM 写进 LI 的 localStorage，前台聊天免再填。
     *   4) 若填了 mimo 云端语音 Key，写进 LI 的 ttsCloud 并切到云端语音源。
     *
     * 原生配置以 JSON 直接内联进脚本（var NATIVE = {...}），避免字符串转义问题。
     * 写入 localStorage 后如需让 LI 立即生效会 location.reload()；靠「内容是否一致」做幂等防死循环。
     */
    private fun injectConfigScript(webView: WebView) {
        val prefs = AppPreferences(this)
        val nativeConfig = JSONObject().apply {
            put("syncToWeb", prefs.syncToWeb)
            put("pendingAction", prefs.pendingWebAction)
            put("push", JSONObject().apply {
                put("apiKey", prefs.apiKey)
                put("apiUrl", prefs.baseUrl)
                put("model", prefs.model)
            })
            put("tts", JSONObject().apply {
                put("apiKey", prefs.ttsApiKey)
                put("baseUrl", prefs.ttsBaseUrl)
                put("model", prefs.ttsModel)
            })
        }.toString()

        // language=JavaScript
        val js = """
            (function() {
                if (window.__liCfgInjected) return;
                window.__liCfgInjected = true;

                var NATIVE = $nativeConfig;
                var STORAGE_KEY = 'liChatData_v2';

                function readData() {
                    try { return JSON.parse(localStorage.getItem(STORAGE_KEY)); }
                    catch (e) { return null; }
                }
                function writeData(d) { localStorage.setItem(STORAGE_KEY, JSON.stringify(d)); }
                function countNodes(n) {
                    if (!n || typeof n !== 'object') return 0;
                    var c = 1;
                    if (n.children) for (var i = 0; i < n.children.length; i++) c += countNodes(n.children[i]);
                    return c;
                }

                // 1) 存储统计回传
                (function () {
                    var total = 0, keys = 0, nodes = 0, ttsSource = '?', ttsCfg = false;
                    for (var i = 0; i < localStorage.length; i++) {
                        var k = localStorage.key(i); var v = localStorage.getItem(k);
                        total += (k ? k.length : 0) + (v ? v.length : 0); keys++;
                    }
                    var d = readData();
                    if (d && d.chatTree) nodes = countNodes(d.chatTree);
                    if (d && d.settings) {
                        ttsSource = d.settings.ttsSource || '?';
                        ttsCfg = !!(d.settings.ttsCloud && d.settings.ttsCloud.apiKey);
                    }
                    try { window.AndroidBridge.onStorageStats(JSON.stringify({
                        totalBytes: total, keyCount: keys, chatNodes: nodes,
                        ttsSource: ttsSource, ttsCloudConfigured: ttsCfg
                    })); } catch (e) {}
                })();

                // 2) 待执行动作
                if (NATIVE.pendingAction === 'clear_chat') {
                    var d = readData() || { settings: {} };
                    d.chatTree = { role: 'system', content: '', children: [] };
                    d.msgIdCounter = 0;
                    writeData(d);
                    try { window.AndroidBridge.onWebActionDone(); } catch (e) {}
                    location.reload(); return;
                }
                if (NATIVE.pendingAction === 'reset_all') {
                    localStorage.removeItem(STORAGE_KEY);
                    try { window.AndroidBridge.resetAllNative(); } catch (e) {}
                    try { window.AndroidBridge.onWebActionDone(); } catch (e) {}
                    location.reload(); return;
                }
                if (NATIVE.pendingAction === 'export') {
                    var raw = localStorage.getItem(STORAGE_KEY) || '{}';
                    try { window.AndroidBridge.exportChat(raw); } catch (e) {}
                    try { window.AndroidBridge.onWebActionDone(); } catch (e) {}
                }

                // 3) 填一次注入两边：把推送 LLM 同步给 LI 前台聊天
                if (NATIVE.syncToWeb && NATIVE.push && NATIVE.push.apiKey) {
                    var d = readData();
                    if (d && d.settings) {
                        var s = d.settings;
                        if (s.apiKey !== NATIVE.push.apiKey || s.apiUrl !== NATIVE.push.apiUrl || s.model !== NATIVE.push.model) {
                            s.apiKey = NATIVE.push.apiKey;
                            s.apiUrl = NATIVE.push.apiUrl;
                            s.model = NATIVE.push.model;
                            writeData(d); location.reload(); return;
                        }
                    }
                }

                // 4) mimo 云端语音
                if (NATIVE.tts && NATIVE.tts.apiKey) {
                    var d = readData();
                    if (d && d.settings) {
                        d.settings.ttsSource = 'cloud';
                        d.settings.ttsCloud = d.settings.ttsCloud || {};
                        d.settings.ttsCloud.apiKey = NATIVE.tts.apiKey;
                        d.settings.ttsCloud.baseUrl = NATIVE.tts.baseUrl || 'https://api.xiaomimimo.com/v1';
                        d.settings.ttsCloud.model = NATIVE.tts.model || 'mimo-v2.5-tts';
                        writeData(d); location.reload(); return;
                    }
                }
            })();
        """
        webView.evaluateJavascript(js, null)
    }
}
