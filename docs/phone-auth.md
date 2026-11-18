# 전화번호 인증

이메일 로그인·회원가입·비밀번호 재설정을 전화번호 인증으로 완전히 대체했다.
번호 입력 → 인증번호 확인 → 신규 계정만 이름 등록 → 홈 순서다. 같은 번호는 같은
Firebase uid로 로그인하고 기존 이름·역할·생성 시각을 유지한다.

## 로컬 실행

Android 에뮬레이터를 부팅하고 두 터미널에서 실행한다.

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
npm run dev:firebase
```

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew -PmopeBackend=emulator :app:installDebug
adb -e shell am start -n com.mope.app/.MainActivity
```

`010-1234-5678` 또는 `+16505550101` 같은 테스트 번호를 입력한다. Emulator는 실제
문자를 보내지 않고 Firebase 실행 터미널에 무작위 6자리 인증번호를 출력한다. 앱은 그
번호를 입력해야 인증된다. 고정 우회 코드·자동 코드 조회는 제품 코드에 넣지 않았다.
`npm run seed:chat:demo`는 고정 테스트 번호 학생 +16505550101 / 관리자 +16505550102로
데모 데이터를 준비한다. 앱에서는 다시 인증번호를 요청해 로그인한다.

## 상태와 재시도

- `requestPhoneCode(phoneNumber, activity)`는 CodeSent(verificationId) 또는 자동 인증 완료를 전달한다.
- `verifyPhoneCode(verificationId, code)`는 SDK credential로 인증한다. 코드 오류는 이름 입력·홈 진입을 허용하지 않는다.
- `session`의 ProfileRequired는 번호 인증을 마쳤지만 Firestore 프로필이 없는 상태다. `completeProfile(name)` 성공 후 SignedIn으로 이동한다.
- 프로필 생성 실패는 재시도한다. 가입 중 재실행해도 번호 인증 세션이 남아 있으면 이름 입력부터 재개한다.
- 인증번호 재요청·전화번호 변경은 이전 UI 요청을 취소한다. 번호 변경 시 코드·verification ID를 지운다. 자동 인증 콜백은 요청이 살아 있을 때만 처리한다.
- 회전 후 수동 코드 입력은 ViewModel에서 유지한다. 프로세스 종료 시 미완료 코드 입력은 다시 번호부터 시작한다.
- SDK 자동 읽기 대기시간은 60초다. 만료·과다 요청·네트워크 오류를 구분하고 수동 재요청을 제공한다.
- 로그아웃은 개인 폼을 비우며 앱 재실행 후에도 유지된다. 다른 번호로 시작하기는 미완료 가입을 로그아웃한다.

전화번호는 Firebase Auth에 저장하며 users 문서에는 기존 displayName/role/createdAt만
저장한다. UID 기반 채팅·개설 요청 Rules와 인덱스는 그대로 사용한다. 기존 이메일
계정과 전화번호 계정의 자동 병합은 하지 않는다. 실제 사용자 이전은 별도 정책이 필요하다.

## 실제 Firebase 연결

개발 프로젝트 **GRU Campus (`gru-campus`)**에 Android 앱 `com.mope.app`을 등록했고, 이 작업 환경의 `app/google-services.json`을 연결했다. 설정 파일은 Git에서 제외한다. `.firebaserc`에는 `development` 별칭만 등록해 암묵적인 기본 배포 대상을 지정하지 않는다. 새 개발 환경에서는 콘솔에서 설정 파일을 별도로 받아야 한다. 실제 SMS 발송 환경은 검증하지 않았다.
Firebase 프로젝트에 com.mope.app를 등록하고 설정 파일을 추가한다. Phone 제공자를
활성화하고 Android SHA-256(Play Integrity)·SHA-1(reCAPTCHA fallback), SMS 허용 국가와
Blaze 결제 설정을 준비한다. 이메일 제공자는 앱 교체만으로 콘솔에서 비활성화되지 않으므로
프로젝트 연결 후 별도로 비활성화한다. 운영 기존 계정을 임의로 삭제하지 않는다.

