package com.li.android

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 网页包（LI 网页）热更新管理器。
 *
 * 设计目标：把「LI 网页」与「App 壳」彻底解耦。网页不再焊死在 assets，而是运行时从
 * li 仓库的最新 GitHub Release 下载 zip 解压到 App 私有目录，WebView 加载该目录。
 * 这样改网页（ESM）只需推 li 仓库触发自动发布，手机 App 下次启动自动拉取，
 * 不必重新打包安装整个 App。只有改 App 壳本身（Kotlin 代码）才需重装一次。
 *
 * 存储位置：app 私有目录 /data/data/com.li.android/app_web/（文件管理器翻不到，系统隔离）。
 *   - index.html 等：解压出来的网页文件
 *   - version.json：{"releaseId":Long,"version":String} 记录已装版本
 *
 * 首次/无网兜底：app_web/index.html 不存在时，从 assets/index.html 复制一份基线，
 * 保证离线也能打开。基线 releaseId=0，version 取 assets/li_version.txt。
 *
 * 更新判定：GitHub Release 的 id 是全局递增整数。本地记录已装 releaseId；
 * 远程最新 id 更大即视为有更新。这与 tag/版本号无关，避免「频繁推却忘改版本号」导致不更新。
 */
class WebBundleManager(private val context: Context) {

    /** 网页解压目录（app 私有）。类型为 File，绝对路径形如 /data/data/com.li.android/app_web。 */
    val webDir: File = context.getDir("web", Context.MODE_PRIVATE)

    /** 入口网页文件。类型为 File。 */
    val indexFile: File get() = File(webDir, "index.html")

    /** 入口网页的 file:// URL，WebView 据此加载。类型为 String。 */
    fun indexUrl(): String = "file://" + indexFile.absolutePath

    /** 判断某 URL 是否为本应用加载的 LI 入口（决定是否注入桥接脚本 + 配置）。 */
    fun isLiEntry(url: String?): Boolean =
        url != null && url.contains("/app_web/") && url.endsWith("index.html")

    /**
     * 确保存在可加载的网页：若 app_web/index.html 不存在，从 assets 复制基线。
     * 必须在 WebView.loadUrl 之前调用。
     */
    fun ensureBaseline() {
        if (indexFile.exists()) return
        try {
            context.assets.open("index.html").use { input ->
                indexFile.outputStream().use { output -> input.copyTo(output) }
            }
            // 同时复制 dist 内其余页面（deps/diag/perf），保证相对路径加载可用
            copyAssetIfPresent("deps.html")
            copyAssetIfPresent("diag.html")
            copyAssetIfPresent("perf.html")
            writeVersion(0L, readBaselineVersion())
        } catch (e: Exception) {
            Log.e(TAG, "ensureBaseline 失败：${e.message}")
        }
    }

    private fun copyAssetIfPresent(name: String) {
        val target = File(webDir, name)
        if (target.exists()) return
        try {
            context.assets.open(name).use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
        } catch (_: Exception) { /* 该文件不存在则跳过 */ }
    }

    private fun readBaselineVersion(): String = try {
        context.assets.open("li_version.txt").bufferedReader().use { it.readText().trim() }
            .takeIf { it.isNotEmpty() } ?: "1.0.0"
    } catch (_: Exception) { "1.0.0" }

    /** 已安装的网页版本号（来自 version.json；基线为 li_version.txt 内容）。类型为 String。 */
    fun getInstalledVersion(): String =
        readVersionJson()?.optString("version")?.takeIf { it.isNotEmpty() } ?: readBaselineVersion()

    /** 已安装的 Release id（基线为 0）。类型为 Long。 */
    fun getInstalledReleaseId(): Long = readVersionJson()?.optLong("releaseId", 0L) ?: 0L

    private fun readVersionJson(): JSONObject? {
        val f = File(webDir, "version.json")
        if (!f.exists()) return null
        return try { JSONObject(f.readText()) } catch (_: Exception) { null }
    }

    private fun writeVersion(releaseId: Long, version: String) {
        File(webDir, "version.json").writeText(
            JSONObject().put("releaseId", releaseId).put("version", version).toString()
        )
    }

