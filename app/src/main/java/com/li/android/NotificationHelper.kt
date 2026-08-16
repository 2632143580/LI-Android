package com.li.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本地通知：直接用系统原生弹窗，不靠 FCM、不靠任何服务器。
 * Android 8+ 必须建通知渠道，否则通知不显示（新手必踩的坑）。
 */
object NotificationHelper {
    private const val CHANNEL_ID = "li_push"
    private const val CHANNEL_NAME = "li 推送"
    private val idCounter = AtomicInteger(1)

    fun show(context: Context, message: String) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
            )
            mgr.createNotificationChannel(ch)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("li")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(R.drawable.ic_notification) // 自绘单色图标（系统会染成白/灰）
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        mgr.notify(idCounter.getAndIncrement(), notif)
    }
}
