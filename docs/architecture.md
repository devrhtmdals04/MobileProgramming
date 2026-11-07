# 아키텍처

## Figma 디자인과 ViewModel

기존 Figma 작업물은 전체 UI 디자인의 기준이며 김민성이 Compose로 구현한다. ViewModel은 화면 상태·사용자 이벤트·Repository 호출을 처리하는 Kotlin 코드이므로 디자인과 별도로 작성한다. 두 작업 모두 김민성의 프론트엔드 범위다. 원본 접근·비교는 아직 미완료다.

## 현재 구조

단일 `:app` 모듈의 MVVM + Repository다. Hilt, Retrofit, 별도 서버, 다중 모듈은 도입하지 않았다. 패키지는 `com.mope.app`, 앱 ID도 동일하다. `MopeApplication`이 `ClubRepository`를 한 번 생성하며 현재 구현체는 `MockClubRepository`다. Activity에서 Navigation으로 전달하고 Navigation의 `viewModelFactory`가 화면별 ViewModel에 주입한다. 테스트는 생성자에 fake를 전달한다.

```text
사용자 이벤트 → Screen 콜백 → ViewModel → Repository 인터페이스
                                    ← Flow / suspend 결과
Screen ← collectAsStateWithLifecycle ← StateFlow<LoadState<T>>
```

| 계층 | 책임 | 금지/경계 |
|---|---|---|
| MainActivity / MopeApplication | Compose 시작, 앱 범위 의존성 생성 | 화면별 데이터 가공 금지 |
| navigation | 경로, ID 인자, ViewModel 생성·생명주기 | Firebase SDK 직접 접근 금지 |
| Screen | UI 상태 렌더링, 사용자 이벤트 전달 | Repository 생성·서버 요청 금지 |
| ViewModel | 코루틴·로딩·오류·검색 상태, 구독 소유 | Activity/Composable/Context 보관 금지 |
| data/repository | Flow/suspend 계약 | UI나 Firebase 타입 노출 금지 |
| data/mock | 네트워크 없는 샘플 데이터 | 실제 인증·저장 성공으로 가장 금지 |
| data/remote | 추후 Firebase SDK, DTO, 리스너, 도메인 변환 | Screen에 SDK 객체 반환 금지 |
| data/model | UI와 저장소가 공유하는 Kotlin 모델 | Firestore annotation·DocumentSnapshot 의존 금지 |
| domain | 현재 순수 검색 함수만 존재 | 불필요한 UseCase 계층·중복 추상화 금지 |
| ui/components, theme | 재사용 컴포넌트·상태·색상·타이포 | 기능별 비즈니스 로직 금지 |

## 구현 수준

- HomeViewModel: 홍보 목록 Flow → Loading/Empty/Error/Content, 재시도 시 기존 Job 취소.
- SearchViewModel: 목록·검색어·카테고리 Flow 결합. `filterClubs`는 공백 제거·이름/소개 포함 검색·카테고리 AND 조건을 수행한다. UI는 생명주기에 맞춰 수집한다.
- ClubViewModel: route ID로 홍보/동아리 상세를 찾는다. 없는 ID는 Empty다. 상세 오류 시 뒤로 간 뒤 다시 여는 방식이다. 세밀한 상세 재시도 UX는 김민성의 후속 작업이다.
- Auth/Chat/Request/Profile: UI 골격이다. AuthViewModel·ChatViewModel·RequestViewModel은 아직 없다. 로컬 입력 상태만 Compose에서 관리하고 실제 인증·저장·송신은 비활성이다. 해당 기능 착수 시 로직을 ViewModel로 옮긴다.
- Mock 데이터는 메모리의 읽기 전용 샘플이다. 영속 저장, 사용자 인증, 원격 변경 동기화는 없다.
- 기본 UI는 작은 샘플 목록을 스크롤 Column으로 표시한다. 실제 데이터 연결 시 김민성이 LazyColumn과 목록 규모·스크롤 복원 정책을 검토한다.

## Repository 계약 v1 초안

`data/repository/`의 Kotlin 인터페이스가 기준이다. SET-03에서 공동 확정한다.

