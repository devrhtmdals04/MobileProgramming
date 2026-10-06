"""Publish reviewed lectures to the user's study folder without overwriting edits."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import time
import unicodedata
from lecture_pipeline import ROOT, Store, read_json, validate_pack


def default_root():
    base = Path.home() / 'Desktop/2026-2'
    if base.exists():
        for path in base.iterdir():
            if unicodedata.normalize('NFC', path.name) == '학습':
                return path
    return base / '학습'


def component(value):
    value = unicodedata.normalize('NFC', value).strip()
    if not value or value in ('.', '..') or any(c in value for c in '/\\\x00'):
        raise ValueError('폴더와 파일 이름에 사용할 수 없는 값입니다.')
    return value


def export(directory, library_root, data_dir, check=False):
    directory, library_root, data_dir = map(lambda p: Path(p).resolve(), (directory, library_root, data_dir))
    raw, pack, guide = validate_pack(directory, directory / 'course-pack.json')
    database = data_dir / 'study.sqlite3'
    # Read the current library, including user edits, before preparing any files.
    with sqlite3.connect(f'{database.as_uri()}?mode=ro', uri=True) as db:
        db.row_factory = sqlite3.Row
        row = db.execute('SELECT l.* FROM lessons l JOIN study_guides g ON g.lesson_id=l.id WHERE g.pack_key=?', (guide['key'],)).fetchone()
        if not row:
            raise ValueError('먼저 register로 서재에 등록하세요.')
        source_rows = db.execute('SELECT l.id,l.original_text FROM lessons l JOIN lesson_sources s ON s.source_id=l.id WHERE s.lesson_id=?', (row['id'],)).fetchall()
    store = Store(data_dir)
    row = store.get(row['id'])
    source_rows = [store.get(source['id']) for source in source_rows]
    if len(source_rows) != 1 or source_rows[0]['original_text'] != raw:
        raise ValueError('서재의 원문 연결이 전사 파일과 다릅니다. 먼저 확인하세요.')
    subject = component(row['subject'])
    match = re.fullmatch(r'0*(\d+)주차', row['unit'].strip())
    unit = f'{int(match[1]):02}주차' if match else '주차 확인 필요'
    title = component(row['title'])
    item = re.search(r'(?<!\d)(\d{7})(?!\d)', guide['key'])
    name = title + (f'-{item[1]}' if item else '')
    base = library_root / subject / unit
    folder = base / '강의' / name
    note = base / '노트' / f'{name}_요약.md'
    quiz = base / '문제' / f'{name}.quiz.json'
    if store.files.root:
        if store.files.root != library_root:
            raise ValueError('현재 연결된 학습 폴더에만 게시할 수 있습니다.')
        existing_links={link['role']:Path(link['path']) for link in store.files.links(row['id'])}
        note=existing_links.get('summary',note)
        quiz=existing_links.get('quiz',quiz)
    payloads = {
        note: ('# ' + title + '\n\n' + row['summary'].strip() + '\n').encode(),
        quiz: (json.dumps(row['quiz'], ensure_ascii=False, indent=2)+'\n').encode(),
    }
    if store.files.root:
        for path in (note,quiz):
            if path.exists():payloads[path]=path.read_bytes()
    for filename in ('audio.m4a', 'transcript.txt', 'transcript.srt', 'transcript.json'):
        path = directory / filename
        if not path.is_file():
            raise ValueError(f'필수 결과 파일이 없습니다: {path}')
        payloads[folder / filename] = path.read_bytes()
    manifest = dict(version=1, lessonId=row['id'], key=guide['key'], subject=subject, unit=unit,
                    summary=str(note.relative_to(library_root)), quiz=str(quiz.relative_to(library_root)),
                    sync='수동 반영: 파일과 앱의 편집 내용이 다르면 덮어쓰지 않음')
    payloads[folder / '자료정보.json'] = (json.dumps(manifest, ensure_ascii=False, indent=2)+'\n').encode()
    # Preflight the whole set. Existing different content is a conflict, not permission to overwrite.
    for path, content in payloads.items():
        if path.name == '자료정보.json' and path.exists():
            continue
        if not path.resolve().is_relative_to(library_root):
            raise ValueError(f'학습 폴더 밖으로 연결된 경로입니다: {path}')
        if path.exists() and path.read_bytes() != content:
            raise ValueError(f'기존 파일과 내용이 다릅니다. 편집 내용을 확인하세요: {path}')
    print(f'{title}\n  {folder}\n  {note}\n  {quiz}')
    if check:
        return
    backup = data_dir / 'backups' / f'before-folder-export-{time.time_ns()}.sqlite3'
    backup.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(database) as source, sqlite3.connect(backup) as target:
        source.backup(target)
    for path, content in payloads.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists():
            with path.open('xb') as target:
                target.write(content)
    store = Store(data_dir)
    if store.files.root:
        previous=store.files.links(source_rows[0]['id'])
        for lesson_id,role,path in [(row['id'],'summary',note),(row['id'],'quiz',quiz),(source_rows[0]['id'],'original',folder/'transcript.txt')]:
            store.files.link(lesson_id,role,path)
        for link in previous:
            old=Path(link['path'])
            with store.connect() as db:
                used=db.execute('SELECT 1 FROM linked_files WHERE path=?',(str(old),)).fetchone()
            if not used and old != folder/'transcript.txt' and old.read_bytes()==raw.encode():old.unlink()
    with store.connect() as db:
        db.execute('INSERT INTO local_materials(lesson_id,folder,summary_path,quiz_path) VALUES (?,?,?,?) ON CONFLICT(lesson_id) DO UPDATE SET folder=excluded.folder,summary_path=excluded.summary_path,quiz_path=excluded.quiz_path', (row['id'], str(folder), str(note), str(quiz)))
        db.execute('INSERT INTO local_materials(lesson_id,folder,summary_path,quiz_path) VALUES (?,?,?,?) ON CONFLICT(lesson_id) DO UPDATE SET folder=excluded.folder,summary_path=excluded.summary_path,quiz_path=excluded.quiz_path', (source_rows[0]['id'], str(folder), '', ''))
        db.execute('UPDATE lessons SET unit=? WHERE id IN (?,?)', (unit, row['id'], source_rows[0]['id']))
        db.execute('UPDATE lessons SET title=? WHERE id=?', (title+' · 전사', source_rows[0]['id']))
        # Normalize only exact week labels; preserve descriptive class names elsewhere.
        for lesson in db.execute('SELECT id,unit FROM lessons WHERE subject=?', (subject,)).fetchall():
            week = re.fullmatch(r'0*(\d+)주차', lesson['unit'].strip())
            if week:
                db.execute('UPDATE lessons SET unit=? WHERE id=?', (f'{int(week[1]):02}주차', lesson['id']))
    print('저장 및 서재 연결 완료 (풀이 기록 유지)')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True, type=Path)
    parser.add_argument('--library-root', type=Path, default=default_root())
    parser.add_argument('--data-dir', type=Path, default=ROOT/'webApp/.local-data')
    parser.add_argument('--check', action='store_true')
    args=parser.parse_args()
    try:
        export(args.directory,args.library_root,args.data_dir,args.check)
    except (ValueError,OSError,KeyError,sqlite3.Error) as error:
        parser.exit(1, f'저장 실패: {error}\n')

if __name__=='__main__': main()
