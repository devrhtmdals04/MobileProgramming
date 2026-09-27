# 실시간 강의 받아쓰기 후보

2026-09-27 조사. **녹음·저장·재생은 구현했으며 실시간 받아쓰기는 아직 연결하지 않았다.**

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

## 다음 구현 구조

- 마이크 캡처 한 곳에서 녹음 파일 저장과 인식 입력으로 오디오를 분기한다. 현재 AVAudioRecorder / MediaRecorder와 별도의 인식기를 동시에 마이크에 붙이지 않는다.
- iOS는 AVAudioEngine 등의 PCM 캡처와 파일 인코딩, SpeechAnalyzer 입력을 통합하는 방향으로 변경한다. 인식이 실패해도 원본 녹음은 유지한다.
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
