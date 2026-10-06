---
name: study-lecture-workflow
description: Turn an online lecture video or audio into a transcript, study summary, and review questions using the study_helper project's local Whisper and library pipeline. Use for repeating the lecture-to-study-material workflow, including requested library registration.
---

# Study Helper 강의 정리

이 스킬은 `/Users/goseungmin/Desktop/WorkSpace/study_helper` 프로젝트의 강의 처리 도구를 사용한다. 다른 위치에 복사된 프로젝트에서는 `tools/lecture_pipeline.py`가 있는 루트를 먼저 확인한다.

1. 프로젝트의 `docs/LECTURE_PIPELINE.md`를 읽고 사용자가 요청한 범위까지 진행한다. 파일 또는 접근 가능한 직접 미디어 URL이 있으면 `tools/lecture_pipeline.py transcribe`를 사용한다. 기존 전사가 있다면 재인식하지 않고 재사용한다.
2. Canvas 강의 항목 URL은 영상 URL이 아니다. 플레이어만 있으면 Network의 실제 미디어 요청을 확인한다. Console의 unload 경고·검은 화면만으로 추출 차단을 판단하지 않는다. 쿠키나 인증 토큰을 메모·결과에 저장하지 않는다.
3. 전사 원본을 보존하며 요약한다. 화면만의 코드·표·수식을 추측하지 않는다. 오래된 강의 기준은 연도를 명시하고 불확실한 음성 인식은 정답 근거에서 제외한다.
4. 문제 생성 요청이 있으면 `docs/QUESTION_FILES.md`와 기존 `webApp/server.py`의 출제 규격을 따른다. 요약만이 아니라 원문에서 출제하고 `sourceQuote`는 원문 그대로 인용한다. 문항 수 미지정 시 자료가 충분하면 객관식 12개·서술형 3개를 기본안으로 사용할 수 있다.
5. `course-pack.json`에 요약·문제·과목·수업·고유 key·원문 경로를 기록하고 `register --check`로 기존 검증기를 실행한다. 기존 완성 예시는 `/Users/goseungmin/Desktop/2026-2/제작기록/2026-2/web-security-lecture/course-pack.json`이다. 이 예시의 과목·주차·ID를 다른 강의에 그대로 적용하지 않는다.
6. 사용자가 서재 등록까지 요청했거나 현재 작업 범위에 포함한 경우 `publish --directory <결과 폴더>`를 실행한다. 이는 서재 등록과 `~/Desktop/2026-2/학습` 게시를 함께 수행한다. 정확한 주차는 두 자리로 통일하고 미확인 강의는 `주차 확인 필요`에 둔다. 학습 폴더에는 음성·전사·자막, 별도 요약 Markdown, 문제 JSON을 저장하며 기존 파일과 내용이 다르면 덮어쓰지 않는다. 파일 기반 서재에서는 학습 폴더를 직접 읽고 수정하며 앱 DB에 본문 사본을 유지하지 않는다. Google 로그인 후 웹 서버가 실행 중이면 Drive와 자동 동기화한다. 모바일은 같은 Drive 폴더에서 지금 동기화를 사용한다. 자세한 범위는 `docs/GOOGLE_DRIVE_SYNC.md`를 확인한다. 기존 편집·풀이 기록은 유지한다. 같은 key는 재등록하지 않으며, 개정본에는 새 key와 필요한 경우 새 원문 경로를 쓴다.

기계적인 변환·검증·등록은 스크립트가 담당하고 요약·출제·내용 검토는 에이전트가 담당한다. 이 도구가 외부 생성 API를 자동 호출하는 것처럼 설명하지 않는다. 사용자가 앱의 기존 API 생성 방식을 선택한 경우 그 선택을 따른다.

결과와 실행 로그는 임시 폴더에만 두지 말고 강의별 결과 폴더에 남긴다. 최종 답변에는 결과 링크, 문항 수, 서재 위치, 실제 수행한 검증과 인식 한계를 간결하게 알린다.
