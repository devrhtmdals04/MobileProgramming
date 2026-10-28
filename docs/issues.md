# GitHub Issue 생성 후보

원격 Issue는 생성하지 않았다. ID는 개발 계획의 기능 ID이며 GitHub 번호가 아니다. 프론트엔드 → 백엔드 → DB 순서로 작업을 분류한다. 이름은 확정했으며 김민성·고승민의 GitHub 계정은 추후 입력한다.

Figma는 전체 화면 UI의 디자인 기준이다. ViewModel은 그 화면의 상태·이벤트를 처리하는 Kotlin 코드로, 프론트엔드 담당 김민성이 작성한다. Figma 디자인을 ViewModel 코드로 취급하지 않는다. 현재 원본 접근이 안 되므로 디자인 확인을 완료 조건에 남긴다.

## 사전 공동 작업

### [SET-03] 모델·Repository·DB 계약 및 파일 경계 확정

- 영역: 공통 · 담당: 김민성·고승민 · 우선순위: P0 · Phase: 0 · 예상량: 각 0.5인일
- 선행: SET-01
- 설명: ID·필드·오류·시간·역할 의미, RequestRepository의 개설/가입 구현 파일 경계를 합의한다.
- 완료 조건: UI에서 소비할 상태·반환값과 DB 필드 매핑, 담당자별 수정 범위에 두 명 동의; 문서·계약 PR 연결.
- 파일: data/model, data/repository, docs/architecture.md, docs/firestore-schema.md.

## 1. 프론트엔드 — 김민성

### [UI-01] Figma 기준 공통 UI와 화면 구현 순서 정리

- 담당: 김민성 · 우선순위: P0 · Phase: 1 · 예상량: 1.5인일
- 선행: SET-01
- 설명: 기존 Figma의 색상·타이포·컴포넌트·화면 흐름을 확인하고 전체 UI 구현 기준을 만든다.
- 완료 조건: Figma 비교와 미확인 항목 기록, 주요 Preview·작은 화면·큰 글꼴·로딩/빈 데이터/오류 상태 확인. 원본 미확인 시 디자인 일치 완료로 처리하지 않음.
- 수정 경계: ui/theme, ui/components, docs/figma-reference.md
- 리뷰: 상대 팀원, 실제 Issue/PR 링크는 생성 후 입력.

### [AUTH-01/FE] AuthViewModel과 인증 화면 상태 구현

- 담당: 김민성 · 우선순위: P0 · Phase: 1 · 예상량: 1인일
- 선행: SET-03
- 설명: Figma 로그인·회원가입·프로필 디자인을 Compose로 구현하고 fake AuthRepository로 ViewModel 상태를 연결한다.
- 완료 조건: 입력 검증·로딩·실패·중복 제출·취소 테스트. 실제 인증은 AUTH-01/BE와 DATA-01 이후 통합.
- 수정 경계: ui/screens/auth, 해당 ViewModel 테스트
- 리뷰: 상대 팀원, 실제 Issue/PR 링크는 생성 후 입력.

### [SEARCH-01/FE] 탐색·검색·상세 UI 개선

- 담당: 김민성 · 우선순위: P0 · Phase: 1 · 예상량: 1인일
- 선행: SET-03, UI-01
- 설명: Figma 기반 탐색·홍보·상세 화면을 기존 Mock 계약으로 개선한다.
- 완료 조건: 이름·소개 검색, 공백·대소문자·카테고리 AND 필터·해제·빈 결과·뒤로 이동 확인.
- 수정 경계: ui/screens/home, search, club 및 해당 테스트
- 리뷰: 상대 팀원, 실제 Issue/PR 링크는 생성 후 입력.

### [CHAT-01/FE] 채팅 목록·대화 화면과 ViewModel 구현

- 담당: 김민성 · 우선순위: P0 · Phase: 1~3 · 예상량: 1.5인일
- 선행: SET-03, UI-01
- 설명: Figma 문의 디자인을 사용하고 fake ChatRepository로 메시지·전송 상태를 표시한다.
- 완료 조건: 목록·전송 중·오류·재시도·화면 종료 테스트. 실제 방 생성·송신은 고승민 구현체와 통합.
- 수정 경계: ui/screens/chat, 해당 ViewModel 테스트
- 리뷰: 상대 팀원, 실제 Issue/PR 링크는 생성 후 입력.

### [REQ-01/FE] 개설·가입·검토 화면과 ViewModel 구현

- 담당: 김민성 · 우선순위: P0 (가입·검토 P1) · Phase: 1~3 · 예상량: 2인일
- 선행: SET-03, UI-01
- 설명: Figma 개설·가입 폼을 구현하고 개설/가입 화면 파일을 분리한다. 원본에 없는 운영 검토 상태는 확인 후 구성한다.
- 완료 조건: 필수 입력·중복 제출·저장 성공/실패·본인 내역 상태 테스트. 개설 Repository는 고승민, 가입 Repository는 김민성 구현과 연결.
- 수정 경계: ui/screens/request, 가입 전용 Screen/ViewModel, navigation
- 리뷰: 상대 팀원, 실제 Issue/PR 링크는 생성 후 입력.

