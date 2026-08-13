package com.example.rp2040monitor.data.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.rp2040monitor.data.EchoLog
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * USB 串口管理器
 *
 * 使用引用计数管理 USB 串口连接的生命周期。
 * 让 [CdcDataSource] 和 [MicroPythonUploader] 共享同一端口，
 * 避免重复打开/关闭 USB 设备。
 *
 * 用法：
 * ```kotlin
 * val port = manager.acquire()   // 引用 +1
 * // ... 使用 port ...
 * manager.release()              // 引用 -1，归零时自动关闭
 * ```
 */
class UsbSerialManager(private val context: Context) {

    private var port: UsbSerialPort? = null
    private var referenceCount = 0

    companion object {
        private const val TAG = "UsbSerialManager"
        private const val DEFAULT_BAUD_RATE = 115200

        /** UsbManager.ACTION_USB_PERMISSION 是隐藏 API，这里用字面字符串 */
        private const val ACTION_USB_PERMISSION = "android.hardware.usb.action.USB_PERMISSION"

        /** 打开串口的最大尝试次数（RP2040 首次 claim 偶发失败） */
        private const val MAX_OPEN_ATTEMPTS = 4

        /** 每次重试前的等待基数（毫秒），按次数递增 */
        private const val OPEN_RETRY_DELAY_MS = 600L
    }

    /**
     * 获取串口实例（引用计数 +1）
     * 首次调用时建立 USB 连接
     */
    @Synchronized
    fun acquire(baudRate: Int = DEFAULT_BAUD_RATE): UsbSerialPort? {
        if (port == null) {
            port = openConnection(baudRate)
        }
        if (port != null) {
            referenceCount++
            Log.d(TAG, "acquire: refCount=$referenceCount")
        }
        return port
    }

    /**
     * 释放串口实例（引用计数 -1）
     * 计数归零时关闭连接
     */
    @Synchronized
    fun release() {
        referenceCount--
        Log.d(TAG, "release: refCount=$referenceCount")
        if (referenceCount <= 0) {
            closeConnection()
            referenceCount = 0
        }
    }

    /**
     * 强制关闭（忽略引用计数）
     */
    @Synchronized
    fun forceClose() {
        Log.w(TAG, "forceClose: 强制断开 USB")
        closeConnection()
        referenceCount = 0
    }

    /** 是否已连接 */
    @Synchronized
    fun isConnected(): Boolean = port != null

    /** 当前引用计数 */
    @Synchronized
    fun currentRefCount(): Int = referenceCount

    // ================================================================
    // 内部
    // ================================================================

