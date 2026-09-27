#!/usr/bin/env python3
"""Build device + ARM64 simulator static libraries, then package an XCFramework."""
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent
BUILD = ROOT / "build"
BUILD.mkdir(exist_ok=True)
headers = BUILD / "headers"
headers.mkdir(exist_ok=True)
shutil.copy2(ROOT / "study_whisper.h", headers)
(headers / "module.modulemap").write_text('module StudyWhisper { header "study_whisper.h" export * }\n')
libraries = []
for sdk in ["iphoneos", "iphonesimulator"]:
    directory = BUILD / sdk
    subprocess.run(["cmake", "-S", str(ROOT), "-B", str(directory), "-G", "Xcode",
                    "-DCMAKE_SYSTEM_NAME=iOS", f"-DCMAKE_OSX_SYSROOT={sdk}",
                    "-DCMAKE_OSX_ARCHITECTURES=arm64", "-DCMAKE_OSX_DEPLOYMENT_TARGET=18.5",
                    "-DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED=NO", "-DGGML_METAL=ON"], check=True)
    subprocess.run(["cmake", "--build", str(directory), "--config", "Release", "--target", "studywhisper", "-j", "6"], check=True)
    archives = list(directory.rglob("Release-*/lib*.a"))
    output = directory / "libStudyWhisper.a"
    subprocess.run(["xcrun", "libtool", "-static", "-o", str(output), *map(str, archives)], check=True)
    libraries.extend(["-library", str(output), "-headers", str(headers)])
target = BUILD / "StudyWhisper.xcframework"
if target.exists():
    shutil.rmtree(target)  # Only this script's generated framework.
subprocess.run(["xcodebuild", "-create-xcframework", *libraries, "-output", str(target)], check=True)