| 계약 | 현재 메서드 | 연결 책임 |
|---|---|---|
| ClubRepository | observeClubs, observePromotions | 김민성 UI·ViewModel·원격 구현 |
| AuthRepository | currentUser, signIn, signUp, signOut | 고승민 Repository / 김민성 UI·ViewModel |
| ChatRepository | observeMyRooms, getOrCreateRoom, observeMessages, sendMessage | 고승민 Repository / 김민성 UI·ViewModel |
| RequestRepository | submitClubRequest, observeMyRequests, observePendingRequests, reviewRequest, applyToClub, observeMyApplications | 고승민 개설 Repository / 김민성 가입 Repository 및 전체 UI·ViewModel |

홍보글 등록은 `createPromotion(clubId, title, body): String` 계약 추가가 필요하다. 회원가입·인증 결과로 사용자 uid를 직접 전달받아 임의 생성하지 않는다. 구현체가 현재 인증 사용자 ID를 읽는다. `UserRole`은 화면용 STUDENT/CLUB_ADMIN 표시이며 실제 쓰기 권한은 `clubs.adminId`와 Rules로 확인한다. 운영자 판단은 신뢰된 custom claim을 사용하는 별도 capability로 Phase 2에 추가한다. 현재 모델에 운영자 필드를 조급하게 노출하지 않았다.

공통 규칙:

- `Flow<List<T>>`의 빈 목록은 정상 결과다. 구독 실패는 예외로 전달하고 ViewModel에서 재시도 가능한 오류로 매핑한다.
- suspend 쓰기는 저장 완료 후 반환하며 오류는 예외로 전달한다. UI는 실패를 성공으로 바꾸지 않는다. 취소(`CancellationException`)는 다시 전파한다.
- Auth 실패 세부 매핑(잘못된 입력·인증 실패·네트워크·권한)을 고승민이 정의하고 김민성과 사용자 메시지를 합의한다. 현재 공용 예외 계층은 없다.
- ID는 문자열, Firestore 문서 ID를 Kotlin 모델의 id에 매핑한다. route 삽입 시 Uri.encode를 사용한다.
- 시간은 Firestore Timestamp를 remote 계층에서 millis로 변환한다. 로컬 pending serverTimestamp는 null일 수 있다. `Message.createdAtMillis`는 nullable이며 UI는 전송 중을 표시해야 한다.
- 변경 계약은 공급자·소비자·fake·문서를 함께 수정한다. 함수명만 맞고 필드 의미가 다른 상태로 병합하지 않는다.

## 상태와 수명주기

UI는 `collectAsStateWithLifecycle`로 구독한다. ViewModel은 공개 읽기 전용 StateFlow를 제공하고 변이 Flow는 private으로 둔다. `viewModelScope`에서 작업하고, 여러 입력이 결합되는 상태는 `stateIn`과 WhileSubscribed를 사용한다. 원격 리스너는 `callbackFlow`와 `awaitClose { registration.remove() }`로 정리할 예정이다. 로그아웃 시 채팅·신청 구독과 개인 상태를 폐기하고 보호 화면 백스택을 비운다.

현재 홈·탐색·상세는 상태 UI를 사용한다. 정적 골격까지 가짜 로딩·가짜 서버 오류를 만들지 않았다. 인증 복원 중에는 보호 화면을 잠깐 표시하지 않도록 별도 Loading 상태를 AUTH-02에서 구현한다. 검색 query의 프로세스 종료 복원과 실제 인증 Navigation은 아직 구현되지 않았다.

## Firebase 도입 절차

Firebase Auth·Firestore SDK와 users Rules를 추가했다. 기본값은 Mock이며 JSON을 놓는 것만으로 Firebase가 활성화되지 않는다. 현재 화면은 Mock 흐름이며 인증 Repository의 UI 연결은 김민성 담당 후속 작업이다.

