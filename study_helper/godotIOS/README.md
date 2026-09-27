# Study Helper iOS

Kotlin/Compose 서재와 Godot 게임을 하나의 iPhone 앱으로 실행한다. 기본 화면은 **노트 서재**이며, 사용자가 문제 모음에서 게임을 시작할 때만 엔진을 초기화한다. 마크다운 읽기·편집, 로컬 저장, 파일 가져오기·내보내기, 프롬프트 복사, 문제 JSON 검증·미리보기, 게임 복습·결과 반환을 연결했다.

- `shared/NotebookHost.kt`: 문서·문제 검증, 서재 상태, 비공개 정답, 학습 세션, 최근 결과.
- `StudyHelper/StudyHelperApp.swift`: SwiftUI 진입점, UIKit 파일 선택기, 기기 저장소·클립보드, 게임 컨테이너, 원자적 JSON 전달.
- `StudyHelper/StudyEngine.mm`: Godot 4.6.2 지연 초기화와 뷰·렌더링 수명. 엔진 소스는 수정하지 않는다.
- `prepare_study_helper.py`: Godot 내보내기 프로젝트에 호스트와 **Shared와 StudyWhisper 정적 XCFramework**를 연결한다. 이전 `StudyBridge.mm` 플러그인은 통합 앱에서 비활성화한다.

서재 복귀 후 렌더링·전송 타이머를 중단하되 엔진 메모리는 보관한다. 재진입 시 Godot 씬과 Kotlin 학습 세션을 새로 만든다. [역할·데이터 계약](../docs/STUDY_GAME_CONTRACT.md).

번들 ID는 기존 설치와 같은 `com.example.studyhelper.jaderun`, 표시 이름은 **Study Helper**다. 이전 Godot 시제품을 덮어 설치하며 앱 데이터를 삭제하지 않는다. 별도 `iosApp`은 이전 Canvas 시제품이다. 통합 앱 최소 대상은 iOS 18.5이며 현재 Compose/Skia 라이브러리의 요구 버전에 맞췄다.

## 빌드

macOS, Xcode, JDK, Godot **4.6.2**가 필요하다. 저장소 루트에서 실행한다.

