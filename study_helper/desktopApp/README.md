# Study Helper Desktop

Compose Desktop 서재와 공통 Kotlin 학습 코어, 별도 프로세스의 Godot 게임을 묶는다. 공부 폴더의 PDF·노트·문제·그림·녹음을 경로 그대로 Google Drive와 양방향 동기화한다. 추가·수정·삭제 목록을 표시하며 모바일에서도 녹음을 올릴 수 있다. [Google 설정·동기화 사용법](../docs/GOOGLE_DRIVE_SYNC.md).

## macOS

JDK 17 이상(현재 환경은 Android Studio JBR), Python 3, Godot 4.6.2를 사용한다.

```sh
env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :desktopApp:createDistributable
# 실행 앱
open 'desktopApp/build/compose/binaries/main/app/Study Helper.app'
# DMG
env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :desktopApp:packageDmg
```

패키지는 Java 런타임·Godot 실행 파일·게임 자료를 포함한다. Godot 경로가 다르면 `GODOT_BIN`을 지정한다. 개발 실행은 `:desktopApp:run`이며 이때 게임은 저장소의 `godot-runner/`를 사용한다. 마이크는 macOS 권한 승인이 필요하다. 공개 배포용 Developer ID 서명·공증은 별도 설정이다.

## Windows

동일 소스를 Windows에서 빌드한다. macOS의 jpackage로 Windows MSI를 만들 수는 없다. JDK 17 이상, Python 3, Windows Godot 4.6.2, Android SDK(루트 Gradle 프로젝트 구성용)를 설치한다. `JAVA_HOME`, `ANDROID_HOME`, `GODOT_BIN`을 실제 경로로 지정한다. MSI에는 WiX Toolset이 필요하다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:GODOT_BIN = 'C:\Tools\Godot\Godot_v4.6.2-stable_win64.exe'
$env:PYTHON = 'python'
.\gradlew.bat :desktopApp:jvmTest :desktopApp:createDistributable
# 런타임을 포함한 실행 폴더: desktopApp\build\compose\binaries\main\app\Study Helper
.\gradlew.bat :desktopApp:packageMsi
```

기본 PC 클라이언트 ID는 `src/jvmMain/resources/google-desktop-client-id.txt`에 포함되어 있어 JSON 선택 없이 브라우저 로그인을 시작한다. Google이 토큰 교환 시 추가 인증 설정을 요구하거나 다른 클라이언트를 사용할 때는 **데스크톱 앱** 유형의 OAuth JSON을 선택한다. 개발 시에는 `STUDY_GOOGLE_DESKTOP_OAUTH` 환경변수로 JSON 경로를 전달해도 된다. 서비스 계정 키나 웹 앱 OAuth JSON은 사용하지 않는다.

현재 PC 클라이언트는 Google 토큰 엔드포인트의 진단 응답에서 `client_secret is missing`이 확인되어, ID만으로는 인증을 완료할 수 없다. 해당 클라이언트의 OAuth JSON을 로컬에서 지정해야 한다. 브라우저 콜백은 토큰 교환 결과를 확인한 뒤 성공/실패를 표시한다. 실제 계정 로그인과 Drive 왕복은 별도 검증이 필요하다.

## 저장 및 검증

- macOS: `~/Library/Application Support/StudyHelperDesktop`
- Windows: `%APPDATA%\StudyHelper`
- Linux: `$XDG_DATA_HOME/study-helper` 또는 `~/.local/share/study-helper`
- 격리된 실행: `STUDY_HELPER_DATA_DIR`로 저장 경로 지정
- 테스트: `./gradlew :shared:jvmTest :desktopApp:jvmTest`
- 패키지 통합 검증: 실행 파일에 `--desktop-probe` 인자를 전달. 임시 서재에 검증용 문제를 만들고 실제 Godot에서 7문제를 채점한 뒤 결과 반환을 검사한다. `DESKTOP PROBE PASSED`와 출력된 임시 폴더의 `game-bridge/*/notebook-engine-2.json`, 게임 PNG를 확인한다. OAuth나 실제 강의 녹음은 실행하지 않는다.

Whisper 실행 파일과 모델은 선택 사항이며 기본 패키지에는 포함하지 않는다. PC 설정에서 구성한 뒤 녹음 목록의 **이 기기에서 텍스트로 변환**을 사용한다. 결과는 수정 후 노트로 저장할 수 있다.

## 이번 환경의 검증 결과

macOS 실행 앱과 DMG 생성, 실제 패키지의 서재→Godot 7문제 채점→결과 복귀가 통과했다. 공통 JVM 54개, 공통 iOS 55개, PC 저장소 2개 테스트가 실패 없이 통과했다. iOS 시뮬레이터 앱과 Android APK도 빌드했다. [검증 기록](artifacts/verification.json) · [PC 게임 화면](artifacts/desktop-game.png).

Windows 실행·MSI 및 PC Google 실제 계정 OAuth/Drive 왕복은 아직 검증하지 않았다. 사용자가 제공한 PC 클라이언트 ID를 기본 설정에 포함했다. ID가 포함된 빌드 성공은 실제 로그인·동기화 성공을 의미하지 않는다.

## 2026-09-28 폴더 공유 변경

상단 메뉴를 **공부 폴더 공유**와 **설정**으로 단순화했다. 선택한 PC 폴더의 PDF·노트·녹음 등을 경로 그대로 주고받으며 파일별 추가·수정·삭제·충돌 내역을 표시한다. 기존 폴더 동기화의 문서 ID와 저장하지 못한 앱 편집 내용도 이어받는다.

공통 JVM 54개와 PC 11개 테스트가 통과했고 Swift 저장 코드 및 실제 Mac 학습 게임도 검증했다. 아이폰에서 공유 화면과 기존 학습 회귀 검증 19개 항목이 통과했다. Mac 앱/DMG·Android APK·iOS 프레임워크 및 서명 앱을 빌드했다. 새 폴더/바이너리 전송의 실제 Google 서버 왕복과 Windows 실행은 아직 검증하지 않았다. [검증 기록](artifacts/folder-sharing-verification.json).
