"""Small fictional lecture data for tests; no personal course files required."""
import json
from pathlib import Path


def write_lecture(directory):
    directory=Path(directory)
    raw='테스트 원문입니다. 빨강과 파랑은 서로 다른 색입니다.\n'
    questions=[dict(id=f'q{i:02}',type='concept',prompt=f'색 구분 연습 {i}',choices=['빨강','파랑','초록'],correctIndex=0,explanation='테스트용 정답입니다.',sourceQuote='테스트 원문입니다.') for i in (1,2)]
    questions.append(dict(id='written',type='written',prompt='두 색의 차이를 쓰세요.',modelAnswer='서로 다른 색입니다.',rubric=['서로 다름을 설명한다'],explanation='가상 자료에 근거한 설명입니다.',sourceQuote='빨강과 파랑은 서로 다른 색입니다.'))
    guide=dict(key='fixture-1234567',title='가상 강의',subject='테스트과목',unit='6주차',summary='## 요약\n두 색을 구분한다. (자료 1)',sources=['테스트과목/6주차/fixture.txt'],quiz=dict(schemaVersion=2,title='색 구분',sourceNoteId='',sourceNoteTitle='가상 강의',questions=questions))
    (directory/'transcript.txt').write_text(raw)
    (directory/'course-pack.json').write_text(json.dumps({'guides':[guide]},ensure_ascii=False))
    (directory/'transcript.json').write_text(json.dumps({'transcription':[{'text':raw}]}))
    (directory/'transcript.srt').write_text('1\n00:00:00,000 --> 00:00:01,000\n'+raw)
    (directory/'audio.m4a').write_bytes(b'fixture audio; never decoded')
