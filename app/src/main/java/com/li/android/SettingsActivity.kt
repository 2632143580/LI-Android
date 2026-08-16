package com.li.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File

/**
 * 设置页：
 *  - 推送总开关 / 推送用 LLM / A·B 独立开关 / 闲置阈值 / 定时时刻
 *  - 云端语音（MiMo TTS）Key
 *  - 数据管理：位置说明 + 统计 + 导出 / 清空 / 重置
 *  - 状态权限：通知 / 电池豁免 / 测试推送
 *  - 关于：应用版本 + 内置 LI 内核版本
 *
 * 网页侧动作（清空/重置/导出）只在此写入 pendingWebAction，真正执行发生在
 * MainActivity 加载 LI 时（因为 WebView 在MainActivity）。故操作后需返回主界面生效。
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = AppPreferences(this)

        bindSwitches()
        bindInputs()
        bindDataPanel()
        bindStatus()
        bindActions()
        showVersions()
        showStats()
    }

    // ===== 开关 =====
    private fun bindSwitches() {
        findViewById<Switch>(R.id.swEnabled).isChecked = prefs.enabled
        findViewById<Switch>(R.id.swA).isChecked = prefs.enableA
        findViewById<Switch>(R.id.swB).isChecked = prefs.enableB
        findViewById<CheckBox>(R.id.cbSyncWeb).isChecked = prefs.syncToWeb
    }

    // ===== 输入框回填 =====
    private fun bindInputs() {
        findViewById<EditText>(R.id.etKey).setText(prefs.apiKey)
        findViewById<EditText>(R.id.etBase).setText(prefs.baseUrl)
        findViewById<EditText>(R.id.etModel).setText(prefs.model)
        findViewById<EditText>(R.id.etIdle).setText(prefs.idleHours.toString())
        findViewById<EditText>(R.id.etSchedule).setText(
            prefs.scheduleTimes.joinToString(",") { "%02d:%02d".format(it.hour, it.minute) }
        )
        findViewById<EditText>(R.id.etTtsKey).setText(prefs.ttsApiKey)
        findViewById<EditText>(R.id.etTtsBase).setText(prefs.ttsBaseUrl)
        findViewById<EditText>(R.id.etTtsModel).setText(prefs.ttsModel)
    }

    // ===== 数据面板：位置说明 + 统计 + 导出/清空/重置 =====
    private fun bindDataPanel() {
        findViewById<Button>(R.id.btnExport).setOnClickListener {
            prefs.pendingWebAction = "export"
            toast("已标记导出，返回主界面后自动生成文件")
            finish()
        }
        findViewById<Button>(R.id.btnClearChat).setOnClickListener {
            prefs.pendingWebAction = "clear_chat"
            toast("已标记清空，返回主界面后立即生效")
            finish()
        }
        findViewById<Button>(R.id.btnResetAll).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("重置全部？")
                .setMessage("将清空 App 配置 + LI 网页全部数据（聊天记录、Key、设置），且不可恢复。")
                .setPositiveButton("确认重置") { _, _ ->
                    prefs.pendingWebAction = "reset_all"
                    toast("已标记重置，返回主界面后立即生效")
                    finish()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // ===== 状态 / 权限 =====
    private fun bindStatus() {
        // 通知权限
        val notifOk = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
        findViewById<TextView>(R.id.tvNotif).text = "通知权限：${if (notifOk) "已授权 ✅" else "未授权 ❌"}"
        findViewById<Button>(R.id.btnOpenNotif).setOnClickListener {
            val intent = if (Build.VERSION.SDK_INT >= 26) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            }
            startActivity(intent)
        }

        // 电池豁免
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        findViewById<TextView>(R.id.tvBattery).text = "电池豁免：${if (batteryOk) "已豁免 ✅" else "未豁免 ❌（推送可能被杀）"}"
        findViewById<Button>(R.id.btnOpenBattery).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }

        // 测试推送
        findViewById<Button>(R.id.btnTestPush).setOnClickListener {
            NotificationHelper.show(this, "这是一条测试推送 ✅ 若看到说明通知通道正常")
        }
    }

    // ===== 保存 =====
    private fun bindActions() {
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            prefs.enabled = findViewById<Switch>(R.id.swEnabled).isChecked
            prefs.enableA = findViewById<Switch>(R.id.swA).isChecked
            prefs.enableB = findViewById<Switch>(R.id.swB).isChecked
            prefs.syncToWeb = findViewById<CheckBox>(R.id.cbSyncWeb).isChecked

            prefs.apiKey = findViewById<EditText>(R.id.etKey).text.toString().trim()
            prefs.baseUrl = findViewById<EditText>(R.id.etBase).text.toString().trim()
            prefs.model = findViewById<EditText>(R.id.etModel).text.toString().trim()
            prefs.idleHours = findViewById<EditText>(R.id.etIdle).text.toString().toFloatOrNull() ?: 3f
            prefs.setSchedule(findViewById<EditText>(R.id.etSchedule).text.toString().trim())

            prefs.ttsApiKey = findViewById<EditText>(R.id.etTtsKey).text.toString().trim()
            prefs.ttsBaseUrl = findViewById<EditText>(R.id.etTtsBase).text.toString().trim()
            prefs.ttsModel = findViewById<EditText>(R.id.etTtsModel).text.toString().trim()

            toast("已保存")
            finish()
        }
    }

    // ===== 关于 =====
    private fun showVersions() {
        findViewById<TextView>(R.id.tvAppVersion).text = "本机应用版本：${getAppVersion()}"
        findViewById<TextView>(R.id.tvLiVersion).text = "内置 LI 内核版本：${getLiVersion()}"
    }

    /** 读取本 App 的 versionName（在 app/build.gradle.kts 定义） */
    private fun getAppVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "未知"
    } catch (_: Exception) { "未知" }

    private fun getLiVersion(): String = try {
        val txt = assets.open("li_version.txt").bufferedReader().use { it.readText().trim() }
        if (txt.isEmpty()) "未知" else txt
    } catch (_: Exception) { "开发版（本地运行）" }

    // ===== 数据面板统计 =====
    private fun showStats() {
        val sb = StringBuilder()
        // App 原生配置占用
        val appFile = File(filesDir.parentFile, "shared_prefs/li_companion.xml")
        val appBytes = if (appFile.exists()) appFile.length() else 0
        sb.append("App 原生配置占用：${formatBytes(appBytes)}\n")

        // LI 网页存储统计（来自上次打开 LI 时回传）
        val json = prefs.storageStatsJson
        if (json.isNotBlank()) {
            try {
                val o = JSONObject(json)
                sb.append("LI 网页存储占用：${formatBytes(o.optLong("totalBytes", 0))}\n")
                sb.append("聊天节点数：${o.optInt("chatNodes", 0)}\n")
                sb.append("语音源：${if (o.optString("ttsSource") == "cloud") "云端（已配 Key）" else "系统（免费）"}\n")
            } catch (_: Exception) {
                sb.append("LI 存储统计：暂不可读\n")
            }
        } else {
            sb.append("LI 存储统计：打开一次 LI 后自动统计\n")
        }

        if (prefs.exportFilePath.isNotBlank()) {
            sb.append("\n最近导出文件：\n${prefs.exportFilePath}")
        }
        findViewById<TextView>(R.id.tvStats).text = sb.toString()
    }

    private fun formatBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        else -> "%.2f MB".format(b / 1024.0 / 1024.0)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
