# 우리 담당 백엔드 완료 기준과 인계

담당 범위는 고승민의 인증·세션·프로필 조회/생성, 채팅·공개 문의 정보, 동아리 개설 요청·운영 검토 및 해당 DB 규칙이다. 전체 앱 또는 Figma 전체 구현 완료와 구분한다.

## 구현 상태

| 영역 | 제공 기능 | 기준 계약 |
|---|---|---|
| 인증·프로필 | 가입·로그인·로그아웃·프로필 저장/복구·세션 Flow·실제 프로세스 복원 | AuthRepository, architecture.md |
| 비밀번호 찾기 | 입력 검증·메일 요청·가입 여부 중립 응답·세션 보존 | auth-password-reset-contract.md |
| 채팅 | 참여자 전용 방/메시지 구독·서버 시각·동일 ID 재시도·로그아웃 정리 | ChatRepository, chat-ui-handoff.md |
| 문의 헤더 | 동아리명·선택 모집 정보·별도 공개 담당 정보, 사적 프로필 접근 금지 | chat-public-context-contract.md |
| 개설 요청 | 저장·본인 내역·운영 대기 목록·단회 승인/반려·동시 처리 충돌 | club-request-backend-contract.md |
| DB | 본인/참여자/운영 권한, 필드·시각·상태 전이 검증, 요청 인덱스 | firestore.rules, firestore.indexes.json |

`MopeApplication.authRepository`, `chatRepository`, `clubRequestRepository`를 앱 범위에서 제공한다. Mock 모드의 null은 미연동이며 저장 성공으로 취급하지 않는다. UI는 SDK를 직접 호출하지 않고 해당 인터페이스를 사용한다. 로그아웃 시 개인 상태를 지우고 다른 계정으로 로그인한 뒤 다시 구독한다.

개설 요청 재시도 ID에는 계정이 포함된다. 채팅은 입력 당시 uid를 expectedUserId로 전달할 수 있다. 한 번의 제출 의도에 ID를 한 번 생성·보관하고 본문과 함께 재시도한다. 상세 오류·null·시간 의미는 각 계약을 따른다.

## 재현 가능한 검증

Node 22 이상, JDK 21 이상, Android SDK 36.1 / Build Tools 36.1.0과 부팅된 Android 에뮬레이터 한 대를 준비한다. macOS에서는 기존 README의 Android Studio JAVA_HOME 예시를 사용할 수 있다.

```sh
npm ci
npm run verify:backend
```

이 명령은 설정 점검 도구 → Mock 단위/APK/Lint → Rules → 데모 반복 생성 → Android 전체 통합 → 실제 프로세스 재시작 검증을 순서대로 실행한다. 하나라도 실패하면 중단한다. 검사마다 Firebase Emulator를 시작·종료하므로 8080/9099 포트를 쓰는 다른 개발 서버는 먼저 종료한다. 운영 Firebase·메일·실데이터를 사용하지 않는다.

Android 에뮬레이터 없이 실행할 핵심 검증은 `npm run verify:backend -- --core`다. 이 결과를 Android 통합 검증 통과로 취급하지 않는다.

GitHub `Backend checks` workflow는 push/PR에서 핵심 검증을 실행하며 자동 배포는 하지 않는다. `Mock build and Firebase rules`가 실제 성공한 뒤에만 필수 검사로 지정한다. Android 통합·프로세스 재시작 검증은 로컬 전체 명령이 담당한다. 보고서는 Actions artifact로 7일 보관한다. 작업 Action은 검토한 커밋으로 고정했다.

공식 설정 참고: [checkout](https://github.com/actions/checkout), [setup-java](https://github.com/actions/setup-java), [setup-node](https://github.com/actions/setup-node), [Android SDK 설정](https://github.com/android-actions/setup-android), [테스트 보고서 업로드](https://github.com/actions/upload-artifact).

최종 로컬 검증: 단위 43개, Rules 29개, 설정 도구 3개, 데모 1개, Android 26개 통과(프로세스 단계 1개 의도적 skip). 실제 프로세스 재시작 seed/restored/signOut/loggedOut 네 단계도 통과했다. 네트워크 검증은 테스트 에뮬레이터의 Wi-Fi·데이터를 잠시 끄고 원래 상태로 복원한다. Google APIs sdk_gphone 에뮬레이터에서 실행하며 호스트 네트워크는 변경하지 않는다.

원격 핵심 CI도 [실행 #43413453571](https://github.com/devrhtmdals04/MobileProgramming/actions/runs/43413453571)에서 통과했다. 로컬 전체 검증과 원격 핵심 검증의 범위는 위 구분을 따른다.

## 실제 Firebase 연결·배포 전 남은 항목

로컬 구현/Emulator 검증과 실제 환경 검증은 다른 완료 조건이다. 현재 저장소에는 app/google-services.json이 없고 운영 프로젝트·메일·실제 인덱스 쿼리를 검증하지 않았다.

1. 팀의 대상 개발 Firebase 프로젝트에 Android 패키지 `com.mope.app`를 등록하고 콘솔의 설정 파일을 app/google-services.json에 둔다. 이 파일·관리자 자격 증명은 Git에 올리지 않는다.
2. `npm run check:firebase -- --project 실제-project-id`로 대상 프로젝트·패키지·앱 ID의 정적 일치를 검사한다. 이 명령은 읽기 전용이며 키나 JSON 원문을 출력하지 않는다. 정적 통과가 유효한 계정/키·Auth 활성화·서버 권한을 보장하지 않는다.
3. Firebase 콘솔에서 Email/Password 인증·Firestore를 준비하고 `./gradlew -PmopeBackend=firebase :app:assembleDebug`로 실제 설정 빌드를 확인한다. 학교 도메인/이메일 확인·로그인 유지 선택은 아직 공동 정책 미확정이므로 구현 완료로 표시하지 않는다.
4. 팀의 다른 DB 규칙과 합쳐 검토한 후 명시적 프로젝트 대상으로 `npx firebase deploy --project 실제-project-id --only firestore:rules,firestore:indexes`를 실행한다. 현재 파일은 우리 담당 컬렉션과 로그인 사용자의 clubs 단건 조회만 허용한다. 다른 담당 규칙을 누락한 채 전체 서비스에 배포하지 않는다. 자동 배포는 추가하지 않았다.
5. 실제 프로젝트에서 본인/타인 권한·양방향 채팅·같은 ID 재시도·운영 검토·두 요청 쿼리 인덱스 사용을 확인한다. Emulator는 실제 복합 인덱스 배포를 검증하지 않는다. 메일 수신·재설정 링크와 새 암호 로그인도 확인한다.
6. 운영 권한은 신뢰된 Admin 환경에서 `operator: true` custom claim으로만 부여한다. users.role의 club_admin과 다르다. claim 변경 후 토큰 갱신이 필요하며 제품 클라이언트에 Admin SDK/서비스 키를 넣지 않는다. 공개 연락 문서도 공개 필드만 신뢰된 환경에서 게시한다.
7. 프론트 담당과 실제 UI 연결 후 오류/로딩/중복 제출/로그아웃 상태 폐기를 검증하고 상대 리뷰를 기록한다.

이번 인계에 포함하지 않는 항목: 다른 담당의 동아리·홍보·검색·가입 신청 원격 구현, 설문형 수요조사·댓글·찜·알림의 범위 확대, 오래된 메시지 페이지·오프라인 전송 큐, 물리 기기/운영 배포 검증. 개설 요청 승인은 동아리 생성·권한 부여를 자동 수행하지 않는다.
