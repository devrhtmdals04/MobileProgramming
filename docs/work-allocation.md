# SET-03 작업 배정

사용자의 배정 위임에 따라 정한 작업 기준이다. 기존 기능별 담당은 유지하고 공유 파일의 작성 책임을 아래처럼 정한다. 배정 완료는 기능 구현이나 두 팀원의 리뷰 완료를 뜻하지 않는다.

## 계약과 공유 파일

| 작업·파일 | 작성 담당 | 리뷰 담당 |
|---|---|---|
| `Models.kt`: User·UserRole·ChatRoom·Message·ClubRequest·RequestStatus | 고승민 | 김민성 |
| `Models.kt`: Club·Promotion·MembershipApplication 필드 정의 | 김민성 | 고승민 |
| `Models.kt`의 위 필드 변경 최종 반영·충돌 정리 | 고승민 | 김민성 |
| `AuthRepository.kt`, `ChatRepository.kt` 계약·오류 정의 | 고승민 | 김민성 |
| `ClubRepository.kt` 계약·홍보 등록 메서드 추가 | 김민성 | 고승민 |
| `RequestRepository.kt` 공유 인터페이스 최종 반영 | 고승민 | 김민성 |
| 가입 신청 메서드·필드·오류 계약 제안 | 김민성 | 고승민 |
| `MopeApplication.kt` Repository 생성·주입 및 Firebase 모드 설정 | 고승민 | 김민성 |
| `MopeNavHost.kt` 화면 연결·인증 경계·ViewModel 생성 | 김민성 | 고승민 |

공유 모델과 인터페이스는 담당자가 상대의 변경 요청을 반영한다. 구현자는 인터페이스를 독자적으로 변경하지 않고 소비하는 ViewModel·fake·문서를 함께 갱신한다.

## 기능 구현 경계

- 김민성: 전체 Screen·ViewModel·Navigation·테마·UI 테스트. 동아리·홍보·검색·가입 신청의 원격 구현과 해당 DB 설계·테스트.
- 고승민: 인증·세션·프로필·채팅·개설 요청의 원격 구현과 해당 DB 설계·테스트. Firebase 의존성·실행 모드·공통 오류 타입.
- 개설 전용 `ClubRequestRepository`와 `FirestoreClubRequestRepository`는 고승민이 구현했다. 공유 `RequestRepository`는 개설 계약을 상속하고 가입 계약을 유지한다. 김민성의 가입 구현이 준비되면 전체 RequestRepository를 조립한다. 가입 기능의 가짜 구현은 제공하지 않는다.
- 통합 `firestore.rules`, `firestore.indexes.json`, Emulator 설정은 고승민이 취합한다. 김민성은 clubs·promotions·membershipApplications 규칙·인덱스·테스트를 작성해 전달하고, 고승민은 나머지 컬렉션을 작성한다.
- 상대 담당 영역의 변경은 해당 담당자가 리뷰한다. 통합 검증·시연·발표는 공동 담당이다.

## 첫 작업 순서와 완료 기준

1. 고승민: AuthRepository와 User 계약을 정리하고 인증 fake·계약 테스트, users 스키마·권한 테스트를 작성한다. 인증 오류는 잘못된 입력·인증 실패·중복 계정·네트워크·권한·알 수 없는 오류로 구분하고 Firebase 타입을 UI에 노출하지 않는다.
2. 김민성: 같은 계약으로 AuthViewModel·로그인/회원가입 화면·화면 상태 테스트를 작성한다. UI는 입력 중·처리 중·성공·실패를 구분하고 중복 제출을 막는다.
3. 고승민: Firebase 연결·AuthRepository 구현·사용자 프로필 저장을 진행한다. 저장 완료 후에만 가입 성공을 전달하고 프로필 저장 실패 후 복구 동작을 정의한다.
4. 김민성: 인증 상태와 Navigation을 연결한다. 공동으로 회원가입 → 로그인 → 재실행 시 세션 복원 → 로그아웃을 검증한다.

문자열 ID, 저장 완료 후 suspend 반환, Flow 오류 전파, 취소 전파, 화면 역할과 실제 권한 분리는 기존 architecture.md 계약을 따른다. 운영 권한은 일반 사용자가 수정할 수 있는 UserRole로 부여하지 않는다.

SET-03은 모델 필드·오류·Flow 의미·공유 파일 경계가 문서와 코드에서 일치하고 상대 리뷰를 마친 뒤 DONE으로 바꾼다. 현재 상태는 배정 완료·계약 리뷰 대기다.
