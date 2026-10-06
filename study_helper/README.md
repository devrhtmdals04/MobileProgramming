# Study Helper

**PC 로컬 웹:** [`Study Helper 웹 실행.command`](<Study Helper 웹 실행.command>)를 더블클릭하면 브라우저에서 실행됩니다. PDF 요약·문제 생성·일반 퀴즈·오답 복습을 제공합니다. [실행·저장 안내](webApp/README.md) · 터미널: `python3 webApp/server.py`

PC 앱과 Google Drive 동기화가 추가되었습니다. [PC 빌드·실행](desktopApp/README.md) · [Google OAuth 설정·폴더 연동](docs/GOOGLE_DRIVE_SYNC.md). Drive는 OAuth 프로젝트 설정 후 사용하며, 현재 노트·문제를 수동으로 양방향 동기화합니다.

노트를 읽고 정리하며, 준비된 문제 파일로 복습하는 Android·iPhone 학습 앱입니다. Android 실행 모듈은 **`godotAndroid`**, iOS 통합 앱은 **`godotIOS/StudyHelper`**이며 앱 이름은 **Study Helper**입니다.

첫 화면은 **노트 서재**입니다. 일반 마크다운을 가져와 제목·문단·목록·인용·표·코드로 읽고 원문을 편집합니다. 노트 검색, 글자 크기 조절, 시스템 다크 모드, 내보내기를 지원합니다. Obsidian의 [읽기/편집 구분](https://obsidian.md/help/edit-and-read)을 참고했고, 그래프·플러그인 등 Obsidian 전체 기능을 구현한 것은 아닙니다.

문제는 노트와 별도의 JSON 파일입니다. **노트의 더 보기 → 문제 생성 프롬프트 복사 → 사용자가 선택한 AI에 붙여넣기 → JSON 파일 가져오기 → 문제·정답·해설 확인 → 게임 복습** 순서로 사용합니다. 일반 노트에 출제용 섹션을 강제하지 않습니다. 기존 프롬프트 복사 방식은 계속 사용할 수 있습니다. **PDF 요약 · 문제 생성**에서는 사용자가 OpenAI API 키를 입력하고 생성 버튼을 누르면 선택한 PDF를 OpenAI에 전송합니다.

- [iPhone·Android 오프라인 음성 변환](docs/DEVICE_WHISPER.md)
- [강의 녹음 사용법·동작·검증](docs/LECTURE_RECORDINGS.md)
- [온라인 강의 전사·요약·문제 생성 반복 절차](docs/LECTURE_PIPELINE.md)
- [노트 읽기·편집과 지원 범위](docs/MARKDOWN_NOTES.md)
- **[문제 파일 형식·프롬프트·검증](docs/QUESTION_FILES.md)**
- [실행 가능한 문제 파일 예제](</Users/goseungmin/Desktop/2026-2/제작기록/2026-2/ai-security-week01.quiz.json>)
- [Kotlin ↔ Godot 역할과 JSON 계약](docs/STUDY_GAME_CONTRACT.md)
- [3D 러너 실행·조작](godot-runner/README.md)
- [iOS 빌드·검증](godotIOS/README.md)

`studyCore`는 노트/문제 파일 검증, 프롬프트 구성, 세션·채점을 담당합니다. `shared`는 엔진 의존성 없는 공통 서재·뷰어·문제 미리보기 UI입니다. `godotAndroid`와 iOS의 `StudyPlatform`이 각 기기의 파일 선택·저장·클립보드·게임 진입을 연결합니다. iOS의 `NotebookHost`도 Kotlin으로 문서 검증·학습 세션·최근 결과를 관리합니다. Godot은 관문을 향해 점프·돌진하고 클로즈업과 슬로모션 상태에서 문제를 보여 줍니다. 정답이면 몸이 닿는 순간 벽을 부수고 통과해 착지하며 다음 관문으로 계속 달리고, 오답이면 충돌해 쓰러진 뒤 해설과 재도전으로 이어집니다. 선택한 파일 전체를 섞어 반복하며 사용자가 종료할 때까지 이어집니다. Kotlin은 최초 답안과 해설 후 재확인을 구분해 누적 집계하고, Godot에는 정답 키를 미리 보내지 않습니다.

iOS도 서재로 시작하며, 문제 모음에서 게임을 선택할 때만 Godot을 초기화합니다. 게임 종료 후 서재로 돌아와 결과를 저장하고 렌더링을 중단합니다. 엔진 인스턴스는 첫 실행 이후 메모리에 보관해 다음 게임에 재사용합니다. `androidApp` / `iosApp`은 이전 Canvas 시제품의 별도 실행 진입점입니다. 강의 녹음·기기 내 저장·재생을 지원합니다. iOS 26에서는 한국어 실시간 받아쓰기와 수정 후 노트 저장을 지원합니다. iPhone과 Android 모두 녹음 후 Whisper 기기 내 변환·수정·노트 저장을 지원합니다. PDF 요약과 앱 내 문제 생성은 아래 OpenAI API 기능으로 제공합니다. 녹음 내용의 자동 요약은 아직 연결하지 않았습니다.

검증: 학습 코어 테스트 JVM·iOS 각각 21개, shared iOS 테스트 35개(새 서재 호스트 테스트 5개 포함), Godot 러너 32개·학습 연결 16개 통과. iPhone 17 Pro Max 실기기와 iOS 시뮬레이터에서 서재→게임→복귀·재진입 통합 검증 각각 19개 통과. Android 빌드 성공, 앞선 Lint 오류 0개. 에뮬레이터에서 노트 뷰어, 프롬프트 복사, 잘못된 문제 파일 거절, 정상 파일 미리보기, 사례형 문제의 게임 표시·채점·홈 복귀를 확인했습니다.

[아이폰 서재](godot-runner/artifacts/iphone-notebook-library.png) · [아이폰 검증 기록](godot-runner/artifacts/iphone-notebook-report.json) · [Android 서재](godot-runner/artifacts/android-note-library.png) · [읽기 화면](godot-runner/artifacts/android-note-reader.png) · [문제 파일로 게임 실행](godot-runner/artifacts/android-question-file-game.png)


## PDF 요약 · 문제 생성 (OpenAI)

서재의 **PDF 요약 · 문제 생성** → **API 설정**에 OpenAI API 키 입력 → **PDF 선택** → **요약 만들기** 순서로 사용합니다. PC·Android·iPhone의 현재 서재에 연결되어 있습니다. 기본 모델은 `gpt-5.4-mini`이며 API 설정에서 계정이 사용할 수 있는 PDF 입력 지원 모델로 바꿀 수 있습니다.

- PDF 원본(최대 12MiB)을 OpenAI Responses API에 보내 텍스트와 표·그림을 함께 분석하도록 요청합니다. API 호출은 요약/문제 생성 버튼을 누를 때만 이루어집니다.
- 요약은 한 줄 요약·핵심 개념·주제별 정리·시험 포인트·용어 표로 구성하며, 생성 중에도 마크다운 서식으로 표시합니다. 완성된 노트는 수정 후 서재에 저장할 수 있습니다.
- 문제 만들기에서는 문항 수(5/10/15/20), 난이도, 서술형 포함 여부를 선택합니다. 현재 요약을 저장한 뒤 **원본 PDF**를 다시 전송해 출제하며, 기존 문제 형식으로 검증합니다. 미리보기에서 정답·해설·근거를 확인하고 문제 모음에 저장합니다. 개념·계산 문제는 기존 게임으로 복습할 수 있습니다.
- API 키와 선택한 PDF는 메모리에만 보관합니다. 앱 종료 후 키와 PDF를 다시 입력/선택해야 합니다. 요약·문제는 저장 버튼을 누르면 기존 파일 저장소에 남고 기존 동기화 대상이 됩니다. 요청에는 `store: false`를 설정합니다.
- 중지·통신 오류·미완성 응답·잘못된 문제 형식은 저장하지 않습니다. 실패 후 수동으로 재시도할 수 있습니다.
- 페이지 근거는 AI가 작성한 텍스트입니다. 원본 PDF 보관·페이지 바로가기, 그림의 노트 삽입, 수식 전용 렌더링은 아직 포함하지 않습니다. 큰 PDF는 나눠서 선택하세요.

키 없이 요청 구성·완료/중지/실패 처리·노트 저장·문제 검증을 테스트합니다. 실제 API 연결과 강의자료의 요약·출제 품질은 사용자 키 연결 후 확인해야 합니다.

API 형식은 [OpenAI 공식 PDF 입력 문서](https://developers.openai.com/api/docs/guides/file-inputs)와 [스트리밍 문서](https://developers.openai.com/api/docs/guides/streaming-responses)를 기준으로 합니다.

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
