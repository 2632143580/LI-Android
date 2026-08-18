package com.li.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File

/**
 * 设置页：
 *  - 推送总开关 / 推送说话内容 / A·B 独立开关 / 闲置阈值 / 定时时刻
 *  - 云端语音（MiMo TTS）Key
 *  - 数据管理：位置说明 + 统计 + 导出 / 清空 / 重置（危险操作区，带确认）
 *  - 状态权限：通知 / 电池豁免 / 测试推送（让 li 说一句话）
 *  - 关于：应用版本 + 网页内核版本（热更新）+ 检查网页更新
 *
 * 推送机制：App 定时把「推送说话内容」插入聊天 → LI 用【网页里配置的模型】回复 →
 *   App 把回复弹成通知。App 不持有任何 LLM 配置（模型/Key 在 LI 网页右上设置里配）。
 *
 * 网页侧动作（清空/重置/导出）只在此写入 pendingWebAction，真正执行发生在
 * MainActivity 加载 LI 时（因为 WebView 在 MainActivity）。操作后需返回主界面生效。
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: AppPreferences
    private lateinit var tvStats: TextView
    private lateinit var webBundle: WebBundleManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_settings)
            prefs = AppPreferences(this)
            webBundle = WebBundleManager(this)
            tvStats = findViewById(R.id.tvStats)

            // 顶部应用栏 + 返回
            val toolbar = findViewById<Toolbar>(R.id.toolbar)
            setSupportActionBar(toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)

            bindSwitches()
            bindInputs()
            bindDataPanel()
            bindStatus()
            bindActions()
            showVersions()
            showStats()
        } catch (e: Exception) {
            // 不再静默闪退：直接把异常弹出来，支持一键复制发给开发者
            AlertDialog.Builder(this)
                .setTitle("设置页打开失败（已捕获异常）")
                .setMessage("${e.javaClass.simpleName}: ${e.message}\n\n${e.stackTraceToString().take(1500)}")
                .setPositiveButton("复制错误") { _, _ -> copyText(e.stackTraceToString()) }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun copyText(text: String) {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("li-crash", text))
            Toast.makeText(this, "已复制，可发给开发者", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "复制失败，请手动记录", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ===== 开关 =====
    private fun bindSwitches() {
        findViewById<Switch>(R.id.swEnabled).isChecked = prefs.enabled
        findViewById<Switch>(R.id.swA).isChecked = prefs.enableA
        findViewById<Switch>(R.id.swB).isChecked = prefs.enableB
    }

    // ===== 输入框回填 =====
    private fun bindInputs() {
        findViewById<EditText>(R.id.etSayText).setText(prefs.companionText)
        findViewById<EditText>(R.id.etIdle).setText(prefs.idleHours.toString())
        findViewById<EditText>(R.id.etSchedule).setText(
            prefs.scheduleTimes.joinToString(",") { "%02d:%02d".format(it.hour, it.minute) }
        )
        findViewById<EditText>(R.id.etTtsKey).setText(prefs.ttsApiKey)
        findViewById<EditText>(R.id.etTtsBase).setText(prefs.ttsBaseUrl)
        findViewById<EditText>(R.id.etTtsModel).setText(prefs.ttsModel)
    }

    // ===== 数据面板：危险操作区（确认流程见下）=====
    private fun bindDataPanel() {
        findViewById<Button>(R.id.btnExport).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("导出聊天记录？")
                .setMessage("将把全部聊天写入 App 私有目录的文件（不会分享出去），返回主界面后生成。")
                .setPositiveButton("导出") { _, _ ->
                    prefs.pendingWebAction = "export"
                    toast("已标记导出，返回主界面后自动生成文件")
                }
                .setNegativeButton("取消", null)
                .show()
        }

        findViewById<Button>(R.id.btnClearChat).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空聊天记录？")
                .setMessage("将删除全部对话树，不可恢复。")
                .setPositiveButton("清空") { _, _ ->
                    prefs.pendingWebAction = "clear_chat"
                    toast("已标记清空，返回主界面后立即生效")
                }
                .setNegativeButton("取消", null)
                .show()
        }

        findViewById<Button>(R.id.btnResetAll).setOnClickListener { confirmResetAll() }
    }

    /** 危险操作：两步确认。第一步告知后果，第二步必须勾选「已知不可恢复」才放行。 */
    private fun confirmResetAll() {
        AlertDialog.Builder(this)
            .setTitle("重置全部？")
            .setMessage("将清空 App 配置 + LI 网页全部数据（聊天记录、Key、设置），且不可恢复。")
            .setPositiveButton("继续") { _, _ -> confirmResetAllFinal() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmResetAllFinal() {
        val cb = CheckBox(this).apply {
            text = "我已知此操作不可恢复"
            setPadding(40, 24, 40, 8)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("最后确认")
            .setMessage("此操作无法撤销，确定要清空一切吗？")
            .setView(cb)
            .setPositiveButton("彻底重置", null)
            .setNegativeButton("取消", null)
            .show()
        val ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
        ok.isEnabled = false
        cb.setOnCheckedChangeListener { _, checked -> ok.isEnabled = checked }
        ok.setOnClickListener {
            if (cb.isChecked) {
                prefs.pendingWebAction = "reset_all"
                toast("已标记重置，返回主界面后立即生效")
                dlg.dismiss()
            }
        }
    }

    // ===== 状态 / 权限 =====
    private fun bindStatus() {
        val notifOk = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
        findViewById<TextView>(R.id.tvNotif).text = "通知权限：${if (notifOk) "已授权" else "未授权"}"
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

        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        findViewById<TextView>(R.id.tvBattery).text = "电池豁免：${if (batteryOk) "已豁免" else "未豁免（推送可能被杀）"}"
        findViewById<Button>(R.id.btnOpenBattery).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }

        findViewById<Button>(R.id.btnTestPush).setOnClickListener {
            NotificationHelper.show(this, "这是一条测试推送 若看到说明通知通道正常")
        }
    }

    // ===== 保存 + 检查网页更新（固定底栏常驻；主界面设置按钮旁才是刷新 LI 网页）=====
    private fun bindActions() {
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            save()
            refreshStats() // 保存后自动重取统计（无独立刷新按钮）
            toast("已保存，返回主界面后同步生效")
        }
        // 手动检查网页更新（热更新）：委托 WebBundleManager，结果回显到 tvUpdateStatus
        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener { checkUpdate() }
        // 危险区折叠：默认收起，点击展开/收起
        findViewById<Button>(R.id.btnToggleDanger).setOnClickListener { toggleDanger() }
        // 测试推送：让 li 说一句话（走真实链路：插入消息 → LI 网页模型回复 → 弹通知）
        findViewById<Button>(R.id.btnTestSay).setOnClickListener { testSay() }
        // 云端语音 Key「显示/隐藏」眼睛切换（与 LI 网页同款交互）
        findViewById<Button>(R.id.btnToggleTtsKey).setOnClickListener {
            toggleTtsKeyVisibility()
        }
        // 推送就绪状态
        bindPushStatus()
    }

    /** 云端语音 Key 输入框「显示/隐藏」眼睛切换。 */
    private fun toggleTtsKeyVisibility() {
        val et = findViewById<EditText>(R.id.etTtsKey)
        val btn = findViewById<Button>(R.id.btnToggleTtsKey)
        val isPassword = (et.inputType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0
        et.inputType = if (isPassword) {
            btn.text = "隐藏"
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            btn.text = "显示"
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        et.setSelection(et.text.length)
    }

    private fun save() {
        prefs.enabled = findViewById<Switch>(R.id.swEnabled).isChecked
        prefs.enableA = findViewById<Switch>(R.id.swA).isChecked
        prefs.enableB = findViewById<Switch>(R.id.swB).isChecked

        prefs.companionText = findViewById<EditText>(R.id.etSayText).text.toString().trim()
        prefs.idleHours = findViewById<EditText>(R.id.etIdle).text.toString().toFloatOrNull() ?: 3f
        prefs.setSchedule(findViewById<EditText>(R.id.etSchedule).text.toString().trim())

        prefs.ttsApiKey = findViewById<EditText>(R.id.etTtsKey).text.toString().trim()
        prefs.ttsBaseUrl = findViewById<EditText>(R.id.etTtsBase).text.toString().trim()
        prefs.ttsModel = findViewById<EditText>(R.id.etTtsModel).text.toString().trim()
        bindPushStatus()
    }

    /** 测试推送：先保存说话内容，再走真实链路让 li 回复（主界面需存活）。 */
    private fun testSay() {
        val tv = findViewById<TextView>(R.id.tvPushStatus)
        save()
        val act = MainActivity.instance
        if (act == null) {
            tv.text = "推送就绪状态：主界面不在（先返回主界面打开 LI，再回来点此测试）"
            return
        }
        tv.text = "推送就绪状态：已让 li 说一句话，等回复弹通知…（网页没配模型会静默失败）"
        act.requestCompanionSay(prefs.companionText)
    }

    /**
     * 推送就绪状态：总开关 + A/B 开关 + 【网页】聊天 LLM 是否已配置。
     * 网页 LLM 配置从注入统计回传读取（打开一次 LI 后更新；保存时 force 重注入也会刷新）。
     */
    private fun bindPushStatus() {
        val tv = findViewById<TextView>(R.id.tvPushStatus)
        val reasons = mutableListOf<String>()
        if (!prefs.enabled) reasons.add("总开关已关闭")
        if (!prefs.enableA && !prefs.enableB) reasons.add("A 定时与 B 久未互动都关着")

        val statsKnown = prefs.storageStatsJson.isNotBlank()
        val llmConfigured = try {
            JSONObject(prefs.storageStatsJson).optBoolean("llmConfigured", false)
        } catch (_: Exception) { false }
        if (!statsKnown) {
            reasons.add("网页 LLM 配置未知（返回主界面打开一次 LI 后回来查看）")
        } else if (!llmConfigured) {
            reasons.add("网页未配置聊天模型（去 LI 网页右上设置填 Key）")
        }

        tv.text = if (reasons.isEmpty()) {
            "推送就绪状态：已就绪 ✓（A/B 任一触发时，li 会用网页模型回复并弹通知）"
        } else {
            "推送就绪状态：未生效（" + reasons.joinToString("；") + "）"
        }
    }

    /** 危险区折叠：展开/收起清空、重置操作。 */
    private fun toggleDanger() {
        val container = findViewById<LinearLayout>(R.id.dangerContainer)
        val btn = findViewById<Button>(R.id.btnToggleDanger)
        val showing = container.visibility == View.VISIBLE
        container.visibility = if (showing) View.GONE else View.VISIBLE
        btn.text = if (showing) "展开危险操作区 ▾（清空 / 重置，不可恢复）" else "收起危险操作区 ▴"
    }

    /** 保存后自动重取统计（无独立刷新按钮；主界面设置按钮旁的「刷新」才是刷新 LI 网页）。 */
    private fun refreshStats() {
        tvStats.text = "刷新中…"
        bindStatus()
        AppBus.refreshStats?.invoke()
        // 注入 + 回写需要一点时间，稍后重读缓存的统计
        Handler(Looper.getMainLooper()).postDelayed({ showStats() }, 1200)
    }

    /** 手动检查网页更新：委托 WebBundleManager，结果回显到状态（含当前/最新内核与具体原因）。 */
    private fun checkUpdate() {
        val tv = findViewById<TextView>(R.id.tvUpdateStatus)
        tv.text = "检查中…（联网查询 li 仓库最新 Release）"
        webBundle.checkAndUpdate { result ->
            runOnUiThread {
                val sb = StringBuilder("更新状态：${result.message}")
                if (result.installedVersion.isNotEmpty()) sb.append("\n当前内核：${result.installedVersion}")
                if (result.latestVersion.isNotEmpty()) sb.append("\n最新内核：${result.latestVersion}")
                tv.text = sb.toString()
                if (result.updated) toast("网页已更新，请重启 App 生效")
            }
        }
    }

    // ===== 关于 =====
    private fun showVersions() {
        findViewById<TextView>(R.id.tvAppVersion).text = "本机应用版本：${getAppVersion()}"
        // 网页内核版本来自热更新已装版本（基线为 assets 内嵌版本）
        findViewById<TextView>(R.id.tvLiVersion).text =
            "网页内核版本：${webBundle.getInstalledVersion()}（自动热更新）\n" +
            "更新来源：GitHub 仓库 2632143580/li 的 Release 页面\n" +
            "（改网页只需推送仓库，App 无需重装）"
    }

    private fun getAppVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "未知"
    } catch (_: Exception) { "未知" }

    // ===== 数据面板统计 =====
    private fun showStats() {
        val sb = StringBuilder()
        val appFile = File(filesDir.parentFile, "shared_prefs/li_companion.xml")
        val appBytes = if (appFile.exists()) appFile.length() else 0
        sb.append("App 原生配置占用：${formatBytes(appBytes)}\n")

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
        tvStats.text = sb.toString()
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
