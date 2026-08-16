package com.li.android

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地存储：所有配置都存在 App 私有沙盒，不联网、不上云。
 * 与 LI 网页自己的 localStorage 是两套独立存储，互不干扰。
 */
class AppPreferences(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("li_companion", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v).apply()

    var baseUrl: String
        get() = sp.getString("base_url", "https://api.openai.com") ?: "https://api.openai.com"
        set(v) = sp.edit().putString("base_url", v).apply()

    var model: String
        get() = sp.getString("model", "gpt-4o-mini") ?: "gpt-4o-mini"
        set(v) = sp.edit().putString("model", v).apply()

    // 闲置阈值（小时）：B 触发用
    var idleHours: Float
        get() = sp.getFloat("idle_hours", 3f)
        set(v) = sp.edit().putFloat("idle_hours", v).apply()

    // 上次聊天时间（由 LI 网页经桥接写入；当前用占位，可在 LI 内联脚本里调用）
    var lastChatEpochMs: Long
        get() = sp.getLong("last_chat", 0L)
        set(v) = sp.edit().putLong("last_chat", v).apply()

    // 上次主动推送时间（防刷屏：两次主动推送至少间隔 idleHours）
    var lastProactiveEpochMs: Long
        get() = sp.getLong("last_proactive", 0L)
        set(v) = sp.edit().putLong("last_proactive", v).apply()

    // A 定时表，如 ["09:00","20:00"]
    val scheduleTimes: List<TimeSlot>
        get() {
            val raw = sp.getString("schedule", "09:00,20:00") ?: "09:00,20:00"
            return raw.split(",").mapNotNull { parseSlot(it) }
        }

    fun setSchedule(raw: String) = sp.edit().putString("schedule", raw).apply()

    fun markProactiveNow() {
        lastProactiveEpochMs = System.currentTimeMillis()
    }

    fun wasSentToday(slot: TimeSlot): Boolean {
        val key = "sent_${slot.hour}_${slot.minute}_${todayKey()}"
        return sp.getBoolean(key, false)
    }

    fun markSentToday(slot: TimeSlot) {
        sp.edit().putBoolean("sent_${slot.hour}_${slot.minute}_${todayKey()}", true).apply()
    }

    private fun todayKey(): String {
        val c = java.util.Calendar.getInstance()
        return "${c.get(java.util.Calendar.YEAR)}${c.get(java.util.Calendar.DAY_OF_YEAR)}"
    }

    private fun parseSlot(s: String): TimeSlot? {
        val parts = s.split(":").mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != 2) return null
        return TimeSlot(parts[0], parts[1])
    }

    data class TimeSlot(val hour: Int, val minute: Int)
}
