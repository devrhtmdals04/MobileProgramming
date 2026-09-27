#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p tools/local-stt/build
if [ ! -d tools/local-stt/build/whisper.cpp ]; then
    git clone --depth 1 --branch v1.9.4 https://github.com/ggml-org/whisper.cpp.git tools/local-stt/build/whisper.cpp
fi
test "$(git -C tools/local-stt/build/whisper.cpp rev-parse HEAD)" = 927cfce34f31707e17f2bff35c349632fb9e2c3a
echo 'Whisper source ready. iOS: python3 native-whisper/build_ios.py'
echo 'Android: ./gradlew :godotAndroid:assembleDebug (NDK 28.2.13676358, CMake 3.22.1)'