콘솔 테스트 번호는 실제 SMS 없이 검증할 수 있다. 실제 배포 전에는 기기에서 문자 수신,
reCAPTCHA fallback, 재전송·제한·만료와 로그인 복원을 확인한다. 전화번호 소유 인증은
학교 재학이나 법적 본인 확인을 보장하지 않는다.

공식 문서: [Android 전화번호 인증](https://firebase.google.com/docs/auth/android/phone-auth),
[Auth Emulator](https://firebase.google.com/docs/emulator-suite/connect_auth),
[SMS 사용 한도·Blaze](https://firebase.google.com/docs/auth/limits).

## 검증 기록 (2026-11-18)

단위 테스트 36개, Lint, Emulator APK 빌드 통과. Android 통합 24개 통과 및
프로세스 단계 1개 의도적 skip. 별도 프로세스 seed/restored/signOut/loggedOut 네 단계
통과. 전화번호 데모 반복 생성 테스트 1개 통과. 실제 SMS 발송·기기 자동 수신은
실제 Firebase 프로젝트 연결 후 검증 대상이다.

## 개발 Firebase 실행

VS Code의 `Android: Run GRU (Firebase)` 작업을 실행한다. 기존 `Android: Run GRU`는
설정 파일 없이 빌드 가능한 Mock 모드를 유지한다. 터미널 실행은 다음과 같다.

```sh
npm run check:firebase -- --project gru-campus
./gradlew -PmopeBackend=firebase :app:installDebug
adb -e shell am start -n com.mope.app/.MainActivity
```

프로젝트는 Spark 요금제이며 결제 계정을 연결하지 않았다. Phone 제공자를 활성화하고
개발 Mac의 debug SHA-1·SHA-256을 등록했다. 다른 개발자는 본인의 debug 서명 지문을
추가 등록해야 한다. 실제 출시 키는 아직 등록하지 않았다. SMS 리전은 한국(KR)과
가상 테스트 번호에 필요한 미국(US)만 허용한다.

Firestore `(default)`는 Standard / 서울 `asia-northeast3`로 생성했다. 저장소의
`firestore.rules`를 콘솔에서 게시했고 `firestore.indexes.json`의 두 개설 요청 색인도
동일하게 생성 요청했다. CLI 로그인 없이 콘솔로 설정했으며 자동 배포는 추가하지 않았다.

무료 클라우드 테스트 번호는 `+16505550101`이다. 인증코드는 Firebase 콘솔에서
설정한 값을 사용하며 저장소에 기록하지 않는다. 실제 SMS는 이 번호로 발송되지 않는다.
명시적으로 실행할 때만 동작하는 `FirebaseProjectConnectionTest`는 런타임 인자로
`firebaseConnection=true`, `testPhone`, `testSmsCode`를 받고, `gru-campus`와 지정된
가상번호만 허용한다. 이 테스트의 앱 검증 우회는 instrumentation 프로세스에서만
적용하며 종료 시 해제한다. 제품 앱의 앱 검증은 우회하지 않는다.

2026-11-18 실제 프로젝트 연결 검증: `FirebaseProjectConnectionTest` 1개 통과.
가상번호 인증 → 신규 프로필 저장 → 서버 프로필 읽기 → 타인 프로필 접근 거절 →
로그아웃 → 같은 번호 재로그인과 기존 프로필 보존을 실제 Android SDK로 확인했다.
이는 실제 SMS 수신·Play Integrity/reCAPTCHA 기기 검증 통과 기록을 대신하지 않는다.
테스트 계정과 `연결 확인` 프로필은 개발 프로젝트에 남겨 두었다.

최종 회귀 검증: Mock APK·단위 36개·Lint, Rules 29개, 설정 도구 3개, 데모 반복 생성 1개, Android 로컬 통합 24개 통과(프로세스/클라우드 전용 2개 skip). 실제 프로젝트 인증 smoke test 1개 통과.
