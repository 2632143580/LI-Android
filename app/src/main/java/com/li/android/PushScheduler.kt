package com.li.android

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 注册后台周期任务。安卓硬限制：最短 15 分钟一次。
 * A 与 B 的判断都在这同一个周期里完成，不用建两个任务。
 */
object PushScheduler {
    private const val WORK_NAME = "li_companion_work"

    fun enqueue(context: Context) {
        val req = PeriodicWorkRequestBuilder<CompanionWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(1, TimeUnit.MINUTES)
            .build()
        // UPDATE：重复调用只替换、不叠加，安全幂等
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req
        )
    }
}
