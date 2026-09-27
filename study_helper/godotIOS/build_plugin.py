#!/usr/bin/env python3
"""Build the transport against the same Kotlin core for device and simulator."""
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "godotIOS/build/plugin"
PLUGIN = ROOT / "godot-runner/ios/plugins/study_bridge"
BUILD.mkdir(parents=True, exist_ok=True)

def run(*args):
    subprocess.run(list(map(str, args)), check=True, cwd=ROOT)

libraries = []
for sdk, target, kotlin_target in [
    ("iphoneos", "arm64-apple-ios15.0", "iosArm64"),
    ("iphonesimulator", "arm64-apple-ios15.0-simulator", "iosSimulatorArm64"),
]:
    output = BUILD / sdk
    output.mkdir(exist_ok=True)
    sdk_path = subprocess.check_output(["xcrun", "--sdk", sdk, "--show-sdk-path"], text=True).strip()
    run("xcrun", "--sdk", sdk, "clang++", "-target", target, "-isysroot", sdk_path,
        "-fobjc-arc", "-fmodules", "-F", ROOT / f"studyCore/build/bin/{kotlin_target}/debugFramework",
        "-c", ROOT / "godotIOS/StudyBridge.mm", "-o", output / "StudyBridge.o")
    run("xcrun", "ar", "rcs", output / "libStudyBridge.a", output / "StudyBridge.o")
    libraries += ["-library", output / "libStudyBridge.a"]

framework = PLUGIN / "StudyBridge.xcframework"
if framework.exists():
    shutil.rmtree(framework)  # Generated output in this script's fixed destination.
run("xcodebuild", "-create-xcframework", *libraries, "-output", framework)
shutil.copytree(ROOT / "studyCore/build/XCFrameworks/debug/StudyCore.xcframework",
                PLUGIN / "StudyCore.xcframework", dirs_exist_ok=True)
