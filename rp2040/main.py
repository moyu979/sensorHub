import sys
import ujson
import random


def get_sensor_data():
    return {
        "humidity": round(random.uniform(40.0, 80.0), 1),
        "temperature": round(random.uniform(22.0, 32.0), 1),
        "oxygen": round(random.uniform(20.5, 21.0), 2),
        "formaldehyde": round(random.uniform(0.01, 0.10), 3),
    }


print("READY")

buffer = ""

while True:
    char = sys.stdin.read(1)

    if char in ("\r", "\n"):
        command = buffer.strip()
        buffer = ""

        if command == "get":
            print(ujson.dumps(get_sensor_data()))
        elif command:
            print(ujson.dumps({
                "error": "unknown command"
            }))
    else:
        buffer += char