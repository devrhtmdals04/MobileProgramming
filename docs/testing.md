# 검증 기록 및 후속 테스트

## 초기 로컬 검증

검증 환경: macOS arm64, Android Studio 내장 JDK, Gradle 8.13, AGP 8.13.2, SDK 36.1 / Build Tools 36.1.0. Firebase 설정·네트워크 SDK 없이 Mock 모드로 검증했다.

| 항목 | 결과 | 근거·한계 |
|---|---|---|
| `:app:assembleDebug` | 성공 | `app/build/outputs/apk/debug/app-debug.apk` 생성 |
| `:app:testDebugUnitTest` | 성공, 9개 | 초기 NO-SOURCE 이후 실제 테스트 추가하여 재실행 |
| `:app:lintDebug` | 성공, 오류 0·경고 14 | 기존 도구/라이브러리 버전·백업 설정·앱 아이콘 관련 경고 |
| `git diff --check` | 통과 | 공백·패치 형식 검사 |
| 기기 실행·화면 이동 | 미검증 | ADB 연결 기기·실행 중 에뮬레이터 없음 |
| Compose Preview 렌더링 | 미검증 | Preview 함수 컴파일 성공, Studio 시각 검증 필요 |
| Firebase / Rules / 인증 / 실시간 송수신 | 미검증 | SDK·설정·Rules 구현 전 |
| 원격 CI / PR / 보호 브랜치 | 미구성 | 로컬 검사 로그를 PR에 첨부하는 방식으로 시작 |

빌드 과정에 SDK XML 버전 차이와 네이티브 라이브러리 심볼 strip 경고가 있었지만 APK 빌드는 성공했다. Lint의 최신 targetSdk/라이브러리 권고는 호환성이 검증된 기존 조합을 유지하면서 문서화했으며 무조건 최신으로 올리지 않았다. 최종 제출 전 SDK·백업 정책·앱 아이콘을 다시 검토한다. 현재 앱은 실제 사용자 데이터를 저장하지 않는다.

