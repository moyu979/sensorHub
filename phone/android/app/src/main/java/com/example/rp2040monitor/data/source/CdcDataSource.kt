package com.example.rp2040monitor.data.source

import android.content.Context
import android.util.Log
import com.example.rp2040monitor.data.EchoLog
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

        EchoLog.log("⏳ 正在连接 USB 串口…")
        val acquiredPort = manager.acquire(baudRate)
        port = acquiredPort
        if (acquiredPort == null) {
            EchoLog.log("❌ 无法获取 USB 串口（详见上方日志）")
            Log.w(TAG, "无法获取 USB 串口")
            return false
        }
        EchoLog.log("✅ USB 串口已连接 @ ${baudRate}bps")
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
            // ---- 第 1 步：发送 "get" 命令（固件要求以换行符结尾才执行） ----
            val command = "get\r\n"
            try {
                currentPort.write(command.toByteArray(StandardCharsets.US_ASCII), 500)
                EchoLog.log("→ 发送命令: get + CRLF")
                Log.d(TAG, "已发送: $command")
            } catch (e: Exception) {
                // 写失败（多为 USB CDC OUT 缓冲瞬时占满 / 固件短暂未读）：
                // 本轮按空响应处理、不断开重连。断开→重连本身有 claim 失败
                // 风险且耗时（会加重“正在连接/initializing 反复刷屏”），
                // 等下一轮重试即可。
                EchoLog.log("⚠️ 写命令异常（本轮跳过，不重连）: ${e.javaClass.simpleName}: ${e.message ?: "未知"}")
                Log.w(TAG, "写命令失败，按空响应处理", e)
                return SensorData(
                    fields = emptyMap(),
                    status = "EMPTY_RESPONSE"
                )
            }

            // ---- 第 2 步：读取 JSON 响应 ----
            // readJsonLine 内部把普通超时（无数据）转成 null → 空响应；
            // 连接异常（设备掉线）会向上抛，由下方 catch 断开重连。
            val response = readJsonLine(currentPort)
            if (response.isNullOrBlank()) {
                EchoLog.log("⚠️ 收到空响应（2s 超时，固件未回复）")
                Log.w(TAG, "收到空响应")
                return SensorData(
                    fields = emptyMap(),
                    status = "EMPTY_RESPONSE"
                )
            }
            EchoLog.log("← 收到数据(${response.length} 字符): ${response.take(200)}")

            // ---- 2.5) 设备停在 REPL 的检测与自动恢复 ----
            // 若设备没有运行 main.py（停在普通/RAW REPL），Android 发的 "get"
            // 会被 REPL 当代码求值 → "File <stdin>, line 1 ... NameError: name
            // 'get' is not defined"（不是 main.py 在跑）。此时发 machine.reset()
            // + Ctrl-D 软重启设备，让 main.py 自动跑起来，避免一直 PARSE_ERROR。
            if (response.contains("NameError") ||
                response.contains("<stdin>") ||
                response.contains("Traceback")) {
                EchoLog.log("⚠️ 设备停在 REPL（main.py 未运行），软重启设备以恢复采集…")
                try {
                    currentPort.write("machine.reset()\n".toByteArray(StandardCharsets.US_ASCII), 500)
                    currentPort.write(byteArrayOf(4), 500)   // Ctrl-D：普通 REPL=软重启 / RAW REPL=提交执行
                } catch (e: Exception) {
                    Log.w(TAG, "发送软重启命令异常", e)
                }
                return SensorData(
                    fields = emptyMap(),
                    status = "REPL_RESET"
                )
            }

            // ---- 第 3 步：解析 JSON ----
            parseJsonToSensorData(response)

        } catch (e: Exception) {
            // 走到这里 = read 阶段连接异常（设备掉线 / 连接断开），此时才断开重连。
            EchoLog.log("❌ 读取连接异常，准备重连: ${e.javaClass.simpleName}: ${e.message ?: "未知错误"}")
            Log.e(TAG, "读取异常，尝试重连", e)
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

    /**
     * 释放串口资源。切换数据源（如切回模拟数据）时调用，
     * 确保 USB 接口被正确释放，否则残留连接会占用接口导致下次 claim 失败。
     */
    override fun close() {
        disconnect()
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

                    // 优先：整段内容就是一个完整 JSON（固件正常 print 一行 JSON 时）
                    val cleaned = raw.trim()
                    if (cleaned.startsWith("{") && cleaned.endsWith("}")) {
                        try {
                            JsonParser.parseString(cleaned)
                            EchoLog.log("✓ 已识别到完整 JSON，长度=${cleaned.length}")
                            Log.d(TAG, "JSON 解析成功，长度=${cleaned.length}")
                            return cleaned
                        } catch (_: JsonSyntaxException) {
                            // 整段不是合法 JSON，继续尝试提取
                        }
                    }

                    // 兜底：从最后一个 '{' 截到最后一个 '}' 提取 JSON 对象，
                    // 容忍响应流里混入非 JSON 输出（如固件的调试 print）。
                    val lastOpen = raw.lastIndexOf('{')
                    val lastClose = raw.lastIndexOf('}')
                    if (lastOpen >= 0 && lastClose > lastOpen) {
                        val candidate = raw.substring(lastOpen, lastClose + 1)
                        try {
                            JsonParser.parseString(candidate)
                            EchoLog.log("✓ 已识别到完整 JSON（含前导输出），长度=${candidate.length}")
                            Log.d(TAG, "JSON 解析成功（提取），长度=${candidate.length}")
                            return candidate
                        } catch (_: JsonSyntaxException) {
                            // 仍不完整，继续读取
                        }
                    }
                } else if (len == 0 && baos.size() > 0) {
                    // 已经没有更多数据可读，返回当前已累积的内容
                    break
                }
            } catch (e: Exception) {
                // 连接类异常（设备掉线/连接断开）向上抛，由 generate() 决定断开重连；
                // 普通超时不会走到这里（usb-serial 的 read 超时返回 0，不抛异常）。
                Log.w(TAG, "读取串口异常（连接异常，上抛触发重连）", e)
                throw e
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
