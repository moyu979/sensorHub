package com.example.rp2040monitor.data.usb

import android.util.Base64
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import kotlin.math.min

/**
 * MicroPython OTA 文件上传器
 *
 * 通过 USB CDC (RAW REPL) 将文件上传到 RP2040 的 MicroPython 文件系统。
 * 协议设计参考 mpremote fs cp:
 *
 * 1. 进入 RAW REPL (Ctrl-A)
 * 2. 部署接收脚本（通过 Ctrl-D 执行）
 * 3. 等待 READY 信号
 * 4. 发送文件名、大小、CRC32
 * 5. 分块发送 Base64 编码数据
 * 6. 发送空行表示 EOF
 * 7. 读取结果（OK / CRC_FAIL / ERR）
 * 8. 发送 Ctrl-D 软重启
 *
 * 用法：
 * ```kotlin
 * val uploader = MicroPythonUploader(port)
 * val result = uploader.upload(fileBytes, "main.py")
 * if (result.success) uploader.softReset()
 * ```
 */
class MicroPythonUploader(private val port: UsbSerialPort) {

    companion object {
        private const val TAG = "MicroPythonUploader"

        /** RAW REPL 控制字符 */
        private const val CTRL_A = 0x01
        private const val CTRL_B = 0x02
        private const val CTRL_C = 0x03
        private const val CTRL_D = 0x04

        /** 每块原始字节数（384 raw → 512 base64，适配 RP2040 RAM） */
        private const val CHUNK_RAW_SIZE = 384

        /** 串口读取超时 */
        private const val TIMEOUT_ENTER_REPL_MS = 3000L
        private const val TIMEOUT_SCRIPT_MS = 5000L
        private const val TIMEOUT_FINAL_MS = 20000L

        /** 内联 MicroPython 接收脚本 — 部署到 RAW REPL 执行 */
        private val RECEIVER_SCRIPT = (
            "import sys,binascii,os,gc\n" +
            "def _rc():\n" +
            " tn=sys.stdin.readline().strip()\n" +
            " if not tn:print('ERR:NF');return\n" +
            " sl=sys.stdin.readline().strip()\n" +
            " try:fs=int(sl)\n" +
            " except:print('ERR:IS');return\n" +
            " cl=sys.stdin.readline().strip()\n" +
            " try:ec=int(cl,16)\n" +
            " except:print('ERR:IC');return\n" +
            " tp=tn+'.tmp'\n" +
            " try:\n" +
            "  if tp in os.listdir():os.remove(tp)\n" +
            " except:pass\n" +
            " br=0\n" +
            " try:\n" +
            "  with open(tp,'wb') as f:\n" +
            "   while True:\n" +
            "    l=sys.stdin.readline()\n" +
            "    if l is None:break\n" +
            "    d=l.strip()\n" +
            "    if not d:break\n" +
            "    try:ck=binascii.a2b_base64(d)\n" +
            "    except:print('ERR:BD');return\n" +
            "    f.write(ck)\n" +
            "    br+=len(ck)\n" +
            "    gc.collect()\n" +
            " except Exception as e:print('ERR:'+str(e));return\n" +
            " ac=0\n" +
            " try:\n" +
            "  with open(tp,'rb') as f:\n" +
            "   while True:\n" +
            "    b=f.read(256)\n" +
            "    if not b:break\n" +
            "    ac=binascii.crc32(b,ac)\n" +
            " except:print('ERR:CR');return\n" +
            " ac=ac&0xFFFFFFFF\n" +
            " if ac!=ec:\n" +
            "  print('CRC_FAIL:%08X!=%08X'%(ac,ec))\n" +
            "  try:os.remove(tp)\n" +
            "  except:pass\n" +
            "  return\n" +
            " try:\n" +
            "  try:\n" +
            "   if tn in os.listdir():os.remove(tn)\n" +
            "  except:pass\n" +
            "  os.rename(tp,tn)\n" +
            "  print('OK:%s updated (%db CRC=%08X)'%(tn,br,ac))\n" +
            " except Exception as e:print('ERR:RN:'+str(e))\n" +
            "sys.stdout.write('READY\\r\\n')\n" +
            "sys.stdout.flush()\n" +
            "_rc()\n"
        )
    }