## 실행 명령

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
git diff --check
```

다른 환경에서는 자신의 JDK 경로를 사용한다. 테스트 보고서 `app/build/reports/tests/testDebugUnitTest/index.html`, Lint 보고서 `app/build/reports/lint-results-debug.html`을 확인한다. 생성된 build 결과는 커밋하지 않는다.

## 작성된 단위 테스트

| 테스트 | 수 | 검증 내용 |
|---|---|---|
| FilterClubsTest | 4 | 빈 검색·공백 제거·소개 검색·AND 필터·대소문자 |
| HomeViewModelTest | 3 | Loading→Content, Empty, 오류 후 재시도 성공 |
| SearchViewModelTest | 1 | 구독 중 검색어/카테고리 변경에 따른 결과·빈 상태 |
| MockClubRepositoryTest | 1 | 샘플 ID 중복 없음·모든 홍보글의 동아리 참조 유효 |

ViewModel은 fake Repository와 테스트 Main dispatcher로 검증한다. Firebase 구현체를 대체해도 동일한 계약 테스트가 가능하도록 생성자 주입을 사용한다.

## 수동 Mock 확인 (기기/Studio 준비 후)

- [ ] 첫 화면에 GRU · 그루 및 미연동 안내, 로그인 버튼 비활성 확인
- [ ] Mock 둘러보기 → 홈 홍보 카드 2개 표시
- [ ] 카드 → 홍보 상세 → 문의/가입 → 뒤로 이동
- [ ] 탐색에 3개 동아리, `사진` 검색 및 운동 필터 조합 시 빈 결과
- [ ] 필터 해제·검색어 삭제 시 목록 복구
- [ ] 대화 탭의 미연동 안내, 개설/가입 폼의 제출 비활성 확인
- [ ] 내 정보 → 둘러보기 종료 → 뒤로 가도 이전 보호 흐름 미노출
- [ ] 작은 화면·큰 글꼴·키보드 표시 상태에서 입력/버튼 가림 확인
- [ ] 회전 후 입력·탭 상태와 스크롤 확인 (미지원 상태 복원은 Issue로 기록)

## Firebase 통합 시나리오 (Phase 4)

| ID | 시나리오 | 기대 결과 | 담당/증거 |
|---|---|---|---|
| E2E-01 | 학생 가입→로그인→재실행→로그아웃 | 인증 복원, 오류 안내, 로그아웃 후 개인 데이터 제거 | 김민성 UI·ViewModel / 고승민 Repository·DB, 상호 검증 / 링크 입력 |
| E2E-02 | 관리자 홍보 등록→학생 피드/상세 조회 | 실제 저장 반영, 타 동아리 관리자 쓰기 거절 | 김민성 UI·Repository·DB / 고승민 검증 |
| E2E-03 | 검색·카테고리 조합→상세 | 올바른 결과·빈 상태, 잘못된 ID 처리 | 김민성 UI·Repository·DB / 고승민 검증 |
| E2E-04 | 두 계정·두 실행 환경 채팅 | 중복 방 없음, 실시간 송수신·정렬·재접속·재시도 | 김민성 UI·ViewModel / 고승민 Repository·DB, 상호 검증 |
| E2E-05 | 개설 요청→본인 내역→운영자 검토 | pending→승인/반려, 타인·일반 관리자 접근 거절 | 김민성 UI·ViewModel / 고승민 Repository·DB, 상호 검증 |
| E2E-06 | 가입 신청→재실행→본인 내역 | 중복 신청 방지, 저장 확인·타인 읽기 거절 | 김민성 UI·Repository·DB / 고승민 검증 |
| E2E-07 | 네트워크 단절·복구·연속 탭·화면 이탈 | 오류/재시도, 중복 쓰기 없음, 리스너 해제 | 공동 |
| E2E-08 | 로그아웃 후 뒤로·화면 재진입 | 인증 경계 유지, 이전 사용자 메시지 미노출 | 공동 |

Firestore 권한의 허용·거절 상세 행렬은 `firestore-schema.md`를 기준으로 SEC-01 자동 테스트에 구현한다. 테스트 파일·규칙·실행 방법과 통과 로그가 없으면 검증 완료로 기록하지 않는다.

## AuthViewModel 화면 상태 검증 (2026-11-09)

- `AuthViewModelTest` 7개: 복원 대기, 로그인 성공·비밀번호 초기화, 잘못된 입력 메시지, 처리 중 중복 제출 무시, 중복 이메일 가입 실패, 프로필 불가 후 재시도, 로그아웃.
- Mock 기본 모드에서 `:app:testDebugUnitTest` 통과. 실제 Firebase 콘솔·프로세스 강제 종료·물리 기기 Navigation은 미검증이다.
- AuthScreen은 로그인/회원가입 전환·처리 중 비활성·오류 문구를 표시한다. ProfileScreen은 역할 표시와 로그아웃을 제공한다.

## 인증 계약 fake 검증

- 테스트 전용 `FakeAuthRepository`·`FakeAuthStore` 추가. 앱 실행 경로에는 연결하지 않음.
- `AuthRepositoryContractTest` 7개: 복원 완료 전 null 발행 방지, 가입 프로필 저장 및 로그아웃, 중복 이메일/잘못된 암호, 저장소를 공유한 세션 복원과 기존 역할 보존, 프로필 실패와 재로그인 복구, 복원 시 프로필 누락 오류, 입력 실패와 취소 전파.
- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` 통과. 전체 단위 테스트 20개, 실패 0개.
- 이 결과는 메모리 fake 검증이다. 실제 Firebase 인증·프로세스 재시작·Firestore Rules 권한은 미검증이다.

## users Rules 검증

