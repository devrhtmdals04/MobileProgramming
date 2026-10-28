# 초기화 작업 인계 기록

## 결과

현재 루트에서 Android Studio로 열 수 있는 단일 `:app` MOPE 템플릿을 구성했다. Firebase 없이 Mock 피드·탐색·상세·화면 이동을 제공한다. 기존 예제는 삭제하지 않았다. 이 기록의 초기화 시점에는 Git 커밋·push·원격 설정·Issue 생성·Firebase 배포를 수행하지 않았다. 이후 사용자 요청으로 `feature/mope-setup` 브랜치에서 템플릿·테스트·문서를 커밋해 push하는 작업을 진행했다. 정확한 전송 결과와 커밋은 원격 브랜치 및 Git 로그를 기준으로 한다.

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- 단위 테스트: 9개 통과
- Android Lint: 오류 0개, 경고 14개 (버전 권고·백업·아이콘)
- `git diff --check`: 통과
- 문서 로컬 링크: 존재 여부 확인, 깨진 링크 없음
- 실제 기기·Preview 렌더링: 미검증 (연결된 기기 없음)
- Firebase 기능·Rules: 구현 및 검증 전
- Figma: 접근 실패, 제공 화면 목록 기준 골격 작성

## 생성·수정 파일 전체 목록

빌드 산출물과 기존 변경 없는 파일은 제외한다.

### 수정 파일

- `.gitignore`
- `.vscode/tasks.json`
- `README.md`
- `build.gradle.kts`
- `gradle.properties`
- `settings.gradle.kts`

### 생성 파일

