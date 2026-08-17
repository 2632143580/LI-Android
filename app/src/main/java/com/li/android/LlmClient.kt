package com.li.android

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Scanner

/**
 * 直连 LLM（OpenAI 兼容 /v1/chat/completions 接口）。
 * 不依赖任何服务器：App 直接 HTTPS 请求你的 LLM 服务商，和 LI 网页里干的事一样。
 */
object LlmClient {

    fun fetchCompanionMessage(prefs: AppPreferences): String? {
        if (prefs.apiKey.isBlank()) return null
        // 地址/模型允许留空：留空时用默认值（App 侧不存默认值，避免覆盖网页配置）
        val base = prefs.baseUrl.trim().removeSuffix("/")
            .ifBlank { "https://api.openai.com" }
        val model = prefs.model.trim().ifBlank { "gpt-4o-mini" }
        val url = URL("$base/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${prefs.apiKey}")
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 30000

            val now = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(System.currentTimeMillis())
            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", SYSTEM_PROMPT)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "现在是 $now，请主动给用户发一句简短的陪伴消息。")
                    })
                })
                put("temperature", 0.9)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != 200) return null
            val text = conn.inputStream.use { stream ->
                Scanner(stream).useDelimiter("\\A").let { if (it.hasNext()) it.next() else "" }
            }
            JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        } catch (e: Exception) {
            Log.e("LlmClient", "fetch failed", e)
            null
        } finally {
            conn.disconnect()
        }
    }

    private const val SYSTEM_PROMPT =
        "你是 li，用户的 AI 伙伴，像朋友一样主动关心用户。不要提问，直接发一句温暖、简短（30字内）的话。"
}
