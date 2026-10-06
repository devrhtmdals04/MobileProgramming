"""File-backed lessons: SQLite stores references and study history, not material copies."""
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import threading
import unicodedata

LOCK = threading.RLock()
EXTENSIONS = {'.pdf', '.md', '.txt', '.json'}


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def atomic(path, data, expected=None):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with LOCK:
        if expected is not None and (sha(path) if path.exists() else '') != expected:
            raise ValueError('원본 파일이 다른 곳에서 변경됐습니다. 새로고침 후 내용을 비교해 주세요.')
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.study-', delete=False) as file:
            temporary = Path(file.name)
            file.write(data)
            file.flush(); os.fsync(file.fileno())
        try:
            if expected is not None and (sha(path) if path.exists() else '') != expected:
                raise ValueError('저장 중 원본이 변경됐습니다. 다시 확인해 주세요.')
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)


def safe(root, relative):
    relative = unicodedata.normalize('NFC', str(relative))
    if Path(relative).is_absolute() or any(p in ('', '.', '..') or p.startswith('.') for p in relative.split('/')):
        raise ValueError('학습 폴더 안의 상대 경로를 지정하세요.')
    path = Path(root) / relative
    if not path.resolve().is_relative_to(Path(root).resolve()):
        raise ValueError('학습 폴더 밖으로 이동할 수 없습니다.')
    current = path
    while current != Path(root):
        if current.is_symlink(): raise ValueError('심볼릭 링크는 학습 자료로 연결하지 않습니다.')
        current = current.parent
    return path


