# 2026-2 학습 자료 가져오기 예제

- **[디지털시스템입문 1~2주차: 노트 2개 + 문제 24문항](digital-systems/README.md)** — 아이폰 서재에 추가한 샘플

## AI와 정보보안 샘플

- 읽기용 노트: [AI와 정보보안 1주차 · CIA](ai-security-week01-cia.md)
- 독립된 문제 파일: [CIA 사례형 3문제](ai-security-week01.quiz.json)
- 원문 출처: `Desktop/2026-2/AI와정보보안/1주차/1주차_정보보안_요약.md`

원본은 수정하지 않았다. 마크다운 사본은 원문 본문을 유지하고 제목에 과목 이름을 붙였다. 이전 생성기 검증에서 추가했던 `복습 개념` 부분도 남아 있지만, 현재 앱은 이를 게임 입력으로 추출하지 않는다. 원본처럼 해당 부분이 없는 일반 마크다운도 그대로 가져와 읽을 수 있다.

문제 JSON은 원문의 CIA 정의를 근거로 이번 작업에서 작성한 사례형 문항이다. 외부 AI API 호출 결과가 아니다. 각 문항에 정답·해설·실제 원문 인용이 들어 있다. `sourceNoteId`는 다른 기기에서도 가져오도록 비워 두었다. 앱에서 복사한 프롬프트에는 해당 기기의 노트 ID가 들어가므로 AI가 이를 유지하면 미리보기에서 원본 노트로 이동할 수 있다.

## Android 에뮬레이터에서 사용

프로젝트 디렉터리에서 두 파일을 Download 폴더로 복사한다.

```sh
~/Library/Android/sdk/platform-tools/adb -e push \
  study-materials/2026-2/ai-security-week01-cia.md \
  /sdcard/Download/ai-security-week01-cia.md
~/Library/Android/sdk/platform-tools/adb -e push \
  study-materials/2026-2/ai-security-week01.quiz.json \
  /sdcard/Download/ai-security-week01.quiz.json
```

1. **노트 서재 → 가져오기**에서 `.md`를 선택한다. 게임 없이 읽기·편집·검색할 수 있다. 이전에 가져온 노트가 있다면 서재에서 연다.
2. **문제 모음 → 문제 파일 가져오기**에서 `.quiz.json`을 선택한다.
3. 미리보기에서 문제·보기·정답·해설·근거를 확인한다.
4. **이 문제로 게임 시작**을 누르면 세 문제의 순서와 보기가 섞여 표시된다.

다른 자료로 문제를 만들 때는 노트의 **더 보기 → 문제 생성 프롬프트**를 복사해 원하는 AI에 붙여넣고 응답을 JSON으로 저장한다. [문제 파일 규격](../../docs/QUESTION_FILES.md)을 참고한다. PDF·이미지·Obsidian 링크 대상은 이 예제에 포함하지 않았다.

2026-09-27: Pixel 4 / Android 37에서 노트 읽기, 문제 파일 가져오기, 사례형 문제 표시와 Kotlin 채점을 확인했다. [문제 미리보기](../../godot-runner/artifacts/android-quiz-preview.png) · [게임 문제](../../godot-runner/artifacts/android-question-file-game.png) · [정답 해설](../../godot-runner/artifacts/android-question-file-feedback.png)
