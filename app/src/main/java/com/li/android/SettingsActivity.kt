package com.li.android

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

/**
 * 设置页：填 LLM 的 Key / 接口地址 / 模型 / 闲置阈值 / 定时表。
 * 这里存的 Key 是 App 侧主动推送用的，和 LI 网页里的 Key 相互独立。
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
    }
}
