"""Migrate legacy web content to references in the existing study folder."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import sys
import time
import unicodedata
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'webApp'))
from server import Store
from file_library import atomic
from export_lecture import default_root


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,default=default_root())
    parser.add_argument('--data-dir',type=Path,default=Path(__file__).resolve().parents[1]/'webApp/.local-data')
    parser.add_argument('--apply',action='store_true')
    args=parser.parse_args();root=args.root.resolve();database=args.data_dir/'study.sqlite3'
    if (args.data_dir/'library.json').exists():
        raise ValueError('이미 파일 기반 서재로 전환했습니다. 중복 전환하지 않습니다.')
    with sqlite3.connect(f'{database.resolve().as_uri()}?mode=ro',uri=True) as db:
        db.row_factory=sqlite3.Row
        lessons=[dict(r) for r in db.execute('SELECT * FROM lessons')]
        local={r['lesson_id']:dict(r) for r in db.execute('SELECT * FROM local_materials')}
    def nfc(value):return unicodedata.normalize('NFC',value)
    paths={nfc(p.relative_to(root).as_posix()):p for p in root.rglob('*') if p.is_file() and not p.is_symlink() and not any(x.startswith('.') for x in p.relative_to(root).parts)}
    writes={};links=[]
    def target(lesson,kind,extension):
        subject=nfc(lesson['subject'] or '미분류');unit=nfc(lesson['unit'] or '공통')
        title=re.sub(r'[\\/:*?"<>|]','_',nfc(lesson['title']))[:60]
        p=root/subject/unit/kind/(title+'--'+lesson['id']+extension)
        if not p.resolve().is_relative_to(root):raise ValueError('잘못된 저장 경로')
        return p
    def bind(lesson,role,path,content=None):
        if content is not None:
            if path.exists() and path.read_bytes()!=content:raise ValueError(f'기존 파일 충돌: {path}')
            if not path.exists():writes[path]=content
        links.append((lesson['id'],role,path))
    def equal_quiz(a,b):
        a,b=dict(a),dict(b)
        for key in ['sourceNoteId','sourceNoteTitle']:a.pop(key,None);b.pop(key,None)
        return a==b
    for lesson in lessons:
        if lesson['id'] in local:
            item=local[lesson['id']]
            if item['summary_path']:
                p=Path(item['summary_path']);content=p.read_text()
                body=content.partition('\n')[2].lstrip('\n') if content.startswith('# ') else content
                if body.strip()!=lesson['summary'].strip():raise ValueError(f'요약 충돌: {p}')
                bind(lesson,'summary',p)
            if item['quiz_path']:
                p=Path(item['quiz_path'])
                if not equal_quiz(json.loads(p.read_text()),json.loads(lesson['quiz'])):raise ValueError(f'문제 충돌: {p}')
                bind(lesson,'quiz',p)
            if lesson['original_text']:
                p=Path(item['folder'])/'transcript.txt'
                if p.read_text()!=lesson['original_text']:raise ValueError(f'전사 충돌: {p}')
                bind(lesson,'original',p)
        roles={role for id,role,_ in links if id==lesson['id']}
        rel=nfc(lesson['source_path']);rel=rel.removeprefix('학습/')
        existing=paths.get(rel)
        if lesson['pdf'] is not None:
            data=lesson['pdf'];p=existing
            if not p or p.read_bytes()!=data:
                p=next((x for x in paths.values() if x.suffix.lower()=='.pdf' and x.stat().st_size==len(data) and x.read_bytes()==data),None)
            if p is None:p=target(lesson,'PDF','.pdf')
            bind(lesson,'pdf',p,data)
        if lesson['original_text'] and 'original' not in roles:
            data=lesson['original_text'].encode();p=existing
            if not p or p.suffix.lower() not in ('.txt','.md') or p.read_text(encoding='utf-8-sig').strip()!=lesson['original_text'].strip():
                p=next((x for x in paths.values() if x.suffix.lower() in ('.txt','.md') and x.read_text(encoding='utf-8-sig').strip()==lesson['original_text'].strip()),None)
            if p is None:
                p=target(lesson,'노트','.md');bind(lesson,'original',p,data)
            else:bind(lesson,'original',p)
        if lesson['summary'] and 'summary' not in roles:
            original=lesson['original_text']
            stripped=original.partition('\n')[2].lstrip('\n') if original.startswith('# ') else original
            if lesson['summary'].strip() not in (original.strip(),stripped.strip()) or not original:
                p=target(lesson,'노트','_요약.md')
                bind(lesson,'summary',p,('# '+lesson['title']+'\n\n'+lesson['summary'].strip()+'\n').encode())
        if lesson['quiz'] and 'quiz' not in roles:
            value=json.loads(lesson['quiz']);p=None
            if existing and existing.suffix.lower()=='.json':
                if equal_quiz(json.loads(existing.read_text()),value):p=existing
            if p is None:
                p=target(lesson,'문제','.quiz.json');bind(lesson,'quiz',p,(json.dumps(value,ensure_ascii=False,indent=2)+'\n').encode())
            else:bind(lesson,'quiz',p)
    print(f'자료 {len(lessons)}개 · 파일 참조 {len(links)}개 · 기존 파일 재사용 {len(links)-len(writes)} · 파일 없는 정리/문제 저장 {len(writes)}개')
    for path in writes:print('새 파일:',path)
    if not args.apply:return
    backup=args.data_dir/'backups'/f'before-file-library-{time.time_ns()}.sqlite3';backup.parent.mkdir(parents=True,exist_ok=True)
    with sqlite3.connect(database) as source,sqlite3.connect(backup) as dest:source.backup(dest)
    for path,data in writes.items():atomic(path,data,'')
    atomic(args.data_dir/'library.json',json.dumps({'root':str(root)},ensure_ascii=False).encode())
    store=Store(args.data_dir)
    for id,role,path in links:store.files.link(id,role,path)
    with store.connect() as db:
        for id in {row[0] for row in links}:
            db.execute("UPDATE lessons SET pdf=NULL,summary='',quiz=NULL,original_text='' WHERE id=?",(id,))
    # Reclaim the old material BLOB pages after the separately stored migration backup.
    with sqlite3.connect(database) as db:db.execute('VACUUM')
    print('파일 참조 전환 완료. 기존 ID와 학습 기록을 유지했습니다. 백업:',backup)

if __name__=='__main__':main()