    /**
     * 上传结果
     */
    data class UploadResult(
        val success: Boolean,
        val message: String,
        val bytesUploaded: Int = 0
    ) {
        companion object {
            fun ok(bytes: Int) = UploadResult(true, "上传成功", bytes)
            fun fail(msg: String) = UploadResult(false, msg)
        }
    }

    /**
     * 上传文件到 RP2040
     *
     * @param fileData 文件原始字节
     * @param remotePath 远程目标路径（如 "main.py"）
     * @return 上传结果
     */
    fun upload(fileData: ByteArray, remotePath: String): UploadResult {
        return try {
            // 1. 进入 RAW REPL
            enterRawRepl()

            // 2. 部署接收脚本
            if (!deployReceiver()) {
                return UploadResult.fail("脚本部署失败：未收到 READY 信号")
            }

            // 3. 发送元数据：文件名、大小、CRC32
            sendMetadata(remotePath, fileData.size, computeCrc32(fileData))

            // 4. 分块发送文件数据
            sendChunks(fileData)

            // 5. 发送 EOF + 读取最终结果
            readFinalResult()
        } catch (e: Exception) {
            Log.e(TAG, "上传异常", e)
            try { interrupt() } catch (_: Exception) {}
            UploadResult.fail("异常: ${e.message}")
        }
    }

    // ================================================================
    // RAW REPL 协议步骤
    // ================================================================

    /**
     * 进入 RAW REPL 模式
     *
     * 时序：
     *   TX: Ctrl-C × 2 (中断当前操作)
     *   TX: Ctrl-A (进入 RAW REPL)
     *   RX: "raw REPL; CTRL-B to exit\r\n>"
     */
    private fun enterRawRepl() {
        // 先中断任何正在运行的程序
        writeByte(CTRL_C)
        sleep(80)
        writeByte(CTRL_C)
        sleep(150)

        // 清空缓冲区
        drainReader()

        // 进入 RAW REPL
        writeByte(CTRL_A)
        sleep(300)

        val response = readUntil(0x3E.toByte(), TIMEOUT_ENTER_REPL_MS)
        if (!response.contains("raw REPL")) {
            Log.w(TAG, "RAW REPL 响应异常，前 80 字符: ${response.take(80)}")
        }
        Log.i(TAG, "已进入 RAW REPL")
    }

    /**
     * 部署接收脚本
     *
     * 时序：
     *   TX: [脚本源码] + Ctrl-D
     *   RX: "READY\r\n" (脚本就绪)
     */
    private fun deployReceiver(): Boolean {
        val scriptBytes = RECEIVER_SCRIPT.toByteArray(StandardCharsets.UTF_8)
        port.write(scriptBytes, 2000)
        writeByte(CTRL_D)
        sleep(500)

        val response = readUntil(
            "READY".toByteArray(StandardCharsets.US_ASCII),
            TIMEOUT_SCRIPT_MS
        )
        val ready = response.contains("READY")
        if (!ready) {
            Log.e(TAG, "未收到 READY，响应: ${response.take(200)}")
        } else {
            Log.i(TAG, "接收脚本已部署，收到 READY")
        }
        return ready
    }

    /**
     * 发送文件元数据：文件名、文件大小、CRC32（十六进制大写）
     */
    private fun sendMetadata(fileName: String, fileSize: Int, crc32: Long) {
        writeLine(fileName)
        writeLine(fileSize.toString())
        writeLine("%08X".format(crc32))
        Log.d(TAG, "元数据: name=$fileName, size=$fileSize, CRC=%08X".format(crc32))
    }

    /**
     * 分块发送文件数据（Base64 编码）
     */
    private fun sendChunks(fileData: ByteArray) {
        val total = fileData.size
        val totalChunks = (total + CHUNK_RAW_SIZE - 1) / CHUNK_RAW_SIZE
        var offset = 0
        var chunkIndex = 0

        while (offset < total) {
            val chunkSize = min(CHUNK_RAW_SIZE, total - offset)
            val chunk = fileData.copyOfRange(offset, offset + chunkSize)

            val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
            writeLine(b64)

            offset += chunkSize
            chunkIndex++

            if (chunkIndex % 50 == 0 || chunkIndex == totalChunks) {
                Log.d(TAG, "进度: $chunkIndex/$totalChunks chunks (${offset}/$total bytes)")
            }
        }
        Log.i(TAG, "所有分块已发送: $totalChunks chunks, $total bytes")
    }

