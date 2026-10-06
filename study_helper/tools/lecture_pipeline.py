#!/usr/bin/env python3
"""Local lecture transcription and reviewed quiz registration for Study Helper."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'webApp'))
from server import Store, text, validate_quiz
from install_guides import prepare, install


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def digest(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def run(command, log=None, timeout=7200):
    subprocess.run([str(arg) for arg in command], check=True, timeout=timeout,
                   stdout=log, stderr=log)


def transcribe(args):
    output = args.output.resolve()
    # Never mix a previous transcript with another input, model, or partial run.
    if output.exists() and any(output.iterdir()):
        raise ValueError('출력 폴더가 비어 있지 않습니다. 기존 결과를 사용하거나 새 폴더를 지정하세요.')
    if not args.cli.is_file() or not args.model.is_file():
        raise ValueError('Whisper 실행 파일/모델이 없습니다. tools/local-stt/setup.sh를 확인하세요.')
    for name in ('ffmpeg', 'ffprobe'):
        if not shutil.which(name):
            raise ValueError(f'{name} 실행 파일이 필요합니다.')
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='lecture-') as temporary:
        work = Path(temporary)
        if urlsplit(args.input).scheme in ('https', 'http'):
            if not shutil.which('curl'):
                raise ValueError('URL 다운로드에는 curl이 필요합니다.')
            source = work / 'source.media'
            print('영상/음성 다운로드 중…', flush=True)
            # URL (including any signed query) is not persisted in the manifest.
            run(['curl', '-sS', '-L', '--fail', '--connect-timeout', '20', '--max-time', '600',
                 '--output', source, args.input], timeout=630)
        else:
            source = Path(args.input).expanduser().resolve()
            if not source.is_file():
                raise ValueError('입력 파일을 찾을 수 없습니다.')
        metadata = json.loads(subprocess.check_output([
            'ffprobe', '-v', 'error', '-show_entries',
            'format=duration:stream=index,codec_type,codec_name', '-of', 'json', str(source)], timeout=30))
        audio_streams = [s for s in metadata['streams'] if s['codec_type'] == 'audio']
        duration = float(metadata['format']['duration'])
        if not audio_streams or not 0 < duration <= 7200:
            raise ValueError('오디오 트랙이 있는 2시간 이내 자료가 필요합니다.')
        print(f'{duration:.1f}초 · 첫 번째 오디오 트랙 추출 중…', flush=True)
        codec = audio_streams[0]['codec_name']
        audio = work / 'audio.m4a'
        audio_options = ['-c:a', 'copy'] if codec == 'aac' else ['-c:a', 'aac', '-b:a', '96k']
        run(['ffmpeg', '-nostdin', '-v', 'error', '-n', '-i', source, '-map', '0:a:0',
             '-vn', *audio_options, audio], timeout=600)
        wav = work / 'audio.wav'
        run(['ffmpeg', '-nostdin', '-v', 'error', '-n', '-i', audio, '-ar', '16000',
             '-ac', '1', '-c:a', 'pcm_s16le', wav], timeout=180)
        print(f'Whisper 변환 중 · 진행 로그: {output / "whisper.log"}', flush=True)
        with (output / 'whisper.log').open('w') as log:
            run([args.cli.resolve(), '-m', args.model.resolve(), '-f', wav, '-l', args.language,
                 '-otxt', '-osrt', '-oj', '-of', work / 'transcript', '-sns', '-t', '6',
                 *(['-ng'] if args.cpu else [])], log=log)
        result = read_json(work / 'transcript.json')
        if not result.get('transcription') or not (work / 'transcript.txt').read_text().strip():
            raise ValueError('인식된 텍스트가 없습니다. whisper.log와 음성을 확인하세요.')
        for name in ('audio.m4a', 'transcript.txt', 'transcript.srt', 'transcript.json'):
            shutil.copyfile(work / name, output / name)
        write_json(output / 'transcription.json', {
            'version': 1, 'sourceSha256': digest(source), 'durationSeconds': duration,
            'sourceAudioCodec': codec, 'audioMode': 'copy' if codec == 'aac' else 'aac-96k',
            'model': args.model.name, 'language': args.language,
            'segments': len(result['transcription']), 'status': 'complete',
        })
    print(f'완료: {output}\n다음: 강의록을 검토해 course-pack.json 작성 → register --check', flush=True)


def validate_pack(directory, pack_path):
    raw = (directory / 'transcript.txt').read_text(encoding='utf-8')
    text(raw, 100_000, '강의록')
    pack = read_json(pack_path)
    if not isinstance(pack, dict) or not isinstance(pack.get('guides'), list) or len(pack['guides']) != 1:
        raise ValueError('이 도구는 강의 하나당 정리본 하나를 등록합니다.')
    guide = pack['guides'][0]
    if not isinstance(guide, dict):
        raise ValueError('정리본 형식을 확인하세요.')
    for name, limit in [('key', 120), ('title', 60), ('subject', 80), ('unit', 80), ('summary', 100_000)]:
        text(guide.get(name), limit, name)
    if not isinstance(guide.get('sources'), list) or len(guide['sources']) != 1:
        raise ValueError('현재 강의록에 해당하는 sources 경로 하나를 지정하세요.')
    validate_quiz(guide['quiz'])
    # Use the real importer and quote validator before touching the user's DB.
    with tempfile.TemporaryDirectory(prefix='lecture-check-') as temp:
        store = Store(temp)
        source_id = store.add('강의 자동 전사', 'transcript.txt', summary=raw, original_text=raw,
                              source_path=guide['sources'][0])
        store.update(source_id, subject=guide['subject'], unit=guide['unit'])
        install(store, prepare(store, pack))
    return raw, pack, guide


def register(args):
    directory = args.directory.resolve()
    pack_path = args.pack or directory / 'course-pack.json'
    raw, pack, guide = validate_pack(directory, pack_path)
    print(f'검증 완료: {len(guide["quiz"]["questions"])}문항 · 형식 및 원문 인용', flush=True)
    if args.check:
        return
    data = args.data_dir.resolve()
    database = data / 'study.sqlite3'
    # Back up with SQLite's API (also handles a database open by the web app).
    if database.exists():
        backup = data / 'backups' / f'before-lecture-{time.time_ns()}.sqlite3'
        backup.parent.mkdir(parents=True, exist_ok=True)
        with sqlite3.connect(database) as source, sqlite3.connect(backup) as target:
            source.backup(target)
        print(f'백업: {backup}', flush=True)
    store = Store(data)
    with store.connect() as db:
        existing = db.execute('SELECT lesson_id FROM study_guides WHERE pack_key=?', (guide['key'],)).fetchone()
        if existing:
            print(f'이미 등록됨: {existing[0]} · 기존 편집·풀이 기록 유지. 개정본은 새 key를 사용하세요.')
            return
        sources = db.execute('SELECT * FROM lessons WHERE source_path=?', (guide['sources'][0],)).fetchall()
        sources = [store.get(row['id']) for row in sources]
        if len(sources) > 1 or (sources and (sources[0]['original_text'] or sources[0]['summary']) != raw):
            raise ValueError('이 원본 경로에 다른 내용이 있습니다. 개정본에는 새 sources 경로를 사용하세요.')
        if sources and sources[0]['subject'] != guide['subject']:
            raise ValueError('기존 원본 과목이 다릅니다. 분류를 확인하거나 새 sources 경로를 사용하세요.')
    if not sources:
        source_id = store.add('강의 자동 전사', 'transcript.txt', summary=raw, original_text=raw,
                              source_path=guide['sources'][0], import_digest=hashlib.sha256(raw.encode()).hexdigest())
        # classify_path guesses the parent folder as subject when no week is present.
        store.update(source_id, subject=guide['subject'], unit=guide['unit'])
    ids = install(store, prepare(store, pack))
    write_json(directory / 'registration.json', {'lessonId': ids[0], 'key': guide['key'],
                                               'database': str(database), 'registeredAt': time.time()})
    print(f'등록 완료: {guide["subject"]} → {guide["unit"]} → {guide["title"]}\nID: {ids[0]}')


def publish(args):
    from export_lecture import default_root, export
    args.pack = None
    register(args)
    if not args.check:
        export(args.directory, args.library_root or default_root(), args.data_dir)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    stt = commands.add_parser('transcribe', help='파일/직접 미디어 URL → 음성·TXT·SRT·JSON')
    stt.add_argument('--input', required=True, help='로컬 영상/음성 파일 또는 직접 HTTP(S) 미디어 URL')
    stt.add_argument('--output', type=Path, required=True, help='새로운 빈 결과 폴더')
    stt.add_argument('--cli', type=Path, default=ROOT / 'tools/local-stt/build/whisper.cpp/build/bin/whisper-cli')
    stt.add_argument('--model', type=Path, default=ROOT / 'tools/local-stt/build/ggml-large-v3-q5_0.bin')
    stt.add_argument('--language', default='ko')
    stt.add_argument('--cpu', action='store_true', help='GPU 없이 실행 (더 느릴 수 있음)')
    stt.set_defaults(action=transcribe)
    save = commands.add_parser('register', help='검토한 정리본·문제를 검증하고 웹 서재에 등록')
    save.add_argument('--directory', type=Path, required=True, help='transcript.txt가 있는 폴더')
    save.add_argument('--pack', type=Path, help='기본값: 결과 폴더의 course-pack.json')
    save.add_argument('--data-dir', type=Path, default=ROOT / 'webApp/.local-data')
    save.add_argument('--check', action='store_true', help='대상 DB를 변경하지 않고 검증만 수행')
    save.set_defaults(action=register)
    publish_cmd = commands.add_parser('publish', help='서재 등록 후 Desktop/2026-2 학습 폴더에 저장·연결')
    publish_cmd.add_argument('--directory', type=Path, required=True)
    publish_cmd.add_argument('--library-root', type=Path)
    publish_cmd.add_argument('--data-dir', type=Path, default=ROOT / 'webApp/.local-data')
    publish_cmd.add_argument('--check', action='store_true', help='서재 등록 형식만 검증; 파일 내보내기 사전 확인은 export_lecture.py --check 사용')
    publish_cmd.set_defaults(action=publish)
    args = parser.parse_args()
    try:
        args.action(args)
    except (ValueError, OSError, KeyError, TypeError, subprocess.SubprocessError) as error:
        # Do not echo subprocess command arguments that may contain signed URLs.
        if isinstance(error, subprocess.SubprocessError):
            print('처리 실패: 위 오류 또는 whisper.log를 확인하세요. 서재 등록은 별도 단계입니다.', file=sys.stderr)
        else:
            print(f'처리 실패: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
