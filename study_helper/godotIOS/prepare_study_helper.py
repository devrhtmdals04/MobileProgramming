#!/usr/bin/env python3
"""Combine the pinned Godot export with the Kotlin notebook and a small UIKit host.

Generated files stay in build/. No engine source patch or second Kotlin runtime.
Run :shared:assembleSharedDebugXCFramework first. Set STUDY_TEAM_ID for device signing.
"""
import argparse
import json
import os
from pathlib import Path
import plistlib
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
EXPORT = ROOT / "godotIOS/build/study-export"
APP = EXPORT / "StudyHelper"
parser = argparse.ArgumentParser()
parser.add_argument("--skip-export", action="store_true", help="Only refresh native host and Kotlin framework")
args = parser.parse_args()
EXPORT.mkdir(parents=True, exist_ok=True)
if not args.skip_export:
    subprocess.run([os.environ.get("GODOT_BIN", "/Applications/Godot.app/Contents/MacOS/Godot"), "--headless", "--path", str(ROOT / "godot-runner"),
                    "--export-debug", "iOS Study Helper", str(EXPORT / "StudyHelper.xcodeproj")], check=True)

for name in ["StudyEngine.h", "StudyEngine.mm", "StudyHelperApp.swift", "LectureAudio.swift", "LectureTranscription.swift", "LectureLocalTranscription.swift", "LectureDeviceTranscription.swift"]:
    shutil.copy2(ROOT / "godotIOS/StudyHelper" / name, APP / name)
shutil.copy2(ROOT / "native-whisper/notices/WHISPER_LICENSES.txt", APP / "WHISPER_LICENSES.txt")
with (APP / "dummy.h").open("a") as header:
    if '#import "StudyEngine.h"' not in (APP / "dummy.h").read_text():
        header.write('\n#import "StudyEngine.h"\n')
framework = ROOT / "shared/build/XCFrameworks/debug/Shared.xcframework"
if not framework.is_dir():
    raise SystemExit("Build :shared:assembleSharedDebugXCFramework first")
shutil.copytree(framework, APP / "Shared.xcframework", dirs_exist_ok=True)
whisper_framework = ROOT / "native-whisper/build/StudyWhisper.xcframework"
if not whisper_framework.is_dir():
    raise SystemExit("Build native-whisper/build_ios.py first")
shutil.copytree(whisper_framework, APP / "StudyWhisper.xcframework", dirs_exist_ok=True)

path = EXPORT / "StudyHelper.xcodeproj/project.pbxproj"
project = json.loads(subprocess.check_output(["plutil", "-convert", "json", "-o", "-", str(path)]))
objects = project["objects"]
def reference(identifier, name, file_type, source_tree="<group>"):
    objects[identifier] = {"isa": "PBXFileReference", "lastKnownFileType": file_type, "path": name, "sourceTree": source_tree}
def build_file(identifier, reference_id):
    objects[identifier] = {"isa": "PBXBuildFile", "fileRef": reference_id}
def append_unique(items, item):
    if item not in items: items.append(item)

