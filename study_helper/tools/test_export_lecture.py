import argparse
import io
import json
from contextlib import redirect_stdout
from pathlib import Path
import tempfile
import unittest
from lecture_pipeline import ROOT, Store, register
from export_lecture import export
from lecture_test_fixture import write_lecture


class ExportTest(unittest.TestCase):
    def test_file_backed_publish_reuses_files_and_keeps_external_edits(self):
        with tempfile.TemporaryDirectory() as temp, redirect_stdout(io.StringIO()):
            root=Path(temp);source=root/'source';source.mkdir();data=root/'db';data.mkdir();library=root/'learning';library.mkdir()
            (data/'library.json').write_text(json.dumps({'root':str(library)}))
            write_lecture(source)
            register(argparse.Namespace(directory=source,pack=None,data_dir=data,check=False))
            store=Store(data);lid=json.loads((source/'registration.json').read_text())['lessonId']
            export(source,library,data)
            note=Path(next(l['path'] for l in store.files.links(lid) if l['role']=='summary'))
            note.write_text('Finder에서 직접 편집')
            before={str(p) for p in library.rglob('*') if p.is_file()}
            export(source,library,data)
            self.assertEqual(before,{str(p) for p in library.rglob('*') if p.is_file()})
            self.assertEqual(store.get(lid)['summary'],'Finder에서 직접 편집')
            with store.connect() as db:
                self.assertEqual(db.execute("SELECT count(*) FROM lessons WHERE original_text<>'' OR summary<>'' OR quiz IS NOT NULL").fetchone()[0],0)

    def test_publish_preserves_records_and_rejects_file_conflicts(self):
        with tempfile.TemporaryDirectory() as temp, redirect_stdout(io.StringIO()):
            root=Path(temp); source=root/'source'; source.mkdir()
            write_lecture(source)
            data=root/'db'; library=root/'learning'
            register(argparse.Namespace(directory=source,pack=None,data_dir=data,check=False))
            store=Store(data)
            lid=json.loads((source/'registration.json').read_text())['lessonId']
            store.update(lid,summary='사용자 수정 노트')
            store.record(lid,{'q01':1})
            export(source,library,data,check=True)
            self.assertFalse(library.exists())
            export(source,library,data)
            export(source,library,data)
            self.assertEqual(len(store.attempts(lid)),1)
            self.assertEqual(store.get(lid)['unit'],'06주차')
            self.assertTrue(Path(store.get(lid)['local_folder']).is_dir())
            note=next(library.rglob('*_요약.md'))
            self.assertIn('사용자 수정 노트',note.read_text())
            note.write_text('Finder에서 수정한 내용')
            with self.assertRaises(ValueError): export(source,library,data)
            self.assertEqual(note.read_text(),'Finder에서 수정한 내용')
            self.assertEqual(store.get(lid)['summary'],'사용자 수정 노트')

if __name__=='__main__': unittest.main()
