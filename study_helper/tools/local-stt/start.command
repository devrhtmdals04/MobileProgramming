#!/bin/bash
set -euo pipefail
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
cd "$(dirname "$0")/../.."
echo 'Whisper 로컬 서버를 시작합니다. 종료하려면 Ctrl+C를 누르세요.'
echo '처리하는 동안 Mac의 잠자기를 방지합니다.'
caffeinate -i python3 tools/local-stt/server.py
