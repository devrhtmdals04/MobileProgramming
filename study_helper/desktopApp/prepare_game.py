#!/usr/bin/env python3
"""Bundle the installed Godot executable and this repository's game, on the build OS."""
import argparse
from pathlib import Path
import shutil
import subprocess
import sys

parser = argparse.ArgumentParser()
parser.add_argument("--godot", required=True)
parser.add_argument("--output", required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
binary = Path(args.godot).resolve()
if not binary.is_file():
    raise SystemExit("Set GODOT_BIN to the Godot 4.6.2 executable for this operating system.")
out = Path(args.output).resolve()
out.mkdir(parents=True, exist_ok=True)
subprocess.run([str(binary), "--headless", "--path", str(root / "godot-runner"), "--editor", "--import", "--quit"], check=True)
subprocess.run([str(binary), "--headless", "--path", str(root / "godot-runner"), "--export-pack", "Android pack", str(out / "study-game.pck")], check=True)
target = out / ("godot.exe" if sys.platform == "win32" else "godot")
shutil.copy2(binary, target)
target.chmod(0o755)
if sys.platform == "darwin":
    # Sign only our bundled copy. The user's Godot installation is untouched.
    subprocess.run(["codesign", "--force", "--sign", "-", str(target)], check=True)
for name in ["GODOT_LICENSE.txt", "GODOT_COPYRIGHT.txt"]:
    shutil.copy2(root / "desktopApp/licenses" / name, out / name)
shutil.copy2(root / "godot-runner/README.md", out / "GAME_README.md")
print("Prepared desktop game:", out)
