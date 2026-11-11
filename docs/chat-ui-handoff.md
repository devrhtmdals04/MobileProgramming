# 그루 채팅 UI 연결 안내

현재 준비된 범위는 Repository → ViewModel → 상태·이벤트 → Compose Route다.
실제 말풍선·목록 디자인과 Navigation의 화면 교체는 아직 하지 않았다.
화면 담당자는 아래 Route의 content에 화면을 넣고 상태와 콜백만 연결하면 된다.

## 제공 코드

| 파일 | 역할 |
|---|---|
| `ChatRoutes.kt` | ViewModel 생성·저장 상태·생명주기 기반 StateFlow 수집 |
| `ChatListViewModel.kt` | 로그인 사용자별 방 목록·빈 목록·오류·재구독 |
| `ChatRoomViewModel.kt` | 방 연결·전송·재시도·입력·계정별 전송 대기 |
| `ChatUiState.kt` | 렌더링 상태·합쳐진 timeline·사용자용 오류 |
| `FirestoreChatRepository.kt` | 실제 SDK 및 Rules 접근, 같은 ID 재전송 |

파일은 `app/src/main/java/com/mope/app/ui/screens/chat/`에 있다. Repository 구현만
`data/remote/chat/`에 둔다. 화면에서 SDK 호출·리스너 생성·전송 ID 생성을 다시 구현하지 않는다.

## 주입과 진입 경로

1. `MainActivity`가 보유한 `MopeApplication.authRepository`, `chatRepository`를 Navigation에 전달한다.
2. 실제 로그인한 분기에서 두 Repository가 모두 있을 때만 Route를 호출한다. Mock 둘러보기에서는 기존 안내 화면을 유지한다.
3. 각 composable의 NavBackStackEntry 내부에서 Route를 호출한다. Activity 전체에 하나의 ChatRoomViewModel을 공유하지 않는다.
4. 동아리 상세의 문의는 `ChatTarget.Club(clubId)`, 대화 목록의 기존 방 선택은 `ChatTarget.Room(roomId)`다.

기존 `chat/{clubId}`는 **동아리 ID**를 받는다. 이 경로에 room.id를 넣지 않는다.
기존 방 선택용으로 `chat-room/{roomId}` 같은 별도 경로를 추가하고 인자는 Uri.encode로 전달한다.

```kotlin
// NavBackStackEntry 내부. 기존 디자인을 content에 넣는다.
ChatListRoute(authRepository, chatRepository) { state, onRetry ->
    // 목록: state.rooms / state.isLoading / state.isEmpty / state.error
    // 항목 선택: room.id를 기존 방 경로로 전달
    // 오류 재시도: onRetry()
}

ChatRoomRoute(
    target = ChatTarget.Club(clubId), // 기존 방은 ChatTarget.Room(roomId)
    authRepository = authRepository,
    chatRepository = chatRepository,
) { state, actions ->
    // 기존 채팅 디자인에 아래 표의 상태·콜백을 연결
}
```

이 예제의 content는 의도적으로 비어 있다. 현재 `ChatScreen`/`ChatListScreen`은
안내용이며 해당 화면을 stateless content로 바꾸는 작업이 남아 있다.
Route가 `createSavedStateHandle()`을 사용하므로 화면에서 `ChatRoomViewModel(...)`을
직접 생성하거나 별도의 기본 SavedStateHandle을 만들어 넘기지 않는다.

## 대화 화면 연결표

| UI | 상태 | 이벤트 |
|---|---|---|
| 로딩·연결 중 | connection == RESTORING 또는 CONNECTING | 없음 |
| 말풍선 목록 | state.timeline, item.id를 key로 사용 | 없음 |
| 내/상대 말풍선 | item.isMine | 없음 |
| 입력란 | state.draft, 사용자 확인 후 활성화 | actions.onDraftChange(text) |
| 전송 버튼·IME 전송 | state.canSend | actions.onSend() |
| 전송 중 | item.delivery == SENDING | 중복 전송 비활성 |
| 실패 행·오류 | item.delivery == FAILED, item.error?.message | actions.onRetryMessage(item.id) |
| 실패 행 제거 | 전송 중이 아닌 행만 | actions.onDismissMessage(item.id) |
| 연결 오류·재시도 | state.error?.message | actions.onRetryConnection() |
| 로그인 필요 | state.error?.requiresSignIn == true | 사용자 확인 후 기존 AuthViewModel.signOut() 흐름으로 인증 화면 복귀 |

실패 재시도는 connection == READY이고 state.isSending이 아닐 때 누를 수 있다.
입력은 최대 2000자이며 초과 입력은 받지 않고 inputError로 안내한다.
입력 오류는 state.inputError, 개별 전송 오류는 item.error로 따로 표시한다.
연결 오류 때문에 새 메시지 ID를 만들거나 입력란 본문으로 실패 메시지를 덮어쓰지 않는다.
이미 새 문장을 입력했다면 이전 메시지 재시도 후에도 그 문장을 유지한다.

