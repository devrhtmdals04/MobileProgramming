# 문의 공개 정보 백엔드 계약

`ChatRepository.getRoomContext(roomId)`는 로그인한 방 참여자에게 서버의 최신 문의 정보를 단건 조회한다. 화면·ViewModel·Route 연결은 프론트 담당이다.

| 반환 필드 | 의미 |
|---|---|
| roomId / clubId / clubName | 방·동아리 식별자와 동아리 이름 |
| adminId | 방 생성 당시 관리자. 현재 clubs.adminId로 교체하지 않는다 |
| recruitmentTitle | 선택 모집 제목. 미등록이면 null |
| recruitmentDeadlineMillis | 선택 모집 마감 Timestamp의 epoch millis. 미등록이면 null |
| contact | 공개 담당 정보. 문서가 없으면 null |
| contact.userId / displayName | 기존 방 관리자 ID와 공개 표시 이름 |
| contact.responseHint / updatedAtMillis | 선택 응답 안내와 공개 문서 갱신 시각 |

## 저장 계약과 권한

`clubs/{clubId}`의 name은 1~50자, 선택 recruitmentTitle은 1~100자, 선택 recruitmentDeadlineAt은 Timestamp다. 공백만인 이름·제목은 거절한다.

`clubs/{clubId}/publicContacts/{adminId}`는 displayName(1~30자), updatedAt(Timestamp)이 필수이며 responseHint(1~120자)는 선택이다. 공백만인 문자열과 명시적 null 안내는 허용하지 않는다. 이 세 키 외에 이메일·전화번호 등 필드를 추가하면 Rules가 문서 조회를 거절한다.

공개 문서는 인증 사용자의 단건 조회만 허용한다. 목록·클라이언트 생성/수정/삭제는 관리자도 거절한다. 신뢰된 운영 도구에서 공개용 정보만 게시해야 한다. 사적 users 문서의 읽기 권한을 확대하거나 이름을 fallback으로 가져오지 않는다.

Repository는 먼저 방 참여 권한을 확인하고 방에 고정된 adminId의 공개 문서를 읽는다. 관리자 변경 후에도 새 관리자가 과거 대화를 읽을 수 없다. 동아리 모집 정보는 현재 값이며 과거 모집의 스냅샷이 아니다. 실시간 구독 API가 아니므로 재조회 시점을 프론트에서 정한다.

잘못된 roomId는 INVALID_INPUT, 로그아웃/계정 변경은 UNAUTHENTICATED, 비참여/공개 문서 정책 위반은 PERMISSION_DENIED, 없는 방·동아리는 NOT_FOUND, 잘못된 데이터 타입·값은 INVALID_DATA다. 네트워크 오류는 기존 ChatFailure 계약을 따른다. 없는 선택 문서는 contact=null로 반환한다.

## 검증과 예제

Android Emulator에서 공개 이름/사적 이름 분리, 양쪽 참여자 조회, 선택 필드 부재, 비참여·로그아웃 거절, 관리자 변경, 잘못된 타입·개인정보 혼입을 검증했다. Rules는 공개 문서 단건·누락·필드 제한 및 쓰기 금지를 검증한다.

`npm run seed:chat:demo`는 localhost demo-mope에 공개 담당 이름·응답 안내·모집 제목을 준비한다. 마감일은 임의로 만들지 않는다. 반복 실행해도 공개 정보 갱신 시각과 기존 방·메시지를 유지한다. 운영 배포·관리자 편집 기능은 포함하지 않는다.
