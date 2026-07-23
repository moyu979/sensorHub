package com.example.rp2040monitor.display.web

import android.content.res.AssetManager
import android.util.Log
import com.example.rp2040monitor.data.model.SensorData
import com.example.rp2040monitor.data.storage.DataBuffer
import com.example.rp2040monitor.data.storage.DataLogger
import com.example.rp2040monitor.display.web.api.ApiResult
import com.example.rp2040monitor.display.web.api.SensorApiHandler
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * 上传操作的结果回调
 */
fun interface UploadHandler {
    fun onUpload(fileData: ByteArray, fileName: String): UploadResult
}

/**
 * 重启操作的回调
 */
fun interface ResetHandler {
    fun onReset(): ResetResult
}

data class UploadResult(
    val success: Boolean,
    val message: String,
    val bytesUploaded: Int = 0
)

data class ResetResult(
    val success: Boolean,
    val message: String
)

/**
 * 嵌入式 Web 服务器
 *
 * 基于 NanoHTTPD 实现，提供:
 * - RESTful API（路径 /api/）
 * - OTA 固件上传（POST /api/upload）
 * - 设备重启（POST /api/reset）
 * - OTA 升级页面（/update）
 * - 静态网页服务（路径 / 映射到 assets/web/）
 *
 * 使用示例:
 * val server = WebServer(8080, dataBuffer, assetManager)
 * server.start()
 * // 每轮采集后更新最新数据
 * server.updateLatestData(data)
 */