timeline에는 서버 수신과 로컬 전송 대기가 ID로 합쳐져 있다. raw messages와 pending을
화면에서 다시 합치지 않는다. SENT는 서버 저장 완료이고, 서버 echo를 기다리는 동안
createdAtMillis가 null일 수 있다. 시각을 임의로 생성하지 않고 잠시 생략한다.

로그아웃·계정 변경·권한 상실 시 ViewModel이 메시지·입력·전송 대기와 저장 상태를
지운다. 화면에서 이전 목록을 별도 remember로 보관하지 않는다. 인증 오류만 보고
새 계정을 자동 로그아웃시키지 말고 기존 인증 경계와 사용자 확인 동작을 따른다.

## 재시도·저장 상태의 범위

- 첫 전송에서 만든 ID와 원문을 ViewModel이 보관한다. 재시도는 같은 ID·원문·방으로 수행한다.
- ViewModel은 입력 당시 userId를 expectedUserId로 전달한다. 인증 변경 알림보다 전송 이벤트가 먼저 처리돼도 다른 세션으로 전송하지 않는다.
- 화면 재생성용 SavedStateHandle에는 소유자·대상·입력·미완료 전송을 저장한다. 복원된 전송은 FAILED가 되고 사용자가 재시도해야 한다. 자동으로 다시 보내지 않는다.
- SavedStateHandle은 Android가 저장한 task 상태의 복원용이다. 강제 종료·task 제거까지 유지하는 영구 outbox나 DB 큐는 아니다. 이 기능은 JVM 스냅샷 재생성으로 검증했고 실제 채팅 화면의 시스템 종료/복원은 화면 연결 후 검증한다.
- ViewModelStore가 해제되면 구독·전송을 취소한다. ViewModel이 back stack에 남아 있는 동안은 구독이 유지될 수 있다.

목록 DTO는 방 ID·clubId·participantIds를 제공한다. 동아리 이름은 별도 동아리 데이터로
표시하며 최근 메시지 미리보기·안 읽은 수·이전 50개 페이지는 아직 제공하지 않는다.

## 로컬 실행 데이터

Android 에뮬레이터 한 대를 완전히 부팅하고 JDK 21 이상·Node.js 22 이상을 사용한다.
macOS 예시는 다음과 같다.

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
npm ci
npm run dev:firebase
```

다른 터미널에서 같은 JDK를 설정하고 실행한다.

```sh
npm run seed:chat:demo
./gradlew -PmopeBackend=emulator :app:installDebug
```

| 구분 | 로컬 데모 값 |
|---|---|
| 학생 | student@gru.example |
| 관리자 | admin@gru.example |
| 공통 비밀번호 | gru-demo-1234 |
| 동아리 | photo / 찰칵 사진부 |
| 방 ID | seed 출력의 roomId |

이 값은 localhost의 demo-mope 전용 테스트 계정이다. seed는 사진부·두 프로필·방과
예시 메시지 두 개를 준비하고 다시 실행해도 같은 방과 메시지를 재사용한다.
사진부 관리자·역할 설정만 테스트용 owner 컨텍스트를 사용한다. 방·메시지는 정상
사용자 SDK로 쓰며 실제 Rules를 적용한다. 스크립트는 운영 주소·키를 입력받지 않는다.

photo는 현재 Mock 피드의 사진부 ID와 일치한다. 다른 Mock 동아리는 별도 데이터를
준비하지 않으면 NOT_FOUND다. 현재 앱은 로그인까지 동작하며 채팅 화면은 아직 안내용이다.
UI를 연결한 뒤 사진부 문의·기존 방 선택 경로로 확인한다.

## 연결 후 확인할 흐름

1. 학생 로그인 → 사진부 문의 → 기존 데모 대화 표시.
2. 메시지 전송 → 한 행만 표시, 관리자 로그인 환경에서 수신.
3. 오류 후 개별 메시지 재시도 → 같은 행·ID 사용, 새 입력 유지.
4. 뒤로/재진입 → 구독 중복 없음, 저장 메시지 표시.
5. 로그아웃·다른 계정 → 이전 사용자의 대화·입력 미표시.
6. 실제 화면에서 키보드·스크롤·작은 화면·큰 글꼴 및 시스템 저장 상태 복원 확인.

검증 명령은 `./gradlew :app:testDebugUnitTest :app:lintDebug`, `npm run test:auth:android`,
`npm run test:chat:demo`다. 세부 결과는 [테스트 기록](testing.md)을 따른다.
SavedState 생성·복원 범위는 [ViewModel Factory](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-factories),
[Saved State 공식 문서](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-savedstate)를 참고한다.
