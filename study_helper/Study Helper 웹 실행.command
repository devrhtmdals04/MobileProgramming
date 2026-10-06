#!/bin/zsh
cd -- "$(dirname -- "$0")"
if ! command -v python3 >/dev/null 2>&1; then
  echo 'Python 3가 필요합니다. Python을 설치한 뒤 다시 실행해 주세요.'
  read -r '?Enter를 누르면 닫습니다.'
  exit 1
fi
python3 webApp/server.py
