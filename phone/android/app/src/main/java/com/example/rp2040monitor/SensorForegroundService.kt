package com.example.rp2040monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.rp2040monitor.data.DataCollectionManager
import com.example.rp2040monitor.data.source.CdcDataSource
import com.example.rp2040monitor.data.source.FakeDataSource
import com.example.rp2040monitor.data.usb.MicroPythonUploader
import com.example.rp2040monitor.data.usb.UsbSerialManager
import com.example.rp2040monitor.display.web.DataSourceHandler
import com.example.rp2040monitor.display.web.DataSourceResult
import com.example.rp2040monitor.display.web.ResetHandler
import com.example.rp2040monitor.display.web.ResetResult
import com.example.rp2040monitor.display.web.UploadHandler
import com.example.rp2040monitor.display.web.UploadResult
import com.example.rp2040monitor.display.web.WebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 前台服务 —— 保活 Web 服务器 + 数据采集
 *
 * 即使 App 退到后台或被系统回收 Activity，服务仍然运行，
 * 通过通知栏告知用户 Web 访问地址。
 */
class SensorForegroundService : Service() {

    companion object {
        private const val TAG = "SensorForegroundService"
        const val CHANNEL_ID = "sensor_hub_channel"
        const val NOTIFICATION_ID = 1
        const val WEB_PORT = 8080

        /** 获取设备局域网 IP 地址 */
        fun getLocalIpAddress(context: Context): String {
            // 1. 优先通过 WifiManager 获取
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                val wifiInfo = wifiManager?.connectionInfo
                if (wifiInfo != null) {
                    val ip = wifiInfo.ipAddress
                    if (ip != 0) {
                        return String.format(
                            "%d.%d.%d.%d",
                            ip and 0xFF,
                            ip shr 8 and 0xFF,
                            ip shr 16 and 0xFF,
                            ip shr 24 and 0xFF
                        )
                    }
                }
            } catch (_: Exception) { /* fall through */ }

            // 2. 遍历 NetworkInterface
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress ?: continue
                        }
                    }
                }
            } catch (_: Exception) { /* fall through */ }

            // 3. Socket 兜底
            try {
                java.net.DatagramSocket().use { socket ->
                    socket.connect(java.net.InetAddress.getByName("8.8.8.8"), 10002)
                    val localAddr = socket.localAddress
                    if (localAddr is java.net.Inet4Address && !localAddr.isLoopbackAddress) {
                        return localAddr.hostAddress ?: "127.0.0.1"
                    }
                }
            } catch (_: Exception) { /* fall through */ }

            return "127.0.0.1"
        }
    }

    /** 供 Activity 获取 Service 实例的 Binder */
    inner class LocalBinder : Binder() {
        fun getService(): SensorForegroundService = this@SensorForegroundService
    }

    /** 服务自身的协程作用域 */
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** 数据采集管理器 */
    lateinit var collectionManager: DataCollectionManager
        private set

    /** USB 串口管理器 */
    val usbSerialManager: UsbSerialManager by lazy { UsbSerialManager(this) }

    /** 当前数据源模式：true=真实(USB CDC)，false=模拟(Fake)；供手机 UI 与 web 双向同步 */
    private val _dataSourceMode = MutableStateFlow(false)
    val dataSourceMode: StateFlow<Boolean> = _dataSourceMode.asStateFlow()

    private var webServer: WebServer? = null

    /** 串口互斥锁：同一时刻只允许一个上传/重启操作使用串口 */
    private val serialLock = Any()

    /** 采集协程；@Volatile 供后台线程（上传/重启）与主线程安全访问 */
    @Volatile
    private var collectionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "前台服务 onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "前台服务 onStartCommand")

        // 启动前台通知
        startForegroundWithNotification()

        // 初始化并启动各子系统
        initAndStart()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "前台服务 onBind")
        return LocalBinder()
    }

    override fun onDestroy() {
        Log.i(TAG, "前台服务 onDestroy")
        stopAll()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ================================================================
    // 初始化 & 启动
    // ================================================================

    private fun initAndStart() {
        // 幂等保护：onStartCommand 可能因 Activity 重建 / START_STICKY 被重复调用。
        // 若重复执行，会重建 collectionManager（清空内存缓冲 → 手机图表/字段下拉框消失，
        // 而旧 WebServer 仍占着端口继续服务旧缓冲，造成"手机上没了、web 还在"），
        // 并重复启动 WebServer（端口冲突）。因此只在首次初始化时创建子系统。
        if (::collectionManager.isInitialized) {
            Log.i(TAG, "子系统已初始化，跳过重复初始化（保护内存缓冲与 Web 服务器）")
            return
        }

        // 1. 创建采集管理器
        collectionManager = DataCollectionManager(
            context = this,
            onDataCollected = { data -> webServer?.updateLatestData(data) }
        )

        // 2. 启动 Web 服务器
        startWebServer()

        // 3. 启动采集循环
        startCollection()
    }

    // ================================================================
    // 通知相关
    // ================================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "传感器监控",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示 SensorHub Web 服务运行状态"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /** 刷新通知内容（IP 变化时调用） */
    fun updateNotification() {
        val notification = buildNotification()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val ip = getLocalIpAddress(this)
        val url = "http://$ip:$WEB_PORT"

        // 点击通知回到 MainActivity
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("SensorHub 运行中")
                .setContentText("Web 服务: $url")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("SensorHub 运行中")
                .setContentText("Web 服务: $url")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: 必须显式指定 foregroundServiceType
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ================================================================
    // Web 服务器
    // ================================================================

    fun getWebUrl(): String = "http://${getLocalIpAddress(this)}:$WEB_PORT"

    /** 当前是否使用真实数据源 */
    val isRealDataSource: Boolean get() = ::collectionManager.isInitialized && collectionManager.isRealDataSource()

    /** 切换数据源：true=USB 真数据, false=模拟假数据 */
    fun switchDataSource(useReal: Boolean) {
        if (!::collectionManager.isInitialized) {
            Log.w(TAG, "collectionManager 尚未初始化，无法切换数据源")
            return
        }
        val newSource = if (useReal) {
            CdcDataSource(context = this, serialManager = usbSerialManager)
        } else {
            FakeDataSource()
        }
        collectionManager.switchDataSource(newSource)
        _dataSourceMode.value = useReal
        Log.i(TAG, "数据源切换: ${if (useReal) "真实(USB CDC)" else "模拟(Fake)"}")
    }

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
                },
                dataSourceHandler = object : DataSourceHandler {
                    override fun current(): DataSourceResult {
                        val isReal = isRealDataSource
                        return DataSourceResult(
                            success = true,
                            message = if (isReal) "真实数据 (USB CDC)" else "模拟数据 (Fake)",
                            source = if (isReal) "cdc" else "fake"
                        )
                    }

                    override fun set(useReal: Boolean): DataSourceResult {
                        switchDataSource(useReal)
                        val nowReal = isRealDataSource
                        return DataSourceResult(
                            success = true,
                            message = if (nowReal) "真实数据 (USB CDC)" else "模拟数据 (Fake)",
                            source = if (nowReal) "cdc" else "fake"
                        )
                    }
                }
            )
            server.start()
            webServer = server
            Log.i(TAG, "Web 服务器已启动: ${getWebUrl()}")
        } catch (e: Exception) {
            Log.e(TAG, "Web 服务器启动失败", e)
        }
    }

    private fun stopWebServer() {
        webServer?.stop()
        webServer = null
        Log.i(TAG, "Web 服务器已停止")
    }

    // ================================================================
    // 采集控制
    // ================================================================

    private fun startCollection() {
        if (collectionJob?.isActive == true) return
        collectionJob = serviceScope.launch {
            collectionManager.collect()
        }
    }

    /** 仅取消采集协程（不等待），用于服务销毁等无需立即让出串口的场景 */
    private fun stopCollection() {
        collectionJob?.cancel()
        collectionJob = null
    }

    /**
     * 取消并等待采集协程完全退出（阻塞直到停止）。
     *
     * 采集协程会在串口上阻塞读写（最长约 2s），`cancel()` 只发取消请求、
     * 不会立即停止；只有 `join()` 之后串口才真正空闲，可安全用于上传/重启。
     * 必须在后台线程（NanoHTTPD worker）调用，禁止在主线程调用。
     */
    private fun stopCollectionAndJoin() {
        val job = collectionJob
        collectionJob = null
        if (job != null) {
            job.cancel()
            runBlocking { job.join() }
        }
    }

    // ================================================================
    // OTA 上传 & 设备重启
    // ================================================================

    private fun handleUpload(fileData: ByteArray, fileName: String): UploadResult {
        Log.i(TAG, "OTA 上传请求: $fileName, ${fileData.size} bytes")

        // 串口互斥：同一时刻只允许一个上传/重启操作使用串口（防并发上传/上传+重启）
        synchronized(serialLock) {
            // 取消并等待采集协程完全退出，确保采集不再读写串口后才开始上传
            stopCollectionAndJoin()

            return try {
                val port = usbSerialManager.acquire()
                    ?: return UploadResult(false, "USB 设备未连接")

                val uploader = MicroPythonUploader(port)
                val result = uploader.upload(fileData, fileName)

                // 无论成败都软重启：成功→加载新固件；失败→让设备恢复运行态（跑旧固件），
                // 否则设备会一直停在 RAW REPL，导致后续数据采集收不到数据
                if (result.success) {
                    Log.i(TAG, "上传成功，发送软重启命令")
                } else {
                    Log.w(TAG, "上传失败，软重启恢复设备: ${result.message}")
                }
                uploader.softReset()

                UploadResult(result.success, result.message, result.bytesUploaded)
            } catch (e: Exception) {
                Log.e(TAG, "OTA 上传异常", e)
                UploadResult(false, "异常: ${e.message ?: "未知错误"}")
            } finally {
                usbSerialManager.release()
                startCollection()
            }
        }
    }

    private fun handleReset(): ResetResult {
        Log.i(TAG, "设备重启请求")

        // 串口互斥：与上传共用同一把锁，防止重启与上传同时操作串口
        synchronized(serialLock) {
            // 取消并等待采集协程完全退出，确保串口空闲
            stopCollectionAndJoin()

            return try {
                val port = usbSerialManager.acquire()
                    ?: return ResetResult(false, "USB 设备未连接")

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
    }

    // ================================================================
    // 停止所有子系统
    // ================================================================

    private fun stopAll() {
        stopCollection()
        stopWebServer()
        usbSerialManager.forceClose()
    }
}
