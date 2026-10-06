import base64
import http.client
import json
import sqlite3
from pathlib import Path
import sys
import tempfile
import threading
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from server import App, Store, request_body, validate_quiz


def quiz():
    return {'schemaVersion': 2, 'title': '보안 복습', 'sourceNoteId': '', 'sourceNoteTitle': '보안 강의', 'questions': [
        {'id': f'q{i}', 'type': 'concept', 'prompt': f'보안 개념 {i}', 'choices': ['기밀성', '무결성', '가용성'],
         'correctIndex': 0, 'explanation': '허가되지 않은 열람을 막습니다. (PDF 2쪽)', 'sourceQuote': '기밀성은 허가되지 않은 열람을 막는다.'} for i in range(5)]}


class LocalWebTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.events = []
        self.calls = []
        def provider(body, key):
            self.calls.append((body,key))
            yield from self.events
        self.app = App(('127.0.0.1', 0), self.temp.name, provider)
        self.app.key = ''
        self.thread = threading.Thread(target=self.app.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.app.shutdown(); self.app.server_close(); self.thread.join(); self.temp.cleanup()

    def call(self, path, data=None, headers=None):
        connection = http.client.HTTPConnection('127.0.0.1', self.app.server_port)
        actual = {'X-Study-Token': self.app.token, 'Content-Type':'application/json'}
        if headers: actual.update(headers)
        connection.request('GET' if data is None else 'POST', path, None if data is None else json.dumps(data), actual)
        response = connection.getresponse(); body = response.read(); status = response.status
        connection.close()
        return status, body

    def document(self):
        status, raw = self.call('/api/import', {'filename':'강의.pdf','base64':base64.b64encode(b'%PDF-1.4 test document').decode()})
        self.assertEqual(status,200)
        return json.loads(raw)['id']

    def generate(self, id, action='summary'):
        return self.call('/api/generate', {'id':id,'action':action,'job':'test-job','count':5})

    def test_open_folder_uses_saved_link_and_requires_token(self):
        from unittest.mock import patch
        id = self.document()
        status, _ = self.call('/api/open-folder', {'id':id})
        self.assertEqual(status, 400)
        with self.app.store.connect() as db:
            db.execute('INSERT INTO local_materials VALUES (?,?,?,?)', (id,self.temp.name,'',''))
        with patch('server.subprocess.run') as launch, patch('server.sys.platform','darwin'):
            status, _ = self.call('/api/open-folder', {'id':id,'folder':'/untrusted'})
            self.assertEqual(status, 200)
            self.assertEqual(launch.call_args.args[0], ['open','--',self.temp.name])
            status, _ = self.call('/api/open-folder', {'id':id}, {'X-Study-Token':'wrong'})
            self.assertEqual(status, 403)
            self.assertEqual(launch.call_count, 1)
        status, body = self.call('/api/lessons/'+id)
        self.assertEqual(json.loads(body)['local_folder'], self.temp.name)

    def test_no_key_means_no_provider_request(self):
        id=self.document(); status,_=self.generate(id)
        self.assertEqual(status,400); self.assertEqual(self.calls,[])

    def test_completed_summary_persists_and_pdf_remains_readable(self):
        id=self.document(); self.app.key='fake-key'
        self.events=[{'type':'response.output_text.delta','delta':'## 핵심 개념\n\n기밀성'}, {'type':'response.completed','response':{'status':'completed'}}]
        status,body=self.generate(id)
        self.assertEqual(status,200); self.assertIn(b'"type":"done"',body)
        self.assertEqual(Store(self.temp.name).get(id)['summary'],'## 핵심 개념\n\n기밀성')
        status,pdf=self.call('/pdf/'+id);self.assertEqual(pdf,b'%PDF-1.4 test document')
        request=self.calls[0][0];self.assertFalse(request['store']);self.assertTrue(request['stream'])
        self.assertTrue(request['input'][0]['content'][0]['file_data'].startswith('data:application/pdf;base64,'))

    def test_incomplete_keeps_existing_summary(self):
        id=self.document();self.app.key='fake-key';self.app.store.update(id,summary='기존 노트')
        self.events=[{'type':'response.output_text.delta','delta':'미완성'},{'type':'response.incomplete'}]
        _,body=self.generate(id)
        self.assertIn(b'"type":"error"',body);self.assertEqual(self.app.store.get(id)['summary'],'기존 노트')

    def test_valid_quiz_is_linked_and_invalid_output_keeps_previous(self):
        id=self.document();self.app.key='fake-key'
        self.events=[{'type':'response.output_text.delta','delta':json.dumps(quiz())},{'type':'response.completed','response':{'status':'completed'}}]
        self.generate(id,'quiz');saved=self.app.store.get(id)['quiz'];self.assertEqual(saved['sourceNoteId'],id)
        self.events=[{'type':'response.output_text.delta','delta':'{}'},{'type':'response.completed','response':{'status':'completed'}}]
        self.generate(id,'quiz');self.assertEqual(self.app.store.get(id)['quiz'],saved)

    def test_local_origin_and_token_required(self):
        for headers in [{'Origin':'https://example.com'},{'X-Study-Token':'bad'},{'Host':'attacker.example'}]:
            self.assertEqual(self.call('/api/settings',{'key':'secret'},headers)[0],403)
        self.assertEqual(self.app.key,'')
        self.assertEqual(self.call('/api/state',headers={'Sec-Fetch-Site':'cross-site'})[0],403)

    def test_key_never_returned_or_persisted(self):
        self.call('/api/settings',{'key':'test-secret-unique','model':'gpt-test'})
        _,body=self.call('/api/state');self.assertNotIn(b'test-secret-unique',body)
        for file in Path(self.temp.name).glob('*'): self.assertNotIn(b'test-secret-unique',file.read_bytes())
        self.call('/api/settings',{'key':''});self.assertEqual(self.app.key,'')

    def test_import_rejects_bad_pdf_and_duplicate_quiz(self):
        self.assertEqual(self.call('/api/import',{'filename':'bad.pdf','base64':base64.b64encode(b'not PDF').decode()})[0],400)
        data=quiz();data['questions'][1]['id']='q0'
        with self.assertRaises(ValueError):validate_quiz(data)

    def test_grading_uses_server_answers_and_persists_attempt(self):
        id=self.app.store.add('강의','test.json',quiz=quiz())
        status,raw=self.call('/api/attempt',{'id':id,'answers':{'q0':0,'q1':2}})
        self.assertEqual(status,200);results=json.loads(raw)['results']
        self.assertEqual([x['correct'] for x in results],[True,False]);self.assertEqual(len(self.app.store.attempts(id)),1)
        self.assertEqual(self.call('/api/attempt',{'id':id,'answers':{'q0':True}})[0],400)

    def test_renaming_note_keeps_exported_quiz_source_title_in_sync(self):
        id=self.app.store.add('원래 제목','test.json',summary='내용',quiz=quiz())
        status,_=self.call('/api/save',{'id':id,'title':'바뀐 제목','summary':'수정 내용'})
        self.assertEqual(status,200)
        self.assertEqual(self.app.store.get(id)['quiz']['sourceNoteTitle'],'바뀐 제목')

    def test_cancel_before_completed_event_never_overwrites(self):
        id=self.document();self.app.key='fake-key';self.app.store.update(id,summary='원래 노트')
        def provider(body,key):
            yield {'type':'response.output_text.delta','delta':'새 노트'}
            with self.app.lock:self.app.jobs['test-job']['cancel'].set()
            yield {'type':'response.completed','response':{'status':'completed'}}
        self.app.provider=provider
        _,body=self.generate(id);self.assertIn(b'"type":"cancelled"',body);self.assertEqual(self.app.store.get(id)['summary'],'원래 노트')

class LibraryTest(unittest.TestCase):
    setUp = LocalWebTest.setUp
    tearDown = LocalWebTest.tearDown
    call = LocalWebTest.call
    document = LocalWebTest.document

    def test_folder_classification_duplicate_and_changed_copy(self):
        payload = {'filename':'강의.md','sourcePath':'학습/정보보안/02주차/강의.md','content':'# 강의\n\n원문 내용'}
        status, raw = self.call('/api/import', payload)
        self.assertEqual(status, 200)
        id = json.loads(raw)['id']
        lesson = self.app.store.get(id)
        self.assertEqual((lesson['subject'],lesson['unit']), ('정보보안','02주차'))
        self.app.store.update(id, summary='직접 수정한 정리')
        self.assertEqual(json.loads(self.call('/api/import',payload)[1])['id'], id)
        self.assertEqual(self.app.store.get(id)['summary'], '직접 수정한 정리')
        payload['content'] += ' 추가된 원문'
        new_id = json.loads(self.call('/api/import', payload)[1])['id']
        self.assertNotEqual(new_id, id)
        self.assertEqual(len(self.app.store.list()), 2)

    def test_classification_is_editable_and_path_remains(self):
        id = self.document()
        self.assertEqual(self.call('/api/classify', {'id':id,'subject':'회로','unit':'3주차'})[0],200)
        row = Store(self.temp.name).get(id)
        self.assertEqual((row['subject'],row['unit'],row['filename']),('회로','3주차','강의.pdf'))
        self.assertEqual(self.call('/api/classify', {'id':id,'subject':['bad']})[0],400)
        self.assertEqual(self.call('/api/import', {'filename':'a.md','sourcePath':'../a.md','content':'test'})[0],400)

    def test_original_used_after_summary_edit(self):
        _, raw = self.call('/api/import', {'filename':'강의.md','content':'# 제목\n\n실제 원문'})
        id = json.loads(raw)['id']
        self.app.store.update(id,summary='새 정리본')
        body = request_body(self.app.store.get(id),'quiz','fake',{'count':5,'difficulty':'보통','written':False})
        self.assertIn('실제 원문',body['input'][0]['content'][0]['text'])
        self.assertNotIn('새 정리본',body['input'][0]['content'][0]['text'])

    def test_dashboard_counts_latest_answers_only(self):
        id = self.app.store.add('강의','문제.json',quiz=quiz(),summary='## 한 줄 요약\n기밀성을 공부합니다.')
        self.app.store.record(id,{'q0':1,'q1':2})
        self.app.store.record(id,{'q0':0})
        row = self.app.store.list()[0]
        self.assertEqual((row['attempt_count'],row['wrong_count']), (2,1))
        self.assertEqual(row['excerpt'],'기밀성을 공부합니다.')


class MigrationTest(unittest.TestCase):
    def test_existing_database_keeps_notes_and_attempts(self):
        with tempfile.TemporaryDirectory() as root:
            with sqlite3.connect(Path(root)/'study.sqlite3') as db:
                db.execute("CREATE TABLE lessons (id TEXT PRIMARY KEY,title TEXT,filename TEXT,pdf BLOB,summary TEXT,quiz TEXT,updated REAL)")
                db.execute("INSERT INTO lessons VALUES ('old','제목','노트.md',NULL,'저장된 노트',NULL,1)")
            store=Store(root)
            self.assertEqual(store.get('old')['summary'],'저장된 노트')
            self.assertEqual(store.get('old')['subject'],'')
            self.assertEqual(Store(root).list()[0]['id'],'old')

if __name__=='__main__': unittest.main()
