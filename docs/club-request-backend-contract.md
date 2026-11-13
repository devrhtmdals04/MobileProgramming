# 개설 요청 백엔드 인계 계약

2026-11-13 구현. 동아리 개설 요청은 Figma의 설문형 수요조사·동아리 가입 신청과 별도 기능이다. 화면·ViewModel·Route는 추가하지 않았다. `MopeApplication.clubRequestRepository`는 emulator/firebase 모드에서 제공되고 Mock에서는 null이다.

## 사용 방법

```kotlin
val repository = requireNotNull(application.clubRequestRepository)
val requestId = repository.newRequestId() // 로그인 후, 제출 직전에 한 번 생성·보관
repository.submitClubRequest("사진부", "함께 사진을 기록해요", requestId)
// 통신 실패 후 같은 계정·입력·ID로 재시도
repository.submitClubRequest("사진부", "함께 사진을 기록해요", requestId)
repository.observeMyRequests().collect { requests -> /* 본인 내역 */ }
// 서버가 operator=true custom claim을 부여한 계정만 가능
repository.observePendingRequests().collect { pending -> /* 운영 검토 목록 */ }
repository.reviewRequest(requestId, RequestStatus.APPROVED) // 또는 REJECTED
```

`ClubRequestRepository`는 개설 전용 인터페이스다. 기존 공유 `RequestRepository`는 이를 상속하며 가입 신청 계약을 보유한다. 구현체는 `FirestoreClubRequestRepository`로, 가입 기능의 미구현을 가짜 성공이나 빈 목록으로 감추지 않는다. 전체 RequestRepository 조립은 가입 구현이 준비된 후 별도 작업이다.

## 저장·재시도·조회

- 이름은 trim 후 1~50자, 목적은 trim 후 1~2000자다. 공백만인 입력은 거절한다. 줄바꿈을 포함한 목적은 허용한다.
- ID는 `{인증 uid}__{UUID}`다. SDK의 현재 인증 사용자를 작성자로 사용하고 클라이언트에서 applicantId를 받지 않는다. uid에는 구분자 `__`가 없어야 한다. 기존 채팅과 동일한 식별자 제약이며 Firebase 기본 생성 uid는 호환된다.
- `newRequestId()`는 로그인 필요. 기본 인자를 사용하면 호출마다 새 요청이므로 실패 재시도 시 명시적 ID를 재사용한다. 다른 계정으로 바뀌면 이전 ID를 폐기한다.
- 트랜잭션은 같은 ID·작성자·정규화된 내용이면 기존 요청을 재사용한다. 승인 후 제출 재시도도 상태를 되돌리지 않는다. 동일 ID의 다른 내용은 CONFLICT다. 새 ID는 별도 요청으로 취급하므로 동일 이름의 중복 개설 자체를 차단하는 기능은 아니다.
- 정상 반환은 서버 저장 확인 이후다. 오프라인 제출 큐는 제공하지 않으며 네트워크 오류 시 같은 ID로 재시도한다. 취소는 CancellationException으로 전파하며 이미 서버가 수락한 쓰기의 취소를 보장하지 않는다.
- 본인 목록은 applicantId 조건 + createdAt 내림차순, 운영 대기는 status=pending + createdAt 오름차순이다. 동일 시각은 Firestore 기본 문서 ID 정렬을 따른다. 현재 페이지 처리·목록 개수 제한은 없다. 수업용 소규모 요청량을 전제로 하며 목록 확장 시 커서 계약을 추가한다.
- 서버가 확인한 snapshot만 발행한다. 빈 목록은 정상이며 손상된 데이터는 INVALID_DATA로 구독을 종료한다. 로그아웃·계정 변경은 UNAUTHENTICATED로 종료하고 리스너를 해제한다. UI는 개인 목록을 지우고 새 계정으로 재구독해야 한다.

## DB와 권한

`clubRequests/{id}`의 필수 필드는 applicantId, name, purpose, status, createdAt, reviewedBy, reviewedAt이다. 추가 키를 허용하지 않는다. 최초 상태는 pending이며 createdAt은 서버 시각, 검토 두 필드는 명시적 null이다. 사용자 프로필이 있는 본인만 제출한다. 본인 내역 또는 신뢰된 운영자만 읽을 수 있다. 전체 목록을 일반 사용자가 읽고 로컬 필터링하는 방식은 거절한다.

운영자는 **서버에서 부여한 boolean `operator=true` custom claim**으로 판단한다. `users.role=club_admin`, 이름, 클라이언트 입력, 문자열 `"true"`는 운영 권한이 아니다. 제품 코드에는 claim 부여 기능이나 운영자 계정 생성 기능이 없다. 테스트에서만 로컬 Auth Emulator owner fixture를 사용한다. 실제 claim은 신뢰된 Admin 환경에서 관리하며 변경 후 토큰 갱신이 필요하다.

검토는 pending에서 approved/rejected로만 한 번 가능하다. reviewedBy는 인증 운영자 uid, reviewedAt은 서버 시각이며 이 세 필드 외 변경·문서 삭제를 금지한다. 동시 승인/반려 중 하나만 성공한다. 동시 변경 때문에 Rules가 먼저 거절한 경우에도 운영 권한과 최신 서버 상태를 재확인해 처리 완료를 CONFLICT로 분류한다. 상태 확인이 실패하면 해당 오류를 전달하며 성공으로 처리하지 않는다. 재검토는 CONFLICT이므로 통신 실패 후 상태를 다시 조회한다. 승인은 동아리 생성이나 관리자 권한 부여를 자동 수행하지 않는다.

`ClubRequest`는 기존 필드에 createdAtMillis, reviewedBy, reviewedAtMillis를 추가했다. 원격 구현은 createdAtMillis를 항상 채우고 pending 검토 필드는 null로 반환한다. 기존 호출 호환을 위한 모델 기본 null을 원격 저장 완료의 의미로 사용하지 않는다.

`firestore.indexes.json`에 본인 내역·대기 목록용 두 복합 인덱스를 추적한다. Emulator는 복합 인덱스 누락을 검증하지 않으므로 운영 연결 전 해당 인덱스 배포와 실제 프로젝트 쿼리 검증이 필요하다. 이번 작업은 로컬 검증이며 Rules·인덱스의 운영 배포를 수행하지 않았다.

## 오류·검증

`RequestFailure.reason`: INVALID_INPUT(형식·길이), UNAUTHENTICATED(로그아웃/다른 계정의 ID), PERMISSION_DENIED(서버 권한 거절), NOT_FOUND(운영 검토 대상 없음), CONFLICT(내용 충돌/이미 검토), NETWORK(연결·일시 장애), INVALID_DATA(손상 문서), UNKNOWN(나머지). Firebase 예외는 원인으로 보존하고 공개 계약은 SDK에 의존하지 않는다.

실행은 `npm run test:rules`, `npm run test:request:android`를 사용한다. 후자는 부팅된 Android 에뮬레이터와 JDK가 필요하다. 전체 인증·채팅 회귀는 `npm run test:auth:android`로 실행한다.
