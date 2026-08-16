package com.li.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 被 WorkManager 每 15 分钟唤醒一次，判断 A 或 B 是否触发。
 * 这是「主动推送」的大脑：没有服务器替你盯着，就靠这个定时器周期性自问自答。
 */
class CompanionWorker(ctx: Context, params: WorkerParameters) :
    CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = AppPreferences(applicationContext)
        if (!prefs.enabled) return Result.success()
        if (!shouldTrigger(prefs)) return Result.success()

        val message = withContext(Dispatchers.IO) {
            LlmClient.fetchCompanionMessage(prefs)
        }
        if (!message.isNullOrBlank()) {
            NotificationHelper.show(applicationContext, message)
            prefs.markProactiveNow()
        }
        return Result.success()
    }

    private fun shouldTrigger(prefs: AppPreferences): Boolean {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)

        // A 定时陪伴：当前落在某预设时刻 ±7 分钟内，且今天该时刻还没发过
        for (slot in prefs.scheduleTimes) {
            if (slot.hour == hour && kotlin.math.abs(slot.minute - minute) <= 7) {
                if (!prefs.wasSentToday(slot)) {
                    prefs.markSentToday(slot)
                    return true
                }
            }
        }

        // B 久未互动：距上次聊天超过阈值，且距上次主动推送也超过阈值（防刷屏）
        val idleMs = (prefs.idleHours * 3600_000L).toLong()
        val sinceChat = System.currentTimeMillis() - prefs.lastChatEpochMs
        val sinceProactive = System.currentTimeMillis() - prefs.lastProactiveEpochMs
        if (sinceChat > idleMs && sinceProactive > idleMs) return true

        return false
    }
}
