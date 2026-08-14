package com.example.rp2040monitor

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.rp2040monitor.data.DataCollectionManager
import com.example.rp2040monitor.display.local.screen.MonitorScreen
import com.example.rp2040monitor.theme.MyApplicationTheme
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    /** 绑定的前台服务实例 */
    private var service: SensorForegroundService? = null
    private var serviceBound = false

    /** 从 Service 获取的采集管理器（可空，绑定前为 null） */
    private var collectionManager: DataCollectionManager? = null

    /** Compose 中触发重组的标志 */
    private var isReady by mutableStateOf(false)

    /** 数据源状态，驱动 Switch 显示 */
    private var isRealData by mutableStateOf(false)

    /** 通知权限请求 */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.d(TAG, "通知权限已授予")
        } else {
            Toast.makeText(this, "通知权限被拒绝，前台服务通知可能不显示", Toast.LENGTH_LONG).show()
        }
        // 无论授权与否，都启动服务
        startAndBindService()
    }

    /** ServiceConnection */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as SensorForegroundService.LocalBinder).getService()
            collectionManager = service!!.collectionManager
            isReady = true
            serviceBound = true
            // 订阅数据源模式：web / 服务端切换数据源时，手机端开关同步
            lifecycleScope.launch {
                service?.dataSourceMode?.collect { isRealData = it }
            }
            Log.i(TAG, "服务已绑定")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            serviceBound = false
            Log.w(TAG, "服务断开连接")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isReady && collectionManager != null) {
                        MonitorScreen(
                            collectionManager = collectionManager!!,
                            webUrl = service?.getWebUrl() ?: "",
                            isRealDataSource = isRealData,
                            onToggleDataSource = { useReal ->
                                isRealData = useReal
                                service?.switchDataSource(useReal)
                            }
                        )
                    } else {
                        // 服务未就绪时的加载状态
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "正在启动服务…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // 请求通知权限（Android 13+）后启动服务
        requestNotificationPermissionIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    // ================================================================
    // 服务启动
    // ================================================================

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startAndBindService()
    }

    private fun startAndBindService() {
        val intent = Intent(this, SensorForegroundService::class.java)
        // Android 8+ 推荐 startForegroundService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        Log.i(TAG, "前台服务已启动并绑定")
    }
}
