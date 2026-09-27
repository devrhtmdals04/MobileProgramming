# Study Helper

이 프로젝트는 `MobileProgramming` 저장소의 `study_helper/`에 있습니다. Android Studio에서 이 폴더를 열고, 아래 명령은 모두 이 폴더에서 실행하세요. Android SDK 위치는 IDE에서 설정하거나 추적되지 않는 `local.properties`에 지정합니다. 빌드 결과·SDK·Godot 내보내기 템플릿·Apple 서명 정보는 저장소에 포함하지 않습니다.

노트를 읽고 정리하며, 준비된 문제 파일로 복습하는 Android·iPhone 학습 앱입니다. Android 실행 모듈은 **`godotAndroid`**, iOS 통합 앱은 **`godotIOS/StudyHelper`**이며 앱 이름은 **Study Helper**입니다.

첫 화면은 **노트 서재**입니다. 일반 마크다운을 가져와 제목·문단·목록·인용·표·코드로 읽고 원문을 편집합니다. 노트 검색, 글자 크기 조절, 시스템 다크 모드, 내보내기를 지원합니다. Obsidian의 [읽기/편집 구분](https://obsidian.md/help/edit-and-read)을 참고했고, 그래프·플러그인 등 Obsidian 전체 기능을 구현한 것은 아닙니다.

문제는 노트와 별도의 JSON 파일입니다. **노트의 더 보기 → 문제 생성 프롬프트 복사 → 사용자가 선택한 AI에 붙여넣기 → JSON 파일 가져오기 → 문제·정답·해설 확인 → 게임 복습** 순서로 사용합니다. 일반 노트에 출제용 섹션을 강제하지 않습니다. 앱에서 AI를 자동 호출하거나 노트를 전송하지 않습니다.

- [iPhone·Android 오프라인 음성 변환](docs/DEVICE_WHISPER.md)
- [강의 녹음 사용법·동작·검증](docs/LECTURE_RECORDINGS.md)
- [노트 읽기·편집과 지원 범위](docs/MARKDOWN_NOTES.md)
- **[문제 파일 형식·프롬프트·검증](docs/QUESTION_FILES.md)**
- [실행 가능한 문제 파일 예제](study-materials/2026-2/ai-security-week01.quiz.json)
- [Kotlin ↔ Godot 역할과 JSON 계약](docs/STUDY_GAME_CONTRACT.md)
- [3D 러너 실행·조작](godot-runner/README.md)
- [iOS 빌드·검증](godotIOS/README.md)

`studyCore`는 노트/문제 파일 검증, 프롬프트 구성, 세션·채점을 담당합니다. `shared`는 엔진 의존성 없는 공통 서재·뷰어·문제 미리보기 UI입니다. `godotAndroid`와 iOS의 `StudyPlatform`이 각 기기의 파일 선택·저장·클립보드·게임 진입을 연결합니다. iOS의 `NotebookHost`도 Kotlin으로 문서 검증·학습 세션·최근 결과를 관리합니다. Godot은 관문을 향해 점프·돌진하고 클로즈업과 슬로모션 상태에서 문제를 보여 줍니다. 정답이면 몸이 닿는 순간 벽을 부수고 통과해 착지하며 다음 관문으로 계속 달리고, 오답이면 충돌해 쓰러진 뒤 해설과 재도전으로 이어집니다. 선택한 파일 전체를 섞어 반복하며 사용자가 종료할 때까지 이어집니다. Kotlin은 최초 답안과 해설 후 재확인을 구분해 누적 집계하고, Godot에는 정답 키를 미리 보내지 않습니다.

iOS도 서재로 시작하며, 문제 모음에서 게임을 선택할 때만 Godot을 초기화합니다. 게임 종료 후 서재로 돌아와 결과를 저장하고 렌더링을 중단합니다. 엔진 인스턴스는 첫 실행 이후 메모리에 보관해 다음 게임에 재사용합니다. `androidApp` / `iosApp`은 이전 Canvas 시제품의 별도 실행 진입점입니다. 강의 녹음·기기 내 저장·재생을 지원합니다. iOS 26에서는 한국어 실시간 받아쓰기와 수정 후 노트 저장을 지원합니다. iPhone과 Android 모두 녹음 후 Whisper 기기 내 변환·수정·노트 저장을 지원합니다. 요약·앱 내 문제 자동 생성은 아직 포함하지 않습니다.

검증: 학습 코어 테스트 JVM·iOS 각각 21개, shared iOS 테스트 35개(새 서재 호스트 테스트 5개 포함), Godot 러너 32개·학습 연결 16개 통과. iPhone 17 Pro Max 실기기와 iOS 시뮬레이터에서 서재→게임→복귀·재진입 통합 검증 각각 19개 통과. Android 빌드 성공, 앞선 Lint 오류 0개. 에뮬레이터에서 노트 뷰어, 프롬프트 복사, 잘못된 문제 파일 거절, 정상 파일 미리보기, 사례형 문제의 게임 표시·채점·홈 복귀를 확인했습니다.

[아이폰 서재](godot-runner/artifacts/iphone-notebook-library.png) · [아이폰 검증 기록](godot-runner/artifacts/iphone-notebook-report.json) · [Android 서재](godot-runner/artifacts/android-note-library.png) · [읽기 화면](godot-runner/artifacts/android-note-reader.png) · [문제 파일로 게임 실행](godot-runner/artifacts/android-question-file-game.png)

This is a Kotlin Multiplatform project targeting Android, iOS.

* [/godotIOS](./godotIOS) contains the current iOS notebook host and Godot integration.
  [/iosApp](./iosApp/iosApp) is the earlier Canvas prototype.

* [/shared](./shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./shared/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./shared/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./shared/src/jvmMain/kotlin)
    folder is the appropriate location.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- 현재 Android 학습 앱: 최초 `bash native-whisper/setup.sh` 실행 후 `./gradlew :godotAndroid:assembleDebug` (NDK 28.2.13676358·CMake 3.22.1 필요)
- 기존 Canvas 시제품: `./gradlew :androidApp:assembleDebug`
- 현재 iOS 앱: [iOS 빌드 안내](godotIOS/README.md)에 따라 `StudyHelper.xcodeproj` 생성 후 실행

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :shared:testAndroidHostTest`
- iOS tests: `./gradlew :shared:iosSimulatorArm64Test`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…
