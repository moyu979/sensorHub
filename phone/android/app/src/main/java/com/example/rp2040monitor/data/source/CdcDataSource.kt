package com.example.rp2040monitor.data.source

import android.content.Context
import android.util.Log
import com.example.rp2040monitor.data.model.SensorData
import com.example.rp2040monitor.data.usb.UsbSerialManager
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * USB CDC 串口数据源
 *
 * 通过 USB CDC (Serial) 与 RP2040 单片机通信：
 * 1. 发送 "GET" 命令（仅三个字母）
 * 2. 单片机返回 JSON 格式的传感器数据
 * 3. 解析 JSON 并构造 [SensorData]
 *
 * 使用 [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android)
 * 库处理 USB 串口通信，已在 build.gradle.kts 中添加依赖。
 *
 * 依赖权限：`<uses-feature android:name="android.hardware.usb.host" />`
 * 需要用户在连接时授权 USB 设备访问权限。
 *
 * 支持通过 [serialManager] 与 [MicroPythonUploader] 共享同一 USB 端口，
 * 避免 OTA 上传期间端口冲突。
 *
 * @param context Android Context
 * @param baudRate 串口波特率，默认 115200
 * @param serialManager 可选的外部共享 USB 管理器
 */
class CdcDataSource(
    private val context: Context,
    private val baudRate: Int = 115200,
    private val serialManager: UsbSerialManager? = null
) : DataSource {

    private var port: UsbSerialPort? = null
    private val manager: UsbSerialManager
        get() = serialManager ?: UsbSerialManager(context)

    companion object {
        private const val TAG = "CdcDataSource"
        /** 读取超时(毫秒) */
        private const val READ_TIMEOUT_MS = 2000L
        /** 单次读取缓冲区大小 */
        private const val READ_BUF_SIZE = 4096
        /** 两次读取之间的等待时间(毫秒) */
        private const val READ_INTERVAL_MS = 50L
    }

    /**
     * 尝试连接 USB 串口设备。
     * 如果已连接则跳过。
     *
     * 优先使用共享的 [UsbSerialManager]，否则自己管理连接。
     */
    private fun connect(): Boolean {
        if (port != null) return true

        val acquiredPort = manager.acquire(baudRate)
        port = acquiredPort
        if (acquiredPort == null) {
            Log.w(TAG, "无法获取 USB 串口")
            return false
        }
        Log.i(TAG, "USB 串口已连接 @ ${baudRate}bps")
        return true
    }

    /**
     * 断开 USB 串口连接
     *
     * 使用共享管理器时只释放引用，不真正关闭（由其他使用者决定）。
     */
    private fun disconnect() {
        if (serialManager != null) {
            // 共享模式：只释放引用
            manager.release()
            port = null
        } else {
            // 独立模式：直接关闭
            try {
                port?.close()
            } catch (e: Exception) {
                Log.w(TAG, "关闭串口时异常", e)
            }
            port = null
        }
    }

    // ---------------------------------------------------------------
    // DataSource 接口实现
    // ---------------------------------------------------------------

    override fun generate(): SensorData {
        // 确保串口已连接
        if (port == null) {
            if (!connect()) {
                return SensorData(
                    fields = emptyMap(),
                    status = "DISCONNECTED"
                )
            }
        }

        val currentPort = port ?: return SensorData(
            fields = emptyMap(),
            status = "DISCONNECTED"
        )

        return try {
            // ---- 第 1 步：发送 "GET" 命令 ----
            val command = "GET"
            currentPort.write(command.toByteArray(StandardCharsets.US_ASCII), 500)
            Log.d(TAG, "已发送: $command")

            // ---- 第 2 步：读取 JSON 响应 ----
            val response = readJsonLine(currentPort)
            if (response.isNullOrBlank()) {
                Log.w(TAG, "收到空响应")
                return SensorData(
                    fields = emptyMap(),
                    status = "EMPTY_RESPONSE"
                )
            }

            // ---- 第 3 步：解析 JSON ----
            parseJsonToSensorData(response)

        } catch (e: Exception) {
            Log.e(TAG, "采集异常，尝试重连", e)
            disconnect()
            SensorData(
                fields = emptyMap(),
                status = "ERROR: ${e.message ?: "未知错误"}"
            )
        }
    }

    override fun currentFieldNames(): List<String> {
        // 无法预知字段名，返回空列表由上层通过实际数据推断
        return emptyList()
    }

    // ---------------------------------------------------------------
    // 串口读取
    // ---------------------------------------------------------------

    /**
     * 从串口读取数据直到收到一个完整的 JSON 对象或超时。
     *
     * 单片机发送的 JSON 可能以换行符结尾，也可能不带换行符。
     * 这里采用策略：不断累积字节，尝试验证是否为合法 JSON，
     * 若合法则返回；若超时则将已收到的内容作为原始字符串返回。
     */
    private fun readJsonLine(port: UsbSerialPort): String? {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(READ_BUF_SIZE)
        val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            try {
                val len = port.read(buf, 100)
                if (len > 0) {
                    baos.write(buf, 0, len)

                    // 尝试解析当前累积的数据
                    val raw = baos.toString(StandardCharsets.UTF_8.name())
                    val cleaned = raw.trim()

                    // 如果包含完整 JSON 对象，尝试解析
                    if (cleaned.startsWith("{") && cleaned.endsWith("}")) {
                        try {
                            JsonParser.parseString(cleaned)
                            Log.d(TAG, "JSON 解析成功，长度=${cleaned.length}")
                            return cleaned
                        } catch (_: JsonSyntaxException) {
                            // 不完整的 JSON，继续读取
                        }
                    }
                } else if (len == 0 && baos.size() > 0) {
                    // 已经没有更多数据可读，返回当前已累积的内容
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "读取串口异常", e)
                break
            }
        }

        // 超时后返回已累积的内容（可能是原始字符串）
        val result = baos.toString(StandardCharsets.UTF_8.name()).trim()
        return result.ifBlank { null }
    }

    // ---------------------------------------------------------------
    // JSON 解析
    // ---------------------------------------------------------------

    /**
     * 将单片机返回的 JSON 字符串解析为 [SensorData]。
     *
     * 期望的 JSON 格式示例：
     * ```json
     * {
     *   "temperature": 25.3,
     *   "humidity": 60.1,
     *   "voltage": 3.30,
     *   "status": "OK"
     * }
     * ```
     *
     * - 字段名为字符串，字段值为数值 → 加入 [SensorData.fields]
     * - 若 JSON 顶层包含 "status" 字段且值为字符串 → 作为状态
     * - 非数值的字段会被忽略（不会放入 fields）
     * - 时间戳由 [SensorData] 自动生成
     */
    private fun parseJsonToSensorData(jsonString: String): SensorData {
        return try {
            val json = JsonParser.parseString(jsonString).asJsonObject
            val fields = mutableMapOf<String, Double>()
            var status = "OK"

            for ((key, element) in json.entrySet()) {
                when {
                    // "status" 特殊处理
                    key.equals("status", ignoreCase = true) && element.isJsonPrimitive -> {
                        status = element.asString
                    }
                    // 数值字段
                    element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> {
                        fields[key] = element.asDouble
                    }
                    // 其他类型跳过
                }
            }

            if (fields.isEmpty()) {
                Log.w(TAG, "JSON 中未解析到任何数值字段: $jsonString")
            }

            SensorData(
                fields = fields,
                status = status
            )
        } catch (e: Exception) {
            Log.e(TAG, "JSON 解析失败: $jsonString", e)
            SensorData(
                fields = mapOf("parse_error" to 0.0),
                status = "PARSE_ERROR: ${e.message ?: "格式错误"}"
            )
        }
    }
}
