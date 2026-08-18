package com.li.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.util.Calendar

/**
 * 被 WorkManager 每 15 分钟唤醒一次，判断 A 或 B 是否触发。
 * 这是「主动推送」的大脑：没有服务器替你盯着，就靠这个定时器周期性自问自答。
 *
 * 触发后不再自己调模型（App 不持有任何 LLM 配置）——而是让 LI 说话：
 *   MainActivity.requestCompanionSay(说话内容) → 聊天里插入可见用户消息 →
 *   LI 用【网页里配置的模型】回复 → AndroidBridge.onCompanionReply 收到回复后弹通知。
 * 主界面不在（App 被杀/页面未就绪）时跳过本轮：网页不在，li 无法回复，这是无服务器方案的固有边界。
 */
class CompanionWorker(ctx: Context, params: WorkerParameters) :
    CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = AppPreferences(applicationContext)
        if (!prefs.enabled) return Result.success()
        if (!shouldTrigger(prefs)) return Result.success()

        // 触发：让 LI 用网页自己的模型回复（回复完成经 onCompanionReply 回传通知 + 记录防刷屏时间）
        val act = MainActivity.instance
        if (act == null) return Result.success()
        act.requestCompanionSay(prefs.companionText)
        return Result.success()
    }

    private fun shouldTrigger(prefs: AppPreferences): Boolean {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)

        // A 定时陪伴：当前落在某预设时刻 ±7 分钟内，且今天该时刻还没发过（需 enableA 开启）
        if (prefs.enableA) {
            for (slot in prefs.scheduleTimes) {
                if (slot.hour == hour && kotlin.math.abs(slot.minute - minute) <= 7) {
                    if (!prefs.wasSentToday(slot)) {
                        prefs.markSentToday(slot)
                        return true
                    }
                }
            }
        }

        // B 久未互动：距上次聊天超过阈值，且距上次主动推送也超过阈值（防刷屏，需 enableB 开启）
        if (prefs.enableB) {
            val idleMs = (prefs.idleHours * 3600_000L).toLong()
            val sinceChat = System.currentTimeMillis() - prefs.lastChatEpochMs
            val sinceProactive = System.currentTimeMillis() - prefs.lastProactiveEpochMs
            if (sinceChat > idleMs && sinceProactive > idleMs) return true
        }

        return false
    }
}