    /**
     * 发送 EOF（空行）并读取最终结果
     *
     * 成功 → "OK:..." + [0x04]
     * CRC 失败 → "CRC_FAIL:..." + [0x04]
     * 错误 → "ERR:..." + [0x04]
     */
    private fun readFinalResult(): UploadResult {
        // 发送空行 = EOF
        writeLine("")

        // 读取直到 0x04
        val response = readUntil(byteArrayOf(CTRL_D.toByte()), TIMEOUT_FINAL_MS)
        Log.i(TAG, "最终响应: ${response.take(300)}")

        return when {
            response.contains("OK:") -> {
                val match = Regex("""(\d+)b""").find(response)
                val bytes = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
                UploadResult.ok(bytes)
            }
            response.contains("CRC_FAIL") -> {
                UploadResult.fail("CRC 校验失败: ${response.take(100)}")
            }
            response.contains("ERR:") -> {
                val msg = Regex("""ERR:(.*?)(\r|\n|$)""")
                    .find(response)?.groupValues?.get(1) ?: response.take(100)
                UploadResult.fail("设备错误: $msg")
            }
            else -> {
                UploadResult.fail("未知响应: ${response.take(200)}")
            }
        }
    }

    /**
     * 软重启 RP2040（在 RAW REPL 中发送 Ctrl-D）
     * 使设备重启并执行新的 main.py
     */
    fun softReset() {
        try {
            writeByte(CTRL_D)
            sleep(600)
            Log.i(TAG, "已发送软重启命令")
        } catch (e: Exception) {
            Log.w(TAG, "软重启异常", e)
        }
    }

    /**
     * 中断当前操作
     */
    fun interrupt() {
        try {
            writeByte(CTRL_C)
            sleep(80)
            writeByte(CTRL_C)
            sleep(100)
        } catch (_: Exception) {}
    }

    // ================================================================
    // 串口读写
    // ================================================================

    private fun writeByte(b: Int) {
        port.write(byteArrayOf(b.toByte()), 500)
    }

    private fun writeLine(line: String) {
        val data = "$line\r\n".toByteArray(StandardCharsets.UTF_8)
        port.write(data, 1000)
    }

    /**
     * 读取直到遇到某个终止字节
     */
    private fun readUntil(terminator: Byte, timeoutMs: Long): String {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            try {
                val len = port.read(buf, 200)
                if (len > 0) {
                    baos.write(buf, 0, len)
                    for (i in 0 until len) {
                        if (buf[i] == terminator) {
                            return baos.toString(StandardCharsets.UTF_8.name())
                        }
                    }
                }
            } catch (e: Exception) {
                break
            }
        }
        return baos.toString(StandardCharsets.UTF_8.name())
    }

    /**
     * 读取直到遇到某个终止字节序列
     */
    private fun readUntil(terminator: ByteArray, timeoutMs: Long): String {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        val deadline = System.currentTimeMillis() + timeoutMs
        val termStr = terminator.toString(StandardCharsets.UTF_8)

        while (System.currentTimeMillis() < deadline) {
            try {
                val len = port.read(buf, 200)
                if (len > 0) {
                    baos.write(buf, 0, len)
                    if (baos.toString(StandardCharsets.UTF_8.name()).contains(termStr)) {
                        return baos.toString(StandardCharsets.UTF_8.name())
                    }
                }
            } catch (e: Exception) {
                break
            }
        }
        return baos.toString(StandardCharsets.UTF_8.name())
    }

    /**
     * 清空读取缓冲区
     */
    private fun drainReader() {
        try {
            val buf = ByteArray(4096)
            var remaining = 300L
            while (remaining > 0) {
                val len = port.read(buf, 30)
                if (len <= 0) break
                remaining -= 50
            }
        } catch (_: Exception) {}
    }

    // ================================================================
    // CRC32
    // ================================================================

    private fun computeCrc32(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
