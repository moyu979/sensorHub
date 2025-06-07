# main.py

from sensor import Sensor
import random
import time

class DummyTemperatureSensor(Sensor):
    """
    模拟温度传感器（测试用）。
    """

    def __init__(self):
        super().__init__("DummyTemperatureSensor")

    def get_value(self):
        # 返回一个随机温度值
        return round(20 + random.uniform(-5, 5), 2)
    
class GeigerSensor(Sensor):

    def __init__(self):
        super().__init__("DummyTemperatureSensor")

    def get_value(self):
        # 返回一个随机温度值
        return round(20 + random.uniform(-5, 5), 2)    


def main():
    temp_sensor = DummyTemperatureSensor()

    while True:
        value = temp_sensor.get_value()
        print(f"[{temp_sensor.name}] 当前温度: {value} °C")
        time.sleep(2)


if __name__ == "__main__":
    main()
