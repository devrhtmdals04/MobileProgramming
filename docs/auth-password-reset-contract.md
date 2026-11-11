# 비밀번호 찾기 백엔드 계약

Figma 로그인 프레임 19:2592의 비밀번호 찾기를 지원한다. 이번 범위는 Repository·검증·테스트이며 버튼·ViewModel·화면 연결은 포함하지 않는다.

```kotlin
suspend fun AuthRepository.requestPasswordReset(email: String)
```

- 로그인 없이 호출할 수 있다. 이메일 앞뒤 공백을 제거하며 비밀번호를 입력받지 않는다.
- 형식 오류는 AuthFailure(INVALID_INPUT)다. 네트워크·요청 제한 등 SDK 실패는 기존 AuthFailure 매핑을 따른다. 취소는 그대로 전파한다.
- 정상 반환은 요청 접수다. 메일 수신·링크 사용·비밀번호 변경 완료를 의미하지 않는다.
- 미가입 이메일에 대한 ERROR_USER_NOT_FOUND는 요청 성공과 같은 반환으로 처리한다. 가입 여부를 결과 값으로 제공하지 않는다.
- 현재 로그인 사용자·프로필·로그아웃 표시는 변경하지 않는다.
- 프론트 완료 안내 예: “가입된 이메일이라면 재설정 안내를 받을 수 있어요.”
- 실제 프로젝트의 메일 템플릿·발송·도메인과 콘솔의 이메일 열거 방지 설정은 이번 Emulator 검증 범위 밖이다.

Android Emulator 테스트에서 재설정 코드 생성 → 코드 적용 → 이전 비밀번호 거절 → 새 비밀번호 로그인과 프로필 보존을 확인했다. 실제 메일은 발송하지 않았다.
Figma의 로그인 유지 선택·학교 이메일 인증 정책은 여전히 별도 결정/구현 항목이다.

공식 참고: [Android 사용자 관리](https://firebase.google.com/docs/auth/android/manage-users),
[Auth Emulator](https://firebase.google.com/docs/emulator-suite/connect_auth).