1. 팀이 사용할 Firebase 프로젝트를 만들거나 선택하고 두 팀원의 적절한 콘솔 권한을 부여한다. 프로젝트 ID·담당자는 문서에 기록하되 서비스 계정 비밀은 기록하지 않는다.
2. Android 앱 `com.mope.app`을 등록한다. Firebase에서 발급받은 `google-services.json`을 로컬 `app/`에 둔다. 파일·키를 직접 만들어 넣지 않는다.
3. 이메일/비밀번호 Authentication을 활성화한다. Firestore 데이터베이스를 만들고 팀의 지역을 결정한다. 광범위한 허용 규칙을 배포하지 않는다.
4. 버전 카탈로그에 Firebase BoM 34.14.0, `firebase-auth`, `firebase-firestore`, `kotlinx-coroutines-play-services`, Google Services 플러그인 4.4.4를 고정했다. 기존 minSdk 24를 유지하며 분리된 `-ktx` 모듈은 사용하지 않는다.
5. 기본 `-PmopeBackend=mock`은 설정 파일 없이 실행한다. `emulator`는 debug 전용 `demo-mope` 설정으로 Auth 9099·Firestore 8080에 연결하고, `firebase`는 실제 `app/google-services.json`이 없으면 Gradle 구성 단계에서 실패한다. Google Services 플러그인은 firebase 모드에서만 적용한다.
6. 인터넷 권한과 `FirebaseAuthRepository`를 추가했다. `MopeApplication.authRepository`는 Mock에서 null, 나머지 모드에서는 실제 Repository를 지연 생성한다. 동아리 Repository와 화면 이동은 여전히 Mock이다. 화면은 null을 인증 성공으로 취급하지 않아야 한다.
7. 별도 Emulator 환경에서 Auth·Firestore·Rules를 테스트한다. 운영 계정/데이터로 개발 테스트를 하지 않는다. 필요 인덱스와 Rules를 코드로 추적하고 검증 후 팀 담당자가 배포한다.
8. 시연용 학생·동아리 관리자 계정을 준비한다. 운영 권한 claim은 신뢰된 운영 환경의 Admin SDK로만 부여한다. 앱에는 Admin SDK 비밀이나 권한 변경 기능을 넣지 않는다.
9. Cloud Storage는 IMAGE-01 합의 이후에만 의존성·권한·이미지 처리를 추가한다.

일반 회원·채팅·조회는 Firebase 클라이언트 SDK와 Rules로 구현하므로 별도 REST 서버가 필수는 아니다. 개설 승인은 운영자가 상태를 검토하는 범위이며, 실제 동아리 등록·관리자 지정은 운영자가 콘솔에서 수행하는 MVP 절차로 둔다. 자동 프로비저닝을 요구하면 그때 신뢰된 배치/서버 작업을 별도 합의한다.

## 공식 참고