- 실행: Node 24.7.0, Java 25, Firebase CLI 15.34.2, Firestore Emulator 1.22.0에서 `npm run test:rules`.
- 결과: 8개 통과, 실패 0개. `git diff --check` 통과.
- users 접근 규칙과 8개 Emulator 테스트를 구현했다. 정상 생성·조회·이름 수정, 비로그인·타인 접근, 목록 조회, 역할 승격·필드 추가·시각 위조·필수 필드 삭제, 타입·길이·공백 오류, 미정의 경로를 검사한다.
- `demo-mope` 로컬 프로젝트에서 실행한다. 실제 Firebase 프로젝트 배포·실제 인증 SDK 연결·다른 컬렉션 권한은 검증 범위 밖이다.
- Android 소스와 의존성은 이번 작업에서 변경하지 않았다. 직전 Android 테스트 20개·빌드·Lint 통과 기록은 유지하며 이번에는 Rules 테스트만 실행한다.

## Firebase 인증 Repository 통합 검증 (2026-11-07)

- Firebase BoM 34.14.0, Google Services 플러그인 4.4.4, Auth·Firestore 기본 모듈 및 coroutines Task await를 추가했다.
- Mock·Emulator·실제 Firebase 선택을 구현했다. 실제 Firebase 모드에 설정 파일이 없으면 구성 단계에서 명확히 실패하는 것을 확인했다.
- `JAVA_HOME`을 Android Studio 내장 JDK로 지정하고 `npm run test:auth:android` 실행: Pixel_4 Android 17/API 37 에뮬레이터에서 5개 통과, 실패 0개.
- 검증 흐름: 가입 → users 프로필 서버 저장 → Repository 재생성 시 세션 조회 → 로그아웃 → 재로그인. 기존 프로필 생성 시각 보존도 확인했다.
- 실패 흐름: 중복 이메일·잘못된 암호 매핑, 미인증 Firestore 클라이언트의 실제 Rules 거절 → PROFILE_UNAVAILABLE → 자격 복구 후 같은 계정 재로그인으로 누락 프로필 생성.
- 구독 중 가입 시 저장 완료 사용자만 전달하는지와 `MopeApplication.authRepository`의 실제 Emulator 연결도 검증했다.
- 실제 Firebase 프로젝트 설정·배포, 인증 화면/ViewModel 연결, 물리 기기, 프로세스 강제 종료 후 영속 세션 복원은 아직 검증하지 않았다. Repository 재생성을 프로세스 재실행으로 간주하지 않는다.
- 최종 회귀: 기본 Mock 모드 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 통과(단위 테스트 20개, 실패 0개), `npm run test:rules` 8개 통과, `git diff --check` 통과.

## 인증 프로세스 재실행 검증 (2026-11-09)

- `npm run test:auth:restart`: Android Pixel_4/API 37에서 seed → restored → signOut → loggedOut 네 단계 통과. 각 단계 사이 앱·테스트 앱을 force-stop하고 PID 변경을 검사했다.
- 최초 실행에서 signOut 직후 종료하면 이전 사용자가 복원되는 결함을 재현했다. 앱 소유 로그아웃 표시를 동기 영속화하고 세션 복원보다 우선하도록 수정한 뒤 네 단계를 통과했다.
- 테스트는 Firebase Emulator 서버를 유지한 실제 Android SDK·앱 프로세스 재시작 검증이다. 물리 기기·OS 재부팅·실제 Firebase 프로젝트는 범위 밖이다.
- 재현 코드: `AuthProcessRestartTest.kt`, `scripts/test-auth-restart.mjs`. 기본 통합 테스트 실행에서는 sessionStage 인자가 없어 이 단계 테스트가 skip된다.
- 회귀: 기존 Android 인증 통합 테스트 5개 통과(별도 프로세스 단계용 테스트 1개는 의도적 skip), 기본 Mock APK 빌드·단위 테스트 20개·Lint 통과.

## 채팅 DB 권한 검증 (2026-11-09)

