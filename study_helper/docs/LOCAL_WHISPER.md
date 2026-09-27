# Mac에서 강의 녹음 변환하기 (선택적 개발 도구)

기본 사용 방식은 [iPhone·Android 기기 내 변환](DEVICE_WHISPER.md)이다. Mac 버튼은 기본 화면에서 숨기며 개발 실행 인자 `--enable-mac-transcription`으로만 표시한다.

iPhone에 저장한 녹음을 같은 네트워크의 Mac으로 보내 **Whisper large-v3 Q5_0**으로 변환한다. 유료 API 키나 외부 AI 서비스가 필요하지 않으며, 모델을 내려받은 뒤에는 인터넷 없이 로컬 네트워크로 처리한다. 실시간 받아쓰기는 기존 Apple 엔진이며, Whisper는 **녹음 완료 후 다시 변환**하는 기능이다. Android에는 아직 전송 기능이 없다.

## 실행

Mac에 `git`, `cmake`, `ffmpeg`(`ffprobe` 포함), `python3`, Xcode Command Line Tools가 필요하다.

```sh
bash tools/local-stt/setup.sh
python3 tools/local-stt/server.py
```

setup은 whisper.cpp v1.9.4의 커밋 `927cfce34f31707e17f2bff35c349632fb9e2c3a`를 확인하고 Metal 지원 CLI를 빌드한다. 약 1.08GB의 모델은 `tools/local-stt/build/`에 저장하며 Git에서 제외한다. 모델 SHA-256은 `d75795ecff3f83b5faa89d1900604ad8c780abd5739fae406de19f23ecd98ad1`이다. 모델 다운로드 중 중단되면 `.part`를 다시 다운로드한다. 기존 모델의 해시가 다르면 해당 파일을 별도로 옮기고 setup을 다시 실행한다.

`tools/local-stt/start.command`를 실행하면 서버 실행 중 Mac 잠자기를 방지한다. 서버를 실행한 터미널과 Mac을 켜 두어야 한다. 중지는 Ctrl+C. 절전 중에는 변환할 수 없다.

## 개발용 iPhone 연결

서버 첫 실행 시 `build/local-stt-pairing.json`에 Mac의 `.local` 주소와 무작위 인증 토큰을 생성한다. 이 파일은 비밀 정보이며 커밋하거나 공유하지 않는다. 연결한 기기에 한 번 복사한다.

```sh
xcrun devicectl device copy to --device YOUR_DEVICE_ID \
  --domain-type appDataContainer --domain-identifier com.example.studyhelper.jaderun \
  --source tools/local-stt/build/local-stt-pairing.json \
  --destination Documents/local-stt-pairing.json
```

앱을 열고 강의 녹음 화면의 새로고침을 누른다. 앱이 인증 정보를 기기 전용 Keychain에 저장하고 Documents의 연결 파일을 삭제한다. 재연결은 새 연결 파일을 같은 위치에 복사하면 된다. 현재는 개발 기기용 연결 방식이며 일반 사용자용 QR 연결 UI는 없다.

1. iPhone과 Mac을 같은 Wi-Fi에 연결한다.
2. 강의를 녹음한 뒤 종료·저장한다.
3. 저장된 녹음에서 **Mac에서 다시 변환 · Whisper**를 누른다.
4. 로컬 네트워크 권한을 허용하고 앱 화면을 유지한다. 최초 권한 허용 직후 요청이 실패했다면 다시 누른다.
5. 완료된 텍스트를 확인·수정하고 **노트로 저장**한다.

기존 음성과 받아쓰기는 서버 오류·연결 실패·취소 시 유지된다. 성공한 Whisper 결과는 별도 필드에 보관하며, 노트 저장을 누를 때 편집한 결과를 현재 받아쓰기로 확정한다. 기존 노트는 같은 녹음 ID로 갱신된다. 변환 중에는 자동 화면 잠금을 잠시 막는다. 앱을 백그라운드로 보내면 완료가 보장되지 않으며, 음성은 남아 있으므로 재시도할 수 있다. 취소는 iPhone 요청을 중단하고 늦은 응답을 무시한다. Mac의 진행 중 추론은 끝날 때까지 계속될 수 있다.

## 처리와 제한

- 공유 Kotlin: 목록·버튼·대기 및 편집 UI. Swift: 음성 파일, Keychain, 업로드 및 결과 저장. Mac Python: 인증, 변환 작업 제어. whisper.cpp: 한국어 추론. Godot는 관여하지 않는다.
- 한 번에 한 녹음, 최대 128MiB·2시간. 요청이 겹치면 409 오류로 재시도를 안내한다.
- 업로드는 파일에서 전송하고 서버에서는 임시 파일로 받는다. ffmpeg로 16kHz mono PCM을 만든 뒤 Whisper를 실행한다. 처리 뒤 임시 파일을 삭제하며 서버 로그에 음성·인증 토큰·텍스트를 남기지 않는다.
- 매 요청 모델을 로드한다. 첫 버전은 간단한 CLI 실행 방식이며 장시간 강의 처리 속도는 아직 측정하지 않았다.
- `.local` 주소만 앱에서 허용하고 리디렉션을 거부한다. ATS는 로컬 네트워크만 허용한다. 개발용 LAN HTTP이므로 전송 자체는 암호화되지 않는다. 신뢰하는 개인 네트워크에서 사용하고 인터넷에 포트를 공개하지 않는다. 배포 서비스에는 HTTPS와 별도 사용자 인증이 필요하다.
- 외부 요금은 없지만 Mac 메모리·GPU·전력을 사용한다. 잡음·거리·전공 용어에 따라 정확도가 달라지며, 시험에 사용할 문장과 수식은 직접 확인한다.

## 검증

```sh
python3 -m unittest discover -s tools/local-stt -p 'test_*.py' -v
```

2026-09-27, ARM64 Mac 24GiB: 한국어 합성 음성 11.55초를 실제 업로드해 19.66초에 변환했다. Apple 받아쓰기의 “영광이를” 부분이 Whisper에서는 “0과 1을”로 변환됐다. 짧은 합성 샘플 한 개의 결과이며 실제 강의나 Soniox와의 성능 비교를 의미하지 않는다.

DEBUG 기기 검사는 Documents의 `lecture-korean-fixture.m4a`와 연결 설정을 준비한 뒤 `--lecture-local-probe`로 실행한다. 마이크 없이 실제 업로드·추론·원본 보존·Kotlin 노트 저장을 검사하고, 격리된 노트 서재를 사용한다. 결과는 `Documents/lecture-local-report.json`에 남는다.

참조: [whisper.cpp](https://github.com/ggml-org/whisper.cpp), [변환된 Whisper 모델](https://huggingface.co/ggerganov/whisper.cpp/tree/main). whisper.cpp와 Whisper는 MIT 라이선스이며 배포 시 각 라이선스 고지를 포함한다.

실기기 업로드·원본 보존·편집 화면·Kotlin 노트 저장 검사 5개 통과: [기기 보고서](artifacts/lecture-local-report.json). 공유 Kotlin iOS 테스트 42개와 서버 HTTP 검사 6개도 통과했다. 최초 IPv4 전용 서버에서 기기 연결에 실패하여 IPv4·IPv6 동시 수신으로 수정한 뒤 실기기 성공을 확인했다.
