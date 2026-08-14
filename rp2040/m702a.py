"""
m702a.py - 四方光电 M702A 空气质量传感器（主动上报模式）驱动

接线（RP2040 / Pico）:
    M702A TX  ->  GPIO1  (UART0 RX, 第2引脚)
    M702A VCC ->  VBUS   (5V)
    M702A GND ->  GND

数据流:
    M702A (主动上报) --UART0--> RP2040 解析帧 --USB CDC--> 手机
    手机发送 "get"（带换行），RP2040 返回 JSON 传感器数据。

帧格式(19字节)说明见: 材料/M702A/readme.md
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

FRAME_LEN = 19            # 帧总长
H1 = 0x3C                 # 帧头1
H2 = 0x02                 # 帧头2

TEMP_FRAC_DIV = 10        # 温度/湿度小数按 1 位小数（/10）；若为 2 位改 100
CHECK_CHECKSUM = True     # 是否校验校验和（算法不确定时设 False 强制解析）
# ==================================================================

uart = UART(0, baudrate=UART_BAUD, tx=UART0_TX, rx=UART0_RX, timeout=50)

# 最近一次解析到的数据（未收到帧前为 0）
latest = {
    "eco2": 0,
    "ch2o": 0.0,
    "tvoc": 0.0,
    "pm25": 0.0,
    "pm10": 0.0,
    "temperature": 0.0,
    "humidity": 0.0,
}

# 最近一次成功收到有效帧的时刻（epoch 秒），用于判断是否“无信号”
last_frame_sec = 0.0
NO_SIGNAL_TIMEOUT_S = 10   # 超过该秒数未收到帧 → 标记为 NO_SIGNAL


def checksum(frame):
    """校验和：前 16 字节累加取低 8 位（假设，以 datasheet 为准）"""
    return sum(frame[0:FRAME_LEN - 1]) & 0xFF


def parse_frame(frame):
    """解析一帧 19 字节数据，返回数值字典"""
    d = {}
    d["eco2"] = (frame[2] << 8) | frame[3]
    d["ch2o"] = (frame[4] << 8) | frame[5]
    d["tvoc"] = (frame[6] << 8) | frame[7]
    d["pm25"] = (frame[8] << 8) | frame[9]
    d["pm10"] = (frame[10] << 8) | frame[11]

    # 温度整数：bit7 = 1 负 / 0 正，低 7 位为绝对值（0x9B = -27）
    t_int = frame[12]
    t_frac = frame[13]
    sign = -1 if (t_int & 0x80) else 1
    t_abs = t_int & 0x7F
    d["temperature"] = round(sign * (t_abs + t_frac / TEMP_FRAC_DIV), 1)

    # 湿度：整数 + 小数
    h_int = frame[14]
    h_frac = frame[15]
    d["humidity"] = round(h_int + h_frac / TEMP_FRAC_DIV, 1)
    return d


def build_payload():
    """组装返回给手机的 JSON。未收到有效信号时带 NO_SIGNAL 状态，便于 App 区分。"""
    no_signal = (last_frame_sec == 0) or (time.time() - last_frame_sec > NO_SIGNAL_TIMEOUT_S)
    d = dict(latest)
    d["status"] = "NO_SIGNAL" if no_signal else "OK"
    return d


print("M702A READY")

buf = bytearray()

while True:
    # ---------- 1) 被动接收 M702A 主动上报帧 ----------
    if uart.any():
        data = uart.read(uart.any())
        for b in data:
            buf.append(b)
            if len(buf) >= FRAME_LEN:
                # 检查帧头对齐
                if buf[0] == H1 and buf[1] == H2:
                    frame = bytes(buf[:FRAME_LEN])
                    if (not CHECK_CHECKSUM) or checksum(frame) == frame[FRAME_LEN - 1]:
                        latest.update(parse_frame(frame))
                        last_frame_sec = time.time()
                    else:
                        print("chksum err")
                    del buf[:FRAME_LEN]
                else:
                    # 未对齐：丢 1 字节，重新找帧头
                    del buf[0]

    # ---------- 2) 响应手机 "get"（USB CDC） ----------
    r, _, _ = select.select([sys.stdin], [], [], 0.05)
    if r:
        line = sys.stdin.readline()
        cmd = line.strip().lower()
        if cmd == "get":
            print(ujson.dumps(build_payload()))
