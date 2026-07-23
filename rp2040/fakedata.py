# main.py
# RP2040 伪数据模式：每秒通过 USB CDC 发送模拟传感器数据
import json
import math
import time

# 各传感器的正弦波参数：(中心值, 振幅, 角速度系数)
# 角速度系数控制波动快慢，值越大波动越快
WAVE_CONFIG = {
    "CO2":        (800,   400,  0.3),
    "CH2O":       (0.08,  0.06, 0.5),
    "TVOC":       (0.3,   0.2,  0.4),
    "PM25":       (35,    25,   0.6),
    "PM10":       (60,    40,   0.55),
    "temperature":(25,    5,    0.2),
    "humidity":   (55,    15,   0.25),
    "smoke":      (200,   150,  0.35),
    "O2":         (20.9,  1.0,  0.15),
    "pressure":   (1013,  10,   0.1),
}

# 每个参数额外偏移相位，让波形错开
PHASE_OFFSETS = {
    "CO2": 0.0, "CH2O": 1.2, "TVOC": 2.5,
    "PM25": 0.8, "PM10": 3.1,
    "temperature": 1.8, "humidity": 4.0,
    "smoke": 2.0, "O2": 3.5, "pressure": 0.5,
}

DECIMALS = {
    "CO2": 1, "CH2O": 3, "TVOC": 3,
    "PM25": 1, "PM10": 1,
    "temperature": 1, "humidity": 1,
    "smoke": 1, "O2": 1, "pressure": 1,
}

def generate_pseudo_data(t):
    """基于时间 t（秒）生成正弦波动的模拟数据"""
    data = {}
    for key, (center, amp, omega) in WAVE_CONFIG.items():
        phase = PHASE_OFFSETS.get(key, 0)
        value = center + amp * math.sin(omega * t + phase)
        data[key] = round(value, DECIMALS[key])
    return data

def main():
    """
    主循环：每秒生成一组正弦波动的伪数据并通过 USB CDC 发送。
    print() 默认输出到 USB CDC 串口，Android 端读取即可。
    """
    print("[sensorHub] 正弦波动模式已启动")
    t = 0.0

    while True:
        data = generate_pseudo_data(t)
        payload = json.dumps(data)
        print(payload)
        time.sleep(1)
        t += 1.0

if __name__ == "__main__":
    main()
