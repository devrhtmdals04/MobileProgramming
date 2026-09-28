# Google Drive와 PC 공부 폴더

Study Helper의 iOS·Android·PC 앱에서 같은 Google 계정과 같은 Drive 폴더를 선택하면 마크다운 노트와 문제 JSON을 동기화할 수 있다. 서버를 별도로 운영하지 않는다. Google Cloud OAuth 설정은 사용자가 소유한 프로젝트에서 한 번 진행해야 한다. 설정되지 않은 앱은 로컬 학습 기능을 그대로 제공하고 Google 연결 시 설정 안내를 표시한다.

## 현재 동작

- **공부 폴더 공유**에서 PC의 공부 폴더와 Google Drive 공유 폴더를 각각 한 번 선택한다. 모바일은 같은 Drive 폴더를 선택한다.
- **지금 동기화** 한 번으로 선택한 폴더의 추가·수정·삭제를 양방향으로 반영한다. 별도의 PC 폴더 동기화 버튼은 사용하지 않는다.
- 지원 파일은 `.md`, `.json`, `.pdf`, `.png`, `.jpg`, `.jpeg`, `.svg`, `.gif`, `.webp`, `.m4a`, `.wav`, `.mp3`, `.aac`, `.flac`, `.txt`이다. 파일이 있는 하위 폴더 경로를 유지한다. 코드 프로젝트와 숨김 파일은 대상에서 제외한다. 빈 디렉터리 자체의 생성·삭제는 동기화하지 않는다.
- **변경사항 확인**은 Drive를 변경하지 않고 추가·수정·삭제·충돌 목록을 보여준다. 앱에서 편집한 노트는 비교를 위해 기기의 공유 폴더에 먼저 저장될 수 있다.
- **저장한 녹음 올리기**는 녹음을 마친 파일을 공유 폴더의 `녹음/`에 복사하고 동기화한다. 제목과 녹음 ID를 파일명에 포함하며 원본 녹음은 유지한다. 같은 녹음을 반복해서 올리지 않도록 기록한다.
- 첫 연결 때 기존 서재 전체를 자동 업로드하지 않는다. 필요한 경우 **기존 서재 노트도 올리기**를 누른다. 최초 동기화 이후 생성한 노트와 문제는 `모바일/노트`, `모바일/문제`에 저장한다. 이미 폴더에서 받은 노트는 원래 경로에 편집 내용을 반영한다.
- 모바일에서 받은 자료는 앱 내부 `shared-folders/<계정>_<Drive 폴더 ID>/`에 보관한다. Markdown과 유효한 문제 JSON은 서재에도 연결된다. PDF·그림·녹음은 폴더 자료 목록에서 열 수 있다.
- 비어 있는 Drive 폴더에는 아직 자료가 없다는 설명과 폴더 선택 안내를 표시한다. 동기화 후 파일별 변경 내역을 보여주고 마지막 완료 내역을 기기에 저장한다.
- 수동 동기화이며 앱 종료 중 백그라운드 전송은 제공하지 않는다. 클라이언트가 선택한 폴더 전체를 조회하고 파일 해시를 비교한다. Google Drive Changes API나 실시간 알림 기반 방식은 아니다.

## Google Cloud 준비

