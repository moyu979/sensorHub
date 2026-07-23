"""
main.py - RP2040 GPIO 测试程序
功能：不断反转第一个 GPIO 引脚（GPIO 0），频率 1Hz
符合 MicroPython 格式，上传到 RP2040 即可自动运行
"""
from machine import Pin
import time

# 使用 GPIO 0（RP2040 的第一个 GPIO 引脚）
# 如果你的板子使用其他引脚（如 Pico 的板载 LED 是 GPIO 25），修改此处即可
LED_PIN = 0

led = Pin(LED_PIN, Pin.OUT)

print(f"[GPIO Test] 开始以 1Hz 频率翻转 GPIO {LED_PIN}")

while True:
    led.toggle()
    time.sleep(0.5)  # 1Hz = 每 0.5 秒翻转一次（周期 1 秒）