- `firestore.rules`에 chatRooms/messages 필드·참조·참여자·서버 시각 검증을 구현했다. 읽기는 방 참여자만, 메시지는 본인 senderId만 작성할 수 있다. 방·메시지의 수정·삭제는 거절한다.
- `npm run test:rules`: users 8개 + 채팅 11개 = 19개 통과, 실패 0개.
- 참여자/발신자/생성 시각 위조, 없는 동아리·사용자, 자기 자신과 채팅, 본문 길이·타입·공백, 목록 쿼리 조건, 동시 transaction에서 같은 방 생성·재사용을 검증했다.
- 관리자 변경 시 새 관리자의 과거 대화 접근 거절, 여러 줄 메시지 허용, 기존 메시지 ID 재조회 허용과 덮어쓰기 거절도 확인했다.
- clubs 문서는 테스트 fixture로만 Rules를 우회해 준비했다. 채팅 요청 자체는 Rules를 우회하지 않는다. clubs 클라이언트 권한·ChatRepository·실시간 채팅 UI·운영 Firebase 배포는 미구현/미수행이다.

## 실시간 채팅 Repository 통합 검증 (2026-11-11)

- `FirestoreChatRepository`와 `ChatFailure`를 구현하고 앱에 인증·채팅이 공유하는 Firebase backend를 연결했다. Mock 화면은 유지하며 UI를 실제 채팅에 연결하지 않았다.
- `npm run test:chat:android`: Android Pixel_4/API 37에서 채팅 7개 테스트 통과.
- `npm run test:auth:android`: 최종 인증 5개 + 채팅 7개 = 12개 통과, 실패 0개. 별도 프로세스 재실행 단계 테스트 1개는 의도적 skip.
- 검증 흐름: 생성 전 목록 구독 → 중복 없는 방 생성·목록 반영 → 학생/관리자 양방향 실시간 수신 → 재구독 후 저장 메시지 확인. 동시 생성, 같은 메시지 ID 동시 재전송, 다른 내용/발신자의 ID 충돌도 확인했다.
- 같은 서버 시각의 55개 메시지 중 문서 ID 기준 최신 50개가 정렬되어 반환되는지 확인했다. 비참여자 읽기/쓰기 거절, 빈/긴 본문·잘못된 ID, 자기 자신 문의·없는 동아리, 구독 취소·로그아웃·다른 사용자 로그인도 검사했다.
- FirebaseBackend의 Auth/Firestore 공유와 명시적 로그아웃 guard를 앱 경로로 확인했다. `npm run test:auth:restart`의 실제 프로세스 재실행 네 단계도 통과했다.
- 테스트의 동아리 fixture만 로컬 Emulator의 owner 권한으로 주입했다. 방 생성·전송·구독은 실제 SDK와 Rules를 통과한다. 생성에 필요한 로그인 사용자의 clubs 단건 조회만 최소 추가했고 목록·쓰기 권한은 열지 않았다.
- 실제 Firebase 프로젝트·물리 기기·채팅 UI·오래된 메시지 페이지·오프라인 전송 큐는 검증/구현 범위 밖이다. 취소 시 리스너 제거는 callbackFlow의 awaitClose에서 수행한다.
- 최종 회귀: `npm run test:rules` users·채팅·동아리 단건 권한 20개 통과. 기본 Mock 모드 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 통과(단위 테스트 20개, 실패 0개), `git diff --check` 통과.
- Push 전 원격 `feature/auth-auth-01` 병합 변경을 확인하고 인증 UI·AuthViewModel·Navigation 커밋들을 보존해 통합했다. 병합 후 기본 Mock APK 빌드·Lint·단위 테스트 27개 통과. 채팅 UI는 연결하지 않았다.
- 병합 후 Android 인증·채팅 통합 테스트도 12개 통과, 실패 0개(프로세스 단계 테스트 1개 의도적 skip).

## 채팅 UI 연결 준비 (2026-11-11)

