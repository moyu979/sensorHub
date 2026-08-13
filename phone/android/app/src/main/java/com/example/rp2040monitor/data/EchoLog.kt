package com.example.rp2040monitor.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 回显日志缓冲（调试用）
 *
 * 全局单例，USB 通信的关键信息（枚举 / 权限 / 收发 / 解析结果）实时写入这里，
 * 由 MonitorScreen 的“回显”对话框展示，方便排查手机连不上 RP2040 的问题。
 */
object EchoLog {

    private const val MAX_LINES = 500

    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** 所有日志行（带时间戳），UI 订阅此流实时刷新 */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    /** 追加一行带时间戳的日志 */
    @Synchronized
    fun log(msg: String) {
        val line = "[${timeFormat.format(Date())}] $msg"
        val current = _lines.value.toMutableList()
        current.add(line)
        if (current.size > MAX_LINES) {
            current.subList(0, current.size - MAX_LINES).clear()
        }
        _lines.value = current
    }

    /** 清空所有日志 */
    fun clear() {
        _lines.value = emptyList()
    }
}
