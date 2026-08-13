package com.example.rp2040monitor.display.local.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.rp2040monitor.data.DataCollectionManager
import com.example.rp2040monitor.data.EchoLog
import com.example.rp2040monitor.display.local.components.FieldSelector
import com.example.rp2040monitor.display.local.components.ScrollingChart
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主监控屏幕
 *
 * 纯展示组件，不负责数据采集。
 * 数据由 [DataCollectionManager] 在 Activity 层采集，
 * 通过 StateFlow 发射到这里渲染。
 *
 * - 顶部：字段下拉选择器
 * - 中部：滚动统计图（1分钟窗口）
 * - 底部：实时数据文本
 */
@Composable
fun MonitorScreen(
    collectionManager: DataCollectionManager,
    webUrl: String = "",
    isRealDataSource: Boolean = false,
    onToggleDataSource: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // 订阅 StateFlow — 数据一变，Compose 自动重组
    val latestData by collectionManager.latestData.collectAsState()
    val fieldNames by collectionManager.fieldNames.collectAsState()
    val chartData by collectionManager.chartData.collectAsState()

    // 当前选中的字段（纯 UI 状态，不需要 Manager 知道）
    var selectedField by remember { mutableStateOf("") }

    // 是否显示 USB 回显调试对话框
    var showEchoDialog by remember { mutableStateOf(false) }

    // 首次收到字段列表时自动选中第一个
    LaunchedEffect(fieldNames) {
        if (selectedField.isEmpty() && fieldNames.isNotEmpty()) {
            selectedField = fieldNames.first()
            collectionManager.selectField(fieldNames.first())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // ---- 标题 + 回显按钮 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                text = "传感器监控",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { showEchoDialog = true }) {
                Text("回显")
            }
        }

        // ---- Web 服务地址 ----
        if (webUrl.isNotBlank()) {
            Text(
                text = "Web: $webUrl",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        // ---- 数据源切换开关 ----
        if (onToggleDataSource != null) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    text = if (isRealDataSource) "真实数据 (USB CDC)" else "模拟数据 (Fake)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isRealDataSource)
                        MaterialTheme.colorScheme.tertiary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = isRealDataSource,
                    onCheckedChange = onToggleDataSource
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // ---- 字段选择器 ----
        FieldSelector(
            fieldNames = fieldNames,
            selectedField = selectedField,
            onFieldSelected = { newField ->
                selectedField = newField
                collectionManager.selectField(newField)
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ---- 滚动统计图 ----
        ScrollingChart(
            dataPoints = chartData,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ---- 实时数据文本 ----
        latestData?.let { data ->
            Text(
                text = buildString {
                    appendLine("时间: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(data.timestamp))}")
                    appendLine("状态: ${data.status}")
                    data.fields.forEach { (name, value) ->
                        appendLine("$name: $value")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- 回显调试对话框 ----
        if (showEchoDialog) {
            EchoDialog(onDismiss = { showEchoDialog = false })
        }
    }
}

// ================================================================
// USB 回显调试对话框
// ================================================================

/**
 * 实时展示 [EchoLog] 中记录的 USB 通信日志（枚举 / 权限 / 收发 / 解析），
 * 新日志到达时自动滚动到底部。
 */
@Composable
fun EchoDialog(onDismiss: () -> Unit) {
    val lines by EchoLog.lines.collectAsState()
    val text = lines.joinToString("\n")
    val scrollState = rememberScrollState()

    // 新日志追加时自动滚动到底部
    LaunchedEffect(text) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("USB 回显 / 调试日志") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
            ) {
                Text(
                    text = if (text.isEmpty())
                        "（暂无日志，切换到“真实数据 (USB CDC)”后开始记录）"
                    else
                        text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { EchoLog.clear() }) { Text("清空") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
