"""Install reviewed course content into the local library, preserving existing work."""
import argparse
import json
from pathlib import Path
import re
import sqlite3
import time
from server import ROOT, Store, validate_quiz


def prepare(store, pack):
    prepared = []
    for guide in pack['guides']:
        validate_quiz(guide['quiz'])
        sources = []
        with store.connect() as db:
            for path in guide['sources']:
                rows = db.execute('SELECT id FROM lessons WHERE source_path=?', (path,)).fetchall()
                if len(rows) != 1:
                    raise ValueError(f'원본 자료를 하나로 확인할 수 없습니다: {path}')
                sources.append(store.get(rows[0]['id']))
        raw = '\n'.join(source['original_text'] or source['summary'] for source in sources)
        for question in guide['quiz']['questions']:
            if question['sourceQuote'] not in raw:
                raise ValueError(f"원문 인용을 확인하세요: {guide['key']} / {question['id']}")
        cited = guide['summary'] + '\n' + '\n'.join(q['explanation'] for q in guide['quiz']['questions'])
        for index, page in re.findall(r'\(자료 (\d+)(?: · PDF (\d+)쪽)?\)', cited):
            if not 1 <= int(index) <= len(sources) or (page and not sources[int(index)-1]['pdf']):
                raise ValueError(f"근거 자료 번호를 확인하세요: {guide['key']}")
        prepared.append((guide, [source['id'] for source in sources]))
    return prepared


def install(store, prepared):
    return [store.add_guide(g['key'], g['title'], g['subject'], g['unit'], g['summary'], g['quiz'], ids)
            for g, ids in prepared]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('pack', type=Path)
    parser.add_argument('--data-dir', type=Path, default=ROOT / '.local-data')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    pack = json.loads(args.pack.read_text())
    # Backup the database before schema migration or content installation.
    if not args.check:
        backup = args.data_dir / 'backups' / f'before-guides-{time.time_ns()}.sqlite3'
        backup.parent.mkdir(parents=True, exist_ok=True)
        with sqlite3.connect(args.data_dir / 'study.sqlite3') as source, sqlite3.connect(backup) as target:
            source.backup(target)
        print('백업:', backup)
    store = Store(args.data_dir)
    prepared = prepare(store, pack)
    print('검증 완료:', len(prepared), '정리본,', sum(len(g['quiz']['questions']) for g, _ in prepared), '문항')
    if not args.check:
        for (guide, _), id in zip(prepared, install(store, prepared)):
            print(guide['title'], id)


if __name__ == '__main__':
    main()
