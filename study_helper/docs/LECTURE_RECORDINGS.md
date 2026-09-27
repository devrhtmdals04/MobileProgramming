# 강의 녹음

서재 하단 **강의 녹음 · 녹음 목록**에서 시작한다. Kotlin 공통 화면은 `LectureRecordings.kt`, 상태·명령 경계는 `LectureHost` / `LecturePlatform`이다. Godot이나 문제 채점 브리지에 오디오를 전달하지 않는다.

## 첫 버전

- 제목을 입력하고 녹음 시작 → 처음 한 번 마이크 권한 허용 → 녹음 종료·저장.
- AAC, M4A 컨테이너, 64 kbps. Android·이전 iOS는 모노 44.1 kHz, iOS 26은 마이크의 샘플링 주파수·채널을 사용한다. 시간당 약 29 MB에 컨테이너 부가 정보가 더해진다.
- 녹음 시간 표시, 저장된 목록, 재생·종료, 15초 이전/이후 이동, 이름 변경, 확인 후 삭제.
- 노트 서재로 돌아가거나 화면을 잠가도 녹음은 계속한다. 게임 진입은 녹음을 저장한 뒤 가능하다.
- 마이크는 시작 버튼을 누르고 권한을 허용한 경우에만 켜진다. 권한 요청 이후 앱이 비활성 상태이면 시작하지 않는다.
- 녹음 내용은 앱 내부 저장소의 `recordings/UUID.m4a`에 저장하고, 제목·생성 시각·재생 시간은 같은 UUID의 JSON에 저장한다. 제목을 경로로 사용하지 않는다.
- 앱은 음성을 서버나 AI에 업로드하지 않는다. OS의 기기 백업 여부는 시스템 설정을 따른다.
- iOS 26 이상에서 설치된 한국어 모델로 실시간 받아쓰기를 제공한다. 임시 문장과 확정 문장을 구분하고, 종료 후 **받아쓰기 보기 · 노트로 저장**에서 수정하여 기존 노트 서재에 저장한다. 같은 녹음에서 다시 저장하면 같은 노트를 갱신한다. Android는 현재 녹음·재생만 제공한다. 요약·문제 자동 생성은 포함하지 않는다.

## 운영체제별 동작

**iPhone:** `LectureAudio.swift`가 녹음·재생과 오디오 세션을 소유한다. iOS 26에서는 `LectureTranscription.swift`의 AVAudioEngine 캡처 하나를 AAC 저장과 SpeechAnalyzer 입력으로 분기하고, 이전 iOS에서는 AVAudioRecorder로 음성만 저장한다. 인식 장애가 발생해도 파일 녹음은 계속하며, 미설치 모델을 자동 다운로드하거나 외부 서버로 전송하지 않는다. 확정 문장은 녹음 메타데이터에 수시로 저장한다. 종료 시 최대 12초 동안 마지막 문장 확정을 기다리며, 마무리 실패는 안내한다. 앱의 `audio` 백그라운드 모드로 잠금 상태 녹음을 지원한다. 통화·오디오 중단·입력 장치 분리·오디오 시스템 초기화는 자동 저장을 시도하고 사용자에게 알린다. 자동 재녹음은 하지 않는다.

**Android:** `LectureAudioService`가 microphone foreground service와 녹음 중 알림을 소유한다. 알림에서 종료·저장할 수 있다. Android 13 이상은 최초 마이크 요청 시 알림 권한도 요청한다. 알림 권한을 거부해도 OS의 활성 앱 표시와 앱 내 종료 버튼으로 녹음을 관리할 수 있다. 오디오 포커스 상실·MediaRecorder 오류는 저장 후 중단한다. 서비스는 강제 종료 후 자동 재시작하지 않는다. 한 녹음은 최대 6시간이며 한도 도달 시 저장한다. 재생은 녹음 화면을 벗어나면 종료한다.

저장 중 오류나 너무 짧은 녹음의 인코딩 실패는 실패 메시지를 보여 준다. 가능한 원본 파일을 보존하며, 재실행 시 재생 가능한 미완료 메타데이터의 길이를 복구한다. **프로세스 강제 종료·기기 전원 차단 중의 M4A 복구는 보장하지 않는다.**

## 검증

- `:shared:iosSimulatorArm64Test`: 기존 테스트와 LectureHost 회귀 검사 4개. 녹음 중 충돌 명령 차단, 권한 대기 중 중복 시작 차단, 잘못된 상태 메시지에도 활성 상태 보존, 녹음 완료→재생 상태 전환.
- `:godotAndroid:assembleDebug`, `:godotAndroid:lintDebug`.
- iPhone 서명 빌드, 기존 앱 데이터를 보존하는 업데이트 설치.
- Debug 앱의 `--lecture-probe`: 격리된 임시 폴더에 3초 무음 AAC를 합성하고 저장·길이 복구·이름 변경 후 재열기·실제 재생·탐색·종료·삭제·경로 ID 검사를 수행한다. 마이크를 켜지 않으며 사용자의 녹음 파일에 접근하지 않는다. `LECTURE COMPLETE` / `LECTURE FAILED` 로그를 확인한다.

2026-09-27: 공통 iOS 테스트 **41개 통과**, Android 빌드·Lint 성공(오류 0), iPhone 서명 빌드·설치 성공. iPhone의 `--lecture-probe` **7개 검사 통과**. 실제 마이크를 켜지 않고 합성 무음 AAC로 검증했다. Android 실행 기기가 연결되어 있지 않아 Android 녹음 동작의 실기기 검증은 아직 하지 않았다.

실제 마이크 권한 승인·거부, 잠금 상태에서 1분 녹음 후 재생, 전화 수신 시 저장, 장시간 녹음과 저장 공간 부족은 수동 실기기 확인 대상이다. 합성 AAC 검사가 실제 마이크 품질이나 모든 시스템 중단 상황을 검증하지는 않는다.

## 구현 근거

- [Apple: recording audio session and background mode](https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/record)
- [Android: MediaRecorder and background microphone access](https://developer.android.com/media/platform/mediarecorder)
- [Android: microphone foreground service requirements](https://developer.android.com/about/versions/14/changes/fgs-types-required)

실시간 받아쓰기 검증: `--lecture-transcription-probe`는 별도로 생성한 한국어 합성 음성을 PCM 스트림으로 변환하여 실제 SpeechAnalyzer에 공급한다. 2026-09-27 iPhone에서 인식·재시도 결과 중복 방지·재열기·편집·Kotlin 노트 저장 **8개 검사 통과**, 동일 녹음 재저장 시 노트 중복 없음도 확인했다. 마이크 캡처와 실제 강의 정확도·잠금 중 인식은 사용자가 직접 테스트한다. [실기기 보고서](artifacts/lecture-transcription-report.json).

## 오프라인 텍스트 변환

저장된 녹음에서 **이 기기에서 텍스트로 변환**을 선택하면 iPhone·Android 내부 Whisper로 처리한다. 최초 약 190MB 모델 설치 후에는 인터넷이 필요하지 않다. [사용법과 제한](DEVICE_WHISPER.md).
