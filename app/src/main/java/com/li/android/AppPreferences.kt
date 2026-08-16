package com.li.android

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地存储：所有配置都存在 App 私有沙盒，不联网、不上云。
 *
 * 两套独立存储（互不干扰）：
 *   1) 本文件（SharedPreferences li_companion.xml）—— App 原生配置，只有原生代码读写：
 *      推送总开关、推送用 LLM、A/B 独立开关、mimo 云端语音、pendingWebAction 等。
 *   2) LI 网页的 localStorage（键名 liChatData_v2）—— LI 自己管理：聊天记录、网页聊天 LLM Key、
 *      mimo 语音 Key、配色等。原生读不到其内容，需经注入脚本在网页侧读写。
 */
class AppPreferences(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("li_companion", Context.MODE_PRIVATE)

    // ===== 推送总开关 =====
    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    // ===== 推送用 LLM（后台 AI 主动说话的脑子）=====
    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v).apply()

    var baseUrl: String
        get() = sp.getString("base_url", "https://api.openai.com") ?: "https://api.openai.com"
        set(v) = sp.edit().putString("base_url", v).apply()

    var model: String
        get() = sp.getString("model", "gpt-4o-mini") ?: "gpt-4o-mini"
        set(v) = sp.edit().putString("model", v).apply()

    // 填一次注入两边：把上面的推送 LLM 同时写进 LI 网页，省得在网页再填一次
    var syncToWeb: Boolean
        get() = sp.getBoolean("sync_to_web", false)
        set(v) = sp.edit().putBoolean("sync_to_web", v).apply()

    // ===== A 定时陪伴 / B 久未互动 独立开关 =====
    var enableA: Boolean
        get() = sp.getBoolean("enable_a", true)
        set(v) = sp.edit().putBoolean("enable_a", v).apply()

    var enableB: Boolean
        get() = sp.getBoolean("enable_b", true)
        set(v) = sp.edit().putBoolean("enable_b", v).apply()

    // ===== 闲置阈值（小时）：B 触发用 =====
    var idleHours: Float
        get() = sp.getFloat("idle_hours", 3f)
        set(v) = sp.edit().putFloat("idle_hours", v).apply()

    // ===== mimo 云端语音 TTS（仅用「云端语音」时需要，和 LLM Key 不是一回事）=====
    var ttsApiKey: String
        get() = sp.getString("tts_api_key", "") ?: ""
        set(v) = sp.edit().putString("tts_api_key", v).apply()

    var ttsBaseUrl: String
        get() = sp.getString("tts_base_url", "https://api.xiaomimimo.com/v1")
            ?: "https://api.xiaomimimo.com/v1"
        set(v) = sp.edit().putString("tts_base_url", v).apply()

    var ttsModel: String
        get() = sp.getString("tts_model", "mimo-v2.5-tts") ?: "mimo-v2.5-tts"
        set(v) = sp.edit().putString("tts_model", v).apply()

    // ===== 网页侧动作队列（设置页写，主界面加载 LI 后执行）=====
    // 取值："" / "clear_chat" / "reset_all" / "export"
    var pendingWebAction: String
        get() = sp.getString("pending_web_action", "") ?: ""
        set(v) = sp.edit().putString("pending_web_action", v).apply()

    // ===== 数据面板缓存（由注入脚本回写）=====
    var storageStatsJson: String
        get() = sp.getString("storage_stats", "") ?: ""
        set(v) = sp.edit().putString("storage_stats", v).apply()

    // 最近一次导出文件路径（App 私有外部目录，PC 通过 USB 可取）
    var exportFilePath: String
        get() = sp.getString("export_file_path", "") ?: ""
        set(v) = sp.edit().putString("export_file_path", v).apply()

    // ===== 上次聊天 / 推送时间（B 判断 + 防刷屏）=====
    var lastChatEpochMs: Long
        get() = sp.getLong("last_chat", 0L)
        set(v) = sp.edit().putLong("last_chat", v).apply()

    var lastProactiveEpochMs: Long
        get() = sp.getLong("last_proactive", 0L)
        set(v) = sp.edit().putLong("last_proactive", v).apply()

    // ===== A 定时表，如 ["09:00","20:00"] =====
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

    /** 清空全部 App 原生配置（重置用） */
    fun clearAll() = sp.edit().clear().apply()

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