## 2. 백엔드 — 고승민·김민성 분담

### [DATA-01] Firebase 환경과 backend 선택 경계

- 담당: 고승민 · 우선순위: P0 · Phase: 2 · 예상량: 1인일
- 선행: SET-03, Firebase 콘솔 접근
- 설명: Firebase 앱 등록·SDK·Mock/실환경 선택·주입 설정을 준비한다.
- 완료 조건: 설정 없는 Mock 빌드 유지, 명시적 Firebase 설정 검사, 발급 JSON Git 제외 및 실행 문서.
- 수정 경계: Gradle, Manifest, MopeApplication, data/remote
- 리뷰: 상대 팀원, 실제 Issue/PR 링크는 생성 후 입력.

### [AUTH-01/BE] 인증 Repository와 세션 처리

- 담당: 고승민 · 우선순위: P0 · Phase: 2 · 예상량: 1.5인일
- 선행: SET-03; 원격 구현은 DATA-01
- 설명: AuthRepository 계약 테스트와 Firebase 인증 구현체를 작성한다. UI·ViewModel은 김민성이 담당한다.
- 완료 조건: 가입·로그인·실패·세션 Flow·로그아웃 검증, 취소 전파. Firebase 준비 전에는 fake 계약 테스트 진행.
- 수정 경계: data/remote의 Auth 구현, Repository 테스트
- 리뷰: 상대 팀원, 실제 Issue/PR 링크는 생성 후 입력.

### [DATA-02/BE-CLUB] 동아리·홍보 조회 및 등록 Repository

- 담당: 김민성 · 우선순위: P0 (등록 P1) · Phase: 2~3 · 예상량: 1.5인일
- 선행: SET-03, DATA-01, 담당 DB 설계
- 설명: 동아리·홍보 조회, 검색용 데이터 제공 및 홍보 등록을 구현한다.
- 완료 조건: DTO 변환·오류·빈 목록 테스트, createPromotion 계약 공동 검토, 본인 동아리만 등록.
- 수정 경계: ClubRepository 구현, domain, Repository 테스트
- 리뷰: 상대 팀원, 실제 Issue/PR 링크는 생성 후 입력.

### [CHAT-01/BE] 채팅방·메시지 실시간 처리

- 담당: 고승민 · 우선순위: P0 · Phase: 3 · 예상량: 3인일
- 선행: AUTH-02, DATA-02, SEC-01
- 설명: 방 생성·본인 목록·메시지 저장·실시간 구독을 구현한다. CHAT-02를 연결 하위 작업으로 추적한다.
- 완료 조건: 중복 방·메시지 방지, 재접속·재시도·리스너 해제, 두 계정 송수신 검증.
- 수정 경계: ChatRepository 구현, Repository 테스트
- 리뷰: 상대 팀원, 실제 Issue/PR 링크는 생성 후 입력.

### [REQ-01/BE] 개설 요청·본인 내역·운영 검토 처리

- 담당: 고승민 · 우선순위: P0 (검토 P1) · Phase: 3 · 예상량: 2인일
- 선행: AUTH-02, DATA-02, SEC-01
- 설명: 개설 요청 저장·조회·승인·반려 처리 로직을 구현한다. REQ-02를 연결해 추적한다.
- 완료 조건: 본인 저장/조회, pending에서만 상태 전이, 운영 권한 검사·실패 테스트.
- 수정 경계: RequestRepository 개설 처리 클래스, 해당 테스트
- 리뷰: 상대 팀원, 실제 Issue/PR 링크는 생성 후 입력.

### [JOIN-01/BE] 가입 신청·중복 방지·본인 내역 처리

- 담당: 김민성 · 우선순위: P1 · Phase: 3 · 예상량: 1인일
- 선행: AUTH-02, DATA-02, SEC-01
- 설명: 가입 신청 저장·중복 방지·본인 내역 Repository를 구현한다.
- 완료 조건: 성공은 저장 완료 이후 표시, 중복 ID·재시도·타인 접근 검증.
- 수정 경계: RequestRepository 가입 처리 클래스, 해당 테스트
- 리뷰: 상대 팀원, 실제 Issue/PR 링크는 생성 후 입력.

## 3. DB — 고승민·김민성 분담

### [SEC-01/DB-USER] 사용자 스키마·권한·Rules 테스트

- 담당: 고승민 · 우선순위: P0 · Phase: 2 · 예상량: 1인일
- 선행: SET-03
- 설명: users 필드·역할 신뢰 기준 및 본인 조회/수정 정책을 설계·검증한다.
- 완료 조건: 본인 정상 접근 허용, 타인 접근·role 상승·운영 권한 위조 거절.
- 수정 경계: users DTO·Rules·Emulator 테스트·설계 문서
- 리뷰: 상대 팀원, 실제 Issue/PR 링크는 생성 후 입력.

