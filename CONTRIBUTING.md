# MOPE 협업 규칙

## 시작하기

1. README대로 Mock 앱을 빌드한다. 김민성·고승민의 GitHub와 가용 시간은 `개발 계획.md`에 입력한다.
2. 본인의 계정 연결을 확인한다: `git config --get user.name`, `git config --get user.email`. 필요하면 이 저장소에서 본인의 실제 정보로 설정한다. 다른 사람의 계정·이메일을 사용하지 않는다.
3. `docs/issues.md`에서 작업을 선택하고 실제 Issue에 담당·기능 ID·우선순위·Phase·예상량·완료 조건을 기록한다. 원격 Issue 생성은 팀원이 직접 한다.
4. 계약 변경 여부를 먼저 확인한다. 모델·Repository·Gradle·Navigation 접점은 상대와 합의한다.

## 브랜치

```sh
git switch main
git pull --ff-only
git switch -c feature/club-search
```

main은 검증된 통합 브랜치다. `feature/auth`, `feature/club-feed`, `feature/club-search`, `feature/chat`, `feature/club-request`를 기본으로 사용하며 중복 시 기능 ID를 붙인다. 수정은 `fix/...`, 문서는 `docs/...`를 사용할 수 있다. 하나의 작업은 하나의 주 담당자가 맡는다. 공유 브랜치는 수정 파일 범위를 합의한다. main 직접 push, `git reset --hard`, 강제 push로 상대 이력을 덮어쓰지 않는다.

## 구현 및 커밋

Kotlin 공식 스타일(4칸 들여쓰기, 클래스 PascalCase, 함수·변수 camelCase)을 따른다. Screen은 상태를 표시하고 이벤트를 전달한다. ViewModel은 상태 전이·비동기 작업을 소유하고 Repository는 데이터 경계를 담당한다. Compose에서 Firebase를 직접 호출하지 않는다. 단일 app 모듈과 최소 의존성을 유지한다.

```text
feat: 동아리 검색 화면 구현
fix: 로그인 오류 처리 수정
refactor: 채팅 Repository 구조 개선
docs: 개발 계획 업데이트
test: 동아리 검색 ViewModel 테스트 추가
chore: Gradle 의존성 설정
```

하나의 커밋에는 설명할 수 있는 작업 단위를 담는다. 횟수 늘리기용 변경, 다른 사람 명의 커밋, 초기 생성 파일을 두 사람의 실적으로 임의 분할하는 행위를 하지 않는다. AI 보조 사용 시 실제 검토·수정·검증 범위를 PR에 기록한다. 비밀번호, 개인 설정, google-services.json, 서비스 계정 JSON, 서명 키는 커밋하지 않는다. `.gitignore`는 이미 추적 중인 파일을 제거하지 않으므로 `git diff --cached`로 확인한다.

## PR 절차

1. 본인 기능 테스트와 `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`를 실행한다.
2. UI 변경은 Preview 또는 기기 화면, 상태 처리 결과를 첨부한다. Firebase 변경은 Emulator 권한 테스트 결과를 첨부한다.
3. `git diff --check`, 변경 파일·설정 포함 여부를 확인하고 의미 있는 커밋을 만든다.
4. 본인 브랜치를 push하고 `.github/PULL_REQUEST_TEMPLATE.md`로 main 대상 PR을 연다. 관련 기능 ID와 실제 Issue가 있다면 `Closes #번호`를 쓴다. 임의 번호를 넣지 않는다.
5. 상대 팀원을 리뷰어로 지정한다. 구현 목적, 테스트 범위·미검증 사항, 계약·스키마 변경을 설명한다.
6. 상대의 수정 요청을 해결한 뒤 승인·검사 통과·충돌 해소를 확인한다.
7. 일반 **Create a merge commit**으로 병합하여 개별 커밋을 보존한다. Squash/Rebase merge는 공동 합의 없이 사용하지 않는다.
8. 계획 상태와 기여도 표에 Issue·PR·테스트·리뷰 링크를 갱신한다.

## 리뷰 기준

- 완료 조건을 실제로 만족하는가? 미구현 동작이 성공으로 표시되지 않는가?
- 다른 팀원이 Mock 또는 fake로 기능을 독립 검증할 수 있는가?
- 로딩·빈 데이터·오류·재시도와 중복 제출·화면 종료를 처리하는가?
- StateFlow는 외부에서 수정 불가능한가? 취소를 오류로 삼거나 리스너를 남기지 않는가?
- Firestore 권한이 UI 숨김에 의존하지 않고 Rules로 검증되는가?
- 민감한 값이 파일·로그·스크린샷에 포함되지 않았는가?
- 변경한 인터페이스의 소비자, 단위 테스트, 문서가 함께 갱신됐는가?

## GitHub 저장소 설정 체크 (SET-02)

저장소 관리자가 팀원 2명의 접근 권한, main 보호/Ruleset, PR 및 상대 승인 1회, 대화 해결, 직접·강제 push 제한을 설정한다. 사용 중인 GitHub 플랜에서 강제가 불가능하면 PR 체크리스트와 리뷰 기록으로 운영한다. Merge commit 허용 및 Squash 기본값 해제를 확인한다. CI는 아직 구성하지 않았으므로 존재하지 않는 필수 상태 검사를 지정하지 않는다. 현재는 PR에 로컬 빌드·테스트·Lint 로그를 첨부하고, CI 도입 시 실제 실행되는 검사만 필수로 지정한다.

문서·발표·테스트·리뷰는 공동 작업이다. 기여도는 커밋 숫자만으로 평가하지 않고 실제 diff, 기능 책임, 검증과 리뷰를 함께 남긴다.

## 담당 영역과 PR 분리 순서

1. 프론트엔드 — 김민성: 전체 Screen·ViewModel·Theme·Navigation. 고승민이 리뷰한다.
2. 백엔드 — 고승민: 인증·채팅·개설 요청, 김민성: 동아리·홍보·검색·가입 신청. 각자 Repository·검증·테스트를 작성한다.
3. DB — 고승민: users·chatRooms/messages·clubRequests, 김민성: clubs·promotions·membershipApplications. 각자 스키마·쿼리·인덱스·Rules 테스트를 작성하고 상호 리뷰한다.

프론트엔드와 백엔드가 같은 기능 ID를 공유하면 Issue/PR 제목에 `[FE]`, `[BE]`, `[DB]`를 붙여 실제 기여 범위를 구분한다. 전체 기능 완료 상태는 모든 하위 작업의 연결·검증 후 갱신한다. 예: `feature/auth-ui`, `feature/auth-repository`, `feature/users-rules`. 공유 계약·RequestRepository 구현 파일·Rules 통합 파일의 수정 순서는 먼저 합의한다.