class FileLibrary:
    def __init__(self, store):
        self.store = store
        config = store.root / 'library.json'
        self.root = Path(json.loads(config.read_text())['root']).resolve() if config.exists() else None
        with store.connect() as db:
            db.executescript('''CREATE TABLE IF NOT EXISTS linked_files (
                lesson_id TEXT NOT NULL, role TEXT NOT NULL, path TEXT NOT NULL,
                hash TEXT NOT NULL, device INTEGER, inode INTEGER,
                PRIMARY KEY(lesson_id,role));''')

    def links(self, id):
        with self.store.connect() as db:
            return [dict(r) for r in db.execute('SELECT * FROM linked_files WHERE lesson_id=?', (id,))]

    def link(self, id, role, path):
        path=Path(path).resolve()
        if not self.root or not path.is_relative_to(self.root): raise ValueError('학습 폴더 안의 파일만 연결할 수 있습니다.')
        stat=path.stat()
        with self.store.connect() as db:
            db.execute('INSERT OR REPLACE INTO linked_files VALUES (?,?,?,?,?,?)', (id,role,str(path),sha(path),stat.st_dev,stat.st_ino))

    def hydrate(self, result, pdf=True):
        links=self.links(result['id'])
        result['file_revision']={}
        result['file_paths']=[]
        result['missing_files']=[]
        result['file_backed']=bool(links)
        for link in links:
            path=Path(link['path']);role=link['role']
            result['file_paths'].append(str(path.relative_to(self.root)) if self.root and path.is_relative_to(self.root) else str(path))
            if not self.root or not path.is_relative_to(self.root) or not path.exists():
                result['missing_files'].append(path.name)
                continue
            try:
                safe(self.root,path.relative_to(self.root))
                if role=='pdf':
                    result['has_pdf']=True
                    if pdf: result['pdf']=path.read_bytes()
                    continue
                if path.stat().st_size>1_000_000: raise ValueError('텍스트 자료가 너무 큽니다.')
                content=path.read_text(encoding='utf-8-sig')
                result['file_revision'][role]=sha(path)
                if role=='summary':
                    result['summary']=content.split('\n',1)[1].lstrip('\n') if content.startswith('# ') else content
                elif role=='original':
                    result['original_text']=content
                    if not any(l['role']=='summary' for l in links): result['summary']=content
                elif role=='quiz':
                    from server import validate_quiz
                    result['quiz']=validate_quiz(json.loads(content))
            except (OSError,ValueError) as error:
                result['missing_files'].append(f'{path.name}: {error}')
        if links:
            primary=next((l for l in links if l['role']=='summary'),links[0])
            result['local_folder']=str(Path(primary['path']).parent)
        return result

    def save(self, id, fields, expected):
        links={l['role']:l for l in self.links(id)}
        if not links: return fields
        writes=[]
        current=self.store.get(id)
        if current.get('missing_files'): raise ValueError('원본 파일을 확인한 뒤 저장하세요.')
        for role in ('summary','quiz'):
            if role not in fields: continue
            link=links.get(role)
            expected_role=role
            if not link and role=='summary' and 'original' in links and Path(links['original']['path']).suffix.lower()=='.md':
                link=links['original'];expected_role='original'
            if not link:
                parent=Path(next(iter(links.values()))['path']).parent
                path=parent / (id + ('.md' if role=='summary' else '.quiz.json'))
                old=''
            else:
                path=Path(link['path']);old=sha(path)
            if expected is not None and link and expected.get(expected_role)!=old:
                raise ValueError('파일이 다른 곳에서 변경됐습니다. 새로고침 후 내용을 확인해 주세요.')
            value=fields[role]
            if role=='summary':
                if expected_role=='original' and value.startswith('# '): value=value.partition('\n')[2].lstrip('\n')
                value='# '+fields.get('title',current['title'])+'\n\n'+value.strip()+'\n'
            writes.append((role,path,value.encode(),old))
        with LOCK:
            for _,path,_,old in writes:
                if (sha(path) if path.exists() else '')!=old: raise ValueError('파일이 변경됐습니다. 다시 확인해 주세요.')
            for role,path,data,old in writes:
                atomic(path,data,old);self.link(id,role,path)
        fields=dict(fields)
        for role in ('summary','quiz'):
            if role in fields: fields[role]='' if role=='summary' else None
        return fields

    def find_existing(self, source_path, pdf, original, summary, quiz):
        if not self.root: return None
        content=pdf if pdf is not None else ((original or summary).encode() if original or summary else None)
        with self.store.connect() as db:
            if content:
                row=db.execute('SELECT lesson_id FROM linked_files WHERE hash=? LIMIT 1',(hashlib.sha256(content).hexdigest(),)).fetchone()
                if row: return row[0]
            if source_path:
                relative=source_path.removeprefix('학습/')
                path=safe(self.root,relative)
                if path.is_file():
                    same=(path.read_bytes()==pdf) if pdf is not None else False
                    if original or summary:
                        same=path.read_text(encoding='utf-8-sig').strip()==(original or summary).strip()
                    if quiz:
                        try:
                            left=json.loads(path.read_text());right=dict(quiz)
                            for key in ('sourceNoteId','sourceNoteTitle'):left.pop(key,None);right.pop(key,None)
                            same=left==right
                        except ValueError:pass
                    if same:
                        row=db.execute('SELECT lesson_id FROM linked_files WHERE path=? LIMIT 1',(str(path),)).fetchone()
                        if row:return row[0]
        return None

    def store_content(self, id, title, subject, unit, pdf=None, summary='', quiz=None, original=''):
        if not self.root: return
        clean=lambda value: re.sub(r'[\\/:*?"<>|\x00-\x1f]', '_', value).strip().lstrip('.') or '미분류'
        week=re.fullmatch(r'0*(\d+)주차',unit.strip())
        if week:unit=f'{int(week[1]):02}주차'
        base=self.root/clean(subject)/clean(unit or '공통')
        stem=clean(title)[:60]+'--'+id
        payloads=[]
        if pdf is not None: payloads.append(('pdf',base/'PDF'/(stem+'.pdf'),pdf))
        if original: payloads.append(('original',base/'노트'/(stem+'.md'),original.encode()))
        if summary and summary.strip()!=original.strip():payloads.append(('summary',base/'노트'/(stem+'_요약.md'),('# '+title+'\n\n'+summary.strip()+'\n').encode()))
        if quiz:payloads.append(('quiz',base/'문제'/(stem+'.quiz.json'),(json.dumps(quiz,ensure_ascii=False,indent=2)+'\n').encode()))
        for role,path,content in payloads:
            atomic(path,content,'');self.link(id,role,path)
        if payloads:
            with self.store.connect() as db: db.execute("UPDATE lessons SET pdf=NULL,summary='',quiz=NULL,original_text='' WHERE id=?",(id,))

    def scan(self):
        if not self.root: return {'enabled':False}
        if not self.root.is_dir(): raise ValueError('학습 폴더에 접근할 수 없습니다.')
        with LOCK:
            files={}
            for path in self.root.rglob('*'):
                rel=path.relative_to(self.root)
                if any(part.startswith('.') or part in ('node_modules','build','.git') for part in rel.parts): continue
                if path.is_symlink() or not path.is_file() or path.suffix.lower() not in EXTENSIONS: continue
                safe(self.root,rel)
                stat=path.stat();files[str(path)]=(path,stat)
                if len(files)>10000: raise ValueError('학습 파일이 너무 많습니다.')
            with self.store.connect() as db:
                linked=[dict(r) for r in db.execute('SELECT * FROM linked_files')]
            known={r['path'] for r in linked};by_inode={}
            for key,(path,stat) in files.items(): by_inode.setdefault((stat.st_dev,stat.st_ino),[]).append(path)
            moved=0
            for row in linked:
                path=Path(row['path'])
                if not path.exists():
                    candidates=by_inode.get((row['device'],row['inode']),[])
                    if len(candidates)==1 and sha(candidates[0])==row['hash']:
                        path=candidates[0];self.link(row['lesson_id'],row['role'],path);known.add(str(path));moved+=1
                        self.classify(row['lesson_id'],path)
                elif str(path) in files:
                    self.link(row['lesson_id'],row['role'],path)
            added=0;invalid=[]
            for key,(path,_) in files.items():
                if key in known: continue
                # JSON metadata is not a quiz; leave it available in Finder without creating cards.
                role='original'
                try:
                    if path.suffix.lower()=='.json':
                        from server import validate_quiz
                        validate_quiz(json.loads(path.read_text()));role='quiz'
                    elif path.suffix.lower()=='.pdf':role='pdf'
                    if role=='original' and path.stat().st_size>1_000_000: continue
                except (ValueError,OSError): continue
                id=self.store.add(path.stem[:60],path.name)
                self.link(id,role,path);self.classify(id,path);added+=1
            return {'enabled':True,'root':str(self.root),'added':added,'moved':moved,'files':len(files)}

    def classify(self,id,path):
        parts=path.relative_to(self.root).parts
        subject=parts[0] if len(parts)>1 else ''
        unit=parts[1] if len(parts)>2 else ''
        with self.store.connect() as db:
            db.execute('UPDATE lessons SET subject=?,unit=? WHERE id=?',(subject,unit,id))

    def move(self,source,destination):
        if not self.root: raise ValueError('학습 폴더를 먼저 연결하세요.')
        old,new=safe(self.root,source),safe(self.root,destination)
        if not old.exists() or new.exists() or new.is_relative_to(old): raise ValueError('원본이 없거나 대상이 이미 존재합니다.')
        with LOCK, self.store.connect() as db:
            rows=[dict(r) for r in db.execute('SELECT * FROM linked_files') if Path(r['path'])==old or Path(r['path']).is_relative_to(old)]
            new.parent.mkdir(parents=True,exist_ok=True)
            old.rename(new)
            try:
                for row in rows:
                    dest=new/Path(row['path']).relative_to(old) if Path(row['path'])!=old else new
                    db.execute('UPDATE linked_files SET path=? WHERE lesson_id=? AND role=?',(str(dest),row['lesson_id'],row['role']))
                    parts=dest.relative_to(self.root).parts
                    db.execute('UPDATE lessons SET subject=?,unit=? WHERE id=?',(parts[0],parts[1] if len(parts)>2 else '',row['lesson_id']))
            except Exception:
                new.rename(old);raise
        return {'moved':len(rows),'path':destination}
