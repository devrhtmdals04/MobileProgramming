# 검증 기록 및 후속 테스트

## 초기 로컬 검증

검증 환경: macOS arm64, Android Studio 내장 JDK, Gradle 8.13, AGP 8.13.2, SDK 36.1 / Build Tools 36.1.0. Firebase 설정·네트워크 SDK 없이 Mock 모드로 검증했다.

| 항목 | 결과 | 근거·한계 |
|---|---|---|
| `:app:assembleDebug` | 성공 | `app/build/outputs/apk/debug/app-debug.apk` 생성 |
| `:app:testDebugUnitTest` | 성공, 9개 | 초기 NO-SOURCE 이후 실제 테스트 추가하여 재실행 |
| `:app:lintDebug` | 성공, 오류 0·경고 14 | 기존 도구/라이브러리 버전·백업 설정·앱 아이콘 관련 경고 |
| `git diff --check` | 통과 | 공백·패치 형식 검사 |
| 기기 실행·화면 이동 | 미검증 | ADB 연결 기기·실행 중 에뮬레이터 없음 |
| Compose Preview 렌더링 | 미검증 | Preview 함수 컴파일 성공, Studio 시각 검증 필요 |
| Firebase / Rules / 인증 / 실시간 송수신 | 미검증 | SDK·설정·Rules 구현 전 |
| 원격 CI / PR / 보호 브랜치 | 미구성 | 로컬 검사 로그를 PR에 첨부하는 방식으로 시작 |

빌드 과정에 SDK XML 버전 차이와 네이티브 라이브러리 심볼 strip 경고가 있었지만 APK 빌드는 성공했다. Lint의 최신 targetSdk/라이브러리 권고는 호환성이 검증된 기존 조합을 유지하면서 문서화했으며 무조건 최신으로 올리지 않았다. 최종 제출 전 SDK·백업 정책·앱 아이콘을 다시 검토한다. 현재 앱은 실제 사용자 데이터를 저장하지 않는다.

## 실행 명령

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
git diff --check
```

다른 환경에서는 자신의 JDK 경로를 사용한다. 테스트 보고서 `app/build/reports/tests/testDebugUnitTest/index.html`, Lint 보고서 `app/build/reports/lint-results-debug.html`을 확인한다. 생성된 build 결과는 커밋하지 않는다.

## 작성된 단위 테스트

| 테스트 | 수 | 검증 내용 |
|---|---|---|
| FilterClubsTest | 4 | 빈 검색·공백 제거·소개 검색·AND 필터·대소문자 |
| HomeViewModelTest | 3 | Loading→Content, Empty, 오류 후 재시도 성공 |
| SearchViewModelTest | 1 | 구독 중 검색어/카테고리 변경에 따른 결과·빈 상태 |
| MockClubRepositoryTest | 1 | 샘플 ID 중복 없음·모든 홍보글의 동아리 참조 유효 |

ViewModel은 fake Repository와 테스트 Main dispatcher로 검증한다. Firebase 구현체를 대체해도 동일한 계약 테스트가 가능하도록 생성자 주입을 사용한다.

## 수동 Mock 확인 (기기/Studio 준비 후)

- [ ] 첫 화면에 MOPE 및 미연동 안내, 로그인 버튼 비활성 확인
- [ ] Mock 둘러보기 → 홈 홍보 카드 2개 표시
- [ ] 카드 → 홍보 상세 → 문의/가입 → 뒤로 이동
- [ ] 탐색에 3개 동아리, `사진` 검색 및 운동 필터 조합 시 빈 결과
- [ ] 필터 해제·검색어 삭제 시 목록 복구
- [ ] 대화 탭의 미연동 안내, 개설/가입 폼의 제출 비활성 확인
- [ ] 내 정보 → 둘러보기 종료 → 뒤로 가도 이전 보호 흐름 미노출
- [ ] 작은 화면·큰 글꼴·키보드 표시 상태에서 입력/버튼 가림 확인
- [ ] 회전 후 입력·탭 상태와 스크롤 확인 (미지원 상태 복원은 Issue로 기록)

## Firebase 통합 시나리오 (Phase 4)

| ID | 시나리오 | 기대 결과 | 담당/증거 |
|---|---|---|---|
| E2E-01 | 학생 가입→로그인→재실행→로그아웃 | 인증 복원, 오류 안내, 로그아웃 후 개인 데이터 제거 | 김민성 UI·ViewModel / 고승민 Repository·DB, 상호 검증 / 링크 입력 |
| E2E-02 | 관리자 홍보 등록→학생 피드/상세 조회 | 실제 저장 반영, 타 동아리 관리자 쓰기 거절 | 김민성 UI·Repository·DB / 고승민 검증 |
| E2E-03 | 검색·카테고리 조합→상세 | 올바른 결과·빈 상태, 잘못된 ID 처리 | 김민성 UI·Repository·DB / 고승민 검증 |
| E2E-04 | 두 계정·두 실행 환경 채팅 | 중복 방 없음, 실시간 송수신·정렬·재접속·재시도 | 김민성 UI·ViewModel / 고승민 Repository·DB, 상호 검증 |
| E2E-05 | 개설 요청→본인 내역→운영자 검토 | pending→승인/반려, 타인·일반 관리자 접근 거절 | 김민성 UI·ViewModel / 고승민 Repository·DB, 상호 검증 |
| E2E-06 | 가입 신청→재실행→본인 내역 | 중복 신청 방지, 저장 확인·타인 읽기 거절 | 김민성 UI·Repository·DB / 고승민 검증 |
| E2E-07 | 네트워크 단절·복구·연속 탭·화면 이탈 | 오류/재시도, 중복 쓰기 없음, 리스너 해제 | 공동 |
| E2E-08 | 로그아웃 후 뒤로·화면 재진입 | 인증 경계 유지, 이전 사용자 메시지 미노출 | 공동 |

Firestore 권한의 허용·거절 상세 행렬은 `firestore-schema.md`를 기준으로 SEC-01 자동 테스트에 구현한다. 테스트 파일·규칙·실행 방법과 통과 로그가 없으면 검증 완료로 기록하지 않는다.
