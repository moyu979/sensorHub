package com.example.rp2040monitor.data

import android.content.Context
import android.util.Log
import com.example.rp2040monitor.data.model.SensorData
import com.example.rp2040monitor.data.source.DataSource
import com.example.rp2040monitor.data.source.FakeDataSource
import com.example.rp2040monitor.data.storage.DataBuffer
import com.example.rp2040monitor.data.storage.DataLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 数据采集管理器
 *
 * 封装采集循环，与 UI 完全解耦。
 * 由 MainActivity 通过 lifecycleScope 启动/停止。
 *
 * 对外暴露 StateFlow，供 MonitorScreen / WebServer 订阅。
 *
 * 添加新数据源时，只需替换 [dataSource] 参数：
 * ```kotlin
 * DataCollectionManager(context, SerialDataSource())
 * ```
 */
class DataCollectionManager(
    private val context: Context,
    dataSource: DataSource = FakeDataSource(),
    private val dataBuffer: DataBuffer = DataBuffer(maxSize = 60),
    private val onDataCollected: ((SensorData) -> Unit)? = null
) {
    private val dataLogger = DataLogger(context)

    /** 当前数据源，@Volatile 确保采集循环能读到最新的切换 */
    @Volatile
    private var currentDataSource: DataSource = dataSource

    // ---- 对外暴露的 StateFlow ----
    private val _latestData = MutableStateFlow<SensorData?>(null)
    val latestData: StateFlow<SensorData?> = _latestData.asStateFlow()

    private val _fieldNames = MutableStateFlow<List<String>>(emptyList())
    val fieldNames: StateFlow<List<String>> = _fieldNames.asStateFlow()

    private val _chartData = MutableStateFlow<List<Double>>(emptyList())
    val chartData: StateFlow<List<Double>> = _chartData.asStateFlow()

    /** 共享 DataBuffer（WebServer 也用它读取历史数据） */
    fun getDataBuffer(): DataBuffer = dataBuffer

    /** 共享 DataLogger（WebServer 也用它读取历史数据） */
    fun getDataLogger(): DataLogger = dataLogger

    // 内部跟踪当前选中的字段，用于决定 chartData 画哪个字段
    private var _selectedField = ""

    /**
     * 运行时切换数据源。
     * 采集循环会在下一次迭代自动使用新数据源。
     */
    fun switchDataSource(newSource: DataSource) {
        val old = currentDataSource
        currentDataSource = newSource
        // 释放旧数据源（如关闭 USB 端口），避免接口残留占用导致后续 claim 失败
        if (old !== newSource) {
            try {
                old.close()
            } catch (e: Exception) {
                Log.w("DataCollectionManager", "关闭旧数据源异常", e)
            }
        }
        Log.i("DataCollectionManager", "数据源已切换: ${newSource::class.simpleName}")
    }

    /** 当前是否使用真实数据源（非 FakeDataSource） */
    fun isRealDataSource(): Boolean = currentDataSource !is FakeDataSource

    /**
     * 由外部切换图表展示的字段
     */
    fun selectField(field: String) {
        _selectedField = field
        refreshChartData()
    }

    /**
     * 采集循环 —— 一个无限循环，由外部协程启动
     *
     * 建议在 Activity 的 lifecycleScope 中启动：
     * ```kotlin
     * lifecycleScope.launch {
     *     repeatOnLifecycle(Lifecycle.State.STARTED) {
     *         collectionManager.collect()
     *     }
     * }
     * ```
     */
    suspend fun collect() {
        while (true) {
            try {
                // 1. 从数据源采集
                val data = currentDataSource.generate()

                // 2. 写内存缓冲（图表 + API 用）
                dataBuffer.append(data)

                // 3. 落盘（IO 线程）
                withContext(Dispatchers.IO) {
                    dataLogger.log(data)
                }

                // 4. 更新字段列表
                val allFields = dataBuffer.fieldNames().sorted()
                if (allFields != _fieldNames.value) {
                    _fieldNames.value = allFields
                    if (_selectedField.isEmpty() || _selectedField !in allFields) {
                        _selectedField = allFields.firstOrNull() ?: ""
                    }
                    refreshChartData()
                }

                // 5. 刷新图表数据
                refreshChartData()

                // 6. 通知外部监听者（WebServer 等）
                onDataCollected?.invoke(data)

                // 7. 发射最新数据 → UI 自动重组
                _latestData.value = data

            } catch (e: Exception) {
                Log.e("DataCollectionManager", "采集异常", e)
            }

            delay(1000)
        }
    }

    private fun refreshChartData() {
        if (_selectedField.isNotEmpty()) {
            _chartData.value =
                dataBuffer.getRecent(_selectedField, 60).map { it.second }
        }
    }
}
