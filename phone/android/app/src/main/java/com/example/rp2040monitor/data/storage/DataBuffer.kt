package com.example.rp2040monitor.data.storage

import com.example.rp2040monitor.data.model.SensorData

/**
 * 内存数据缓冲
 *
 * 每个字段维护一个定长环形缓冲区，供图表快速读取。
 * 相比直接读磁盘，内存缓冲具有纳秒级延迟。
 */
class DataBuffer(private val maxSize: Int = 60) {

    /** fieldName -> (timestamp, value) 列表 */
    private val buffers = mutableMapOf<String, MutableList<Pair<Long, Double>>>()

    /** 当前持有的所有字段名 */
    fun fieldNames(): Set<String> = buffers.keys.toSet()

    /**
     * 追加一条传感器数据到内存缓冲区
     */
    fun append(data: SensorData) {
        for ((name, value) in data.fields) {
            val list = buffers.getOrPut(name) { mutableListOf() }
            list.add(data.timestamp to value)
            // 裁剪到 maxSize
            if (list.size > maxSize) {
                list.removeAt(0)
            }
        }
    }

    /**
     * 获取某个字段最近 N 个数据点（从旧到新）
     */
    fun getRecent(fieldName: String, count: Int = maxSize): List<Pair<Long, Double>> {
        val list = buffers[fieldName] ?: return emptyList()
        return list.takeLast(count)
    }

    /**
     * 清空所有缓冲数据
     */
    fun clear() {
        buffers.clear()
    }
}
