package com.li.android

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 设置页：填 LLM 的 Key / 接口地址 / 模型 / 闲置阈值 / 定时表。
 * 这里存的 Key 是 App 侧主动推送用的，和 LI 网页里的 Key 相互独立。
 * 底部"关于"区展示本机应用版本与内置 LI 内核版本，便于核对手机装的是哪版。
 */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = AppPreferences(this)
        val etKey = findViewById<EditText>(R.id.etKey)
        val etBase = findViewById<EditText>(R.id.etBase)
        val etModel = findViewById<EditText>(R.id.etModel)
        val etIdle = findViewById<EditText>(R.id.etIdle)
        val etSchedule = findViewById<EditText>(R.id.etSchedule)

        etKey.setText(prefs.apiKey)
        etBase.setText(prefs.baseUrl)
        etModel.setText(prefs.model)
        etIdle.setText(prefs.idleHours.toString())
        etSchedule.setText(
            prefs.scheduleTimes.joinToString(",") { "%02d:%02d".format(it.hour, it.minute) }
        )

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            prefs.apiKey = etKey.text.toString().trim()
            prefs.baseUrl = etBase.text.toString().trim()
            prefs.model = etModel.text.toString().trim()
            prefs.idleHours = etIdle.text.toString().toFloatOrNull() ?: 3f
            prefs.setSchedule(etSchedule.text.toString().trim())
            finish()
        }

        // 关于区：展示版本，便于核对手机装的是哪版 LI 内核
        findViewById<TextView>(R.id.tvAppVersion).text = "本机应用版本：${getAppVersion()}"
        findViewById<TextView>(R.id.tvLiVersion).text = "内置 LI 内核版本：${getLiVersion()}"
    }

    /** 读取本 App 的 versionName（在 app/build.gradle.kts 定义） */
    private fun getAppVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "未知"
    } catch (_: Exception) { "未知" }

    /**
     * 读取内置 LI 内核版本。
     * 该文件由 CI 在构建时写入 assets/li_version.txt（来自 LI 的 package.json version）。
     * 本地直接跑未构建时文件不存在，回退显示"开发版（本地运行）"。
     */
    private fun getLiVersion(): String = try {
        val txt = assets.open("li_version.txt").bufferedReader().use { it.readText().trim() }
        if (txt.isEmpty()) "未知" else txt
    } catch (_: Exception) { "开发版（本地运行）" }
}
