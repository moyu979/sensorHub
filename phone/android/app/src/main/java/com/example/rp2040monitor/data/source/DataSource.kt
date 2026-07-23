package com.example.rp2040monitor.data.source

import com.example.rp2040monitor.data.model.SensorData

/**
 * 数据源接口
 *
 * 所有数据采集源（模拟数据、串口数据、视频分析、音频分析等）
 * 都应实现此接口，以确保文件位置和调用方式一致。
 *
 * 添加新数据源时:
 * 1. 在 [source] 包下创建类实现此接口
 * 2. 若数据源有特殊依赖，在 source/ 下建子目录组织
 *    例如: source/video/, source/audio/, source/serial/
 */
interface DataSource {

    /**
     * 生成/采集一条传感器数据
     */
    fun generate(): SensorData

    /**
     * 获取当前可用的字段名列表
     */
    fun currentFieldNames(): List<String>
}
