package com.example.rp2040monitor

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.rp2040monitor.data.DataCollectionManager
import com.example.rp2040monitor.data.usb.MicroPythonUploader
import com.example.rp2040monitor.data.usb.UsbSerialManager
import com.example.rp2040monitor.display.local.screen.MonitorScreen
import com.example.rp2040monitor.display.web.ResetHandler
import com.example.rp2040monitor.display.web.ResetResult
import com.example.rp2040monitor.display.web.UploadHandler
import com.example.rp2040monitor.display.web.UploadResult
import com.example.rp2040monitor.display.web.WebServer
import com.example.rp2040monitor.theme.MyApplicationTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        /** Web 服务器端口号 */
        const val WEB_PORT = 8080
    }

    /** 数据采集管理器（持有一个 DataBuffer，与 WebServer 共享） */
    private lateinit var collectionManager: DataCollectionManager

    /** 嵌入式 Web 服务器实例 */
    private var webServer: WebServer? = null

    /** 当前采集协程的 Job，用于暂停/恢复 */
    private var collectionJob: Job? = null

    /** USB 串口管理器（CdcDataSource 和 Uploader 共享） */
    private val usbSerialManager by lazy { UsbSerialManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 创建采集管理器
        collectionManager = DataCollectionManager(
            context = this,
            onDataCollected = { data -> webServer?.updateLatestData(data) }
        )

        // 2. 启动 Web 服务器（传入共享的 DataBuffer 和上传/重启回调）
        startWebServer()

        // 3. 启动采集循环
        startCollection()

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MonitorScreen(
                        collectionManager = collectionManager,
                        webUrl = "http://${getLocalIpAddress()}:$WEB_PORT"
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCollection()
        stopWebServer()
    }

    // ================================================================
    // 采集控制
    // ================================================================

    private fun startCollection() {
        collectionJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                collectionManager.collect()
            }
        }
    }

    private fun stopCollection() {
        collectionJob?.cancel()
        collectionJob = null
    }

    // ================================================================
    // OTA 上传
    // ================================================================

    /**
     * 处理 OTA 上传请求（由 WebServer 回调）
     *
     * 流程：
     * 1. 暂停数据采集（释放 USB 端口）
     * 2. 获取 USB 串口
     * 3. 使用 MicroPythonUploader 上传文件
     * 4. 上传成功 → 软重启 RP2040
     * 5. 释放 USB 端口
     * 6. 恢复数据采集
     */
    private fun handleUpload(fileData: ByteArray, fileName: String): UploadResult {
        Log.i(TAG, "OTA 上传请求: $fileName, ${fileData.size} bytes")

        // 1. 暂停采集
        stopCollection()
        Log.d(TAG, "采集已暂停")

        return try {
            // 2. 获取 USB 串口
            val port = usbSerialManager.acquire()
            if (port == null) {
                Log.e(TAG, "USB 设备未连接")
                return UploadResult(false, "USB 设备未连接")
            }

            // 3. 执行上传
            val uploader = MicroPythonUploader(port)
            val result = uploader.upload(fileData, fileName)

            if (result.success) {
                Log.i(TAG, "上传成功，发送软重启命令")
                // 4. 上传成功 → 软重启加载新固件
                uploader.softReset()
            } else {
                Log.w(TAG, "上传失败: ${result.message}")
            }

            UploadResult(result.success, result.message, result.bytesUploaded)
        } catch (e: Exception) {
            Log.e(TAG, "OTA 上传异常", e)
            UploadResult(false, "异常: ${e.message ?: "未知错误"}")
        } finally {
            // 5. 释放 USB 端口
            usbSerialManager.release()
            // 6. 恢复采集
            startCollection()
            Log.d(TAG, "USB 已释放，采集已恢复")
        }
    }

    /**
     * 处理设备重启请求
     */
    private fun handleReset(): ResetResult {
        Log.i(TAG, "设备重启请求")
        stopCollection()

        return try {
            val port = usbSerialManager.acquire()
            if (port == null) {
                return ResetResult(false, "USB 设备未连接")
            }

            val uploader = MicroPythonUploader(port)
            uploader.softReset()

            ResetResult(true, "重启命令已发送")
        } catch (e: Exception) {
            Log.e(TAG, "重启异常", e)
            ResetResult(false, "异常: ${e.message ?: "未知错误"}")
        } finally {
            usbSerialManager.release()
            startCollection()
        }
    }

    // ================================================================
    // Web 服务器
    // ================================================================

    /**
     * 启动嵌入式 Web 服务器
     */
    private fun startWebServer() {
        try {
            val server = WebServer(
                port = WEB_PORT,
                dataBuffer = collectionManager.getDataBuffer(),
                assetManager = assets,
                dataLogger = collectionManager.getDataLogger(),
                uploadHandler = UploadHandler { fileData, fileName ->
                    handleUpload(fileData, fileName)
                },
                resetHandler = ResetHandler {
                    handleReset()
                }
            )
            server.start()
            webServer = server
            Log.i(TAG, "Web 服务器已启动: http://${getLocalIpAddress()}:$WEB_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Web 服务器启动失败", e)
        }
    }

    /**
     * 停止 Web 服务器
     */
    private fun stopWebServer() {
        webServer?.stop()
        webServer = null
        Log.i(TAG, "Web 服务器已停止")
    }

    /**
     * 获取设备局域网 IP 地址
     */
    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取 IP 地址失败", e)
        }
        return "127.0.0.1"
    }
}
