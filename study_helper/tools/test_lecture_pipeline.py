"""Regression checks for local lecture registration without touching the real library."""
import argparse
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import unittest

import lecture_pipeline as pipeline
from lecture_test_fixture import write_lecture


class LecturePipelineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.output = self.root / 'lecture'
        self.output.mkdir()
        write_lecture(self.output)
        self.args = argparse.Namespace(directory=self.output, pack=None,
                                       data_dir=self.root / 'library', check=False)

    def register(self):
        with redirect_stdout(io.StringIO()):
            pipeline.register(self.args)

    def change_pack(self, edit):
        path = self.output / 'course-pack.json'
        pack = json.loads(path.read_text())
        edit(pack['guides'][0])
        path.write_text(json.dumps(pack, ensure_ascii=False))

    def test_check_does_not_create_target_library(self):
        self.args.check = True
        self.register()
        self.assertFalse(self.args.data_dir.exists())
        self.assertFalse((self.output / 'registration.json').exists())

    def test_invented_quote_is_rejected_before_writing(self):
        self.change_pack(lambda guide: guide['quiz']['questions'][0].update(sourceQuote='강의에 없는 인용 문장입니다.'))
        with self.assertRaises(ValueError):
            self.register()
        self.assertFalse(self.args.data_dir.exists())

    def test_repeat_keeps_edits_attempts_and_source_identity(self):
        self.register()
        store = pipeline.Store(self.args.data_dir)
        id = json.loads((self.output / 'registration.json').read_text())['lessonId']
        store.update(id, summary='사용자가 편집한 요약')
        quiz = store.get(id)['quiz']
        answers = {q['id']: q['correctIndex'] for q in quiz['questions'] if q['type'] == 'concept'}
        graded = store.record(id, answers)
        self.assertTrue(all(row['correct'] for row in graded['results']))
        store.record(id, {'written': {'text': '사용자 연습 답안', 'correct': False}})
        self.register()
        self.assertEqual(store.get(id)['summary'], '사용자가 편집한 요약')
        self.assertEqual(len(store.attempts(id)), 2)
        with store.connect() as db:
            self.assertEqual(db.execute('SELECT count(*) FROM lessons').fetchone()[0], 2)
            self.assertEqual(db.execute('SELECT count(*) FROM study_guides').fetchone()[0], 1)
            source = db.execute('SELECT original_text,subject,unit FROM lessons WHERE original_text<>\'\'').fetchone()
        self.assertEqual(source['original_text'], (self.output / 'transcript.txt').read_text())
        self.assertEqual((source['subject'], source['unit']), ('테스트과목', '6주차'))
        self.assertEqual(len(list((self.args.data_dir / 'backups').glob('*.sqlite3'))), 1)

    def test_changed_source_requires_new_path(self):
        self.register()
        self.change_pack(lambda guide: guide.update(key='new-revision'))
        transcript = self.output / 'transcript.txt'
        transcript.write_text(transcript.read_text() + '\n추가된 원문\n')
        with self.assertRaises(ValueError):
            self.register()
        store = pipeline.Store(self.args.data_dir)
        with store.connect() as db:
            self.assertEqual(db.execute('SELECT count(*) FROM lessons').fetchone()[0], 2)

    def test_transcription_never_overwrites_existing_results(self):
        before = (self.output / 'transcript.txt').read_bytes()
        with self.assertRaises(ValueError):
            pipeline.transcribe(argparse.Namespace(output=self.output))
        self.assertEqual((self.output / 'transcript.txt').read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
