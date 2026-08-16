package com.li.android

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常处理器。
 *
 * 痛点：CI 的「绿」只验证编译，不验证运行；真机运行时崩溃（如设置页打开即闪退）
 * 只会静默关闭，用户看不到任何原因，排查要绕一大圈。
 *
 * 这里把任何崩溃的堆栈写入 App 私有文件 crash_log.txt，下次启动由 MainActivity
 * 弹出展示并支持一键复制——无需 adb / IDE 即可把真机错误反馈回来，彻底缩短
 * 「开发 → 打包 → 启动 → 反馈」链路。
 */
class LiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val def = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                File(filesDir, "crash_log.txt").writeText("崩溃时间：$stamp\n\n${sw.toString()}")
            } catch (_: Exception) {
                // 写文件失败也不能影响原有崩溃流程
            }
            def?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        /** 读取并清除上次崩溃日志（有则返回文本，无则返回 null）。 */
        fun takeLastCrash(ctx: Context): String? {
            val f = File(ctx.filesDir, "crash_log.txt")
            if (!f.exists()) return null
            return try {
                f.readText().also { f.delete() }
            } catch (_: Exception) {
                null
            }
        }
    }
}
