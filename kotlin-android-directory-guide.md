# 코틀린 Android 개발 디렉토리 구조

Android Studio에서 Kotlin으로 Android 앱을 개발하는 기준으로 정리한 문서다. Kotlin 언어 자체가 강제하는 구조는 아니며, 선택한 템플릿과 프로젝트 설정에 따라 일부 경로가 달라질 수 있다.

## 1. 프로젝트 전체 구조

아래 경로는 프로젝트 최상위 폴더를 기준으로 한다.

| 경로·파일 | 역할 |
| --- | --- |
| `app/` | 실제 Android 앱을 구성하는 모듈 |
| `app/src/main/` | 앱에 포함되는 소스 코드와 리소스 |
| `app/src/test/` | PC의 JVM에서 실행하는 단위 테스트 |
| `app/src/androidTest/` | 에뮬레이터 또는 실제 기기에서 실행하는 테스트 |
| `app/build.gradle.kts` | 앱 모듈의 라이브러리, SDK 버전 등 빌드 설정 |
| `build.gradle.kts` | 프로젝트 공통 플러그인 등 설정 |
| `settings.gradle.kts` | 프로젝트에 포함할 모듈과 저장소 설정 |
| `gradle/libs.versions.toml` | 버전 카탈로그를 사용하는 프로젝트의 라이브러리·플러그인 버전 관리 |
| `gradle/wrapper/` | 프로젝트에서 사용할 Gradle 배포판 정보와 실행 구성 |
| `gradlew`, `gradlew.bat` | 프로젝트에 지정된 Gradle을 실행하는 스크립트 |
| `local.properties` | 내 컴퓨터의 Android SDK 경로 등 로컬 설정 |
| `build/`, `app/build/` | 빌드 과정에서 자동 생성되는 결과물 |

`build/` 내부는 보통 직접 수정하지 않는다. `local.properties`는 컴퓨터별 설정이므로 일반적으로 Git에 포함하지 않는다.

## 2. 앱 코드와 리소스 위치

아래 경로는 `app/src/main/`을 기준으로 한다.

| 경로·파일 | 넣는 내용 |
| --- | --- |
| `java/com/example/myapp/` 또는 `kotlin/com/example/myapp/` | Kotlin 소스 코드 |
| `AndroidManifest.xml` | 앱 구성, Activity 등록, 인터넷 등 권한 선언 |
| `res/drawable/` | 이미지와 벡터 그래픽 |
| `res/mipmap-*/` | 앱 실행 아이콘 |
| `res/values/strings.xml` | 앱에서 사용하는 문자열 |
| `res/values/` | 색상, 테마 등 XML 리소스 |
| `res/layout/` | XML 방식의 화면 레이아웃 |
| `assets/` | JSON 등 원본 파일명과 경로로 읽을 파일. 필요할 때 생성 |

### Kotlin 코드가 java 폴더에 있어도 되는가?

가능하다. Android 프로젝트에서는 `java/` 폴더에 `.kt` 파일을 넣는 것이 일반적이다. Kotlin을 사용한다고 폴더 이름을 반드시 `kotlin/`으로 바꿀 필요는 없다.

`com/example/myapp/`은 예시 패키지 경로다. 해당 파일의 패키지 선언은 다음과 같다.

```kotlin
package com.example.myapp
```

### Compose와 XML 화면의 차이

| 방식 | 화면 작성 위치 | 예시 |
| --- | --- | --- |
| Jetpack Compose | Kotlin 소스 파일 | `ui/home/HomeScreen.kt` |
| XML 기반 View | XML 레이아웃과 Kotlin 코드 | `res/layout/activity_main.xml`, `MainActivity.kt` |

Compose를 사용하면 `res/layout/`이 없을 수 있다. 이미지와 문자열 등은 Compose에서도 `res/`에 둘 수 있다.

## 3. 작은 앱의 권장 코드 구조

