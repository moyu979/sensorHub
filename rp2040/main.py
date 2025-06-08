# main.py
import json
from machine import UART, Pin, ADC
import random
import time
import _thread
# Sensor 基类
class Sensor:
    def __init__(self, name):
        self.name = name

    def get_value(self):
        raise NotImplementedError
datas={
    "CO2":0,
    "CH2O":0,
    "TVOC":0,
    "PM25":0,
    "PM10":0,
    "temperature":0,
    "humidity":0,
    "smoke":0,
    "O2":0,
    "pressure":0.0
}

class multiAir(Sensor):
    """
    模拟温度传感器（测试用）。
    """
    def __init__(self):
        super().__init__("multiAir")
        self.uart:UART=UART(0, baudrate=9600, tx=Pin(0), rx=Pin(1))

    def get_value(self):
        # 返回一个随机温度值
        if self.uart.any():
            data:bytes=self.uart.read() # type: ignore
            if data is None:
                return None
            elif data[0]!=0x3C or data[1]!=0x02:
                return None
            else:
                datas["CO2"]=int((data[2])<<8|data[3])
                datas["CH2O"]=int((data[4])<<8|data[5])
                datas["TVOC"]=int((data[6])<<8|data[7])
                datas["PM25"]=int((data[8])<<8|data[9])
                datas["PM10"]=int((data[10])<<8|data[11])
        else:
            return None
        
class pressure(Sensor):
    """
    模拟温度传感器（测试用）。
    """
    def __init__(self):
        super().__init__("multiAir")
        self.adc = ADC(Pin(26))  # ADC0 通道（引脚 GP26）
        self.max_voltage=5

    def get_value(self):
        # 返回一个随机温度值
        value = self.adc.read_u16()  # 读取原始 16-bit 值（范围：0 ~ 65535）
        voltage = value * self.max_voltage / 65535  # 换算为电压（假设参考电压为 3.3V）
        datas["pressure"]=self.convert(voltage)

    def convert(self,voltage):
        #这个需要根据对应传感器写
        return voltage
class O2(Sensor):
    """
    模拟温度传感器（测试用）。
    """
    def __init__(self):
        super().__init__("multiAir")
        self.adc = ADC(Pin(27))  # ADC0 通道（引脚 GP26）
        self.max_voltage=5

    def get_value(self):
        value = self.adc.read_u16()  # 读取原始 16-bit 值（范围：0 ~ 65535）
        voltage = value * self.max_voltage / 65535  # 换算为电压（假设参考电压为 3.3V）
        datas["O2"]=self.convert(voltage)

    def convert(self,voltage):
        #这个需要根据对应传感器写
        return voltage

        
    
class GeigerSensor(Sensor):

    def __init__(self):
        super().__init__("DummyTemperatureSensor")

    def get_value(self):
        # 返回一个随机计数，需要进一步修改
        return round(20 + random.uniform(-5, 5), 2)    


# 主核心任务（core0）
def main():
    sensors = [
        multiAir(),
        pressure(),
        O2(),
        GeigerSensor()
    ]

    while True:
        for sensor in sensors:
            try:
                sensor.get_value()
            except Exception as e:
                print(f"Error reading {sensor.name}: {e}")
        time.sleep(1)

# 串口通信函数 → 放入第二核运行，需要调试
def usbcdc_communicate():
    uart = UART(1, baudrate=9600, tx=Pin(0), rx=Pin(1))
    buffer = b""

_thread.start_new_thread(usbcdc_communicate, ())

if __name__ == "__main__":
    main()
