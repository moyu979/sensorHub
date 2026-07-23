package com.example.rp2040monitor.data.source

import android.util.Log
import com.example.rp2040monitor.data.model.SensorData
import kotlin.math.sin
import kotlin.random.Random

/**
 * 模拟数据源
 *
 * 产生正弦波 + 随机噪声的数据，用于开发和测试阶段。
 * 后续可被 [SerialDataSource] 等真实数据源替代。
 *
 * @see DataSource 数据源接口
 */
class FakeDataSource : DataSource {

    private var elapsed = 0.0

    /** 当前活跃的字段池 */
    private val fieldPool = mutableListOf(
        // freq = 10 / 周期(秒); 40s周期 → freq=0.25
        FieldDef("temperature", 25.0, 5.0, 0.25),
        FieldDef("voltage", 3.3, 0.15, 0.27),
        FieldDef("humidity", 60.0, 12.0, 0.23),
        FieldDef("pressure", 1013.0, 10.0, 0.20),
    )

    override fun generate(): SensorData {
        elapsed += 0.1

        val fields = mutableMapOf<String, Double>()
        for (def in fieldPool) {
            val value = def.baseValue +
                def.amplitude * sin(elapsed * def.freq * 2.0 * Math.PI) +
                (Random.nextDouble() - 0.5) * def.amplitude * 0.3
            fields[def.name] = String.format("%.2f", value).toDouble()
        }

        return SensorData(
            fields = fields,
            status = if (Random.nextDouble() < 0.95) "OK" else "WARNING"
        )
    }

    override fun currentFieldNames(): List<String> =
        fieldPool.map { it.name }.sorted()

    // ---- 以下为内部辅助 ----

    private data class FieldDef(
        val name: String,
        val baseValue: Double,
        val amplitude: Double,
        val freq: Double
    )
}
