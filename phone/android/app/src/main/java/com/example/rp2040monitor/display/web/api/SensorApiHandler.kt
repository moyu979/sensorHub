package com.example.rp2040monitor.display.web.api

import com.example.rp2040monitor.data.EchoLog
import com.example.rp2040monitor.data.model.SensorData
import com.example.rp2040monitor.data.storage.DataBuffer
import com.example.rp2040monitor.data.storage.DataLogger
import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * 传感器 REST API 处理逻辑
 *
 * 提供以下端点:
 * - GET /api/fields   → 获取所有可用字段名
 * - GET /api/current  → 获取最新一条传感器数据
 * - GET /api/history/<field>?count=N → 获取某字段历史数据
 * - GET /api/status   → 获取服务器运行状态
 */
class SensorApiHandler(
    private val dataBuffer: DataBuffer,
    private val dataLogger: DataLogger? = null,
    private val sourceMode: () -> String = { "unknown" }
) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /** 最近一次采集的数据，由外部每轮更新 */
    @Volatile
    var latestData: SensorData? = null

    /**
     * 处理 API 请求
     * @return JSON 字符串
     */
    fun handle(uri: String, queryParams: Map<String, String>): ApiResult {
        return when {
            uri == "/api/fields"  -> handleFields()
            uri == "/api/current" -> handleCurrent()
            uri == "/api/history/query" -> handleHistoryQuery(queryParams)
            uri.startsWith("/api/history/") -> handleHistory(uri, queryParams)
            uri == "/api/status"  -> handleStatus()
            uri == "/api/log"     -> handleLog()
            else -> ApiResult(404, """{"error":"not_found","message":"未知的 API 端点: $uri"}""")
        }
    }

    // ---- 内部处理 ----

    private fun handleFields(): ApiResult {
        val fields = dataBuffer.fieldNames().sorted()
        val json = gson.toJson(mapOf(
            "fields" to fields,
            "count" to fields.size
        ))
        return ApiResult(200, json)
    }

    private fun handleCurrent(): ApiResult {
        val data = latestData
        if (data == null) {
            return ApiResult(200, """{"timestamp":0,"status":"NO_DATA","fields":{}}""")
        }
        val json = gson.toJson(mapOf(
            "timestamp" to data.timestamp,
            "status" to data.status,
            "fields" to data.fields
        ))
        return ApiResult(200, json)
    }

    private fun handleHistory(uri: String, queryParams: Map<String, String>): ApiResult {
        // 从 /api/history/temperature 提取字段名
        val fieldName = uri.removePrefix("/api/history/")
            .trim('/')
            .ifEmpty { return ApiResult(400, """{"error":"bad_request","message":"缺少字段名"}""") }

        val count = queryParams["count"]?.toIntOrNull()?.coerceIn(1, 300) ?: 60
        val points = dataBuffer.getRecent(fieldName, count)

        val dataList = points.map { (ts, value) ->
            mapOf("timestamp" to ts, "value" to value)
        }

        val json = gson.toJson(mapOf(
            "field" to fieldName,
            "count" to dataList.size,
            "data" to dataList
        ))
        return ApiResult(200, json)
    }

    /**
     * 按日期范围和时间段查询历史数据（从落盘 CSV 读取）
     * GET /api/history/query?startDate=2026-07-23&startTime=00:00&endDate=2026-07-25&endTime=23:59&fields=temperature,humidity
     */
    private fun handleHistoryQuery(queryParams: Map<String, String>): ApiResult {
        val logger = dataLogger
        if (logger == null) {
            return ApiResult(503, """{"error":"unavailable","message":"历史数据记录器未启用"}""")
        }

        val startDate = queryParams["startDate"] ?: return ApiResult(400, """{"error":"bad_request","message":"缺少 startDate 参数"}""")
        val endDate = queryParams["endDate"] ?: return ApiResult(400, """{"error":"bad_request","message":"缺少 endDate 参数"}""")
        val startTime = queryParams["startTime"] ?: "00:00"
        val endTime = queryParams["endTime"] ?: "23:59"
        val fieldsParam = queryParams["fields"] ?: return ApiResult(400, """{"error":"bad_request","message":"缺少 fields 参数"}""")
        val fieldList = fieldsParam.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        if (fieldList.isEmpty()) {
            return ApiResult(400, """{"error":"bad_request","message":"fields 不能为空"}""")
        }

        // 解析时间范围为时间戳
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        val startMs = try {
            dateFormat.parse("$startDate $startTime")?.time ?: throw Exception()
        } catch (e: Exception) {
            return ApiResult(400, """{"error":"bad_request","message":"起始时间格式错误"}""")
        }
        val endMs = try {
            dateFormat.parse("$endDate $endTime")?.time ?: throw Exception()
        } catch (e: Exception) {
            return ApiResult(400, """{"error":"bad_request","message":"结束时间格式错误"}""")
        }

        if (startMs > endMs) {
            return ApiResult(400, """{"error":"bad_request","message":"起始时间不能晚于结束时间"}""")
        }

        val rawData = logger.queryByRange(fieldList, startMs, endMs)

        // 转换为 JSON 友好格式
        val resultData = rawData.mapValues { (_, points) ->
            points.map { (ts, value) ->
                mapOf("timestamp" to ts, "value" to value)
            }
        }

        val json = gson.toJson(mapOf(
            "startDate" to startDate,
            "startTime" to startTime,
            "endDate" to endDate,
            "endTime" to endTime,
            "fields" to resultData
        ))
        return ApiResult(200, json)
    }

    private fun handleStatus(): ApiResult {
        val hasData = latestData != null
        val fieldCount = dataBuffer.fieldNames().size
        val json = gson.toJson(mapOf(
            "app" to "sensorHub",
            "version" to "1.0",
            "data_source" to if (hasData) "active" else "idle",
            "source_mode" to sourceMode(),
            "field_count" to fieldCount,
            "fields" to dataBuffer.fieldNames().sorted(),
            "buffer_size" to 60
        ))
        return ApiResult(200, json)
    }

    /**
     * 回显日志（网页端查看 USB/通信调试日志）
     * GET /api/log → {"count":N,"lines":["[HH:mm:ss.SSS] ...", ...]}
     */
    private fun handleLog(): ApiResult {
        val lines = EchoLog.lines.value.takeLast(300)
        val json = gson.toJson(mapOf(
            "count" to lines.size,
            "lines" to lines
        ))
        return ApiResult(200, json)
    }
}

/**
 * API 响应结果
 */
data class ApiResult(
    val httpCode: Int,
    val body: String
)