class WebServer(
    port: Int = 8080,
    private val dataBuffer: DataBuffer,
    private val assetManager: AssetManager,
    private val dataLogger: DataLogger? = null,
    private val uploadHandler: UploadHandler? = null,
    private val resetHandler: ResetHandler? = null
) : NanoHTTPD(port) {

    private val apiHandler = SensorApiHandler(dataBuffer, dataLogger)

    companion object {
        private const val TAG = "WebServer"
        private const val WEB_ROOT = "web"
    }

    /**
     * 由外部每轮采集后调用，更新最新数据快照
     */
    fun updateLatestData(data: SensorData) {
        apiHandler.latestData = data
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return try {
            when {
                // ---- OTA 文件上传 ----
                uri == "/api/upload" && method == Method.POST -> {
                    handleUpload(session)
                }
                // ---- 设备重启 ----
                uri == "/api/reset" && method == Method.POST -> {
                    handleReset()
                }
                // ---- OTA 升级页面 ----
                (uri == "/update" || uri == "/upload") -> {
                    serveStatic("upload.html")
                }
                // ---- API 路由 ----
                uri.startsWith("/api/") && method == Method.GET -> {
                    @Suppress("DEPRECATION")
                    handleApi(uri, session.parms ?: emptyMap())
                }
                // ---- 静态文件 ----
                else -> {
                    serveStatic(uri)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "请求处理异常: $uri", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Internal Server Error: ${e.message}"
            )
        }
    }

    // ==================== API ====================

    private fun handleApi(uri: String, params: Map<String, String>): Response {
        val result: ApiResult = apiHandler.handle(uri, params)
        return newFixedLengthResponse(
            lookupHttpStatus(result.httpCode),
            "application/json; charset=utf-8",
            result.body
        )
    }

    // ==================== OTA 上传 ====================

    /**
     * 处理 OTA 文件上传
     *
     * 接收 multipart/form-data 格式上传的 .py 文件，
     * 通过 [uploadHandler] 回调交给上层处理（USB CDC 上传到 RP2040）。
     */
    private fun handleUpload(session: IHTTPSession): Response {
        if (uploadHandler == null) {
            return jsonResponse(503, """{"success":false,"message":"上传功能未启用"}""")
        }

        try {
            // 解析 multipart 数据
            val files = LinkedHashMap<String, String>()
            val parms = LinkedHashMap<String, String>()

            session.parseBody(files)

            // 提取上传的文件——NanoHTTPD 把文件内容放在 files map 中
            val fileEntry = files.entries.firstOrNull()
            if (fileEntry == null) {
                return jsonResponse(400, """{"success":false,"message":"未找到上传文件"}""")
            }

            // 从 multipart 头部提取文件名
            val headers = session.headers
            val disposition = headers["content-disposition"] ?: ""
            val fileName = Regex("""filename="?(.+?)"?(\s|$)""")
                .find(disposition)
                ?.groupValues?.get(1)
                ?: "main.py"

            // 读取文件内容
            val fileContent = fileEntry.value
            val fileData = fileContent.toByteArray(StandardCharsets.UTF_8)

            if (fileData.isEmpty()) {
                return jsonResponse(400, """{"success":false,"message":"文件内容为空"}""")
            }

            Log.i(TAG, "收到上传文件: $fileName, ${fileData.size} bytes")

            // 调用上传回调
            val result = uploadHandler.onUpload(fileData, fileName)

            val json = """{
                "success": ${result.success},
                "message": "${result.message}",
                "bytes": ${result.bytesUploaded}
            }""".trimIndent()

            return jsonResponse(
                if (result.success) 200 else 500,
                json
            )

        } catch (e: Exception) {
            Log.e(TAG, "上传处理异常", e)
            return jsonResponse(500, """{"success":false,"message":"${e.message?.let { escapeJson(it) } ?: "未知错误"}"}""")
        }
    }

    /**
     * 处理设备重启请求
     */
    private fun handleReset(): Response {
        if (resetHandler == null) {
            return jsonResponse(503, """{"success":false,"message":"重启功能未启用"}""")
        }

        return try {
            val result = resetHandler.onReset()
            val json = """{"success":${result.success},"message":"${result.message}"}"""
            jsonResponse(if (result.success) 200 else 500, json)
        } catch (e: Exception) {
            Log.e(TAG, "重启异常", e)
            jsonResponse(500, """{"success":false,"message":"${e.message}"}""")
        }
    }

    // ==================== 静态文件 ====================

    /**
     * 从 assets/web/ 目录提供静态文件
     * - `/` → `index.html`
     * - `/update` → `upload.html`
     * - `/style.css` → `style.css`
     */
    private fun serveStatic(uri: String): Response {
        // 安全处理：防止路径遍历
        val safePath = uri
            .trimStart('/')
            .takeIf { it.isNotEmpty() }
            ?: "index.html"

        if (safePath.contains("..")) {
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                MIME_PLAINTEXT,
                "Forbidden"
            )
        }

        val assetPath = "$WEB_ROOT/$safePath"

        return try {
            val mimeType = getMimeType(safePath)
            val inputStream: InputStream = assetManager.open(assetPath)
            newChunkedResponse(Response.Status.OK, mimeType, inputStream)
        } catch (e: Exception) {
            // 文件不存在 → 返回 index.html（支持 SPA 式路由）
            try {
                val inputStream: InputStream = assetManager.open("$WEB_ROOT/index.html")
                newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", inputStream)
            } catch (e2: Exception) {
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "404 Not Found"
                )
            }
        }
    }

    /**
     * 根据文件扩展名获取 MIME 类型
     */
    private fun getMimeType(path: String): String {
        return when {
            path.endsWith(".html") -> "text/html; charset=utf-8"
            path.endsWith(".css")  -> "text/css; charset=utf-8"
            path.endsWith(".js")   -> "application/javascript; charset=utf-8"
            path.endsWith(".json") -> "application/json; charset=utf-8"
            path.endsWith(".png")  -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".svg")  -> "image/svg+xml"
            path.endsWith(".ico")  -> "image/x-icon"
            else -> "application/octet-stream"
        }
    }

    /**
     * 将整数 HTTP 状态码转为 NanoHTTPD 枚举
     */
    private fun lookupHttpStatus(code: Int): Response.Status {
        return Response.Status.lookup(code) ?: Response.Status.OK
    }

    private fun jsonResponse(status: Int, body: String): Response {
        return newFixedLengthResponse(
            lookupHttpStatus(status),
            "application/json; charset=utf-8",
            body
        )
    }

    /** 转义 JSON 字符串中的特殊字符 */
    private fun escapeJson(s: String): String {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
