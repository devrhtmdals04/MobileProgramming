# Asset credits

## Character and skeletal animations

- Author: **Kay Lousberg / KayKit**.
- Pack: KayKit Character Pack — Adventurers 1.0.
- Source: https://github.com/KayKit-Game-Assets/KayKit-Character-Pack-Adventures-1.0
- Source revision: `672074b73ba276876a19e8816ecdc5241817ab47`.
- Original file: `addons/kaykit_character_pack_adventures/Characters/gltf/Rogue_Hooded.glb`.
- Local file: `assets/character/runner.glb` (unchanged source model).
- License: **CC0**, original notice preserved in `assets/character/LICENSE.txt`.
- Godot extracts the model's embedded texture as `runner_rogue_texture.png`.
- Equipment meshes are hidden at runtime; no equipment geometry has been removed from the source.

## Korean typeface

- Noto Sans KR variable font, Google / Noto contributors.
- Source: https://github.com/google/fonts/tree/main/ofl/notosanskr
- License: **SIL Open Font License 1.1**, preserved in `assets/fonts/OFL.txt`.
- Local file: `assets/fonts/NotoSansKR.ttf`.

## Engine

Godot Engine is used under the MIT license. Engine and third-party notices:
https://godotengine.org/license/ and https://godotengine.org/license/#thirdparty.
The Android module uses the official Maven artifact `org.godotengine:godot:4.6.2.stable`.

## Original project assets

The bridge, canyon, palm fronds, architecture, obstacles and collectible mesh were created
for this project with `tools/build_environment.py`. The six audio cues were synthesized with
`tools/build_audio.py`. They do not depend on any external paid assets or services.
