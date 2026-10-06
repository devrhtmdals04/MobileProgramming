import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from urllib.parse import urlsplit,parse_qs
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from server import Store
from google_drive import Drive,md5

class FakeDrive(Drive):
    def __init__(self,store,settings):
        super().__init__(store,settings);self.remote={};self.uploads=[];self.downloads=[];self.fail_listing=False
    def listing(self):
        if self.fail_listing:raise ValueError('목록 조회 실패')
        return {p:dict(id=p,md5Checksum=hashlib.md5(b).hexdigest()) for p,b in self.remote.items()},{'':'root'}
    def upload(self,path,rel,remote,dirs):
        self.uploads.append(rel);self.remote[rel]=path.read_bytes();return {'id':rel,'md5Checksum':md5(path)}
    def download(self,remote,target,expected):
        self.downloads.append(remote['id']);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(self.remote[remote['id']])

class DriveTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        root=Path(self.temp.name);self.library=root/'study';self.library.mkdir();data=root/'db';data.mkdir()
        (data/'library.json').write_text(json.dumps({'root':str(self.library)}))
        settings=root/'prefs.json';settings.write_text(json.dumps({'drive_folder':'root'}))
        self.drive=FakeDrive(Store(data),settings)
    def test_roundtrip_and_simultaneous_changes_conflict(self):
        p=self.library/'a.md';p.write_text('처음')
        self.drive.sync();self.assertEqual(self.drive.uploads,['a.md'])
        self.drive.remote['a.md']='모바일 수정'.encode();self.drive.sync()
        self.assertEqual(p.read_text(),'모바일 수정')
        p.write_text('웹 수정');self.drive.sync();self.assertEqual(self.drive.remote['a.md'],'웹 수정'.encode())
        p.write_text('로컬 동시 수정');self.drive.remote['a.md']='원격 동시 수정'.encode();self.drive.sync()
        self.assertEqual(p.read_text(),'로컬 동시 수정');self.assertTrue(self.drive.last['conflicts'])
    def test_incomplete_listing_never_uploads(self):
        (self.library/'a.md').write_text('내용');self.drive.fail_listing=True;self.drive.sync()
        self.assertFalse(self.drive.uploads)
    def test_remote_delete_does_not_resurrect_or_delete_local(self):
        p=self.library/'a.md';p.write_text('내용');self.drive.sync();self.drive.remote.clear();self.drive.sync()
        self.assertTrue(p.exists());self.assertEqual(self.drive.uploads,['a.md']);self.assertTrue(self.drive.last['conflicts'])
    def test_first_sync_different_content_keeps_both(self):
        p=self.library/'a.md';p.write_text('로컬');self.drive.remote['a.md']=b'remote';self.drive.sync()
        self.assertEqual(p.read_text(),'로컬');self.assertFalse(self.drive.uploads);self.assertFalse(self.drive.downloads)
    def test_oauth_invalid_state_is_rejected(self):
        self.drive.pending={'state':'expected','expires':9999999999}
        with self.assertRaises(ValueError):self.drive.callback({'state':'other','code':'code'})
        self.assertFalse(self.drive.tokens)

    def test_local_rename_preserves_remote_identity_without_upload(self):
        p=self.library/'a.md';p.write_text('내용');self.drive.sync();p.rename(self.library/'b.md')
        def api(url,method='GET',body=None,extra=None):
            item={'id':'a.md','parents':['root'],'md5Checksum':hashlib.md5('내용'.encode()).hexdigest()}
            if method=='PATCH':
                self.assertNotIn('addParents',parse_qs(urlsplit(url).query))
                self.drive.remote['b.md']=self.drive.remote.pop('a.md')
                item['name']=body['name']
            return item,{'ETag':'version1'}
        self.drive.api=api
        self.drive.sync()
        self.assertEqual(self.drive.uploads,['a.md'])
        self.assertEqual(set(self.drive.remote),{'b.md'})
        self.assertFalse(self.drive.last['conflicts'])
        with self.drive.store.connect() as db:
            self.assertEqual(tuple(db.execute('SELECT path,remote_id FROM web_drive_baseline').fetchone()),('b.md','a.md'))

    def test_local_move_remote_edit_keeps_both_without_duplicate_upload(self):
        p=self.library/'a.md';p.write_text('내용');self.drive.sync();p.rename(self.library/'b.md')
        self.drive.remote['a.md']=b'edited'
        self.drive.sync()
        self.assertEqual(self.drive.uploads,['a.md'])
        self.assertEqual(set(self.drive.remote),{'a.md'})
        self.assertTrue(self.drive.last['conflicts'])

if __name__=='__main__':unittest.main()
