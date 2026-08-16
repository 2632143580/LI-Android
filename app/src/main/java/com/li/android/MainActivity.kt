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
 *   3. 注入「配置同步 + 数据面板」脚本（写入 localStorage 时严格幂等，且最多 reload 2 次，杜绝闪烁死循环）
 *   4. 注册 WorkManager 周期任务（推送调度）
 *   5. 请求通知权限 + 电池优化豁免
 *   6. 注册 AppBus.refreshStats，供设置页「刷新」重新统计
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: AndroidBridge
    private var webViewDestroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        PushScheduler.enqueue(this)

        // 用 applicationContext，避免 WebView/桥持有 Activity 导致泄漏
        bridge = AndroidBridge(applicationContext) { AppPreferences(this) }

        webView = findViewById(R.id.webview)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(bridge, "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // 只在本体文档（LI 入口）注入，避免子框架/about:blank 误触发
                if (url == "file:///android_asset/index.html") {
                    injectChatObserver(view)
                    injectConfigScript(view)
                }
            }
        }

        webView.loadUrl("file:///android_asset/index.html")

        // 设置页点「刷新」时，重新向 WebView 注入统计脚本，拿到最新 LI 存储统计
        AppBus.refreshStats = {
            runOnUiThread { if (!webViewDestroyed) injectConfigScript(webView) }
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        requestPostNotificationPermission()
        requestIgnoreBatteryOptimizations()
    }

    override fun onDestroy() {
        webViewDestroyed = true
        AppBus.refreshStats = null
        super.onDestroy()
    }

    /**
     * 向 LI 网页注入监听脚本（不修改 LI 源码）：
     * MutationObserver 监听 .chat-bubble--user，或兜底拦截 fetch POST /completions。
     */
    private fun injectChatObserver(webView: WebView) {
        if (webViewDestroyed) return
        val js = """
            (function() {
                if (window.__liBridgeInjected) return;
                window.__liBridgeInjected = true;

                var lastReported = 0;
                function reportIfNew() {
                    var now = Date.now();
                    if (now - lastReported < 1000) return;
                    lastReported = now;
                    try { window.AndroidBridge.onUserMessage(); } catch(e) {}
                }

                var observer = new MutationObserver(function(mutations) {
                    for (var i = 0; i < mutations.length; i++) {
                        var added = mutations[i].addedNodes;
                        for (var j = 0; j < added.length; j++) {
                            if (added[j].nodeType === 1 &&
                                (added[j].matches && added[j].matches('.chat-bubble--user') ||
                                 added[j].querySelector && added[j].querySelector('.chat-bubble--user'))) {
                                reportIfNew();
                            }
                        }
                    }
                });
                observer.observe(document.body || document.documentElement, { childList: true, subtree: true });

                var origFetch = window.fetch;
                window.fetch = function() {
                    var args = arguments;
                    var url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url) || '';
                    if (args[1] && args[1].method === 'POST' &&
                        (url.indexOf('/chat/completions') !== -1 || url.indexOf('/completions') !== -1)) {
                        reportIfNew();
                    }
                    return origFetch.apply(this, args);
                };
            })();
        """
        webView.evaluateJavascript(js, null)
    }

    /**
     * 配置同步 + 数据面板脚本（不修改 LI 源码，与版本解耦）。
     *
     * 防闪烁设计（关键）：
     *   - 每次写入 localStorage 前先「全量比对」，只有不一致才写 + reload（幂等）。
     *   - sessionStorage 计数上限 2：即便比对逻辑有疏漏，reload 也绝不会超过 2 次，
     *     从根本上杜绝「一直闪、无法操作」的死循环。
     */
    private fun injectConfigScript(webView: WebView) {
        if (webViewDestroyed) return
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

        val js = """
            (function() {
                if (window.__liCfgInjected) return;
                window.__liCfgInjected = true;

                // 防闪烁硬上限：最多 reload 2 次
                var rn = parseInt(sessionStorage.getItem('liReloads') || '0', 10);
                if (rn >= 2) return;
                sessionStorage.setItem('liReloads', String(rn + 1));

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

                // 2) 待执行动作（一次性，执行后由 onWebActionDone 清除标记）
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

                // 3) 填一次注入两边：仅当与 LI 现有设置「不一致」才写 + reload（幂等）
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

                // 4) mimo 云端语音：同样先全量比对，不一致才写 + reload（幂等，杜绝闪烁死循环）
                if (NATIVE.tts && NATIVE.tts.apiKey) {
                    var d = readData();
                    if (d && d.settings) {
                        var s = d.settings;
                        var tc = s.ttsCloud || {};
                        var wantBase = NATIVE.tts.baseUrl || 'https://api.xiaomimimo.com/v1';
                        var wantModel = NATIVE.tts.model || 'mimo-v2.5-tts';
                        if (s.ttsSource !== 'cloud' || tc.apiKey !== NATIVE.tts.apiKey ||
                            (tc.baseUrl || 'https://api.xiaomimimo.com/v1') !== wantBase ||
                            (tc.model || 'mimo-v2.5-tts') !== wantModel) {
                            s.ttsSource = 'cloud';
                            s.ttsCloud = tc;
                            tc.apiKey = NATIVE.tts.apiKey;
                            tc.baseUrl = wantBase;
                            tc.model = wantModel;
                            writeData(d); location.reload(); return;
                        }
                    }
                }
            })();
        """
        webView.evaluateJavascript(js, null)
    }

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
