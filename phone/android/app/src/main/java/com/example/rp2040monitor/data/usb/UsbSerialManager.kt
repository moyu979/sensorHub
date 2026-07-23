package com.example.rp2040monitor.data.usb

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

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
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

            if (drivers.isEmpty()) {
                Log.w(TAG, "未发现 USB 串口设备")
                return null
            }

            val driver = drivers.first()
            val connection = usbManager.openDevice(driver.device)
                ?: run {
                    Log.w(TAG, "USB 设备权限不足")
                    return null
                }

            val usbPort = driver.ports.first()
            usbPort.open(connection)
            usbPort.setParameters(
                baudRate,
                8,                  // dataBits
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            // 清空硬件缓冲区
            usbPort.purgeHwBuffers(true, true)

            Log.i(TAG, "USB 串口已连接: ${driver.device.productName} @ ${baudRate}bps")
            usbPort
        } catch (e: Exception) {
            Log.e(TAG, "打开 USB 串口失败", e)
            null
        }
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
