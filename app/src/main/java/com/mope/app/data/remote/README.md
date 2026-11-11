# Firebase 구현 경계
`auth/`에 FirebaseAuthRepository와 실행 모드별 생성 코드를 구현했다.
기본은 Mock이며 `-PmopeBackend=emulator`는 로컬 테스트, `-PmopeBackend=firebase`는 실제 설정 파일을 사용한다.
Auth·Firestore SDK를 추가했고 Google Services 플러그인은 firebase 모드에서만 적용한다.
`chat/FirestoreChatRepository`에 채팅방 생성·목록·메시지 구독·ID 기반 전송 재시도를 구현했다.
인증·채팅은 MopeApplication의 단일 FirebaseRepositories에서 같은 Auth/Firestore를 공유한다.
동아리 목록·신청 원격 구현과 인증·채팅 UI 연결은 후속 작업이다.
계약, 오류 처리, timestamp 변환, snapshot listener 해제 기준은 docs/architecture.md 참조.
