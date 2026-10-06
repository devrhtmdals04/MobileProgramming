import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from server import Store
from file_library import safe

class FileLibraryTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.library=self.root/'study';self.library.mkdir()
        self.data=self.root/'db';self.data.mkdir()
        (self.data/'library.json').write_text(json.dumps({'root':str(self.library)}))
        self.store=Store(self.data)

    def test_original_read_directly_and_database_empty(self):
        id=self.store.add('수업','note.md',summary='본문',original_text='본문',source_path='과목/01주차/노트/note.md')
        links=self.store.files.links(id);self.assertEqual(len(links),1)
        path=Path(links[0]['path']);path.write_text('Finder 수정')
        self.assertEqual(self.store.get(id)['summary'],'Finder 수정')
        with self.store.connect() as db:
            row=db.execute('SELECT summary,original_text FROM lessons WHERE id=?',(id,)).fetchone()
            self.assertEqual(tuple(row),('',''))

    def test_web_save_writes_file_and_stale_editor_is_rejected(self):
        id=self.store.add('수업','note.md',summary='요약')
        old=self.store.get(id)
        self.store.update(id,summary='웹 수정',file_revision=old['file_revision'])
        self.assertEqual(self.store.get(id)['summary'].strip(),'웹 수정')
        with self.assertRaises(ValueError):self.store.update(id,summary='오래된 편집',file_revision=old['file_revision'])
        self.assertEqual(self.store.get(id)['summary'].strip(),'웹 수정')

    def test_edit_imported_markdown_reuses_the_original_file(self):
        p=self.library/'과목/01주차/노트/a.md';p.parent.mkdir(parents=True);p.write_text('# 제목\n\n본문')
        self.store.files.scan();id=self.store.list()[0]['id'];old=self.store.get(id)
        self.store.update(id,summary='# 제목\n\n웹 수정',file_revision=old['file_revision'])
        self.assertEqual(len(list(self.library.rglob('*.md'))),1)
        self.assertIn('웹 수정',p.read_text())
        self.assertEqual(p.read_text().count('# '),1)
        with self.assertRaises(ValueError):self.store.update(id,summary='낡은 수정',file_revision=old['file_revision'])

    def test_import_same_file_reuses_identity(self):
        p=self.library/'과목/01주차/노트/a.md';p.parent.mkdir(parents=True);p.write_text('본문')
        self.store.files.scan();id=self.store.list()[0]['id']
        imported=self.store.add('제목','a.md',original_text='본문',summary='본문',source_path='학습/과목/01주차/노트/a.md')
        self.assertEqual(imported,id)
        self.assertEqual(len(list(self.library.rglob('*.md'))),1)
    def test_scan_tracks_move_and_preserves_id(self):
        p=self.library/'과목/01주차/노트/a.md';p.parent.mkdir(parents=True);p.write_text('원문')
        self.store.files.scan();id=self.store.list()[0]['id']
        self.store.files.move('과목/01주차','과목/02주차')
        self.store.files.scan()
        self.assertEqual(len(self.store.list()),1)
        self.assertEqual(self.store.get(id)['unit'],'02주차')
        self.assertEqual(self.store.get(id)['summary'],'원문')

    def test_path_escape_and_missing_file_do_not_delete_record(self):
        with self.assertRaises(ValueError):safe(self.library,'../secret')
        id=self.store.add('수업','note.md',summary='요약')
        Path(self.store.files.links(id)[0]['path']).unlink()
        self.store.files.scan()
        self.assertTrue(self.store.get(id)['missing_files'])
        self.assertEqual(len(self.store.list()),1)

if __name__=='__main__':unittest.main()
