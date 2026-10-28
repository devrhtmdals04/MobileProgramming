# Firestore 데이터·접근 권한 설계 (v1 초안)

**설계 문서이며 배포된 스키마·Security Rules가 아니다.** SET-03에서 두 명이 계약을 확정하고 SEC-01에서 실제 Rules와 Emulator 테스트를 작성한다. 클라이언트 화면의 버튼 숨김은 보안 검증을 대체하지 않는다.

## DB 담당과 작업 순서

프론트엔드는 전체 김민성 담당이며, 백엔드와 DB는 다음 기능 경계로 분담한다. 이 표는 설계·구현 책임이며 기존 코드의 작성자 기록을 변경하지 않는다.

| 순서 | 담당 | 컬렉션 | 담당 산출물 |
|---|---|---|---|
| 1 | 고승민 | users | 인증 사용자·역할 필드, 본인 접근 Rules·권한 상승 테스트 |
| 2 | 김민성 | clubs, promotions | 동아리·관리자 관계, 홍보 필드, 조회 인덱스·관리자 쓰기 Rules |
| 3 | 고승민 | chatRooms/messages | 참여자·메시지 스키마, 정렬·구독 쿼리, 비참여자 거절 테스트 |
| 4 | 고승민 | clubRequests | 신청·검토 필드, 본인/운영 조회 인덱스·상태 전이 Rules |
| 5 | 김민성 | membershipApplications | 가입 신청·중복 ID, 본인 내역 인덱스·소유자 Rules |
| 6 | 공동 | 전체 Rules·인덱스 통합 | 상호 리뷰 및 전체 Emulator 회귀 검증 |

통합 Rules 파일은 고승민이 통합 PR을 맡고 김민성이 자신의 컬렉션 규칙·테스트를 별도 PR로 제출한다. 작성자의 실제 커밋은 유지한다. DB 순서는 문서 정리 기준이며 공통 계약 합의 후 서로 다른 컬렉션은 병렬 작업할 수 있다.

## 컬렉션과 관계

```text
users/{uid}
clubs/{clubId}
promotions/{promotionId}
chatRooms/{roomId}
  messages/{messageId}
clubRequests/{requestId}
membershipApplications/{applicationId}
```

사용자 1명은 여러 동아리를 관리할 수 있지만 MVP 동아리에는 관리자 1명(`adminId`)만 둔다. 학생 1명은 여러 동아리에 문의·신청할 수 있다. 홍보글은 하나의 동아리에 속하며, 채팅방은 학생 1명과 해당 동아리 관리자 1명으로 구성한다. 실제 가입 완료 명단 관리는 MVP 범위 밖이며 가입 신청만 저장한다. 문서 ID는 Kotlin `id`로 매핑하고 중복 id 필드를 저장하지 않는다.

문자열 제한은 초기 제안이며 UI·Repository·Rules에서 같은 기준으로 확정한다. 모든 날짜는 서버 Timestamp를 사용하고, 상태값은 소문자 문자열로 저장 후 Kotlin enum에 매핑한다. 알 수 없는 enum/필수 필드 누락은 DTO 변환 오류로 처리하여 조용히 잘못된 권한을 부여하지 않는다.

| 경로 | 필드·타입 | 제약·설명 |
|---|---|---|
| users/{uid} | displayName: string, role: string, createdAt: timestamp | 이름 1~30자, role=`student` 또는 `club_admin`; uid는 Auth uid. email/password는 별도 Firestore 저장하지 않음 |
| clubs/{clubId} | name: string, category: string, description: string, recruitment: string, adminId: string, createdAt: timestamp | 이름 1~50자, 소개 1~2000자, 모집 1~1000자, 카테고리 문화/학술/운동 초기값; adminId는 유효 사용자 |
| promotions/{id} | clubId: string, authorId: string, title: string, body: string, createdAt: timestamp | 제목 1~100자, 본문 1~5000자, 작성자=인증 uid=해당 club.adminId |
| chatRooms/{roomId} | clubId: string, studentId: string, adminId: string, participantIds: list<string>, createdAt: timestamp | 참여자 정확히 2명, studentId != adminId, adminId는 해당 동아리 관리자, 생성자=studentId |
| chatRooms/{roomId}/messages/{id} | senderId: string, text: string, createdAt: timestamp | 발신자=인증 uid, 방 참여자만, 본문 공백 제외 1~2000자. 수정·삭제 없음 |
| clubRequests/{id} | applicantId: string, name: string, purpose: string, status: string, createdAt: timestamp, reviewedBy: string?, reviewedAt: timestamp? | 이름 1~50자, 목적 1~2000자, 생성 상태 pending. 검토 필드는 최초 null |
| membershipApplications/{id} | clubId: string, applicantId: string, introduction: string, status: string, createdAt: timestamp | 소개 1~1000자, 신청자는 본인, 존재하는 동아리만, 생성 상태 pending |

