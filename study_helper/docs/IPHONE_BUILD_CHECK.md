# iPhone 통합 앱 확인 · 2026-09-27

**Study Helper 서재와 Godot 게임을 연결한 앱의 개발 서명·아이폰 설치·실행 검증을 완료했다.** 테스트 모드를 종료한 뒤 일반 서재 화면으로 다시 실행했다. 이전 Godot 시제품과 동일한 번들 ID를 사용하여 설치 데이터를 유지한다.

## 실행 결과

- 기기: iPhone 17 Pro Max / iOS 26.6.2. 유선 연결·페어링·개발자 모드 확인.
- Xcode 26.4.1 (17E202), iPhoneOS SDK 26.4, Godot 4.6.2, Kotlin 2.4.20.
- 앱 이름: **Study Helper**, 번들 ID: `com.example.studyhelper.jaderun`.
- 자동 개발 서명 빌드 성공. `codesign --verify --deep --strict` 통과.
- 실기기 통합 검증 **19개 통과, 실패 0개**. 별도 테스트 서재·환경설정을 사용했다.

| 검증 항목 | 확인 내용 |
|---|---|
| 기본 화면 | Godot을 초기화하지 않고 Kotlin/Compose 서재 표시 |
| 문서 | 자유 형식 Markdown 가져오기·기기 저장·다시 읽기 |
| 시스템 연결 | 파일 선택 창 표시, 클립보드 복사 |
| 문제 파일 | 잘못된 JSON 거절·미저장, 정상 파일 검증·등록 |
| 실제 게임 | 두 문제 파일로 각각 세 문제 표시·선택·Kotlin 채점·결과 집계 |
| 재진입 | 선택한 파일과 새 세션으로 시작 |
| 종료 | Godot 홈 동작 및 세 번째 진입의 네이티브 조기 종료 |
| 복귀 | 최근 결과 저장, 게임 렌더링·전송 타이머 중단 |

[실기기 보고서](../godot-runner/artifacts/iphone-notebook-report.json) · [서재](../godot-runner/artifacts/iphone-notebook-library.png) · [문제 화면](../godot-runner/artifacts/iphone-notebook-game.png) · [복귀 화면](../godot-runner/artifacts/iphone-notebook-return.png)

같은 통합 검증 19개가 iOS 26.4.1 시뮬레이터에서도 통과했다. `shared` iOS 테스트 35개, Godot 러너 32개·학습 연결 16개도 통과했다. 앱의 모든 터치 동작, 파일 제공자별 클라우드 다운로드, 장시간 성능·발열, App Store 배포는 이번 자동 검증 범위가 아니다.

## 산출물·재현

- iPhone 앱: `godotIOS/build/study-device-derived/Build/Products/Debug-iphoneos/StudyHelper.app`
- 시뮬레이터 앱: `godotIOS/build/study-derived/Build/Products/Debug-iphonesimulator/StudyHelper.app`
- Xcode 프로젝트: `godotIOS/build/study-export/StudyHelper.xcodeproj`
- 서명 빌드 로그: `/private/tmp/study-helper-iphone-build.log`
- 실기기 검증 로그: `/private/tmp/study-notebook-iphone.log`

[현재 iOS 빌드 안내](../godotIOS/README.md)를 따른다. Godot 내보내기마다 프로젝트가 재생성되므로 `STUDY_TEAM_ID` 또는 `DEVELOPMENT_TEAM`으로 본인의 팀을 전달한다. `0000000000`은 서명 없는 내보내기 자리 표시자이다.

일반 서재에서는 엔진을 시작하지 않는다. 첫 게임 이후에는 같은 엔진 인스턴스를 메모리에 보관하고 렌더링을 멈췄다가, 다음 진입에서 게임 씬과 Kotlin 세션을 새로 만든다. [수명·역할 분리](STUDY_GAME_CONTRACT.md).

## 이전 확인 기록

처음에는 `iosApp`의 Canvas 시제품과 Godot 단독 시제품을 서명 없이 ARM64 빌드했다. 이후 Apple 계정·개발 팀·인증서·프로비저닝을 준비하고 Godot 단독 시제품의 실기기 왕복 검증 12개를 통과했다. 현재 설치 앱은 그 뒤에 완성한 위 서재 통합 버전이다. 별도 `iosApp`은 이전 Canvas 실행 경로로 남아 있다.