reference("AA0000000000000000000001", "StudyHelperApp.swift", "sourcecode.swift")
reference("AA0000000000000000000002", "StudyEngine.mm", "sourcecode.cpp.objcpp")
reference("AA0000000000000000000003", "StudyEngine.h", "sourcecode.c.h")
reference("AA0000000000000000000004", "StudyHelper/Shared.xcframework", "wrapper.xcframework")
reference("AA0000000000000000000020", "WHISPER_LICENSES.txt", "text")
build_file("AA0000000000000000000030", "AA0000000000000000000020")
reference("AA0000000000000000000008", "LectureDeviceTranscription.swift", "sourcecode.swift")
build_file("AA0000000000000000000018", "AA0000000000000000000008")
reference("AA0000000000000000000009", "StudyHelper/StudyWhisper.xcframework", "wrapper.xcframework")
build_file("AA0000000000000000000019", "AA0000000000000000000009")
reference("AA0000000000000000000007", "LectureLocalTranscription.swift", "sourcecode.swift")
build_file("AA0000000000000000000017", "AA0000000000000000000007")
reference("AA0000000000000000000006", "LectureTranscription.swift", "sourcecode.swift")
build_file("AA0000000000000000000016", "AA0000000000000000000006")
reference("AA0000000000000000000005", "LectureAudio.swift", "sourcecode.swift")
build_file("AA0000000000000000000015", "AA0000000000000000000005")
build_file("AA0000000000000000000011", "AA0000000000000000000001")
build_file("AA0000000000000000000012", "AA0000000000000000000002")
build_file("AA0000000000000000000014", "AA0000000000000000000004")
for obj in list(objects.values()):
    if obj.get("isa") == "PBXGroup" and obj.get("path") == "StudyHelper":
        for ref in ["AA0000000000000000000001", "AA0000000000000000000002", "AA0000000000000000000003", "AA0000000000000000000005", "AA0000000000000000000006", "AA0000000000000000000007", "AA0000000000000000000008", "AA0000000000000000000020"]: append_unique(obj["children"], ref)
    if obj.get("isa") == "PBXSourcesBuildPhase":
        for ref in ["AA0000000000000000000011", "AA0000000000000000000012", "AA0000000000000000000015", "AA0000000000000000000016", "AA0000000000000000000017", "AA0000000000000000000018"]: append_unique(obj["files"], ref)
    if obj.get("isa") == "PBXResourcesBuildPhase": append_unique(obj["files"], "AA0000000000000000000030")
    if obj.get("isa") == "PBXFrameworksBuildPhase":
        append_unique(obj["files"], "AA0000000000000000000014")
        append_unique(obj["files"], "AA0000000000000000000019")
    if obj.get("isa") == "XCBuildConfiguration":
        settings = obj["buildSettings"]
        settings["IPHONEOS_DEPLOYMENT_TARGET"] = "18.5"
        if "PRODUCT_BUNDLE_IDENTIFIER" in settings:
            settings["INFOPLIST_KEY_CFBundleDisplayName"] = "Study Helper"
            settings["SWIFT_VERSION"] = "5.0"
            settings["OTHER_LDFLAGS"] = ["$(inherited)", "$(LD_CLASSIC_$(XCODE_VERSION_ACTUAL))", "-lc++", "-framework", "Accelerate", "-framework", "Metal", "-framework", "MetalKit"]
            settings["CLANG_ENABLE_OBJC_ARC"] = "YES"
            settings["ENABLE_USER_SCRIPT_SANDBOXING"] = "NO"
            if obj.get("name") == "Debug": settings["SWIFT_ACTIVE_COMPILATION_CONDITIONS"] = "DEBUG"
            if os.environ.get("STUDY_TEAM_ID"): settings["DEVELOPMENT_TEAM"] = os.environ["STUDY_TEAM_ID"]
main_group = objects[objects[project["rootObject"]]["mainGroup"]]
append_unique(main_group["children"], "AA0000000000000000000004")
append_unique(main_group["children"], "AA0000000000000000000009")
path.write_bytes(plistlib.dumps(project, sort_keys=False))

plist_path = APP / "StudyHelper-Info.plist"
info = plistlib.loads(plist_path.read_bytes())
info["CFBundleDisplayName"] = "Study Helper"
info["UIStatusBarHidden"] = False
info["LSSupportsOpeningDocumentsInPlace"] = True
info["NSMicrophoneUsageDescription"] = "강의 녹음을 기기에 저장하기 위해 마이크를 사용합니다."
info["NSLocalNetworkUsageDescription"] = "연결한 Mac에서 강의 녹음을 텍스트로 변환합니다."
info["NSAppTransportSecurity"] = {"NSAllowsLocalNetworking": True}
info["UIBackgroundModes"] = ["audio"]
for key in ["NSCameraUsageDescription", "NSPhotoLibraryUsageDescription"]:
    info.pop(key, None)
plist_path.write_bytes(plistlib.dumps(info, sort_keys=False))
for strings in APP.glob("*.lproj/InfoPlist.strings"):
    strings.write_text('CFBundleDisplayName = "Study Helper";\n')

# The exported Godot launch storyboard contains an engine splash image. A blank native
# launch surface transitions directly to the Kotlin library, before any engine starts.
storyboard = APP / "Launch Screen.storyboard"
storyboard.write_text('''<?xml version="1.0" encoding="UTF-8"?>
<document type="com.apple.InterfaceBuilder3.CocoaTouch.Storyboard.XIB" version="3.0" toolsVersion="23094" targetRuntime="iOS.CocoaTouch" useAutolayout="YES" launchScreen="YES" useTraitCollections="YES" useSafeAreas="YES" initialViewController="study-launch">
 <device id="retina6_12" orientation="portrait" appearance="light"/>
 <dependencies><plugIn identifier="com.apple.InterfaceBuilder.IBCocoaTouchPlugin" version="23084"/><capability name="Safe area layout guides" minToolsVersion="9.0"/></dependencies>
 <scenes><scene sceneID="study-scene"><objects><viewController id="study-launch" sceneMemberID="viewController"><view key="view" contentMode="scaleToFill" id="study-view"><rect key="frame" x="0.0" y="0.0" width="393" height="852"/><viewLayoutGuide key="safeArea" id="study-safe"/><color key="backgroundColor" red="0.98" green="0.976" blue="0.988" alpha="1" colorSpace="custom" customColorSpace="sRGB"/></view></viewController><placeholder placeholderIdentifier="IBFirstResponder" id="study-first" sceneMemberID="firstResponder"/></objects></scene></scenes>
</document>
''')
print("Prepared", path.parent)
