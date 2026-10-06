"""Compare an iPhone app snapshot with the canonical study folder (read-only).

Use devicectl to copy the app preferences, notes, question-sets, and selected
shared-folders directory to temporary storage first. Never read Keychain tokens.
"""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import sqlite3
import unicodedata


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream,'md5').hexdigest()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot',type=Path,required=True,help='preferences.plist, notes/, question-sets/, shared/가 있는 임시 폴더')
    parser.add_argument('--data-dir',type=Path,default=Path(__file__).resolve().parents[1]/'webApp/.local-data')
    parser.add_argument('--report',type=Path,required=True)
    args=parser.parse_args();snapshot=args.snapshot
    preferences=plistlib.load((snapshot/'preferences.plist').open('rb'))
    folder=preferences['study_drive_folder'];account=preferences['study_drive_account']
    scope=f'study_folder_mirror_{account}_{folder}_mobile'
    bases=json.loads(preferences.get(scope+'_hashes','{}'))
    bindings=json.loads(preferences.get(scope+'_notes','{}'))
    history=json.loads(preferences.get(f'study_folder_last_{account}_{folder}_mobile','{}'))
    root=Path(json.loads((args.data_dir/'library.json').read_text())['root'])
    with sqlite3.connect(args.data_dir/'study.sqlite3') as db:
        pc=dict(db.execute('SELECT path,hash FROM web_drive_baseline WHERE folder=?',(folder,)))
    nfc=lambda s:unicodedata.normalize('NFC',s)
    actual={nfc(p.relative_to(snapshot/'shared').as_posix()):p for p in (snapshot/'shared').rglob('*') if p.is_file()}
    mismatches=[];verified=0;unsupported=[]
    for path,expected in pc.items():
        if Path(path).suffix.lower()=='.srt':unsupported.append(path);continue
        mobile=actual.get(nfc(path))
        if not mobile:mismatches.append({'path':path,'reason':'mobile file missing'});continue
        if digest(mobile)!=expected or digest(root/path)!=expected:
            mismatches.append({'path':path,'reason':'file hash differs'});continue
        if bases.get(path,{}).get('hash')!=expected:
            mismatches.append({'path':path,'reason':'mobile sync baseline differs'});continue
        verified+=1
    bound_verified=0
    for path,row in bindings.items():
        kind=row['kind'];id=row['id']
        if kind not in ('notes','question-sets') or '/' in id or '..' in id:raise ValueError('Invalid binding')
        doc=snapshot/kind/(id+('.md' if kind=='notes' else '.json'))
        source=actual.get(nfc(path))
        if not doc.is_file() or doc.read_text()!=row['content']:
            mismatches.append({'path':path,'reason':'library content differs from binding'});continue
        if source is None or (row.get('raw') is not None and source.read_text()!=row['raw']):
            mismatches.append({'path':path,'reason':'binding differs from received file'});continue
        bound_verified+=1
    report=dict(folderName=preferences.get('study_drive_folder_name'),syncResult=history.get('result'),syncFinished=history.get('finished'),syncFailed=history.get('failed'),pcFiles=len(pc),verifiedFiles=verified,verifiedLibraryBindings=bound_verified,mobileBindings=len(bindings),unsupportedFiles=unsupported,mismatches=mismatches,uiVerified=False,editRoundTripVerified=False)
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k not in ('unsupportedFiles','mismatches')},ensure_ascii=False))
    print('mismatches',len(mismatches),'unsupported',len(unsupported))
    if mismatches or not history.get('finished') or history.get('failed'):raise SystemExit(1)

if __name__=='__main__':main()
