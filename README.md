# 모프 MOPE

대학생을 위한 동아리 홍보·탐색·문의·개설 요청 Android 앱. 모바일 프로그래밍 수업의 2인 팀 프로젝트입니다.

**현재는 Firebase 없이 실행하는 Mock 개발 템플릿입니다.** 샘플 홍보 피드·검색·상세와 화면 이동을 사용할 수 있습니다. 로그인·채팅 송수신·개설/가입 제출·프로필 저장은 후속 구현이며, 화면에 미연동 상태를 표시합니다.

## 핵심 기능 및 기술

목표 기능: 동아리 홍보 목록·상세·관리자 등록, 이름/키워드·카테고리 탐색, 1:1 실시간 채팅, 개설 요청·본인 내역·운영 검토, 회원가입·인증·프로필, 가입 신청.

Kotlin · Jetpack Compose / Material 3 · Navigation Compose · ViewModel / StateFlow · Coroutines · MVVM + Repository. Firebase Authentication / Firestore는 Phase 2에 도입합니다. Cloud Storage는 추후 검토합니다. Retrofit 및 별도 REST 서버는 사용하지 않습니다.

버전은 [gradle/libs.versions.toml](gradle/libs.versions.toml)에서 관리합니다. AGP 8.13.2, Gradle 8.13, Kotlin/Compose compiler 2.2.21, Compose BOM 2025.10.01을 사용합니다. 최신 버전 일괄 업그레이드보다 기존 AGP·SDK 조합의 안정적인 빌드를 우선했습니다. [AGP 호환성](https://developer.android.com/build/releases/agp-8-13-0-release-notes)과 [Compose Kotlin 설정](https://developer.android.com/jetpack/androidx/releases/compose-kotlin)을 기준으로 구성했습니다.

## 개발 환경 및 실행

1. Android Studio에서 **저장소 루트**를 Open합니다. Gradle JDK는 17 이상(권장 17)으로 설정합니다.
2. SDK Manager에서 Android SDK 36.1과 Build Tools 36.1.0을 설치합니다. minSdk 24, targetSdk 36입니다.
3. Android Studio가 생성하는 `local.properties`에 자신의 `sdk.dir`을 사용합니다. 파일은 Git 제외 대상입니다.
4. Gradle Sync 후 실행 구성 `app`과 API 24 이상 에뮬레이터/기기를 선택하여 Run합니다. 첫 빌드는 의존성 다운로드가 필요합니다.
5. 시작 화면의 **Mock 화면 둘러보기**로 들어갑니다. 이메일/비밀번호 입력은 인증에 사용되지 않습니다.
6. 홈 → 홍보 상세, 탐색 → 검색/필터 → 동아리 상세 → 문의/가입 화면을 확인합니다. 하단 탭에서 개설 요청과 내 정보를 볼 수 있습니다.
7. Screen 파일의 `@Preview`를 Android Studio Split/Design에서 확인합니다. Preview 실제 렌더링은 아직 수동 검증 대상입니다.

macOS의 Android Studio 내장 JDK를 사용하는 예:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windows에서는 `gradlew.bat`을 사용합니다. 다른 환경의 JAVA_HOME은 자신의 JDK 경로로 바꿉니다.

APK: `app/build/outputs/apk/debug/app-debug.apk` (debug 서명, 제출용 최종 빌드는 Phase 5).
테스트: `app/build/reports/tests/testDebugUnitTest/index.html`.
Lint: `app/build/reports/lint-results-debug.html`.
VS Code에는 `Android: Build MOPE`, `Install MOPE`, `Run MOPE` 작업이 있습니다. Install/Run 작업은 에뮬레이터 한 대를 가정합니다.

Firebase 프로젝트나 `google-services.json`은 현재 빌드에 필요하지 않습니다. 설정 파일만 추가해도 원격 모드로 전환되지 않습니다. [Firebase 연동 절차](docs/architecture.md#firebase-도입-절차)를 먼저 확인하세요.

## 디렉터리

```text
.
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/mope/app/
│       │   │   ├── MainActivity.kt, MopeApplication.kt
│       │   │   ├── navigation/
│       │   │   ├── ui/{theme,components,screens}/
│       │   │   │   └── screens/{auth,home,club,search,chat,request}/
│       │   │   ├── data/{model,repository,mock,remote}/
│       │   │   └── domain/
│       │   └── res/values/
│       └── test/java/com/mope/app/{data,domain,ui}/
├── gradle/{libs.versions.toml,wrapper/}
├── docs/
├── .github/{ISSUE_TEMPLATE/,PULL_REQUEST_TEMPLATE.md}
├── legacy/hello-world/       # 기존 설정·README 원본
├── src/, test.kt             # 기존 예제 원본, MOPE 빌드에서 제외
├── README.md
├── CONTRIBUTING.md
└── 개발 계획.md
```

`settings.gradle.kts`에는 `:app`만 포함합니다. 기존 `test.kt`, `src/`, 디렉터리 안내 문서는 삭제하지 않았습니다. 예전 설정과 VS Code 작업은 `legacy/hello-world/`에 보존했습니다. 기존 예제 재현은 해당 NOTICE를 참고하세요.

## 협업 및 문서

| 순서 | 영역 | 담당 및 범위 |
|---|---|---|
| 1 | 프론트엔드 | 김민성 — 전체 Compose 화면·ViewModel·Navigation·공통 UI |
| 2 | 백엔드 | 고승민 — 인증·채팅·개설 요청 / 김민성 — 동아리·홍보·검색·가입 신청 |
| 3 | DB | 고승민 — users·chatRooms/messages·clubRequests / 김민성 — clubs·promotions·membershipApplications |

전체 화면은 기존 Figma 디자인을 기준으로 김민성이 구현합니다. ViewModel은 화면 상태·이벤트를 처리하는 Kotlin 코드로 김민성의 프론트엔드 담당에 포함합니다.

백엔드는 Firebase 연동과 Repository 처리 로직이며 별도 REST 서버를 추가하지 않습니다. DB는 각자 담당 컬렉션의 스키마·쿼리·인덱스·Rules·테스트까지 포함합니다. 문서·통합 검증·리뷰·발표는 공동으로 진행합니다. GitHub 계정은 김민성 @___ / 고승민 @___로 추후 입력합니다.

각자 기능 브랜치와 본인 계정으로 구현·검증 후 상대 리뷰 PR을 병합합니다. 초기 자동 생성 코드의 기여도를 임의로 배분하지 않습니다.

- [개발 계획](개발%20계획.md): 기능 ID, Phase 0~5, 상태·완료 조건·기여도 표
- [CONTRIBUTING](CONTRIBUTING.md): 커밋·리뷰·Merge 규칙
- [아키텍처](docs/architecture.md), [Firestore 설계](docs/firestore-schema.md)
- [화면 흐름](docs/screen-flow.md), [Figma 참고](docs/figma-reference.md)
- [Issue 후보](docs/issues.md), [테스트·검증 기록](docs/testing.md)
- [Figma 원본](https://www.figma.com/design/vUavTwW7VuTMCQojbNkrJT/모프?node-id=0-1): 현재 구조 접근 불가, 실제 디자인 일치 여부 미확인

초기 검증: Mock APK 빌드·단위 테스트·Lint 실행 기록은 `docs/testing.md`가 기준입니다. 원격 Issue/PR 생성과 Firebase 배포는 수행하지 않았습니다. 초기 템플릿·테스트·문서는 `feature/mope-setup` 브랜치에서 검토하며 실제 커밋 이력은 Git 로그를 참고하세요.
