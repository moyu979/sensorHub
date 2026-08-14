"""
ota_serial_test.py - 在电脑上复现手机 OTA 上传流程，定位 MicroPython RAW REPL 传输问题

用法:
    python ota_serial_test.py <串口> [LF|CRLF]

    <串口> 例: /dev/tty.usbmodem1101   (macOS 用 `ls /dev/tty.usbmodem*` 或
                                        `ls /dev/cu.usbmodem*` 查看)
    [LF|CRLF] 行尾模式，默认 LF（与手机新版一致），可切 CRLF 对比

前提:
    pip install pyserial
    先关闭 MicroPico vREPL 等占用串口的程序，否则打不开串口

这个脚本完整复现 Android MicroPythonUploader 的 OTA 上传步骤，
并打印每个阶段设备端的原始响应，用来判断:
    - 设备端能否进入 RAW REPL
    - 部署脚本后能否收到 RDRDY1
    - _rc() 的 readline 能否读到主机发的数据 (MT:/MS:/MC:/CH:)
    - 最终能否收到 OK/CRC_FAIL/ERR
"""

import sys
import time
import base64
import binascii

import serial

CTRL_A = b"\x01"
CTRL_B = b"\x02"
CTRL_C = b"\x03"
CTRL_D = b"\x04"

# 与 Android MicroPythonUploader 的 RECEIVER_SCRIPT 保持一致（含诊断输出）
RECEIVER_SCRIPT = (
    "import sys,binascii,os,gc\n"
    "def _rc():\n"
    " tn=sys.stdin.readline().strip()\n"
    " if not tn:print('ERR:NF');return\n"
    " print('MT:'+tn)\n"
    " sl=sys.stdin.readline().strip()\n"
    " try:fs=int(sl)\n"
    " except:print('ERR:IS');return\n"
    " print('MS:'+sl)\n"
    " cl=sys.stdin.readline().strip()\n"
    " try:ec=int(cl,16)\n"
    " except:print('ERR:IC');return\n"
    " print('MC:'+cl)\n"
    " tp=tn+'.tmp'\n"
    " try:\n"
    "  if tp in os.listdir():os.remove(tp)\n"
    " except:pass\n"
    " br=0\n"
    " n=0\n"
    " try:\n"
    "  with open(tp,'wb') as f:\n"
    "   while True:\n"
    "    l=sys.stdin.readline()\n"
    "    if l is None:break\n"
    "    d=l.strip()\n"
    "    if not d:break\n"
    "    try:ck=binascii.a2b_base64(d)\n"
    "    except:print('ERR:BD');return\n"
    "    f.write(ck)\n"
    "    br+=len(ck)\n"
    "    n+=1\n"
    "    gc.collect()\n"
    "  print('CH:'+str(n)+':'+str(br))\n"
    " except Exception as e:print('ERR:'+str(e));return\n"
    " ac=0\n"
    " try:\n"
    "  with open(tp,'rb') as f:\n"
    "   while True:\n"
    "    b=f.read(256)\n"
    "    if not b:break\n"
    "    ac=binascii.crc32(b,ac)\n"
    " except:print('ERR:CR');return\n"
    " ac=ac&0xFFFFFFFF\n"
    " if ac!=ec:\n"
    "  print('CRC_FAIL:%08X!=%08X'%(ac,ec))\n"
    "  try:os.remove(tp)\n"
    "  except:pass\n"
    "  return\n"
    " try:\n"
    "  try:\n"
    "   if tn in os.listdir():os.remove(tn)\n"
    "  except:pass\n"
    "  os.rename(tp,tn)\n"
    "  print('OK:%s updated (%db CRC=%08X)'%(tn,br,ac))\n"
    " except Exception as e:print('ERR:RN:'+str(e))\n"
    "sys.stdout.write('RDRDY1\\n')\n"
    "_rc()\n"
)


def drain(ser, timeout=0.3):
    """读走所有等待中的数据，返回字节"""
    out = b""
    deadline = time.time() + timeout
    while time.time() < deadline:
        n = ser.in_waiting
        if n:
            out += ser.read(n)
        else:
            time.sleep(0.02)
    return out


def read_until(ser, terminator, timeout=8.0):
    """读直到遇到终止字节（含），返回字节"""
    buf = b""
    deadline = time.time() + timeout
    while time.time() < deadline:
        n = ser.in_waiting
        if n:
            chunk = ser.read(n)
            buf += chunk
            if terminator in buf:
                return buf
        else:
            time.sleep(0.02)
    return buf


def main():
    if len(sys.argv) < 2:
        print("用法: python ota_serial_test.py <串口> [LF|CRLF]")
        sys.exit(1)
    port = sys.argv[1]
    line_ending = sys.argv[2].upper() if len(sys.argv) > 2 else "LF"
    eol = b"\r\n" if line_ending == "CRLF" else b"\n"
    print(f"[i] 打开 {port} @ 115200, 行尾={line_ending}")

    ser = serial.Serial(port, 115200, timeout=0.1)
    time.sleep(0.2)

    # ---------- 1. 进 RAW REPL ----------
    ser.write(CTRL_C * 2)
    time.sleep(0.3)
    drain(ser)
    ser.write(CTRL_A)
    time.sleep(0.3)
    resp = read_until(ser, b">", 3.0)
    print(f"[1] 进 RAW REPL 响应: {resp!r}")

    # ---------- 2. 部署接收脚本 ----------
    ser.write(RECEIVER_SCRIPT.encode("utf-8"))
    time.sleep(0.2)
    ser.write(CTRL_D)
    resp = read_until(ser, b"RDRDY1", 5.0)
    print(f"[2] 部署脚本响应: {resp!r}")

    # ---------- 3. 发元数据 + 数据 ----------
    file_data = b"print('ota test ok')\n" * 50   # 约 850 字节测试文件
    crc = binascii.crc32(file_data) & 0xFFFFFFFF
    ser.write(b"main.py" + eol)
    ser.write(str(len(file_data)).encode() + eol)
    ser.write(("%08X" % crc).encode() + eol)
    for i in range(0, len(file_data), 384):
        chunk = file_data[i:i + 384]
        ser.write(base64.b64encode(chunk) + eol)
    ser.write(eol)  # EOF 空行
    print(f"[3] 已发送 {len(file_data)} 字节, CRC={crc:08X}")

    # ---------- 4. 读最终结果 ----------
    resp = read_until(ser, CTRL_D, 12.0)
    print(f"[4] 最终响应: {resp!r}")

    # ---------- 5. 软重启恢复设备 ----------
    try:
        ser.write(b"machine.reset()" + eol)
        ser.write(CTRL_D)
    except Exception:
        pass
    ser.close()
    print("[i] 完成")


if __name__ == "__main__":
    main()
