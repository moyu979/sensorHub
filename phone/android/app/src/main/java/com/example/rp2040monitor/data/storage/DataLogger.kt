package com.example.rp2040monitor.data.storage

import android.content.Context
import com.example.rp2040monitor.data.model.SensorData
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据落盘模块
 *
 * 对每个字段独立写一个 CSV 文件: 时间戳, 数值。
 * 日志目录: context.filesDir/sensor_logs/
 */
class DataLogger(private val context: Context) {

    private val logDir: File = File(context.filesDir, "sensor_logs")

    /** 日期格式化，精确到毫秒 */
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    init {
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
    }

    /**
     * 写入一条传感器数据
     * 每个字段写入各自对应的 csv 文件
     */
    fun log(data: SensorData) {
        val timeStr = dateFormat.format(Date(data.timestamp))
        for ((fieldName, value) in data.fields) {
            val file = getFieldFile(fieldName)
            // CSV: 时间, 数值
            file.appendText("$timeStr,${data.timestamp},$value\n")
        }
    }

    /**
     * 获取当前日志中存在的所有字段名
     * 通过扫描日志目录下的 .csv 文件得到
     */
    fun getLoggedFieldNames(): List<String> {
        if (!logDir.exists()) return emptyList()
        return logDir.listFiles()
            ?.filter { it.extension == "csv" && it.isFile }
            ?.map { it.nameWithoutExtension }
            ?.sorted() ?: emptyList()
    }

    /**
     * 读取某个字段最近 N 条历史数据
     * @return List of Pair(timestamp, value)
     */
    fun readRecent(fieldName: String, maxLines: Int = 60): List<Pair<Long, Double>> {
        val file = getFieldFile(fieldName)
        if (!file.exists()) return emptyList()

        return file.useLines { lines ->
            lines
                .map { line ->
                    val parts = line.split(",")
                    if (parts.size >= 3) {
                        Pair(parts[1].trim().toLongOrNull() ?: 0L,
                             parts[2].trim().toDoubleOrNull() ?: 0.0)
                    } else null
                }
                .filterNotNull()
                .toList()
                .takeLast(maxLines)
        }
    }

    /**
     * 按日期范围、时间段和字段名查询历史数据
     * @param fields 要查询的字段名列表
     * @param startMs 起始时间戳（毫秒，含）
     * @param endMs 结束时间戳（毫秒，含）
     * @return Map<字段名, List<Pair(时间戳, 数值)>>
     */
    fun queryByRange(fields: List<String>, startMs: Long, endMs: Long): Map<String, List<Pair<Long, Double>>> {
        val result = mutableMapOf<String, List<Pair<Long, Double>>>()
        for (field in fields) {
            val file = getFieldFile(field)
            if (!file.exists()) {
                result[field] = emptyList()
                continue
            }
            val points = file.useLines { lines ->
                lines
                    .map { line ->
                        val parts = line.split(",")
                        if (parts.size >= 3) {
                            val ts = parts[1].trim().toLongOrNull() ?: 0L
                            val value = parts[2].trim().toDoubleOrNull() ?: 0.0
                            Pair(ts, value)
                        } else null
                    }
                    .filterNotNull()
                    .filter { (ts, _) -> ts in startMs..endMs }
                    .toList()
            }
            result[field] = points
        }
        return result
    }

    /** 获取日志目录路径（用于调试） */
    fun getLogDirPath(): String = logDir.absolutePath

    private fun getFieldFile(fieldName: String): File {
        // 对字段名做安全处理，防止文件名非法字符
        val safeName = fieldName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return File(logDir, "$safeName.csv")
    }
}