    private fun openConnection(baudRate: Int): UsbSerialPort? {
        return try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

            // ---- 1. 枚举串口驱动（默认 prober 按接口类型识别 CDC-ACM） ----
            var driver = UsbSerialProber.getDefaultProber()
                .findAllDrivers(usbManager)
                .firstOrNull()

            // ---- 2. 兜底：默认 prober 找不到时，直接按 CDC-ACM 接口探测 ----
            if (driver == null) {
                driver = usbManager.deviceList.values
                    .filter { CdcAcmSerialDriver.probe(it) }
                    .map { CdcAcmSerialDriver(it) }
                    .firstOrNull()
                if (driver != null) {
                    EchoLog.log("ℹ️ 默认 prober 未识别，已通过 CDC-ACM 接口兜底找到设备")
                }
            }

            if (driver == null) {
                val allDevices = usbManager.deviceList.values.toList()
                if (allDevices.isEmpty()) {
                    EchoLog.log("❌ 手机 host 层未发现任何 USB 设备（deviceList 为空）")
                    EchoLog.log("   ➜ 排查：手机是否支持 OTG / USB host？拓展坞是否供电？")
                    EchoLog.log("     是否用 OTG 线直连？通知栏是否需开启 USB 模式 / OTG 开关？")
                } else {
                    EchoLog.log("❌ 共枚举到 ${allDevices.size} 个 USB 设备，但都未识别为串口：")
                    allDevices.forEach { d ->
                        EchoLog.log(
                            "   设备: VID=0x${Integer.toHexString(d.vendorId).uppercase()}" +
                                " PID=0x${Integer.toHexString(d.productId).uppercase()} 产品=${d.productName}"
                        )
                    }
                    EchoLog.log("   ➜ 若列表里有 VID=0x2E8A (RP2040) 却识别不了 = 固件 USB 描述符问题；")
                    EchoLog.log("     若列表里完全没有 0x2E8A = 拓展坞 / OTG / 供电问题")
                }
                Log.w(TAG, "未发现 USB 串口设备")
                return null
            }

            val device = driver.device
            EchoLog.log(
                "✅ 找到串口设备: VID=0x${Integer.toHexString(device.vendorId).uppercase()}" +
                    " PID=0x${Integer.toHexString(device.productId).uppercase()} 产品=${device.productName}"
            )

            // ---- 3. 关键：USB 权限（Android 无权限时 openDevice 必返回 null） ----
            if (!usbManager.hasPermission(device)) {
                EchoLog.log("⏳ 无 USB 权限，正在请求（请在系统弹窗中点击“允许”）…")
                Log.i(TAG, "请求 USB 权限")
                val granted = requestUsbPermission(usbManager, device)
                if (!granted || !usbManager.hasPermission(device)) {
                    EchoLog.log("❌ USB 权限未授予，无法打开设备")
                    Log.w(TAG, "USB 设备权限不足")
                    return null
                }
                EchoLog.log("✅ USB 权限已授予")
            } else {
                EchoLog.log("✅ 已具备 USB 权限")
            }

            // ---- 打开串口（含多次重试；RP2040 打开/claim 偶发失败，多为供电或时序抖动） ----
            val usbPort = openPortWithRetry(usbManager, driver, baudRate)
            if (usbPort == null) {
                Log.w(TAG, "打开 USB 串口失败（多次重试后仍失败）")
                return null
            }

            EchoLog.log("✅ USB 串口已连接: ${device.productName} @ ${baudRate}bps")
            Log.i(TAG, "USB 串口已连接: ${device.productName} @ ${baudRate}bps")
            return usbPort
        } catch (e: Exception) {
            EchoLog.log("❌ 打开 USB 串口异常: ${e.javaClass.simpleName}: ${e.message ?: "未知错误"}")
            EchoLog.log(
                "   堆栈: ${e.stackTraceToString().lineSequence().take(5).joinToString(" | ")}"
            )
            Log.e(TAG, "打开 USB 串口失败", e)
            null
        }
    }

    /**
     * 打开串口并自动重试。
     *
     * RP2040 在手机上打开时偶尔会 claim 失败（供电波动 / 枚举时序导致设备响应不稳定），
     * 每次重试都会重新 openDevice 获取全新连接，避免上一次失败的接口残留占用，
     * 并递增等待让设备稳定下来。
     */
    private fun openPortWithRetry(
        usbManager: UsbManager,
        driver: UsbSerialDriver,
        baudRate: Int
    ): UsbSerialPort? {
        var lastError: Exception? = null
        val targetDeviceId = driver.device.deviceId
        for (attempt in 1..MAX_OPEN_ATTEMPTS) {
            // ---- 诊断：确认设备此刻是否还在枚举列表（掉线 = 供电/接触不稳） ----
            val device = usbManager.deviceList.values.firstOrNull { it.deviceId == targetDeviceId }
            if (device == null) {
                EchoLog.log("⚠️ 第 $attempt 次：设备不在枚举列表中（疑似掉线/供电不稳）")
                sleepBeforeRetry(attempt)
                continue
            }
            val ifaceDesc = (0 until device.interfaceCount).joinToString(";") { i ->
                val itf = device.getInterface(i)
                "if$i[cl=0x${Integer.toHexString(itf.interfaceClass)}" +
                    ",sub=0x${Integer.toHexString(itf.interfaceSubclass)}]"
            }
            EchoLog.log("   设备在线，接口: $ifaceDesc")

            var connection: UsbDeviceConnection? = null
            var currentPort: UsbSerialPort? = null
            try {
                connection = usbManager.openDevice(device)
                if (connection == null) {
                    EchoLog.log("⚠️ 第 $attempt 次：openDevice 返回 null")
                    sleepBeforeRetry(attempt)
                    continue
                }
                // 每次都用全新驱动实例，避免复用旧 port 对象残留的 claim 状态
                currentPort = CdcAcmSerialDriver(device).ports.first()
                currentPort.open(connection)
                currentPort.setParameters(
                    baudRate,
                    8,                  // dataBits
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                )
                // 清空硬件缓冲区：CDC-ACM 虚拟串口不支持此操作（会抛
                // UnsupportedOperationException），跳过即可，不影响收发
                try {
                    currentPort.purgeHwBuffers(true, true)
                } catch (_: UnsupportedOperationException) {
                    EchoLog.log("   （CDC-ACM 不支持 purgeHwBuffers，已跳过）")
                } catch (_: Exception) {
                    // 忽略其他清理类异常，不影响打开结果
                }
                EchoLog.log("✅ 第 $attempt 次打开串口成功")
                return currentPort
            } catch (e: Exception) {
                lastError = e
                EchoLog.log(
                    "⚠️ 第 $attempt/$MAX_OPEN_ATTEMPTS 次打开失败: " +
                        "${e.javaClass.simpleName}: ${e.message ?: "未知错误"}"
                )
                // 彻底释放：先关刚打开的端口再关连接，避免接口残留导致后续 claim 全部失败
                try {
                    (currentPort as? java.io.Closeable)?.close()
                } catch (_: Exception) { }
                try { connection?.close() } catch (_: Exception) { }
                sleepBeforeRetry(attempt)
            }
        }
        EchoLog.log(
            "❌ 连续 $MAX_OPEN_ATTEMPTS 次打开失败，最后错误: " +
                "${lastError?.javaClass?.simpleName}: ${lastError?.message ?: "未知错误"}"
        )
        EchoLog.log("   ➜ 若日志显示“不在枚举列表中”= 供电/接触不稳；")
        EchoLog.log("     若设备在线但仍 claim 失败 = 接口占用 或 固件 USB 兼容问题，")
        EchoLog.log("     建议换台支持 OTG 的手机/平板交叉验证。")
        return null
    }

    /** 重试前递增等待，让设备稳定下来 */
    private fun sleepBeforeRetry(attempt: Int) {
        if (attempt < MAX_OPEN_ATTEMPTS) {
            val delay = OPEN_RETRY_DELAY_MS * attempt
            EchoLog.log("   等待 ${delay}ms 后重试…")
            try { Thread.sleep(delay) } catch (_: InterruptedException) { }
        }
    }

    /**
     * 请求 USB 设备访问权限，阻塞等待用户在系统弹窗中做出选择。
     * 必须在非主线程调用（如采集协程 / WebServer 线程）。
     */
    private fun requestUsbPermission(usbManager: UsbManager, device: UsbDevice): Boolean {
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    latch.countDown()
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        usbManager.requestPermission(device, pendingIntent)
        val received = latch.await(15, TimeUnit.SECONDS)
        context.unregisterReceiver(receiver)
        return received
    }

    private fun closeConnection() {
        try {
            port?.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭串口异常", e)
        }
        port = null
        Log.i(TAG, "USB 串口已断开")
    }
}
