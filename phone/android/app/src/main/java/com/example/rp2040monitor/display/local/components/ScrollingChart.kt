package com.example.rp2040monitor.display.local.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 滚动统计图
 * 类似 Windows 任务管理器的性能图，从右向左滚动
 * 窗口宽度 = maxPoints 个数据点
 */
@Composable
fun ScrollingChart(
    dataPoints: List<Double>,          // 按时间正序排列的数据点，最新在最后
    maxPoints: Int = 60,               // 窗口内最多显示的点数
    minValue: Double? = null,          // Y 轴最小值，null 则自动
    maxValue: Double? = null,          // Y 轴最大值，null 则自动
    lineColor: Color = Color(0xFF00D4AA),
    backgroundColor: Color = Color(0xFF1A1A2E),
    gridColor: Color = Color(0xFF2A2A3E),
    labelColor: Color = Color(0xFF888888),
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
    ) {
        val title = when {
            dataPoints.isEmpty() -> "暂无数据"
            else -> "实时趋势 (最近 ${dataPoints.size}s)"
        }
        Text(
            text = title,
            color = Color(0xFFCCCCCC),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp)
        )

        val points = dataPoints.takeLast(maxPoints)
        val actualMin = minValue ?: (points.minOrNull() ?: 0.0)
        val actualMax = maxValue ?: (points.maxOrNull() ?: 1.0)
        val range = if ((actualMax - actualMin) < 0.001) 1.0 else actualMax - actualMin
        val textMeasurer = rememberTextMeasurer()

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 8.dp, end = 8.dp, bottom = 4.dp)
        ) {
            val chartWidth = size.width
            val chartHeight = size.height - 20f

            // ---- 绘制网格线 ----
            drawGridLines(chartWidth, chartHeight, gridColor)

            // ---- 绘制 Y 轴标签 ----
            drawYLabels(actualMin, actualMax, chartHeight, labelColor, textMeasurer)

            // ---- 绘制数据线 ----
            if (points.size >= 2) {
                drawDataLine(points, chartWidth, chartHeight, actualMin, range, lineColor, maxPoints)
            }

            // ---- 绘制当前值 ----
            if (points.isNotEmpty()) {
                val latestVal = points.last()
                val yPos = chartHeight - ((latestVal - actualMin) / range * chartHeight).toFloat()
                drawCircle(
                    color = lineColor,
                    radius = 5f,
                    center = Offset(chartWidth, yPos + 20f)
                )
                val latestText = String.format("%.1f", latestVal)
                val textResult = textMeasurer.measure(
                    text = latestText,
                    style = TextStyle(fontSize = 12.sp, color = labelColor)
                )
                drawText(
                    textLayoutResult = textResult,
                    topLeft = Offset(chartWidth - textResult.size.width - 4f, yPos + 20f - textResult.size.height - 4f)
                )
            }
        }
    }
}

/**
 * 绘制背景网格线（4条水平线）
 */
private fun DrawScope.drawGridLines(
    width: Float,
    height: Float,
    gridColor: Color
) {
    val gridCount = 4
    for (i in 0..gridCount) {
        val y = height * i / gridCount + 20f
        drawLine(
            color = gridColor,
            start = Offset(0f, y),
            end = Offset(width, y),
            strokeWidth = 1f
        )
    }
}

/**
 * 绘制 Y 轴刻度标签
 */
private fun DrawScope.drawYLabels(
    minVal: Double,
    maxVal: Double,
    chartHeight: Float,
    labelColor: Color,
    textMeasurer: TextMeasurer
) {
    val gridCount = 4
    val style = TextStyle(fontSize = 11.sp, color = labelColor)
    for (i in 0..gridCount) {
        val y = chartHeight * i / gridCount + 20f
        val labelValue = maxVal - (maxVal - minVal) * i / gridCount
        val label = String.format("%.1f", labelValue)
        val textResult = textMeasurer.measure(text = label, style = style)
        drawText(
            textLayoutResult = textResult,
            topLeft = Offset(4f, y - textResult.size.height / 2f)
        )
    }
}

/**
 * 绘制数据折线（从右向左滚动）
 */
private fun DrawScope.drawDataLine(
    points: List<Double>,
    chartWidth: Float,
    chartHeight: Float,
    minVal: Double,
    range: Double,
    lineColor: Color,
    maxPoints: Int = 60
) {
    val stepX = chartWidth / (maxPoints.coerceAtLeast(points.size) - 1).coerceAtLeast(1)
    val path = Path()

    val offsetX = if (points.size < maxPoints) {
        chartWidth - (points.size - 1) * stepX
    } else {
        0f
    }

    for (i in points.indices) {
        val x = offsetX + i * stepX
        val normalizedValue = (points[i] - minVal) / range
        val y = chartHeight - (normalizedValue * chartHeight).toFloat() + 20f

        if (i == 0) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
    }

    drawPath(
        path = path,
        color = lineColor,
        style = Stroke(width = 3f)
    )

    // 填充区域
    val fillPath = Path().apply {
        addPath(path)
        lineTo(chartWidth, chartHeight + 20f)
        lineTo(offsetX, chartHeight + 20f)
        close()
    }
    drawPath(
        path = fillPath,
        color = lineColor.copy(alpha = 0.15f),
    )
}
