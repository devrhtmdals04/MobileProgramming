#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build
for command in cmake ffmpeg ffprobe curl git python3; do
    command -v "$command" >/dev/null || { echo "Missing command: $command"; exit 1; }
done
if [ ! -d build/whisper.cpp ]; then
    git clone --depth 1 --branch v1.9.4 https://github.com/ggml-org/whisper.cpp.git build/whisper.cpp
fi
test "$(git -C build/whisper.cpp rev-parse HEAD)" = 927cfce34f31707e17f2bff35c349632fb9e2c3a
cmake -S build/whisper.cpp -B build/whisper.cpp/build -DCMAKE_BUILD_TYPE=Release -DGGML_METAL=ON
cmake --build build/whisper.cpp/build --config Release --target whisper-cli -j 6
if [ ! -f build/ggml-large-v3-q5_0.bin ]; then
    curl -L --fail --retry 3 -o build/model.part https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-q5_0.bin
    mv build/model.part build/ggml-large-v3-q5_0.bin
fi
echo 'd75795ecff3f83b5faa89d1900604ad8c780abd5739fae406de19f23ecd98ad1  build/ggml-large-v3-q5_0.bin' | shasum -a 256 -c -
echo 'Ready. Run: python3 tools/local-stt/server.py'