    /**
     * 检查并安装更新（后台线程执行，结果通过 onResult 回主线程）。
     * 网络异常/限流/无更新均安全返回，不影响当前网页使用。
     * 结果携带 当前内核/最新内核 与具体失败原因（HTTP 状态码等），便于设置页展示。
     */
    fun checkAndUpdate(onResult: (UpdateResult) -> Unit) {
        val installed = getInstalledVersion()
        Thread {
            try {
                val rel = fetchLatestRelease()
                if (rel == null) {
                    onResult(UpdateResult(false, "暂时无法获取更新：li 仓库暂无 Release 产物", installed))
                    return@Thread
                }
                if (rel.id <= getInstalledReleaseId()) {
                    onResult(UpdateResult(false, "已是最新内核（${rel.version}）", installed, rel.version))
                    return@Thread
                }
                val ok = downloadAndInstall(rel)
                onResult(
                    if (ok) UpdateResult(true, "已下载新内核 ${rel.version}，重启 App 生效", installed, rel.version)
                    else UpdateResult(false, "下载失败（网络中断或文件异常），下次启动自动重试", installed, rel.version)
                )
            } catch (e: Exception) {
                onResult(UpdateResult(false, "更新检查失败：${e.message ?: "未知错误"}", installed))
            }
        }.start()
    }

    /** 拉取 li 仓库最新 Release 信息；无可用 Release 返回 null，网络/HTTP 错误抛异常带状态码。 */
    private fun fetchLatestRelease(): ReleaseInfo? {
        val conn = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "li-android")
        }
        if (conn.responseCode != 200) {
            val code = conn.responseCode
            conn.disconnect()
            throw IOException("GitHub 返回 HTTP $code（未联网或接口限流）")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val arr = JSONArray(body)
        if (arr.length() == 0) return null
        val r = arr.getJSONObject(0)
        val assets = r.optJSONArray("assets") ?: JSONArray()
        var zipUrl = ""
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".zip")) {
                zipUrl = a.optString("browser_download_url")
                break
            }
        }
        if (zipUrl.isEmpty()) return null
        val tag = r.optString("tag_name", "")
        val version = tag.removePrefix("v").ifBlank { tag.ifBlank { "最新" } }
        return ReleaseInfo(id = r.optLong("id", 0L), tag = tag, version = version, zipUrl = zipUrl)
    }

    /** 下载 zip 并安全解压覆盖 app_web（防 zip slip），最后写 version.json。成功返回 true。 */
    private fun downloadAndInstall(rel: ReleaseInfo): Boolean {
        val tmp = File(webDir, ".tmp_dist.zip")
        try {
            val conn = (URL(rel.zipUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20000
                readTimeout = 60000
            }
            if (conn.responseCode != 200) { conn.disconnect(); return false }
            conn.inputStream.use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
            conn.disconnect()

            // 清空旧网页文件（保留 version.json 与临时 zip 本身）
            webDir.listFiles()?.forEach { f ->
                if (f.name != "version.json" && f.name != tmp.name) f.deleteRecursively()
            }

            // 安全解压（逐条目校验路径，防 zip slip 穿越）
            ZipInputStream(tmp.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val outFile = safeEntryFile(entry.name)
                        if (outFile != null) {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
            writeVersion(rel.id, rel.version)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "downloadAndInstall 失败：${e.message}")
            return false
        } finally {
            tmp.delete()
        }
    }

    /** 校验 zip 条目路径，防止路径穿越（zip slip）。越界或非法返回 null。 */
    private fun safeEntryFile(entryName: String): File? {
        val clean = entryName.replace("\\", "/").removePrefix("/")
        if (clean.startsWith("..") || clean.contains("../")) return null
        val target = File(webDir, clean)
        val webCanon = webDir.canonicalPath
        val targetCanon = target.canonicalPath
        if (targetCanon != webCanon && !targetCanon.startsWith(webCanon + File.separator)) return null
        return target
    }

    /** 远程 Release 摘要。 */
    data class ReleaseInfo(
        val id: Long,
        val tag: String,
        val version: String,
        val zipUrl: String
    )

    /** 一次检查/更新结果。updated=true 表示已下载新版本（需重启生效）。 */
    data class UpdateResult(
        val updated: Boolean,
        val message: String,
        /** 当前已装内核版本（基线或上次下载）。 */
        val installedVersion: String = "",
        /** 远端最新内核版本（检查失败时为空）。 */
        val latestVersion: String = ""
    )

    companion object {
        private const val TAG = "WebBundleManager"
        // li 仓库公开 Release 列表接口（未认证限速 60 次/小时/IP，真机足够）
        private const val RELEASES_API =
            "https://api.github.com/repos/2632143580/li/releases?per_page=1"
    }
}
