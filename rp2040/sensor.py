class Sensor:
    """
    传感器基类，所有具体传感器都应继承该类，并实现 get_value 方法。
    """

    def __init__(self, name="UnnamedSensor"):
        self.name = name

    def get_value(self):
        """
        获取传感器当前值。
        应由子类实现此方法。
        """
        raise NotImplementedError("子类必须实现 get_value 方法")
    
    def refresh(self):
        raise NotImplementedError("子类必须实现 refresh 方法")
