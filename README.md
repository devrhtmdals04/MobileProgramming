# MobileProgramming

Kotlin 안드로이드 Hello World 앱입니다. 화면 코드는 루트의 `test.kt`에 있습니다.
화면을 탭하면 `Hello`의 `o` 안에 있는 작은 `Hello World!`로 부드럽게 확대됩니다.
확대가 끝난 뒤 다시 탭하면 계속 안으로 들어가며 색상이 바뀝니다.
확대 중 추가 탭은 무시하고, 탭을 예약하지 않습니다. 안내 문구는 표시하지 않습니다.

## VS Code에서 실행 (macOS)

1. 이 폴더를 VS Code로 엽니다.
2. **Android iOS Emulator** (`DiemasMichiels.emulate`) 확장을 설치합니다.
3. `⌘⌥E` → **Android** → **Pixel_4**를 선택하고 부팅을 기다립니다.
4. `⌘⇧P` → **Tasks: Run Task** → **Android: Run Hello World**를 실행합니다.
5. 에뮬레이터 화면 중앙의 **Hello World!**를 확인하고 화면을 탭해 확대합니다.

`test.kt`를 수정한 뒤 4번을 다시 실행하면 다시 빌드하고 설치합니다.
먼저 파일을 저장하세요 (`⌘S`). `⌘⇧B`는 APK만 빌드하므로 에뮬레이터의 앱은 바뀌지 않습니다.
변경 사항을 화면에 반영하려면 **Android: Run Hello World**로 재설치하고 새로 실행하세요.
에뮬레이터는 한 대만 실행해 주세요. Kotlin 파일의 Run Code 버튼 대신 위 작업을 사용합니다.

Android Studio의 내장 JDK 및 `~/Library/Android/sdk`를 사용합니다.
다른 컴퓨터에서는 Android SDK 36.1과 Build Tools 36.1.0을 설치하고,
`local.properties`에 `sdk.dir=자신의 Android SDK 절대경로`를 설정하세요.
첫 빌드에는 Gradle 및 의존성 다운로드를 위한 인터넷 연결이 필요합니다.

빌드 설정: AGP 8.13.2, Gradle 8.13, Kotlin 2.2.21.
[AGP 공식 호환성 안내](https://developer.android.com/build/releases/agp-8-13-0-release-notes)

## 터미널에서 빌드

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

APK: `build/outputs/apk/debug/HelloWorld-debug.apk`