- ChatListViewModel·ChatRoomViewModel·ChatUiState 및 상태/콜백만 전달하는 Compose Route 어댑터를 추가했다. 기존 화면 디자인·Navigation 연결은 유지한다.
- 새 ViewModel 테스트 13개 통과. 전체 JVM 단위 테스트 40개, 실패 0개. Mock APK 빌드·Lint 통과.
- 중복 제출 방지, 같은 ID/본문 재시도, 새 입력 보존, 서버 수신과 로컬 전송 행 중복 제거, 오류 후 재구독, SavedStateHandle 스냅샷 복원과 명시적 재시도, 계정/대상 변경·권한 상실 시 상태 삭제, 화면 ViewModel 해제 시 구독·전송 취소를 검증했다.
- UI가 보던 사용자와 실제 Firebase 세션이 다르면 전송을 차단하는 expectedUserId 검사를 추가했다.
- Android 인증·채팅 통합 테스트 13개 통과, 실패 0개(별도 프로세스 단계 테스트 1개 의도적 skip). 실제 Repository → ViewModel → 상태의 학생 전송·관리자 답변·로그아웃 흐름을 포함한다.
- SavedStateHandle 검증은 스냅샷으로 ViewModel을 재생성한 테스트다. 새 채팅 UI의 실제 프로세스 종료/복원 및 시각 검증은 아직 수행하지 않았다.
- `npm run test:chat:demo` 1개 통과: 학생·관리자 데모 계정과 photo 동아리·방·예시 메시지 두 개를 준비하고 두 번째 실행에서도 동일 ID·생성 시각·메시지 수가 유지되는 것을 확인했다. 이 스크립트는 localhost의 demo-mope 전용이며 기존 사진부 Mock ID와 일치한다.
- 화면 인계 문서 `docs/chat-ui-handoff.md`에 Route content 연결, 상태/버튼 매핑, Mock 분기, SavedState 범위와 데모 실행 절차를 기록했다. 시각 UI와 Navigation 연결은 미완료다.

## 비밀번호 재설정 백엔드 (2026-11-11)

- 전용 브랜치: feature/auth-password-reset. requestPasswordReset(email) 계약·이메일 검증·Firebase 요청 구현과 미가입 주소의 중립 결과 처리를 추가했다. UI 소스는 수정하지 않았다.
- Mock APK 빌드·Lint·단위 테스트 43개 통과. 형식 오류·네트워크 실패·취소·기존 세션 보존을 검사했다.
- Android 인증/채팅 15개 통과, 실패 0개(프로세스 단계 테스트 1개 의도적 skip). 추가한 2개는 요청 후 세션 보존/미가입 처리 및 Emulator 재설정 코드 적용 후 기존 암호 거절·새 암호 로그인·동일 프로필 확인이다.
- 실제 Firebase 프로젝트 메일 발송·템플릿과 UI 버튼 연결은 검증 범위 밖이다.

## 2026-11-11 문의 공개 정보

feature/chat-public-context에서 Rules 23개 통과, Android SDK 통합 19개 통과·프로세스 재시작 단계 1개 건너뜀(XML tests=20, failures=0, errors=0). Mock 단위 43개·빌드·lint 통과. 프로덕션 Screen/ViewModel/Route는 수정하지 않았다. 공개 연락처와 모집 제목을 로컬 데모 fixture에 추가했다.

## 2026-11-13 개설 요청 백엔드

`feature/club-request-backend`에서 다음을 검증했다. 운영 Firebase에 배포하지 않았다.