아래는 Compose를 사용하는 작은 앱의 구성 예시다. 경로는 `app/src/main/java/com/example/myapp/`을 기준으로 한다. 각 폴더는 필요한 기능이 생길 때 추가한다.

| 경로 | 파일 예시 | 역할 |
| --- | --- | --- |
| 기본 패키지 | `MainActivity.kt` | 앱 실행 시 화면을 띄우는 진입 Activity |
| `ui/home/` | `HomeScreen.kt`, `HomeViewModel.kt` | 홈 화면과 화면 상태·처리 로직 |
| `ui/profile/` | `ProfileScreen.kt`, `ProfileViewModel.kt` | 프로필 화면과 관련 로직 |
| `ui/components/` | `InterestChip.kt` | 여러 화면에서 사용하는 공통 UI |
| `ui/theme/` | `Color.kt`, `Theme.kt`, `Type.kt` | Compose 색상, 테마, 글꼴 스타일 |
| `data/model/` | `User.kt`, `Interest.kt` | 데이터 구조 |
| `data/repository/` | `UserRepository.kt` | 데이터를 읽고 저장하는 기능 제공 |
| `data/remote/` | `UserApi.kt` | 서버 API 통신 |
| `data/local/` | `AppDatabase.kt` | 기기 내부 데이터 저장 |
| `navigation/` | `AppNavHost.kt` | 화면 간 이동 구성 |

### 역할 구분 예시

사용자가 프로필 화면에서 저장 버튼을 누르는 경우 다음처럼 역할을 나눌 수 있다.

1. `ProfileScreen.kt`에서 입력과 버튼 클릭을 받는다.
2. `ProfileViewModel.kt`에서 입력을 검증하고 저장을 요청한다.
3. `UserRepository.kt`에서 저장할 데이터와 저장소를 연결한다.
4. 서버 저장은 `data/remote/`, 기기 내부 저장은 `data/local/`의 구현이 처리한다.
5. ViewModel이 저장 결과에 따라 화면 상태를 갱신한다.

화면 파일에 UI, 서버 통신, 데이터 저장 코드를 모두 넣지 않으면 수정할 범위를 파악하기 쉽다.

## 4. 작업별 수정 위치

| 하려는 작업 | 주로 확인할 위치 |
| --- | --- |
| 화면 추가·수정 | `ui/` |
| 화면 상태와 버튼 동작 수정 | 해당 화면의 `ViewModel` |
| 여러 화면에서 쓰는 버튼·카드 추가 | `ui/components/` |
| 앱 색상·글꼴 스타일 변경 | `ui/theme/` |
| 데이터 항목 정의 | `data/model/` |
| 데이터 조회·저장 기능 추가 | `data/repository/` |
| 서버 통신 추가 | `data/remote/` |
| 로컬 데이터베이스 추가 | `data/local/` |
| 화면 이동 추가 | `navigation/` |
| 이미지 추가 | `app/src/main/res/drawable/` |
| 문자열 추가 | `app/src/main/res/values/strings.xml` |
| 권한 선언 | `app/src/main/AndroidManifest.xml` |
| 외부 라이브러리 추가 | `app/build.gradle.kts`, 필요하면 `gradle/libs.versions.toml` |

## 5. 처음 시작할 때의 범위

처음에는 `MainActivity.kt`, `ui/`, `data/` 정도로 시작하면 충분하다.

- 화면이 늘어나면 `ui/home/`, `ui/profile/`처럼 화면별로 나눈다.
- 공통 UI가 생기면 `ui/components/`로 분리한다.
- 서버나 로컬 저장이 필요해지면 `data/remote/`, `data/local/`을 추가한다.
- 여러 화면을 연결할 때 `navigation/`을 추가한다.

## 6. Android Studio에서 실제 구조 확인

왼쪽 프로젝트 창의 **Android** 보기는 관련 파일을 묶어 보여주는 논리적 보기다. 실제 디스크에 저장된 디렉토리 구조를 확인하려면 프로젝트 창 상단의 보기 선택을 **Project**로 변경한다.