- [Android AGP 호환성](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [Kotlin 2.0 이상 Compose compiler 플러그인](https://developer.android.com/jetpack/androidx/releases/compose-kotlin)
- [Firebase Android 설정](https://firebase.google.com/docs/android/setup)
- [Firestore 조건 기반 Rules](https://firebase.google.com/docs/firestore/security/rules-conditions)
- [Rules와 쿼리 제약](https://firebase.google.com/docs/firestore/security/rules-query)
- [Rules Emulator 테스트](https://firebase.google.com/docs/firestore/security/test-rules-emulator)

## 계층별 담당 순서

1. 프론트엔드: 김민성이 모든 Screen·ViewModel·Navigation·공통 UI와 화면 테스트를 담당한다.
2. 백엔드: 고승민은 인증·채팅·개설 Repository, 김민성은 동아리·홍보·검색·가입 Repository/처리 로직을 담당한다. 별도 서버는 도입하지 않는다.
3. DB: 각 Repository 담당자가 해당 컬렉션의 DTO·스키마·쿼리·인덱스·Rules 테스트를 작성하고 상대가 리뷰한다. 세부 컬렉션 소유권은 firestore-schema.md를 따른다.

RequestRepository의 개설/가입 공유 계약은 유지하되 구현 클래스의 파일 분리·조립 방식은 SET-03에서 먼저 합의한다. 현재 코드는 이 담당 변경으로 수정하지 않았다.

## AUTH-01 인증 기반 구현

인증 입력 검증은 `domain/AuthInput.kt`, SDK와 무관한 오류는
`data/repository/AuthFailure.kt`에 정의한다. 이메일·표시 이름의 앞뒤 공백은
제거하고 비밀번호 원문은 유지한다. 가입 시 이름은 trim 후 1~30 UTF-16
코드 단위, 비밀번호는 6자 이상이며 공백만으로 구성할 수 없다. 로그인은
기존 계정 정책과의 충돌을 피하기 위해 비밀번호 길이 대신 비어 있는지만 검사한다.
이는 앱의 초기 입력 정책이며 서버 검증을 대체하지 않는다.

`AuthRepository.currentUser`는 복원 중 최초 값을 보류하고, 복원 완료 후
미인증일 때 null을 발행한다. 인증 계정이 있지만 프로필을 읽을 수 없으면
PROFILE_UNAVAILABLE 오류로 구분한다. UI는 최초 발행 전 로딩 상태를 유지하고
오류 시 재구독한다. 가입 프로필 저장 실패 후 다음 로그인에서 누락 프로필
생성을 재시도하며, 기존 프로필의 역할·생성 시각은 덮어쓰지 않는다.

현재 입력 검증·오류 타입·계약 문서와 입력 경계 테스트를 구현했다.
테스트 소스의 `FakeAuthRepository`와 `FakeAuthStore`로 복원 대기·가입·로그인·
로그아웃·프로필 저장 실패·재로그인 복구·취소 전파를 검증한다. fake는 앱에
주입하지 않는다. 새 Repository에 같은 메모리 저장소를 전달하는 복원 테스트는
실제 프로세스 재시작이나 Firebase 영속 세션 검증을 대신하지 않는다.
복원 완료는 `completeRestoration()`으로 테스트에서 명시적으로 제어한다.
users Rules와 Emulator 테스트는 `firestore.rules` 및 `tests/firestore/`에 구현했다.
Firebase 인증 구현은 `data/remote/auth/`에 추가했다. 나머지 컬렉션 규칙과 인증 UI 연결은 후속 작업이다.
AUTH-01, AUTH-02, SET-03은 아직 DONE이 아니다.

## 인증 원격 구현과 통합 테스트

`FirebaseAuthRepository`는 Auth 변경 리스너를 구독하고 `awaitClose`에서 해제한다.
프로필은 서버에서 읽으며 캐시의 오래된 사용자를 복원 성공으로 취급하지 않는다.
변경 작업과 프로필 조회를 Mutex로 직렬화해 가입 중 부분 완료 사용자 발행을 막는다.
가입은 Auth 표시 이름 저장과 Firestore transaction의 프로필 생성이 완료된 후 반환한다.
로그인은 누락 프로필만 student로 생성하며 기존 이름·역할·생성 시각을 덮어쓰지 않는다.
프로필 실패는 PROFILE_UNAVAILABLE로 전달하고 원인을 cause에 보존한다.
Auth 표시 이름 저장도 실패했다면 복구 이름은 이메일의 @ 앞부분(최대 30자), 없으면
`학생`이다. 프로필 복구가 필요한 계정은 재가입 대신 기존 자격 증명으로 로그인한다.

`currentUser` Flow 오류는 구독을 종료하므로 UI는 복구/재시도 시 다시 구독해야 한다.
Firebase SDK 객체는 이 계층 내부에서만 사용하고 CancellationException은 변환하지 않는다.
운영 권한 claim·프로필 편집 기능은 이 작업 범위에 포함하지 않는다.

Android 에뮬레이터 한 대를 부팅한 후 실행한다. 호스트 주소 `10.0.2.2`는 Android
에뮬레이터용이다. 물리 기기 연결 설정은 별도로 필요하며 이번 검증 범위 밖이다.

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
npm ci
npm run test:auth:android
```

이 명령은 Auth·Firestore Emulator를 함께 실행하고 Android instrumentation 테스트를
실행한 뒤 종료한다. 테스트 계정은 매번 고유 이메일을 사용한다. 초기화·네트워크·로컬
저장은 실제 Android SDK를 사용하며 users 규칙을 우회하지 않는다. 복원 테스트는 같은
Auth 세션에서 Repository 재생성까지이며 실제 프로세스 종료·재실행은 별도 검증 대상이다.

공식 근거: [Android SDK 릴리스](https://firebase.google.com/support/release-notes/android),
[Auth Emulator 연결](https://firebase.google.com/docs/emulator-suite/connect_auth),
[Firestore Emulator 연결](https://firebase.google.com/docs/emulator-suite/connect_firestore).