현재 Kotlin 모델은 Mock 화면에 필요한 부분만 담는다. 예를 들어 Message 외 createdAt, Promotion.authorId, 요청 검토 정보는 remote DTO 및 UI 필요에 따라 DATA-02에서 모델에 추가한다. 필드 누락을 기본값으로 덮어 처리하지 않고 경계 테스트를 작성한다.

## 권한의 신뢰 기준

- `request.auth.uid`: 실제 로그인 사용자. Mock 방문자는 이에 해당하지 않는다.
- `users.role`: 화면 표시용 역할. 본인 쓰기로 `club_admin`으로 승격할 수 없다. 최초 생성은 `student`만, 본인 업데이트는 displayName만 허용한다.
- 동아리 관리 권한: `clubs/{clubId}.adminId == request.auth.uid`. role 문자열만으로 다른 동아리 권한을 얻지 않는다.
- 운영 권한: 신뢰된 운영 환경에서 부여한 `request.auth.token.operator == true` custom claim. 일반 동아리 관리자에게 자동 부여하지 않는다. 앱이 이 값을 쓰거나 임의 요청하여 승격할 수 없다. Kotlin의 `UserRole`과 별도 capability로 처리한다.
- 운영자의 원시 Admin SDK 접근은 Rules를 우회하므로 운영자 자격 증명을 앱/저장소에 포함하지 않는다.

## 접근 권한 행렬

`본인`은 auth uid, `동아리 관리자`는 해당 club.adminId, `운영자`는 별도 claim을 뜻한다.

| 리소스 | 읽기 | 생성 | 변경·삭제 |
|---|---|---|---|
| users | 본인만 | 본인 uid + role=student | 본인 displayName만; 역할 변경은 신뢰된 운영 환경, 클라이언트 삭제 금지 |
| clubs | 로그인 사용자 | 운영자 또는 콘솔의 신뢰된 운영 작업 | 해당 관리자 소개·모집만; adminId/생성 시각 불변, 클라이언트 삭제 금지 |
| promotions | 로그인 사용자 | 해당 동아리 관리자 | MVP 등록·조회만; 수정·삭제 금지 |
| chatRooms | participantIds에 본인 포함 | 본인이 학생이며 정확히 해당 관리자와 구성 | 참여자·clubId·생성 시각 변경 및 삭제 금지 |
| messages | 부모 방 참여자 | 부모 방 참여자 + senderId=본인 | 수정·삭제 금지 |
| clubRequests | 본인 또는 운영자 | 본인 + pending + 검토 필드 null | 운영자만 pending→approved/rejected, 검토 필드만 변경; 삭제 금지 |
| membershipApplications | 본인 | 본인 + pending + 중복 없는 ID | MVP 변경·삭제 금지; 관리자 열람·승인 UI는 별도 범위 합의 |

모든 create는 허용 필드 집합·필수 키·타입·길이·현재 auth·참조 문서를 검사한다. 타임스탬프는 생성 시 request.time과 일치시키고 immutable 필드 변경을 거절한다. update는 diff의 변경 키 목록을 제한한다. 정의되지 않은 모든 경로는 거절한다. 다른 사람 신청 정보를 포함한 전체 목록을 읽고 클라이언트에서 필터링하지 않는다.

## 채팅 생성과 송수신

1. 학생이 상세에서 문의를 누르면 Repository가 동아리 adminId를 조회한다. 본인이 관리자면 자기 자신과 채팅을 생성하지 않는다.
2. 방 ID는 `{clubId}__{studentUid}` 규칙으로 중복 생성을 막는다. MVP에서 clubId/uid는 구분자 `__`를 포함하지 않는 식별자로 제한하고 Rules에서도 해당 제약 및 ID 일치를 확인한다. 이 제한을 바꾸면 충돌 없는 인코딩 규칙과 Rules를 함께 변경한다.
3. transaction에서 방이 있으면 참여자·동아리 일치를 검증 후 재사용하고, 없으면 immutable 참여자 목록과 서버 시각으로 생성한다. 동시에 두 번 눌러도 방은 하나다.
4. 현재 관리자를 참여자로 고정한다. MVP에서는 관리자 변경을 앱에서 허용하지 않으며 운영자가 관리자 변경 시 기존 방의 처리 정책을 먼저 정한다. 새 관리자가 자동으로 이전 사적 대화를 읽게 하지 않는다.
5. 메시지는 방 하위에 저장하며 senderId를 인증 사용자에서 가져온다. 서버 승인 전 시각은 null일 수 있다. 시각과 문서 ID의 일관된 정렬 기준을 정하고 전송 중/실패를 표시한다.
6. 전송마다 로컬에서 메시지 문서 ID를 한 번 생성하고 재시도에 재사용한다. 이미 저장된 동일 ID는 재조회하여 중복 전송을 피한다. 버튼 연타 방지는 ViewModel에서도 수행한다.
7. 목록·방 리스너는 화면 종료 및 로그아웃 시 해제한다. 전체 사용자 메시지를 조회하지 않는다.

