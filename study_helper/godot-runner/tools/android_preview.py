"""Pixel_4 visual smoke: capture touch movement and return to the start screen."""
import subprocess
import time
from pathlib import Path

ADB = "/Users/goseungmin/Library/Android/sdk/platform-tools/adb"
PREFIX = [ADB, "-s", "emulator-5554"]
OUT = Path(__file__).resolve().parents[1] / "artifacts"


def adb(*args):
    return subprocess.check_output(PREFIX + list(args), timeout=30)


def capture(name):
    (OUT / name).write_bytes(adb("exec-out", "screencap", "-p"))
    print("Captured", name, flush=True)


pid = adb("shell", "pidof", "com.example.study_helper.jaderun").decode().strip()
for _ in range(40):
    logs = adb("logcat", "-d", "--pid=" + pid, "-s", "godot:I").decode()
    if "Jade Run: scene ready" in logs:
        break
    time.sleep(1)
else:
    raise RuntimeError("Scene did not report ready")
time.sleep(1)
capture("android-ready.png")
adb("shell", "input", "tap", "540", "2050")
adb("shell", "input", "swipe", "540", "1400", "220", "1400", "150")
time.sleep(.15)
capture("android-running.png")
adb("shell", "input", "swipe", "540", "1450", "540", "1050", "150")
capture("android-jump.png")
time.sleep(1)
adb("shell", "input", "swipe", "540", "1000", "540", "1450", "150")
capture("android-slide.png")
adb("shell", "input", "tap", "970", "114")
time.sleep(.25)
capture("android-final-pause.png")
# The same secondary button returns home from pause or result.
adb("shell", "input", "tap", "540", "1295")
time.sleep(.3)
capture("android-home.png")
print("Visual smoke complete; inspect the screenshots for input results.", flush=True)