### [SEC-01/DB-CLUB] 동아리·홍보 스키마·인덱스·Rules

- 담당: 김민성 · 우선순위: P0 · Phase: 2 · 예상량: 1인일
- 선행: SET-03
- 설명: clubs/promotions 관계·adminId·피드 쿼리/인덱스·권한을 작성한다.
- 완료 조건: 정상 조회와 해당 관리자 등록 허용, 다른 관리자 등록 거절, 인덱스·필드 검증.
- 수정 경계: clubs/promotions DTO·Rules·인덱스·Emulator 테스트
- 리뷰: 상대 팀원, 실제 Issue/PR 링크는 생성 후 입력.

### [SEC-01/DB-CHAT] 채팅 참여자·메시지 스키마와 Rules

- 담당: 고승민 · 우선순위: P0 · Phase: 2~3 · 예상량: 1.5인일
- 선행: SET-03, DB-USER·DB-CLUB 계약
- 설명: chatRooms/messages의 참여자·방 ID·시각·조회 조건을 정의한다.
- 완료 조건: 비참여자 접근·senderId 위조·참여자 변경 거절, 정상 송수신·정렬·중복 방 테스트.
- 수정 경계: 채팅 DTO·Rules·쿼리·Emulator 테스트
- 리뷰: 상대 팀원, 실제 Issue/PR 링크는 생성 후 입력.

### [SEC-01/DB-REQUEST] 개설 요청·검토 상태와 Rules

- 담당: 고승민 · 우선순위: P0 · Phase: 2~3 · 예상량: 1인일
- 선행: SET-03, 운영 권한 계약
- 설명: clubRequests 작성자·상태·검토자·시간·조회 인덱스를 정의한다.
- 완료 조건: 본인 요청/조회 및 운영자 검토 허용, 타인 조회·학생 승인·처리 후 재변경 거절.
- 수정 경계: 개설 DTO·Rules·인덱스·Emulator 테스트
- 리뷰: 상대 팀원, 실제 Issue/PR 링크는 생성 후 입력.

### [SEC-01/DB-JOIN] 가입 신청 스키마·중복 ID와 Rules

- 담당: 김민성 · 우선순위: P1 · Phase: 2~3 · 예상량: 1인일
- 선행: SET-03, DB-USER·DB-CLUB 계약
- 설명: membershipApplications 관계·중복 신청 ID·본인 조회 인덱스를 정의한다.
- 완료 조건: 본인 생성/조회 허용, 중복·타인 조회·임의 승인 거절.
- 수정 경계: 가입 DTO·Rules·인덱스·Emulator 테스트
- 리뷰: 상대 팀원, 실제 Issue/PR 링크는 생성 후 입력.

## 통합과 진행 기준

- 기존 기능 ID 뒤 `/FE`, `/BE`, `/DB-*`는 영역별 하위 작업 표기다. DB-USER 등의 짧은 표기는 위 SEC-01 하위 작업을 뜻한다. 상위 ID·상태·최종 완료 조건은 `개발 계획.md`를 따른다.
- 첫 병렬 작업: 계약 합의 후 김민성 UI-01, 고승민 AUTH-01/BE 계약 테스트와 DB-USER 설계. 김민성의 백엔드·DB 작업은 기본 UI 골격 이후 진행한다.
- 문서 순서는 프론트엔드 → 백엔드 → DB이며 실제 의존 순서는 계약·DB 설계 합의 → 독립 구현 → 통합이다.
- Firebase 콘솔 접근 없이도 Mock/fake와 로컬 Emulator로 가능한 작업을 진행한다. 실제 연결에 필요한 설정이 없으면 원격 구현 완료로 표시하지 않는다.
- 공유 RequestRepository 구현·통합 Rules 파일은 SET-03에서 파일/메서드 경계를 확정한다. 각자 작성분은 각자 커밋하고 상대가 리뷰한다.
- SET-02(공동, Phase 0, 각 0.5인일): GitHub 접근·브랜치 보호·Merge 규칙 확인.
- NAV-01(김민성, Phase 1~2, 1인일): 인증 경계·화면 이동·로그아웃 백스택 검증.
- AUTH-02(프론트 김민성 / 백엔드·DB 고승민, Phase 2, 각 0.5~1인일): 세션·프로필·로그아웃 통합 검증.
- TEST-02(공동, Phase 4, 각 2인일): P0/P1 구현 후 docs/testing.md 시나리오·권한 회귀 통과.
- REL-01(공동, Phase 5, 각 1인일): TEST-02 이후 새 clone 빌드·APK·발표·기여도 기록 정리.

예상량은 초기 추정이며 2인일을 넘는 작업은 기능·테스트 기준으로 작은 PR로 나눈다. 모든 신규 작업은 TODO이며 원격 생성·실제 구현 여부에 맞춰 상태를 갱신한다.
