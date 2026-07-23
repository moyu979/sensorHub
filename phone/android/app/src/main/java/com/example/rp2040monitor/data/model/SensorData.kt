package com.example.rp2040monitor.data.model

/**
 * 传感器数据模型
 * 支持动态字段: fields 是一个 Map，字段名和字段值可以动态增减
 */
data class SensorData(
    /** 所有数值型字段的键值对，字段名 -> 数值 */
    val fields: Map<String, Double>,
    /** 状态文本 */
    val status: String = "OK",
    /** 数据采集时间戳(毫秒) */
    val timestamp: Long = System.currentTimeMillis()
) {
    /** 获取所有数值字段名列表 */
    fun fieldNames(): List<String> = fields.keys.toList().sorted()
}
