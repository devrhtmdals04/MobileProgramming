import json
from pathlib import Path
import tempfile
import unittest
import test_server as fixtures
from test_server import quiz
from server import Store, request_body, validate_quiz


class GuideTests(unittest.TestCase):
    setUp = fixtures.LocalWebTest.setUp
    tearDown = fixtures.LocalWebTest.tearDown
    call = fixtures.LocalWebTest.call
    document = fixtures.LocalWebTest.document
    generate = fixtures.LocalWebTest.generate
    def guide(self):
        source = self.document()
        self.app.store.update(source, subject='보안')
        note = self.app.store.add('필기', 'note.md', original_text='원본 필기', summary='편집된 필기')
        self.app.store.update(note, subject='보안')
        guide = self.app.store.add_guide('test-guide','주차 정리','보안','1주차','## 한 줄 요약\n정리',quiz(),[source,note])
        return guide, source, note

    def test_guide_links_and_reinstall_preserve_user_work(self):
        guide, source, note = self.guide()
        self.app.store.update(guide, summary='사용자가 수정함')
        self.app.store.record(guide, {'q0':1})
        repeated = self.app.store.add_guide('test-guide','다른 제목','보안','1주차','바꿀 내용',quiz(),[source,note])
        self.assertEqual(repeated,guide)
        self.assertEqual(self.app.store.get(guide)['summary'],'사용자가 수정함')
        self.assertEqual(len(self.app.store.attempts(guide)),1)
        status, body = self.call('/api/lessons/'+guide)
        self.assertEqual(status,200)
        result=json.loads(body)
        self.assertTrue(result['is_guide'])
        self.assertEqual([s['id'] for s in result['sources']],[source,note])
        self.assertTrue(result['sources'][0]['has_pdf'])
        self.assertNotIn('pdf',result['sources'][0])
        self.assertTrue(next(l for l in self.app.store.list() if l['id']==guide)['is_guide'])

    def test_regeneration_uses_linked_originals(self):
        guide, source, note = self.guide()
        self.app.key='fake'
        self.events=[{'type':'response.output_text.delta','delta':'## 학습 목표\n새 정리'},{'type':'response.completed','response':{'status':'completed'}}]
        self.generate(guide)
        body=self.calls[0][0]
        content=body['input'][0]['content']
        self.assertEqual(len([item for item in content if item['type']=='input_file']),1)
        text='\n'.join(item.get('text','') for item in content)
        self.assertIn('원본 필기',text)
        self.assertNotIn('편집된 필기',text)
        self.assertIn('(자료 N · PDF M쪽)',text)
        self.assertEqual(self.app.store.get(note)['summary'],'편집된 필기')

    def test_invalid_sources_do_not_create_partial_guide(self):
        count=len(self.app.store.list())
        with self.assertRaises(ValueError):
            self.app.store.add_guide('bad','정리','보안','1주차','내용',quiz(),['missing'])
        self.assertEqual(len(self.app.store.list()),count)

    def test_course_pack_checks_quotes_before_installing(self):
        from install_guides import prepare, install
        store=self.app.store
        q=quiz()
        raw=q['questions'][0]['sourceQuote']
        source=store.add('가상 원문','fixture.txt',original_text=raw,source_path='테스트/01주차/fixture.txt')
        pack={'guides':[dict(key='fictional-pack',title='가상 정리',subject='테스트',unit='01주차',summary='## 핵심 개념\n기밀성 (자료 1)',sources=['테스트/01주차/fixture.txt'],quiz=q)]}
        ids=install(store,prepare(store,pack))
        self.assertEqual(len(ids),1)
        self.assertEqual(store.sources(ids[0])[0]['id'],source)
        q['questions'][0]['sourceQuote']='원문에 없는 인용'
        with self.assertRaises(ValueError):prepare(store,pack)
