#!/usr/bin/env python3
"""Add a source-built ARM64 simulator slice to the official 4.6.2 template.

Run the source build described in README.md first. Leaves device binaries intact.
"""
import subprocess
from pathlib import Path
from zipfile import ZipFile

root = Path(__file__).resolve().parents[1]
build = root / 'godotIOS/build'
template = build / 'templates/ios.zip'
source = build / 'godot-source/bin/libgodot.ios.template_debug.arm64.simulator.a'
member = 'libgodot.ios.debug.xcframework/ios-arm64_x86_64-simulator/libgodot.a'
original = build / 'templates/simulator-original.a'
combined = build / 'templates/simulator-universal.a'
with ZipFile(template) as archive:
    original.write_bytes(archive.read(member))
architecture = subprocess.check_output(['xcrun', 'lipo', '-info', original], text=True)
if 'arm64' in architecture:
    intel = build / 'templates/simulator-intel.a'
    subprocess.run(['xcrun', 'lipo', str(original), '-thin', 'x86_64', '-output', str(intel)], check=True)
    original = intel
subprocess.run(['xcrun', 'lipo', '-create', str(original), str(source), '-output', str(combined)], check=True)
temporary = template.with_suffix('.tmp.zip')
with ZipFile(template) as old, ZipFile(temporary, 'w') as new:
    for info in old.infolist():
        new.writestr(info, combined.read_bytes() if info.filename == member else old.read(info), compresslevel=1)
temporary.replace(template)
print('Installed source-built ARM64 simulator slice in the project-local template.')