- `.github/ISSUE_TEMPLATE/bug.md`
- `.github/ISSUE_TEMPLATE/feature.md`
- `.github/PULL_REQUEST_TEMPLATE.md`
- `CONTRIBUTING.md`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/mope/app/MainActivity.kt`
- `app/src/main/java/com/mope/app/MopeApplication.kt`
- `app/src/main/java/com/mope/app/data/mock/MockClubRepository.kt`
- `app/src/main/java/com/mope/app/data/model/Models.kt`
- `app/src/main/java/com/mope/app/data/remote/README.md`
- `app/src/main/java/com/mope/app/data/repository/AuthRepository.kt`
- `app/src/main/java/com/mope/app/data/repository/ChatRepository.kt`
- `app/src/main/java/com/mope/app/data/repository/ClubRepository.kt`
- `app/src/main/java/com/mope/app/data/repository/RequestRepository.kt`
- `app/src/main/java/com/mope/app/domain/FilterClubs.kt`
- `app/src/main/java/com/mope/app/navigation/MopeNavHost.kt`
- `app/src/main/java/com/mope/app/ui/components/CommonUi.kt`
- `app/src/main/java/com/mope/app/ui/components/LoadState.kt`
- `app/src/main/java/com/mope/app/ui/screens/auth/AuthScreen.kt`
- `app/src/main/java/com/mope/app/ui/screens/auth/ProfileScreen.kt`
- `app/src/main/java/com/mope/app/ui/screens/chat/ChatScreen.kt`
- `app/src/main/java/com/mope/app/ui/screens/club/ClubScreen.kt`
- `app/src/main/java/com/mope/app/ui/screens/club/ClubViewModel.kt`
- `app/src/main/java/com/mope/app/ui/screens/home/HomeScreen.kt`
- `app/src/main/java/com/mope/app/ui/screens/home/HomeViewModel.kt`
- `app/src/main/java/com/mope/app/ui/screens/request/RequestScreen.kt`
- `app/src/main/java/com/mope/app/ui/screens/search/SearchScreen.kt`
- `app/src/main/java/com/mope/app/ui/screens/search/SearchViewModel.kt`
- `app/src/main/java/com/mope/app/ui/theme/Color.kt`
- `app/src/main/java/com/mope/app/ui/theme/Theme.kt`
- `app/src/main/java/com/mope/app/ui/theme/Type.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/styles.xml`
- `app/src/test/java/com/mope/app/data/MockClubRepositoryTest.kt`
- `app/src/test/java/com/mope/app/domain/FilterClubsTest.kt`
- `app/src/test/java/com/mope/app/ui/HomeViewModelTest.kt`
- `app/src/test/java/com/mope/app/ui/MainDispatcherRule.kt`
- `app/src/test/java/com/mope/app/ui/SearchViewModelTest.kt`
- `docs/architecture.md`
- `docs/figma-reference.md`
- `docs/firestore-schema.md`
- `docs/initialization-report.md`
- `docs/issues.md`
- `docs/screen-flow.md`
- `docs/testing.md`
- `gradle/libs.versions.toml`
- `legacy/hello-world/.vscode/tasks.json`
- `legacy/hello-world/NOTICE.md`
- `legacy/hello-world/README.md`
- `legacy/hello-world/build.gradle.kts`
- `legacy/hello-world/gradle.properties`
- `legacy/hello-world/settings.gradle.kts`
- `개발 계획.md`

## 최종 주요 구조

```text
MOPE/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/mope/app/
│       │   │   ├── MainActivity.kt
│       │   │   ├── MopeApplication.kt
│       │   │   ├── navigation/
│       │   │   ├── ui/
│       │   │   │   ├── theme/
│       │   │   │   ├── components/
│       │   │   │   └── screens/{auth,home,club,search,chat,request}/
│       │   │   ├── data/{model,repository,remote,mock}/
│       │   │   └── domain/
│       │   └── res/values/
│       └── test/java/com/mope/app/{data,domain,ui}/
├── gradle/{libs.versions.toml,wrapper/}
├── .github/{ISSUE_TEMPLATE/,PULL_REQUEST_TEMPLATE.md}
├── docs/
│   ├── architecture.md
│   ├── firestore-schema.md
│   ├── screen-flow.md
│   ├── figma-reference.md
│   ├── issues.md
│   ├── testing.md
│   └── initialization-report.md
├── legacy/hello-world/        # 기존 설정·README·VS Code 작업 보존
├── src/, test.kt              # 기존 예제, 빌드 대상 제외
├── kotlin-android-directory-guide.md
├── README.md
├── CONTRIBUTING.md
└── 개발 계획.md
```

## 첫 개발 순서

사용자 요청에 따라 업무 분담을 프론트엔드 → 백엔드 → DB 순서로 갱신했다. 위 파일 목록과 빌드 결과는 초기화 당시 기록이며 이번 변경은 문서만 수정했다.

1. 공동: SET-03 계약·구현 파일 경계 리뷰, GitHub·가용 시간·마감일 입력.
2. 프론트엔드: 김민성이 전체 화면·ViewModel·Navigation을 담당한다. UI-01부터 Mock으로 진행한다.
3. 백엔드: 고승민은 인증·채팅·개설, 김민성은 동아리·홍보·검색·가입 신청을 담당한다.
4. DB: 고승민은 users·chatRooms/messages·clubRequests, 김민성은 clubs·promotions·membershipApplications의 스키마·쿼리·Rules·테스트를 담당한다.
5. 계약 합의 후 김민성 UI-01과 고승민 인증 계약 테스트·사용자 DB 작업을 병렬 진행한다. 각자 작성분을 상대 리뷰 후 통합한다.

전체 일정과 우선순위는 [개발 계획](../개발%20계획.md), 최초 작업은 [Issue 후보](issues.md)를 기준으로 한다. 초기 자동 생성 변경을 개인 기여로 임의 배분하지 않는다.

## 후속 Codex 작업 요청 예시

- 김민성/프론트엔드: “UI-01과 AUTH-01/FE를 진행해 줘. Mock 계약으로 전체 화면 상태와 AuthViewModel 테스트를 구현해 줘.”
- 고승민/백엔드: “AUTH-01/BE 인증 Repository 계약 테스트와 Firebase 연동을 구현해 줘. 화면·ViewModel은 김민성 작업 경계로 유지해 줘.”
- 김민성/DB: “SEC-01/DB-CLUB의 동아리·홍보 스키마와 Rules 테스트를 작성해 줘. 사용자·채팅·개설 규칙은 고승민 담당으로 유지해 줘.”