1. [공식 4.6.2 내보내기 템플릿](https://godotengine.org/download/archive/4.6.2-stable/)의 `templates/ios.zip`을 `godotIOS/build/templates/ios.zip`에 둔다. Apple Silicon 시뮬레이터를 쓴다면 아래 아키텍처 보완 여부를 먼저 확인한다.
2. Whisper 엔진과 Kotlin 프레임워크를 만들고 통합 Xcode 프로젝트를 생성한다.

```sh
bash native-whisper/setup.sh
python3 native-whisper/build_ios.py
env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :shared:assembleSharedDebugXCFramework --max-workers=2
# STUDY_TEAM_ID는 본인의 Apple 개발 팀 ID. 서명 없이 내보낼 때는 생략 가능.
env STUDY_TEAM_ID=YOUR_TEAM_ID python3 godotIOS/prepare_study_helper.py
```

Swift/Objective-C 호스트만 수정한 경우 `--skip-export`로 호스트와 프레임워크를 갱신한다. Kotlin 수정 시 프레임워크를 먼저 재빌드하고, Godot 수정 시 전체 내보내기를 실행한다. 생성된 프로젝트를 직접 수정하면 다음 내보내기에 덮어쓰인다.

3. Xcode에서 `godotIOS/build/study-export/StudyHelper.xcodeproj`를 열고 `StudyHelper` scheme으로 실행하거나 다음을 사용한다.

```sh
# 연결된 아이폰, 자동 개발 서명
xcodebuild -project godotIOS/build/study-export/StudyHelper.xcodeproj -scheme StudyHelper \
  -configuration Debug -destination 'id=YOUR_IPHONE_UDID' \
  -derivedDataPath godotIOS/build/study-device-derived \
  DEVELOPMENT_TEAM=YOUR_TEAM_ID CODE_SIGN_STYLE=Automatic \
  -allowProvisioningUpdates -allowProvisioningDeviceRegistration build

# Apple Silicon 시뮬레이터, 서명 생략
xcodebuild -project godotIOS/build/study-export/StudyHelper.xcodeproj -scheme StudyHelper \
  -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  ARCHS=arm64 -derivedDataPath godotIOS/build/study-derived CODE_SIGNING_ALLOWED=NO build
```

내보내기 프리셋의 `0000000000`은 서명 없는 내보내기에만 사용하는 자리 표시자이다. 실제 서명에는 본인의 팀을 지정한다. 현재 스크립트는 개발용 Debug 프레임워크를 조립하며 App Store 배포 설정은 별도 작업이다.

## Godot 4.6.2 Apple Silicon 시뮬레이터 보완

이번에 내려받은 공식 템플릿은 시뮬레이터 폴더 이름과 메타데이터에 ARM64가 있었지만,
실제 `libgodot.a`가 x86_64 단일 아키텍처였다. `lipo -info`로 확인했다.
해당 템플릿에서는 ARM64 링크 중 `_main` 누락과 아키텍처 불일치가 발생한다.
아래는 동일 4.6.2 소스로 ARM64 simulator 라이브러리를 추가하는 재현 절차다.
템플릿 자체에 ARM64가 있는 경우 이 단계는 생략한다.

```sh
git clone --depth 1 --branch 4.6.2-stable https://github.com/godotengine/godot.git godotIOS/build/godot-source
python3 -m venv godotIOS/build/venv
godotIOS/build/venv/bin/python -m pip install scons==4.11.1
cd godotIOS/build/godot-source
../venv/bin/scons platform=ios target=template_debug arch=arm64 simulator=yes \
  vulkan=no metal=no modules_enabled_by_default=no \
  module_gdscript_enabled=yes module_freetype_enabled=yes \
  module_text_server_adv_enabled=yes module_godot_physics_3d_enabled=yes module_godot_physics_2d_enabled=yes \
  module_svg_enabled=yes module_webp_enabled=yes module_msdfgen_enabled=yes debug_symbols=no -j8
cd ../../..
python3 godotIOS/prepare_simulator_template.py
```

이 프로젝트가 사용하는 Compatibility 렌더러·GDScript·폰트·3D 물리 모듈을 포함한다.
추가 엔진 기능을 도입하면 모듈 설정을 함께 갱신해야 한다.
실기기용 엔진과 기존 Intel 시뮬레이터 슬라이스는 그대로 유지한다.
완료 후 `prepare_study_helper.py`와 아래 Xcode 빌드를 실행한다.

## 설치·검증

```sh
xcrun simctl install booted godotIOS/build/study-derived/Build/Products/Debug-iphonesimulator/StudyHelper.app
xcrun simctl launch booted com.example.studyhelper.jaderun

# 실제 아이폰
xcrun devicectl device install app --device YOUR_DEVICE_ID \
  godotIOS/build/study-device-derived/Build/Products/Debug-iphoneos/StudyHelper.app
xcrun devicectl device process launch --device YOUR_DEVICE_ID com.example.studyhelper.jaderun
```

명시적인 Debug 통합 검증은 `-- --notebook-probe` 인자로 실행한다. 테스트 전용 서재·환경설정을 사용하며 사용자 노트와 문제 파일을 바꾸지 않는다. 시스템 파일 선택 창 표시와 기기 파일 저장·불러오기, 실제 Kotlin 채점, Godot 문제 화면, 게임 복귀·재진입·중도 종료를 검증한다. 파일 제공자의 개별 iCloud 다운로드나 모든 편집 동작을 자동 검사하는 UI 테스트는 아니다.

```sh
xcrun simctl terminate booted com.example.studyhelper.jaderun
xcrun simctl launch --console booted com.example.studyhelper.jaderun -- --notebook-probe
# 실기기는 devicectl device process launch --console ... BUNDLE_ID -- --notebook-probe
```

앱 데이터 `Documents/notebook-report.json`, `notebook-engine-{1,2}.json`, `notebook-{library,file-picker,game-1,game-2,return}.png`에 결과를 기록한다. 이전 결과와 혼동하지 않도록 로그의 `NOTEBOOK COMPLETE`와 수정 시간을 함께 확인한다. 검증 후 앱을 종료하고 인자 없이 다시 실행한다.

2026-09-27 확인:

- iPhone 17 Pro Max / iOS 26.6.2: 개발 서명·설치·실행 성공, 통합 검증 **19개 통과**.
- iPhone 17 Pro / iOS 26.4.1 Apple Silicon 시뮬레이터: 통합 검증 **19개 통과**.
- `shared:iosSimulatorArm64Test` **35개 통과**, 새 서재 호스트 테스트 5개 포함. Godot 러너 32개·학습 연결 16개 통과.
- [아이폰 보고서](../godot-runner/artifacts/iphone-notebook-report.json) · [서재](../godot-runner/artifacts/iphone-notebook-library.png) · [게임 문제](../godot-runner/artifacts/iphone-notebook-game.png) · [시뮬레이터 보고서](../godot-runner/artifacts/ios-notebook-report.json).

첫 게임 이후 엔진 메모리를 유지하므로 장시간 메모리·발열·배터리는 추가 측정 대상이다. 시뮬레이터의 OpenGL은 CPU 렌더링이어서 첫 셰이더 준비가 느리며, 해당 환경에서만 3D 비율 0.25·MSAA/그림자 끄기를 적용한다. UI 해상도와 실기기 그래픽은 유지한다.

이전 엔진 단독 시제품은 `iOS` export preset, `build_plugin.py`, `StudyBridge.mm`, `--study-probe`로 남아 있다. 새 서재 앱 실행에는 **iOS Study Helper** preset과 위 절차를 사용한다.
