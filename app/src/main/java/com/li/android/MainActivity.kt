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
import android.widget.Toast
import org.json.JSONObject
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 主界面：全屏 WebView 加载 LI 网页（运行时热更新：加载 app 私有 app_web 目录，
 * 无网时回退 assets 内嵌基线）。
 *
 * 关键机制：
 *   1. 注入 AndroidBridge（JS → Native 桥），让网页能回传"用户发消息"事件
 *   2. 页面加载完成后注入 MutationObserver 脚本，监听 .chat-bubble--user 出现
 *   3. 注入「配置同步 + 数据面板」脚本（写入 localStorage 时严格幂等，且最多 reload 2 次，杜绝闪烁死循环）
 *   4. 注册 WorkManager 周期任务（推送调度）
 *   5. 请求通知权限 + 电池优化豁免
 *   6. 注册 AppBus.refreshStats，供设置页「刷新」重新统计
 *   7. 启动后后台静默检查网页更新（WebBundleManager 热更新）
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** 存活中的 MainActivity 实例（CompanionWorker 触发"让 li 说话"时经此注入 WebView） */
        @Volatile
        var instance: MainActivity? = null
    }

    private lateinit var webView: WebView
    private lateinit var bridge: AndroidBridge
    private lateinit var webBundle: WebBundleManager
    private var webViewDestroyed = false
    /** LI 页面是否已加载完成（__liCompanionSay 入口就绪后才有意义） */
    @Volatile
    private var pageReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        instance = this

        PushScheduler.enqueue(this)

        // 用 applicationContext，避免 WebView/桥持有 Activity 导致泄漏
        bridge = AndroidBridge(applicationContext) { AppPreferences(this) }
        // 网页包热更新管理器：确保 app_web 有网页（无则复制 assets 基线）
        webBundle = WebBundleManager(this)

        webView = findViewById(R.id.webview)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // 关键：Android 11(API 30)+ 默认禁止加载应用私有目录的 file:// 文件；
        // 热更新网页位于 /data/user/0/<pkg>/app_web/index.html（普通文件路径），必须显式放行，
        // 否则 WebView 报 net::ERR_ACCESS_DENIED（旧版加载 android_asset 内置资源不受此限制，故未暴露）。
        // 单文件网页内 JS/CSS 已内联，无需放开 allowFileAccessFromFileURLs（保持默认 false 更安全）。
        webView.settings.allowFileAccess = true
        webView.addJavascriptInterface(bridge, "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // 只在 LI 入口（app_web/index.html）注入，避免子框架/about:blank 误触发
                if (webBundle.isLiEntry(url)) {
                    pageReady = true
                    injectChatObserver(view)
                    injectConfigScript(view)
                }
            }
        }

        // 确保网页存在后再加载；无网时自动用 assets 基线
        webBundle.ensureBaseline()
        webView.loadUrl(webBundle.indexUrl())

        // 设置页保存后重新注入统计脚本（force：绕过 __liCfgInjected 守卫；写入幂等，安全）
        AppBus.refreshStats = {
            runOnUiThread { if (!webViewDestroyed) injectConfigScript(webView, force = true) }
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 刷新 LI 网页（主界面唯一刷新入口：重载 WebView，网页配置/对话状态重新从存储加载）
        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            if (!webViewDestroyed) webView.reload()
        }

        requestPostNotificationPermission()
        requestIgnoreBatteryOptimizations()

        // 启动时若有上次崩溃日志，弹出展示并支持一键复制（缩短真机排错链路）
        LiApplication.takeLastCrash(this)?.let { showCrashDialog(it) }

        // 后台静默检查网页更新（热更新）；有更新下载完成后提示重启生效
        webBundle.checkAndUpdate { result ->
            if (result.updated) {
                runOnUiThread {
                    if (!webViewDestroyed) {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showCrashDialog(text: String) {
        AlertDialog.Builder(this)
            .setTitle("上次运行时崩溃（已捕获）")
            .setMessage(text.take(4000))
            .setPositiveButton("复制错误") { _, _ -> copyText(text) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun copyText(text: String) {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("li-crash", text))
            android.widget.Toast.makeText(this, "已复制，可发给开发者", android.widget.Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "复制失败，请手动记录", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从设置页返回时，重新同步网页配置（语音/LLM 改动立即生效，不闪屏：脚本全量比对后才写+reload）
        if (!webViewDestroyed) {
            webView.postDelayed({
                if (!webViewDestroyed && webView.url != null) injectConfigScript(webView, force = true)
            }, 300)
        }
    }

    override fun onDestroy() {
        webViewDestroyed = true
        pageReady = false
        AppBus.refreshStats = null
        instance = null
        super.onDestroy()
    }

    /**
     * 让 LI 说一句话（推送核心：插入可见用户消息 → LI 用网页配置的模型回复）。
     * 由 CompanionWorker（定时触发）或设置页「测试推送」调用。
     * 回复完成后经 __liCompanionOnReply → AndroidBridge.onCompanionReply 回传并弹通知。
     * 页面未就绪 / WebView 已销毁时静默跳过（无服务器方案的固有边界）。
     */
    fun requestCompanionSay(text: String) {
        runOnUiThread {
            if (webViewDestroyed || !pageReady) return@runOnUiThread
            val quoted = JSONObject.quote(text)
            webView.evaluateJavascript(
                "window.__liCompanionSay && window.__liCompanionSay($quoted);", null)
        }
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
                    // li 主动说话（companion-say）插入的 user 气泡不算「用户互动」，
                    // 否则 li 自己说话会刷新"久未互动"计时器，B 功能永远不触发第二次。
                    if (sessionStorage.getItem('liCompanionSaying') === '1') return;
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
    private fun injectConfigScript(webView: WebView, force: Boolean = false) {
        if (webViewDestroyed) return
        val prefs = AppPreferences(this)
        val nativeConfig = JSONObject().apply {
            put("pendingAction", prefs.pendingWebAction)
            put("tts", JSONObject().apply {
                put("apiKey", prefs.ttsApiKey)
                put("baseUrl", prefs.ttsBaseUrl)
                put("model", prefs.ttsModel)
            })
        }.toString()

        val js = """
            (function() {
                if (!$force && window.__liCfgInjected) return;
                window.__liCfgInjected = true;

                var NATIVE = $nativeConfig;
                var STORAGE_KEY = 'liChatData_v2';

                function readData() {
                    try { return JSON.parse(localStorage.getItem(STORAGE_KEY)); }
                    catch (e) { return null; }
                }
                function writeData(d) { localStorage.setItem(STORAGE_KEY, JSON.stringify(d)); }
                // 防闪烁（关键）：写入 localStorage 永远执行（持久化一定成功）；
                // 只有 location.reload() 有上限（最多 2 次，杜绝闪烁死循环）。
                // 达到上限时配置已存入存储，下次打开 LI 即生效——绝不因上限而丢弃写入。
                function safeReload() {
                    var rn = parseInt(sessionStorage.getItem('liReloads') || '0', 10);
                    if (rn >= 2) return;
                    sessionStorage.setItem('liReloads', String(rn + 1));
                    location.reload();
                }
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
                    // llmConfigured：网页是否已配置聊天模型（推送就绪状态用它判断）
                    var llmCfg = !!(d && d.settings && d.settings.apiKey);
                    try { window.AndroidBridge.onStorageStats(JSON.stringify({
                        totalBytes: total, keyCount: keys, chatNodes: nodes,
                        ttsSource: ttsSource, ttsCloudConfigured: ttsCfg,
                        llmConfigured: llmCfg
                    })); } catch (e) {}
                })();

                // 2) 待执行动作（一次性，执行后由 onWebActionDone 清除标记）
                if (NATIVE.pendingAction === 'clear_chat') {
                    var d = readData() || { settings: {} };
                    d.chatTree = { role: 'system', content: '', children: [] };
                    d.msgIdCounter = 0;
                    writeData(d);
                    try { window.AndroidBridge.onWebActionDone(); } catch (e) {}
                    safeReload(); return;
                }
                if (NATIVE.pendingAction === 'reset_all') {
                    localStorage.removeItem(STORAGE_KEY);
                    try { window.AndroidBridge.resetAllNative(); } catch (e) {}
                    try { window.AndroidBridge.onWebActionDone(); } catch (e) {}
                    safeReload(); return;
                }
                if (NATIVE.pendingAction === 'export') {
                    var raw = localStorage.getItem(STORAGE_KEY) || '{}';
                    try { window.AndroidBridge.exportChat(raw); } catch (e) {}
                    try { window.AndroidBridge.onWebActionDone(); } catch (e) {}
                }

                // 3) mimo 云端语音：同样「App 空字段不覆盖网页已有配置」+ 全量比对（幂等，杜绝闪烁死循环）
                if (NATIVE.tts && NATIVE.tts.apiKey) {
                    var d = readData();
                    if (d && d.settings) {
                        var s = d.settings;
                        var tc = s.ttsCloud || {};
                        var changed = false;
                        if (s.ttsSource !== 'cloud') { s.ttsSource = 'cloud'; changed = true; }
                        if (tc.apiKey !== NATIVE.tts.apiKey) { tc.apiKey = NATIVE.tts.apiKey; changed = true; }
                        if (NATIVE.tts.baseUrl && tc.baseUrl !== NATIVE.tts.baseUrl) { tc.baseUrl = NATIVE.tts.baseUrl; changed = true; }
                        if (NATIVE.tts.model && tc.model !== NATIVE.tts.model) { tc.model = NATIVE.tts.model; changed = true; }
                        if (changed) { s.ttsCloud = tc; writeData(d); safeReload(); return; }
                    }
                }

                // 4) 注册"li 主动说话回复"回调：CompanionWorker/测试按钮触发说话后，
                //    LI 用【网页自己的模型】回复完成，回复内容经此回传原生弹通知。
                //    重复注入会覆盖旧回调（同一定义），幂等无副作用。
                if (window.__liCompanionOnReply) {
                    window.__liCompanionOnReply(function(content, isError) {
                        try { window.AndroidBridge.onCompanionReply(content || '', !!isError); } catch(e) {}
                    });
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
