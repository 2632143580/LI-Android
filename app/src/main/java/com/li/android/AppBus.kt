package com.li.android

/**
 * 进程内轻量事件总线（无需额外依赖）。
 *
 * MainActivity 在前台存活时注册 refreshStats 回调；SettingsActivity 点「刷新」时调用它，
 * 触发 MainActivity 重新向 WebView 注入统计脚本，从而让设置页拿到最新的 LI 存储统计。
 * 若 MainActivity 已被销毁（回调为 null），刷新仅重新展示已缓存的统计，不会崩溃。
 */
object AppBus {
    var refreshStats: (() -> Unit)? = null
}
