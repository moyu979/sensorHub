"""
m702a.py - 四方光电 M702A 空气质量传感器驱动（只解析最新一帧）

接线（RP2040 / Pico）:
    M702A TX  ->  GPIO1  (UART0 RX, 第2引脚)
    M702A VCC ->  VBUS   (5V)
    M702A GND ->  GND

数据流:
    M702A (主动上报) --UART0--> RP2040 逐帧解析 --USB CDC--> 手机
    手机发送 "get"（带换行），返回最新一帧的解析值 JSON。

帧格式(17字节，实测确认)：
    3c 02 | eCO2高 低 | CH2O高 低 | TVOC高 低 | PM2.5高 低 | PM10高 低 |
    温度整数 温度小数 | 湿度整数 湿度小数 | 校验(前16字节累加取低8位)
"""

import select
import sys
import time
import ujson
from machine import Pin, UART

# ============================== 配置 ==============================
UART_BAUD = 9600          # M702A 主动上报波特率（收不到数据时试 115200）
UART0_TX = Pin(0)         # 仅初始化用，此模式不发送
UART0_RX = Pin(1)         # M702A TX 接这里（第2引脚）

FRAME_LEN = 17            # 帧总长（实测确认）
H1 = 0x3C                 # 帧头1
H2 = 0x02                 # 帧头2
TEMP_FRAC_DIV = 10        # 温度/湿度小数 1 位（/10）
CHECK_CHECKSUM = True     # 是否校验校验和
DEBUG_HEX = False         # 调试：打印收到的原始帧 hex（调好关 False）
NO_SIGNAL_TIMEOUT_S = 90  # 超过该秒数未收到有效帧 → NO_SIGNAL
# ==================================================================

uart = UART(0, baudrate=UART_BAUD, tx=UART0_TX, rx=UART0_RX, timeout=50)

# 最新一帧的解析值（未收到有效帧前为 0）
latest = {
    "eco2": 0, "ch2o": 0.0, "tvoc": 0.0,
    "pm25": 0.0, "pm10": 0.0, "temperature": 0.0, "humidity": 0.0,
}
last_frame_sec = 0.0      # 最近一次收到有效帧的时刻（epoch 秒）
rx_total = 0              # 累计收到的 UART 字节数


def checksum(frame):
    """校验和：前 16 字节累加取低 8 位（实测验证通过）"""
    return sum(frame[0:16]) & 0xFF


def parse_frame(frame):
    """解析一帧 17 字节，返回数值字典"""
    d = {}
    d["eco2"] = (frame[2] << 8) | frame[3]
    d["ch2o"] = (frame[4] << 8) | frame[5]
    d["tvoc"] = (frame[6] << 8) | frame[7]
    d["pm25"] = (frame[8] << 8) | frame[9]
    d["pm10"] = (frame[10] << 8) | frame[11]
    # 温度：bit7=1 负 / 0 正，低 7 位为绝对值
    t_int = frame[12]
    t_frac = frame[13]
    sign = -1 if (t_int & 0x80) else 1
    t_abs = t_int & 0x7F
    d["temperature"] = round(sign * (t_abs + t_frac / TEMP_FRAC_DIV), 1)
    # 湿度
    h_int = frame[14]
    h_frac = frame[15]
    d["humidity"] = round(h_int + h_frac / TEMP_FRAC_DIV, 1)
    return d


def build_payload():
    """组装 get 返回的 JSON：最新一帧解析值 + 状态"""
    no_signal = (last_frame_sec == 0) or (time.time() - last_frame_sec > NO_SIGNAL_TIMEOUT_S)
    d = dict(latest)
    d["status"] = "NO_SIGNAL" if no_signal else "OK"
    d["rx_total"] = rx_total
    d["last_frame_age"] = 0 if last_frame_sec == 0 else round(time.time() - last_frame_sec, 1)
    return d


# 启动时清空 USB 输入缓冲：OTA 软重启后可能残留 Ctrl-C 等控制字节，
# 若不 drain，残留的 0x03 会触发下面的 Ctrl-C 分支把 main.py 干掉（停在 REPL）。
r, _, _ = select.select([sys.stdin], [], [], 0)
while r:
    sys.stdin.read(1)
    r, _, _ = select.select([sys.stdin], [], [], 0)

print("M702A READY")

# 帧组装缓冲：用游标 skip 标记已消费字节。
# ⚠️ MicroPython 的 bytearray 不支持 del 删除（会抛 TypeError: 'bytearray' object
# doesn't support item deletion），所以不能 del buf[:17]，改用 skip 偏移标记，
# 攒够一批再重建一次，既不崩也不会累积/爆。
buf = bytearray()
skip = 0
cmd_buf = ""


def _run():
    """主循环（放进函数，便于外层 try-except 捕获并打印真实异常）"""
    global buf, skip, cmd_buf, rx_total, last_frame_sec
    while True:
        # ---------- 1) 接收 M702A 帧（游标 skip 方式，不用 bytearray del） ----------
        if uart.any():
            data = uart.read(uart.any())
            if data:
                rx_total += len(data)
                if DEBUG_HEX:
                    print("RX:%s" % data.hex())
                buf.extend(data)
        # 从 skip 处尽量多地解析完整帧：够 17 字节取一帧，否则丢 1 字节找帧头
        while len(buf) - skip >= FRAME_LEN:
            if buf[skip] == H1 and buf[skip + 1] == H2:
                frame = bytes(buf[skip:skip + FRAME_LEN])
                if (not CHECK_CHECKSUM) or checksum(frame) == frame[16]:
                    latest.update(parse_frame(frame))
                    last_frame_sec = time.time()
                skip += FRAME_LEN
            else:
                skip += 1
        # 攒够一批已消费字节后重建一次，避免 buf 无限增长（比每帧 del 高效且不崩）
        if skip >= 256:
            buf = buf[skip:]
            skip = 0

        # ---------- 2) 响应手机 "get"（USB CDC） ----------
        # 非阻塞逐字符读命令：遇到 \r 或 \n 才算一条命令，命令不丢失不阻塞。
        r, _, _ = select.select([sys.stdin], [], [], 0.05)
        while r:
            ch = sys.stdin.read(1)
            if not ch:
                break
            if ch in ("\r", "\n"):
                if cmd_buf:
                    cmd = cmd_buf.strip().lower()
                    cmd_buf = ""
                    if cmd == "get":
                        # 只返回最新一帧的解析值
                        print(ujson.dumps(build_payload()))
                    # 其他命令忽略
            elif ord(ch) == 3:
                # Ctrl-C (0x03)：OTA 上传的中断信号，主动退出到 REPL 供 RAW REPL 进入
                print("\nCtrl-C received -> exit to REPL")
                raise SystemExit
            else:
                cmd_buf += ch
            r, _, _ = select.select([sys.stdin], [], [], 0)


# ---- 顶层自诊断 ----
# 若 main.py 崩溃（主循环抛异常），打印真实异常与行号（回显可见），
# 避免设备静默退到 REPL 后只能看到 "NameError: get"（那是 REPL 在求值 get）。
# 据此能一眼区分：崩在哪一行 vs 设备根本没跑 main.py（停在 REPL）。
try:
    _run()
except SystemExit:
    raise
except Exception as e:
    print("\n[main.py ERROR]")
    sys.print_exception(e)
    print("[END]")
    raise SystemExit
