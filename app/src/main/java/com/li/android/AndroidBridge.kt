package com.li.android

import android.content.Context
import android.webkit.JavascriptInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * JS → Native 桥接对象。
 * 通过 WebView.addJavascriptInterface 注入到 LI 网页的 window.AndroidBridge 上。
 *
 * 网页侧（注入脚本）可调用：
 *   - onUserMessage()      记录「用户刚发消息」时间戳（B 功能用）
 *   - onStorageStats(json) 回传 LI 存储统计，供数据面板显示
 *   - onWebActionDone()    通知原生「网页侧动作已执行」，清除 pendingWebAction
 *   - exportChat(json)     把聊天存档写成文件，供用户导出
 *   - resetAllNative()     清空 App 原生配置（配合网页侧 removeItem 完成「重置全部」）
 *
 * 安全说明：
 *   @JavascriptInterface 限制只有带此注解的方法才能被 JS 调用。
 *   不暴露任何敏感能力（无任意文件读写、无 Intent 启动）。
 */
class AndroidBridge(
    private val ctx: Context,
    private val prefsProvider: () -> AppPreferences
) {

    /** 最近一次 onUserMessage 的 epoch 毫秒（防抖用） */
    private val lastReported = AtomicLong(0L)

    /**
     * 网页端检测到用户发出聊天消息时调用，更新 lastChatEpochMs。
     * 同一秒内重复调用忽略。
     */
    @JavascriptInterface
    fun onUserMessage() {
        val now = System.currentTimeMillis()
        val prev = lastReported.get()
        if (now - prev < 1000L) return
        if (!lastReported.compareAndSet(prev, now)) return
        prefsProvider().lastChatEpochMs = now
    }

    /**
     * 网页侧注入脚本计算好 LI 存储统计后回传。
     * json 形如 {"totalBytes":N,"keyCount":N,"chatNodes":N,"ttsSource":"system|cloud","ttsCloudConfigured":bool}
     */
    @JavascriptInterface
    fun onStorageStats(json: String?) {
        if (json.isNullOrBlank()) return
        prefsProvider().storageStatsJson = json
    }

    /** 网页侧动作（清空/重置/导出）执行完毕，清除队列标记，避免下次加载重复执行。 */
    @JavascriptInterface
    fun onWebActionDone() {
        prefsProvider().pendingWebAction = ""
    }

    /** 清空 App 原生配置——与网页侧 removeItem 配合完成「重置全部」。 */
    @JavascriptInterface
    fun resetAllNative() {
        prefsProvider().clearAll()
    }

    /**
     * 导出聊天存档为 JSON 文件，写到 App 私有外部目录（PC 经 USB 可读取）。
     * 返回写入路径给网页无意义，故仅存本地供设置页展示。
     */
    @JavascriptInterface
    fun exportChat(json: String?) {
        if (json.isNullOrBlank()) return
        try {
            val dir = ctx.getExternalFilesDir(null)
                ?: File(ctx.filesDir, "export")
            if (!dir.exists()) dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "li_chat_export_$ts.json")
            file.writeText(json)
            prefsProvider().exportFilePath = file.absolutePath
        } catch (_: Exception) {
            // 导出失败静默忽略，设置页会显示错误提示
        }
    }
}
