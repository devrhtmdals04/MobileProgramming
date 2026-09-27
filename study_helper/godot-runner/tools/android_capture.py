"""Capture the running prototype and exercise Android background/resume.

Run on the Pixel_4 emulator after pausing the game. Does not clear app data.
"""
import subprocess
import time
from pathlib import Path

ADB = "/Users/goseungmin/Library/Android/sdk/platform-tools/adb"
PREFIX = [ADB, "-s", "emulator-5554"]
OUT = Path(__file__).resolve().parents[1] / "artifacts"


def adb(*args):
    return subprocess.check_output(PREFIX + list(args), timeout=20)


def capture(name):
    (OUT / name).write_bytes(adb("exec-out", "screencap", "-p"))
    print("Captured", name, flush=True)


capture("android-paused.png")
adb("shell", "input", "keyevent", "3")
time.sleep(.8)
adb("shell", "am", "start", "-n", "com.example.study_helper.jaderun/.JadeRunActivity")
time.sleep(1.0)
capture("android-resumed.png")
print("Android background/resume sequence completed")