- `npm run test:rules`: 총 29개 통과(개설 요청 6개 추가). 본인 생성/목록, 운영 권한 claim, 위조·필드 경계, 상태 불변성·검토 전이·삭제 금지.
- `npm run test:auth:android`: XML tests=25, failures=0, errors=0, skipped=1 → 실제 통과 24개. 기존 인증 7개·채팅 12개와 신규 개설 요청 5개. 프로세스 재시작 단계는 별도 실행용이므로 일반 실행에서 건너뛴다.
- 신규 Android 검증: 중복 제출 transaction·처리 후 제출 재시도, 실시간 본인/대기 목록, 승인 결과와 원본 시각 보존, 동시 승인/반려의 단일 승자, 비운영자 거절, 입력/계정 경계, 로그아웃 구독 종료·취소 후 재접속, 손상 문서 거절, 앱 공통 인증 주입.
- 동시 검토 테스트에서 늦은 transaction이 Rules의 pending 조건 때문에 PERMISSION_DENIED를 받는 현상을 재현했다. 운영 권한과 서버의 처리 완료 상태를 재확인해 CONFLICT로 구분한 뒤 전체 Android 회귀를 통과했다. 권한 규칙은 완화하지 않았다.
- Mock 단위 43개·assembleDebug·lintDebug 모두 통과했다.

테스트의 operator claim은 localhost Auth Emulator의 owner fixture로만 부여한다. 제품 코드에 운영자 권한 부여 기능은 없다. 복합 인덱스 설정은 저장소에 추적하지만 Emulator 테스트만으로 실제 프로젝트의 인덱스 배포를 검증하지는 않는다. UI·ViewModel·Navigation은 수정하지 않았다.

## 우리 담당 최종 인계 검증 (2026-11-13)

- `npm run verify:backend` 전체 순서 통과: 설정 점검 도구 3개 → Mock 단위 43개·APK·Lint → Rules 29개 → 데모 1개 → Android 통합 26개(프로세스 단계 1개 의도적 skip) → 실제 프로세스 재시작 네 단계.
- 실제 Android Emulator Wi-Fi·모바일 데이터를 끄고 activeNetwork=null을 확인한 뒤 채팅 전송·공개 정보 조회·개설 요청이 NETWORK 오류를 전달하는지 검증했다. 원래 연결을 복원한 뒤 같은 ID로 두 번 재시도해 저장이 한 건인지 확인했다. 테스트 취소/실패 시에도 NonCancellable finally에서 연결 설정을 복원한다. 호스트 컴퓨터의 네트워크는 변경하지 않는다.
- SDK disableNetwork()만으로는 이 환경의 transaction 통신이 차단되지 않는 것을 확인했다. 해당 방식의 실패 결과를 완료 근거로 사용하지 않고 실제 Emulator 연결 차단으로 바꿔 통과했다.
- Firebase 설정 점검 도구는 대상 프로젝트·앱 ID/패키지 일치·필수 설정을 검사한다. 설정이 없으면 명확히 실패하며 키/JSON 원문을 출력하지 않는다. 실제 계정·메일·Rules/인덱스 배포는 검사하지 않는다.
- CI는 Backend checks workflow의 원격 실행 결과를 별도로 확인한다. 로컬 검증 완료를 원격 CI 통과로 대신하지 않는다. 운영 연결·프론트 연결·상대 리뷰의 미완료 항목은 backend-ready.md에 기록했다.

최종 원격 CI: [Backend checks #43413453571](https://github.com/devrhtmdals04/MobileProgramming/actions/runs/43413453571) 성공(33cd7f4). 새 Ubuntu 환경에서 Mock APK·단위/Lint·설정 도구·Rules·데모 반복 생성 검증을 통과했다. 첫 실행에서 폐기된 SDK tools 패키지 설치 요청을 수정했고, 다음 새 컴파일에서 AuthViewModelTest의 익명 인증 대역에 재설정 메서드가 누락된 것을 찾아 수정했다. 로컬에서도 `-Pkotlin.incremental=false --rerun-tasks`로 모든 55개 작업을 다시 실행해 단위 43개·APK·Lint 통과를 확인했다. 기존 증분 빌드 결과만으로 새 환경의 컴파일 성공을 추정하지 않는다.
