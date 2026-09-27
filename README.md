# MobileProgramming

이 저장소의 `study-helper` 브랜치에는 학습 앱 **Study Helper**가 `study_helper/`에 있습니다. 기존 말랑이 프로젝트는 루트에 보존했습니다.

- [Study Helper 소개 및 실행 방법](study_helper/README.md)
- [iPhone 빌드 방법](study_helper/godotIOS/README.md)
- Android Studio에서는 저장소 루트 대신 **`study_helper/` 폴더를 프로젝트로 열어 주세요**.
- 터미널의 Study Helper 빌드·검증 명령도 `cd study_helper` 후 실행합니다.

---

# mallang ii · 말랑이

`mallang-ii` 브랜치의 Kotlin Android 디지털 장난감 프로토타입입니다. 앱을 열면 바로 만질 수 있습니다.

- **톡**: 움찔하는 탄성 반응, 짧은 합성 효과음과 시스템 햅틱.
- **꾹**: 250ms 이후 서서히 납작해지고 눈이 양옆으로 벌어집니다.
- **쭉**: 잡은 위치 주변의 윤곽이 손가락 방향으로 늘어납니다. 놓으면 출렁이며 복원됩니다.
- **가만히**: 숨 쉬듯 움직이고 눈을 깜빡이며 마지막 터치 위치를 바라봅니다.
- **젤리 / 푸딩 / 모찌**: 색상, 광택, 탄성, 감쇠, 늘어남이 다릅니다.
- 소리 설정과 재질 선택은 앱 재실행 후 유지됩니다. 햅틱은 기기의 시스템 설정을 따릅니다.

## 실행

Android Studio 내장 JDK와 Android SDK 36.1 / Build Tools 36.1.0을 사용합니다.

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
~/Library/Android/sdk/platform-tools/adb -e install -r build/outputs/apk/debug/HelloWorld-debug.apk
~/Library/Android/sdk/platform-tools/adb -e shell am start -n com.example.helloworld/.MainActivity
```

VS Code에서는 기존 **Android: Run Hello World** 작업으로도 빌드·설치·실행합니다.
기존 실행 도구와의 호환성을 위해 APK 파일명과 applicationId는 유지했습니다. 기기에 표시되는 앱 이름은 **말랑이**입니다.

진입점은 `test.kt`, 화면·입력·재질·스프링은 `src/main/kotlin/com/example/helloworld/MallangScreen.kt`입니다.
`src/main/res/raw/pop.wav`는 직접 합성한 효과음입니다. 외부 음원이나 이미지 파일은 사용하지 않습니다.

## 오픈소스 선택

실제로 사용하는 의존성은 [AndroidX DynamicAnimation 1.1.0](https://developer.android.com/jetpack/androidx/releases/dynamicanimation)의 `SpringAnimation` / `SpringForce`입니다.
[AndroidX 소스](https://github.com/androidx/androidx/tree/androidx-main/dynamicanimation), [Apache-2.0 라이선스](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt).
기존 Canvas/View 기반 앱에 붙이기 쉽고, 강성과 감쇠를 재질별로 조절할 수 있어 선택했습니다. 이 라이브러리는 현재 유지보수 모드입니다.

[LiquidFun](https://github.com/google/liquidfun)도 조사했습니다. 입자 기반 유체·연성 물체에 적합하지만 저장소가 보관 처리되어 있고 NDK 통합이 필요하여 이번 버전에는 도입하지 않았습니다.

현재 모델은 스프링 값으로 64개 윤곽점을 변형하는 시각적 시뮬레이션입니다. 부피 보존, 자가 충돌, 여러 손가락으로 찢기까지 구현한 물리 엔진은 아닙니다. 한 번에 손가락 하나로 조작합니다.

## 확인 항목

1. 몸통 탭 → 짧은 반응. 빈 배경 탭 → 변형 없음.
2. 몸통을 1초 이상 누르기 → 납작해짐과 눈 간격 변화. 놓기 → 복원.
3. 몸통에서 상하좌우 드래그 → 부분적인 늘어남. 놓기 → 감쇠 진동.
4. 세 재질을 바꾸며 광택, 늘어남, 복원 속도 비교.
5. 소리 끄기, 재실행 → 설정 유지.
6. 누르는 도중 홈 화면 이동·복귀 → 입력과 변형 초기화.
7. 실제 기기에서 소리 볼륨과 햅틱의 촉감 확인.

## 표정 반응

- 한 번 톡: 웃음. 1.4초 안에 이어서 톡: 깜짝 → 새침 → 네 번째부터 삐짐.
- 살짝 당기기: 눈을 가늘게 뜨고 곁눈질하는 새침한 표정.
- 몸통 반지름의 0.85배 이상 당기기: 소용돌이 눈과 물결 입의 어질어질한 표정.
- 1.3초 이상 꾹 누르기: 눈을 찡그리고 눈물이 맺히는 울상. 놓으면 우울한 표정.
- 손을 떼고 기다리기: 삐짐·어질어질 → 우울 → 새침 → 웃음 순서로 풀립니다.

표정은 눈·눈썹·입·볼·눈물로 그리며, 몸통의 눌림과 늘어남을 따라 이동합니다.

## 표면과 변형

`SoftSurface.kt`는 반구의 표면 방향으로 계산한 조명 텍스처를 재질별로 생성하고 캐시합니다.
32×32 메시가 몸통과 반사광을 함께 변형합니다. 젤리의 날카로운 반사, 푸딩의 넓은 반사,
모찌의 미세한 무광 질감, 가장자리 빛과 바닥 접촉 그림자를 표현합니다.
누르면 옆으로 부풀고 누른 위치에 오목한 음영이 생기며, 당기면 횡방향으로 가늘어집니다.
놓은 후에는 감쇠하는 표면 잔물결이 추가됩니다.
이는 입체감을 보강한 2D 근사 표현이며, 실시간 3D 광선 추적이나 엄밀한 부피 보존 시뮬레이션은 아닙니다.
