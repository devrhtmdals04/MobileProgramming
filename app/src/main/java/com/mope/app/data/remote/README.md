# Firebase 구현 경계
`auth/`에 FirebaseAuthRepository와 실행 모드별 생성 코드를 구현했다.
기본은 Mock이며 `-PmopeBackend=emulator`는 로컬 테스트, `-PmopeBackend=firebase`는 실제 설정 파일을 사용한다.
Auth·Firestore SDK를 추가했고 Google Services 플러그인은 firebase 모드에서만 적용한다.
`chat/FirestoreChatRepository`에 채팅방 생성·목록·메시지 구독·ID 기반 전송 재시도를 구현했다.
인증·채팅·개설 요청은 MopeApplication의 단일 FirebaseRepositories에서 같은 Auth/Firestore·로그아웃 상태를 공유한다.
`request/FirestoreClubRequestRepository`에서 개설 요청 저장·본인 내역·운영 검토를 구현했다. 같은 저장소의 RequestRepository 계약으로 관리자 홍보글 등록, 최신 20개 구독, 가입 신청 및 본인 내역 조회를 제공한다. 가입 대상은 Firestore에 등록된 동아리다. 홍보글 재시도에는 최초 호출 전 생성한 newRequestId() 값을 재사용한다.
우리 담당 전체 검증·운영 연결 기준은 docs/backend-ready.md를 따른다.
계약, 오류 처리, timestamp 변환, snapshot listener 해제 기준은 docs/architecture.md 참조.