1. [Google Cloud Console](https://console.cloud.google.com/)에서 프로젝트를 만들거나 선택하고 **Google Drive API**를 사용 설정한다.
2. Google Auth Platform에서 앱 이름·연락처·대상을 설정한다. 개인 개발 중에는 테스트 상태로 두고 사용할 Google 계정을 테스트 사용자에 추가한다.
3. 데이터 액세스 범위에 `https://www.googleapis.com/auth/drive`를 추가한다. 이 범위는 기존 폴더를 재귀적으로 탐색하고 기존 문서를 편집하기 위해 사용한다. Google 화면에서는 전체 Drive 접근으로 표시된다. 앱 자체는 선택한 폴더만 동기화한다.
4. 같은 프로젝트에 플랫폼별 OAuth 클라이언트를 만든다.

| 플랫폼 | 클라이언트 종류 | 설정 |
|---|---|---|
| PC (macOS/Windows/Linux) | 데스크톱 앱 | 기본 ID는 앱에 포함. 다른 클라이언트나 추가 인증 설정이 필요하면 JSON 다운로드 → PC 설정에서 **Google OAuth JSON 선택 (선택)** |
| iOS | iOS | 번들 ID `com.example.studyhelper.jaderun`, 필요한 Apple 팀 정보 입력 → 클라이언트 ID를 아래 빌드 환경변수로 전달 |
| Android | Android | 패키지 `com.example.study_helper.jaderun`, 설치 APK의 서명 인증서 SHA-1 등록 |

OAuth JSON이나 토큰을 저장소에 커밋하지 않는다. `client_secret_*.json`·`google-oauth*.json`은 gitignore에 포함한다. PC는 OAuth 설정 파일의 경로만 앱 설정에 저장하고 액세스/리프레시 토큰은 메모리에만 보관한다. PC 앱을 다시 실행하면 브라우저 로그인이 다시 필요할 수 있다. iOS 리프레시 토큰은 기기 Keychain에 저장하며 Android는 Google Play services의 인증 캐시를 사용한다. 로그아웃은 앱 연결 상태를 해제하며 Google 계정에서 기존 권한 자체를 철회하려면 Google 계정의 연결된 앱 설정을 사용한다.

`drive`는 Google의 제한된 범위이다. 테스트 계정으로 개발할 수 있으나 다른 사람에게 공개 배포할 때에는 Google의 OAuth 검증 요건을 확인해야 한다. 테스트 상태에서는 리프레시 토큰이 만료될 수 있으므로 재로그인 경로를 유지한다.

참고: [iOS·데스크톱 OAuth/PKCE](https://developers.google.com/identity/protocols/oauth2/native-app), [Android 인증](https://developer.android.com/identity/authorization), [Drive 범위](https://developers.google.com/workspace/drive/api/guides/api-specific-auth).

## iOS 빌드

현재 아이폰용 공개 클라이언트 ID는 `godotIOS/google_client_id.txt`에 설정되어 있다. 준비 스크립트가 이를 읽으므로 사용자가 ID나 JSON을 앱에 입력할 필요는 없다. 다른 Google 프로젝트로 빌드할 때 아래 환경변수로 재정의한다. 클라이언트 ID 포함 여부와 실제 Google 로그인·동기화 성공 여부는 별도로 확인한다.

```sh
env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :shared:assembleSharedDebugXCFramework --max-workers=2
STUDY_GOOGLE_IOS_CLIENT_ID='YOUR_IOS_CLIENT_ID.apps.googleusercontent.com' \
  python3 godotIOS/prepare_study_helper.py --skip-export
```

그 뒤 [기존 Xcode 빌드 절차](../godotIOS/README.md)를 따른다. 준비 스크립트가 Info.plist에 클라이언트 ID 및 역순 URL scheme을 추가한다. 웹/데스크톱 클라이언트 ID를 iOS에 넣으면 안 된다. 최초로 생성하는 Xcode 프로젝트에는 `--skip-export`를 생략한다.

## Android 빌드

Google Play services가 있는 기기에서 동작한다. OAuth 클라이언트에는 개발용 debug 서명과 출시 서명을 각각 등록해야 한다.

```sh
./gradlew :godotAndroid:signingReport
./gradlew :godotAndroid:assembleDebug
```

## PC 앱과 Desktop/2026-2

1. **공부 폴더 공유 → 올릴 폴더 선택**에서 `~/Desktop/2026-2/학습`을 선택한다. `실습/`은 공유 대상에 넣지 않는다.
2. **Google Drive에서 폴더 선택**으로 전용 폴더를 선택하고 **이 폴더로 공유**를 누른다.
3. **지금 동기화**를 누른다. PC 폴더의 PDF·노트 등이 원래 상대 경로로 업로드된다.
4. 아이폰·Android에서 같은 Google 계정과 같은 폴더를 선택하고 **지금 동기화**로 받는다.
5. 모바일에서 노트를 편집했으면 **지금 동기화**, 녹음 원본을 보낼 때는 **저장한 녹음 올리기**를 누른다. PC에서도 **지금 동기화**하면 같은 공부 폴더에 받는다.

PC의 **폴더 열기**는 사용자가 선택한 공부 폴더를 연다. OAuth JSON·녹음 도구 설정은 별도의 **설정**에 두고, 앱 내부 경로는 **고급: 앱 저장 폴더 열기**로 구분한다. 기존의 Google Drive 데스크톱 클라이언트가 같은 폴더를 동시에 동기화하게 구성하지 않는다.

## 보존과 충돌 처리

새 폴더 동기화는 마지막 성공한 해시·기기 파일·Drive 파일을 비교한다. 양쪽이 서로 다르게 변경되거나 삭제와 수정이 충돌하면 원본을 유지하고 충돌로 표시한다. 기기 서재와 외부 파일의 편집이 충돌한 경우 앱 편집 내용을 별도 충돌 노트로 보존한다.

한쪽에서만 삭제하고 다른 쪽은 이전 내용 그대로라면 삭제를 반영한다. Drive 파일은 휴지통으로 옮기며 기기 파일은 앱 내부 `folder-history/<작업 ID>/`에 사본을 보관한 뒤 공유 폴더에서 제거한다. 덮어쓰는 기기 파일도 이전 사본을 보관한다. 연결된 서재 문서 역시 사본을 보관하고 목록에서 제거한다. 기존 녹음 목록의 원본은 공유 파일 삭제와 별개다.

목록 조회가 실패하면 Drive 변경을 시작하지 않는다. 원격 수정·삭제에는 메타데이터 ETag 조건을 적용한다. 다운로드의 `alt=media` 응답에는 메타데이터 ETag를 보내지 않고, 받은 바이트의 MD5를 목록의 값과 비교한 뒤 로컬 변경 여부를 재확인하여 교체한다. 메타데이터 ETag를 미디어 요청에 재사용하면 HTTP 412로 전체 동기화가 중단될 수 있다. 성공한 파일마다 기준을 저장하며, 중단 시 파일 경로와 오류를 화면 및 로컬 진행 기록에 남긴다. 녹음 등 큰 파일은 네이티브 파일 스트림과 Google resumable 업로드 세션으로 전송한다. 현재 세션의 오프셋을 영구 저장하지 않으므로 앱이 종료된 전송은 다음 실행에서 처음부터 재시도한다. 성공 응답만 유실된 경우 다음 조회에서 경로와 해시로 완료 여부를 확인한다.

같은 이름의 Drive 파일, 대소문자만 다른 경로, 경로 이탈, 지원하지 않는 이름은 오류로 알린다. 폴더가 바뀌면 별도의 기준을 사용한다. 기존 평면 동기화의 기록은 보존되지만 새 폴더 동기화가 예전 UUID 파일을 자동 정리하거나 삭제하지는 않는다.

## 검증 범위

`FolderMirrorTest`는 임시 기기 폴더와 모의 Drive로 경로 유지, PDF·녹음 바이트 왕복, 모바일 노트 수정, 삭제와 보관, 동시 수정 충돌, 불완전한 목록, 전송 응답 유실 복구, 변경사항 미리보기, 경로 이탈 방지를 검사한다. Swift 저장 코드도 임시 폴더에서 해시·경합·보관 처리를 검사한다.

`FolderDownloadTest`는 실제 JVM 파일 전송 코드에 HTTP 응답을 주입해 메타데이터 ETag가 미디어 요청으로 전달되지 않는지, 체크섬 불일치 및 다운로드 도중 로컬 수정 시 원본을 유지하는지 검사한다. 중단 상태가 화면을 다시 열어도 유지되고 재시도로 해제되는 회귀 테스트도 포함한다. 2026-09-28 수정 기준 JVM 테스트는 desktop 13개, shared 54개 통과했다.

실제 계정 로그인은 사용자가 PC와 iPhone 모두 확인했다. 2026-09-28 macOS 실계정 동기화에서 미디어 요청의 HTTP 412를 수정한 후 130개 변경사항 반영과 총 139개 파일 처리를 확인했다. 이어 Drive 전체 목록을 다시 조회해 남은 변경사항 없이 최신 상태임을 확인했다. iPhone 수정 빌드는 2026-09-28 14:01 KST 연결된 기기에 설치하고 실행했다. 14:07 KST iPhone의 실제 Drive 다운로드 139개 완료 기록을 확인했고, 다운로드 후 저장된 139개 콘텐츠 해시가 PC의 동기화 기록과 모두 일치했다. 모바일 녹음 업로드의 실제 서버 왕복은 아직 별도 검증이 필요하다. 모의 테스트를 실제 연동 완료로 간주하지 않는다.
