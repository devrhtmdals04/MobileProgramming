# 실시간 강의 받아쓰기 후보

2026-09-27 조사. **iOS 26 한국어 실시간 받아쓰기와 수정 후 노트 저장을 연결했다. Android는 녹음·재생만 지원한다.**

## 연결된 iPhone의 실제 지원 상태

iPhone 17 Pro Max / iOS 26.6.2에서 SpeechTranscriber를 직접 조회했다.

```json
{"available":true,"koreanInstalled":["ko_KR"],"koreanSupported":["ko_KR"]}
```

마이크를 켜지 않았고 모델을 다운로드하지 않았다. 이는 한국어 모델 사용 가능성 검증이며 실제 강의의 인식률·실시간 처리 속도 검증은 아니다.

## 비교

| 후보 | 처리 위치 | 장점 | 구현 및 검증할 점 |
| --- | --- | --- | --- |
| Apple SpeechAnalyzer + SpeechTranscriber | iPhone 내부 | 현재 기기에 한국어 모델 설치됨. 긴 녹음·실시간 전사용 API | iOS 26 이상 분기, 기기·언어 지원 검사, 실제 강의 정확도 측정 |
| whisper.cpp | iPhone / Android 내부 | MIT 오픈소스, 양 플랫폼 지원, 서버 없이 동작 가능 | 모델 다운로드·메모리·발열·배터리·지연을 측정해야 함. 스트리밍 예제를 제품 수준으로 보완 |
| whisper.cpp를 Mac/서버에서 실행 | 사용자의 Mac 또는 별도 서버 | 폰의 추론 부담을 줄일 수 있는 구성 | 앱↔서버 오디오 스트림·인증·접속 복구 구현 필요. 서버가 켜져 있어야 함 |
| Deepgram Nova-3 | 외부 API | 한국어 지원 및 WebSocket 실시간 인식 API | 인터넷·분당 비용·음성 외부 전송. 키는 앱에 하드코딩하지 않고 서버/단기 자격증명 사용 |
| Android 시스템 온디바이스 SpeechRecognizer | 지원 Android 기기 내부 | 지원 기기에서 별도 엔진을 번들링하지 않아도 됨 | 기기별 인식 서비스·한국어 모델 설치 지원 차이. 장시간 연속 강의 처리·오디오 캡처 통합은 별도 검증 필요 |

추천 순서는 **연결된 iPhone 내장 한국어 인식 → 실제 강의 정확도 평가 → 필요하면 오픈소스 또는 온라인 방식 비교**다. 온라인 방식으로 자동 전환하거나 음성을 외부에 전송하지 않는다.

## 구현 구조

- 마이크 캡처 한 곳에서 녹음 파일 저장과 인식 입력으로 오디오를 분기한다. iOS 26에서는 AVAudioEngine 입력을 공유하며 별도의 인식기를 동시에 마이크에 붙이지 않는다.
- iOS 26은 AVAudioEngine PCM 캡처와 AAC 파일 인코딩, SpeechAnalyzer 입력을 통합했다. 인식이 실패해도 원본 녹음은 유지한다.
- 화면에서 임시 문장과 확정 문장을 구분하고, 확정 문장에 시간 정보를 저장한다. 중간 결과 갱신을 문장 추가로 잘못 처리하지 않도록 한다.
- 종료 후 사용자가 받아쓰기 내용을 수정하고 **마크다운 노트로 저장**한다.
- 요약·문제 생성은 받아쓰기에 이어지는 별도 단계다. 기존 노트→프롬프트→문제 JSON 흐름을 유지한다.

## 공식 자료

- [Apple WWDC25: SpeechAnalyzer, on-device live/long-form transcription](https://developer.apple.com/videos/play/wwdc2025/277/)
- [Apple SpeechTranscriber: runtime device and locale checks](https://developer.apple.com/documentation/speech/speechtranscriber)
- [whisper.cpp: MIT, iOS/Android, memory table and stream example](https://github.com/ggml-org/whisper.cpp)
- [Deepgram models and Korean support](https://developers.deepgram.com/docs/models-languages-overview)
- [Deepgram live WebSocket API](https://developers.deepgram.com/reference/speech-to-text/listen-streaming)
- [Deepgram pricing](https://deepgram.com/pricing) — 조회 당시 Nova-3 Monolingual streaming PAYG 프로모션 $0.0048/분, 정규 표시 $0.0077/분. 추가 기능·서버 비용 제외. 가격은 도입 시 다시 확인.
- [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)

실기기 합성 한국어 음성 검사에서 실제 전사와 Kotlin 노트 저장을 확인했다. 예문 “영과 일을”이 “영광이를”로 인식되는 오류도 확인하여 편집 단계를 유지한다. 이 결과는 실제 강의의 정확도를 보장하지 않는다. 인식 결과 전문은 [검사 보고서](artifacts/lecture-transcription-report.json)에 보관한다.

## 오픈소스 후처리 추가

기존 iPhone 실시간 받아쓰기와 별도로, 저장된 녹음을 Mac의 Whisper large-v3 Q5_0에서 다시 변환할 수 있다. 실기기 업로드·변환·원본 보존·Kotlin 노트 저장을 확인했다. [설정과 제한](LOCAL_WHISPER.md), [기기 검사 결과](artifacts/lecture-local-report.json).

## 기본 경로: 모바일 기기 단독 처리

현재 기본 경로는 [iPhone·Android 오프라인 Whisper](DEVICE_WHISPER.md)다. 녹음 후 각 휴대폰에서 small Q5_1을 실행하며, Mac 서버는 필수가 아니다.