## 개설 요청과 가입 신청

개설 요청은 작성자 본인만 생성하며, 승인/반려 여부를 클라이언트 입력으로 받지 않는다. 운영자는 pending 상태인 문서만 검토하며 reviewedBy=본인, reviewedAt=서버 시각, status만 변경한다. 승인 후 실제 clubs 등록 및 adminId 지정은 운영자가 콘솔에서 확인해 수행한다. 이 초기 MVP에서 자동 개설을 수행하지 않는다. 승인 상태와 동아리 등록 완료는 UI에서 혼동하지 않게 설명한다.

가입 신청 ID는 `{clubId}__{applicantUid}`로 두고 동일 동아리 중복 신청을 차단한다. 위 식별자 제한을 동일하게 적용한다. 기존 문서가 있으면 중복 상태를 알려 주며 덮어쓰지 않는다. 신청 성공은 저장 완료 이후 표시하고 재시작 후 본인 내역으로 확인한다. 개설 요청 반복 제출은 ViewModel 중복 제출 차단 및 재시도 시 같은 문서 ID 사용으로 방지한다.

## 쿼리·검색·인덱스 후보

| 용도 | 쿼리 | 인덱스 계획 |
|---|---|---|
| 동아리 목록 | clubs orderBy(name), 수업용 소규모 목록 | 단일 필드 |
| 홍보 피드 | promotions orderBy(createdAt, desc), limit(20) | 단일 필드; 후속 페이지 커서 |
| 동아리별 홍보 | where clubId == id + createdAt desc | clubId ASC, createdAt DESC |
| 본인 대화 | participantIds array-contains uid | 기본 배열 인덱스; 최근 대화 정렬은 MVP 후속 합의 |
| 메시지 | 해당 방 messages orderBy(createdAt), limitToLast(50) | 단일 필드; 오래된 메시지 페이지 처리 별도 테스트 |
| 본인 개설 요청 | applicantId == uid + createdAt desc | applicantId ASC, createdAt DESC |
| 운영 검토 | status == pending + createdAt asc | status ASC, createdAt ASC |
| 본인 가입 신청 | applicantId == uid + createdAt desc | applicantId ASC, createdAt DESC |

MVP는 소규모 대학 동아리 목록(가정: 100개 이하)을 읽어 앱 내에서 이름·소개 포함 검색 및 카테고리 AND 필터를 수행한다. 서버 전체 텍스트 검색이 구현된 것처럼 표현하지 않는다. 목록이 커지면 데이터 규모와 검색 방식부터 재합의하며 외부 검색 서비스를 임의 도입하지 않는다. 현재 Mock은 3개 동아리/2개 홍보글이다.

인덱스는 위 표가 후보이며 실제 쿼리와 Emulator/개발 프로젝트 확인 후 `firestore.indexes.json`으로 추적한다. Rules는 조회 결과를 필터링하지 않으므로 본인 신청·채팅 목록 쿼리 자체에 사용자 조건이 들어가야 한다. [공식 쿼리 보안 안내](https://firebase.google.com/docs/firestore/security/rules-query)

## Rules 검증 목록 (SEC-01)

| 검증 주체·행위 | 기대 |
|---|---|
| 비로그인 사용자의 컬렉션 읽기/쓰기 | 거절 |
| 학생의 본인 프로필 생성·이름 수정 | 허용 |
| 학생의 role 변경·operator 필드 삽입 | 거절 |
| 관리자 A가 자기 동아리 홍보 등록 | 허용 |
| 관리자 A가 다른 동아리 홍보 등록 | 거절 |
| 방 참여자가 본인 senderId로 메시지 생성 | 허용 |
| 타인의 방/메시지 읽기·목록·쓰기 | 거절 |
| 참여자 위조·추가, 방 clubId 변경, senderId 위조 | 거절 |
| 본인의 pending 개설 요청·가입 생성/조회 | 허용 |
| 타인의 신청 목록·직접 문서 조회 | 거절 |
| 신청자가 approved 상태로 생성·직접 상태 변경 | 거절 |
| 일반 동아리 관리자의 개설 요청 일괄 조회·검토 | 거절 |
| 운영자의 pending 승인·반려, 검토 정보 기록 | 허용 |
| 이미 처리된 요청 상태 재변경 | 거절 |
| 필드 초과·잘못된 타입·빈 본문·길이 초과·시간 위조 | 거절 |
| 동일 방/가입 동시 생성·메시지 재시도 | 중복 문서 없음 |

Rules와 해당 테스트가 아직 구현되지 않았으므로 실제 데이터베이스의 보안 검증 완료를 주장하지 않는다. [조건 검증](https://firebase.google.com/docs/firestore/security/rules-conditions) 및 [Emulator 테스트](https://firebase.google.com/docs/firestore/security/test-rules-emulator)를 기준으로 구현한다.
