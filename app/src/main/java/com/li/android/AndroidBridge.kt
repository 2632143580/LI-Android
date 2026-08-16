package com.li.android

import android.webkit.JavascriptInterface
import java.util.concurrent.atomic.AtomicLong

/**
 * JS → Native 桥接对象。
 * 通过 WebView.addJavascriptInterface 注入到 LI 网页的 window.AndroidBridge 上。
 * 网页端（注入的 observer 脚本）检测到用户发消息时调用 onUserMessage()，
 * 原生层记录时间戳，供 CompanionWorker 的 B 功能（久未互动）判断。
 *
 * 安全说明：
 *   @JavascriptInterface 限制：只有带此注解的方法才能被 JS 调用。
 *   不暴露任何敏感 API（无文件读写、无 Intent 启动、无其他方法）。
 *   只接收事件并写时间戳到 SharedPreferences。
 */
class AndroidBridge(private val prefsProvider: () -> AppPreferences) {

    /** 最近一次 onUserMessage 调用的 epoch 毫秒（用于防抖：同一秒内多次调用只算一次） */
    private val lastReported = AtomicLong(0L)

    /**
     * 网页端检测到用户发出聊天消息时调用。
     * 更新 AppPreferences.lastChatEpochMs 为当前时间。
     * 同一秒内的重复调用会被忽略（防 DOM 观察器对同一消息的多次触发）。
     */
    @JavascriptInterface
    fun onUserMessage() {
        val now = System.currentTimeMillis()
        val prev = lastReported.get()
        // 防抖：同一秒内不重复写入（DOM 变更可能触发多次回调）
        if (now - prev < 1000L) return
        if (!lastReported.compareAndSet(prev, now)) return

        prefsProvider().lastChatEpochMs = now
    }
}
