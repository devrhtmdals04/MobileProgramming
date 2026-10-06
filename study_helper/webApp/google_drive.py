"""Google Drive OAuth and conservative file sync; credentials stay in memory."""
import base64
import hashlib
import json
import mimetypes
from pathlib import Path
import secrets
import threading
import time
import urllib.parse
import urllib.request
import urllib.error
import tempfile
import unicodedata
from file_library import safe, LOCK

API='https://www.googleapis.com/drive/v3/files'
FOLDER='application/vnd.google-apps.folder'
EXTENSIONS={'.md','.json','.pdf','.png','.jpg','.jpeg','.svg','.gif','.webp','.m4a','.wav','.mp3','.aac','.flac','.txt','.srt'}


def md5(path):
    with Path(path).open('rb') as file: return hashlib.file_digest(file,'md5').hexdigest()


def request(url, method='GET', data=None, headers=None):
    try:
        with urllib.request.urlopen(urllib.request.Request(url,data=data,headers=headers or {},method=method),timeout=90) as response:
            return response.read(),dict(response.headers),response.status
    except urllib.error.HTTPError as error:
        raise ValueError(f'Google 요청 실패 (HTTP {error.code}). 401이면 다시 연결하고, 412이면 원격 변경을 확인하세요.') from None


class Drive:
    def __init__(self,store,preferences=None):
        self.store=store
        path=preferences or Path.home()/'Library/Application Support/StudyHelperDesktop/preferences.json'
        self.settings=json.loads(path.read_text()) if path.exists() else {}
        self.tokens={};self.expires=0;self.pending=None
        self.busy=False;self.last={'message':'Google 연결이 필요합니다.','changes':[],'conflicts':[]}
        self.lock=threading.RLock();self.stop=threading.Event()
        with store.connect() as db:
            db.execute('CREATE TABLE IF NOT EXISTS web_drive_baseline (folder TEXT, path TEXT, remote_id TEXT, hash TEXT, PRIMARY KEY(folder,path))')

    def config(self):
        file=self.settings.get('oauth_file')
        if not file: raise ValueError('PC 앱의 Google OAuth 설정 파일이 필요합니다.')
        config=json.loads(Path(file).read_text()).get('installed')
        if not config: raise ValueError('데스크톱 OAuth 설정을 확인하세요.')
        return config

    def status(self):
        return {'configured':bool(self.settings.get('oauth_file') and self.settings.get('drive_folder')),
                'connected':bool(self.tokens),'folderName':self.settings.get('drive_folder_name',''),
                'folderId':self.settings.get('drive_folder',''),'busy':self.busy,**self.last}

    def begin(self,port):
        config=self.config();verifier=secrets.token_urlsafe(48);state=secrets.token_urlsafe(32)
        redirect=f'http://127.0.0.1:{port}/oauth2callback'
        self.pending=dict(verifier=verifier,state=state,redirect=redirect,expires=time.time()+600)
        query=dict(client_id=config['client_id'],redirect_uri=redirect,response_type='code',scope='https://www.googleapis.com/auth/drive',
                   state=state,code_challenge=base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b'=').decode(),
                   code_challenge_method='S256',access_type='offline',prompt='consent')
        return 'https://accounts.google.com/o/oauth2/v2/auth?'+urllib.parse.urlencode(query)

    def token_request(self,fields):
        config=self.config();fields.update(client_id=config['client_id'])
        if config.get('client_secret'): fields['client_secret']=config['client_secret']
        raw,_,_=request('https://oauth2.googleapis.com/token','POST',urllib.parse.urlencode(fields).encode(),{'Content-Type':'application/x-www-form-urlencoded'})
        value=json.loads(raw);self.tokens.update(value);self.expires=time.time()+int(value.get('expires_in',3600))-120

    def callback(self,params):
        pending=self.pending
        if not pending or time.time()>pending['expires'] or not secrets.compare_digest(params.get('state',''),pending['state']):
            raise ValueError('만료되었거나 올바르지 않은 로그인입니다. 다시 연결하세요.')
        self.pending=None
        if not params.get('code'): raise ValueError('Google 로그인이 취소되었습니다.')
        self.token_request(dict(code=params['code'],code_verifier=pending['verifier'],redirect_uri=pending['redirect'],grant_type='authorization_code'))
        self.last={'message':'연결 완료. 자동 동기화를 시작합니다.','changes':[],'conflicts':[]}

    def headers(self):
        if not self.tokens: raise ValueError('먼저 Google에 연결하세요.')
        if time.time()>=self.expires:
            if not self.tokens.get('refresh_token'): self.tokens={};raise ValueError('Google에 다시 연결하세요.')
            self.token_request(dict(refresh_token=self.tokens['refresh_token'],grant_type='refresh_token'))
        return {'Authorization':'Bearer '+self.tokens['access_token']}

    def api(self,url=API,method='GET',body=None,extra=None):
        headers=self.headers()
        if extra:headers.update(extra)
        if body is not None:headers['Content-Type']='application/json';body=json.dumps(body).encode()
        raw,response,status=request(url,method,body,headers)
        return json.loads(raw) if raw else {},response

    def listing(self):
        root=self.settings.get('drive_folder')
        if not root: raise ValueError('PC 앱에서 Drive 공유 폴더를 먼저 선택하세요.')
        self.api(API+'/'+urllib.parse.quote(root,safe='')+'?fields=id,name,mimeType')
        directories={'':root};files={};seen=set()
        def walk(parent,prefix,depth):
            if depth>20 or parent in seen: raise ValueError('Drive 폴더 구조를 확인하세요.')
            seen.add(parent);token=None;names=set()
            while True:
                query={'q':"'"+parent.replace("'","\\'")+"' in parents and trashed=false",'pageSize':1000,
                       'fields':'nextPageToken,files(id,name,mimeType,md5Checksum,version,parents)','supportsAllDrives':'true','includeItemsFromAllDrives':'true'}
                if token:query['pageToken']=token
                result,_=self.api(API+'?'+urllib.parse.urlencode(query))
                for item in result.get('files',[]):
                    name=unicodedata.normalize('NFC',item['name'])
                    if name.startswith('.'):continue
                    if name.casefold() in names or '/' in name or '\\' in name or name in ('.','..'): raise ValueError('Drive에 중복되거나 잘못된 파일 이름이 있습니다.')
                    names.add(name.casefold());rel=prefix+name
                    if item['mimeType']==FOLDER:
                        directories[rel]=item['id'];walk(item['id'],rel+'/',depth+1)
                    elif Path(name).suffix.lower() in EXTENSIONS:
                        if not item.get('md5Checksum'):continue
                        files[rel]=item
                    if len(files)>10000:raise ValueError('공유 파일이 너무 많습니다.')
                token=result.get('nextPageToken')
                if not token:break
        walk(root,'',0)
        return files,directories

    def parent(self,path,directories):
        parent=str(Path(path).parent)
        if parent=='.':return directories['']
        prefix=''
        for part in Path(parent).parts:
            next_path=(prefix+'/' if prefix else '')+part
            if next_path not in directories:
                item,_=self.api(method='POST',body={'name':part,'mimeType':FOLDER,'parents':[directories[prefix]]})
                directories[next_path]=item['id']
            prefix=next_path
        return directories[parent]

    def upload(self,path,rel,remote,directories):
        size=path.stat().st_size;before=md5(path)
        fields='id,name,md5Checksum,version,parents'
        meta={'name':Path(rel).name};headers={}
        if remote:
            current,response=self.api(API+'/'+remote['id']+'?fields='+fields)
            if current.get('md5Checksum')!=remote.get('md5Checksum'):raise ValueError('업로드 전 Drive 파일이 변경됐습니다.')
            if response.get('ETag') or response.get('Etag'):headers['If-Match']=response.get('ETag') or response.get('Etag')
            url='https://www.googleapis.com/upload/drive/v3/files/'+remote['id'];method='PATCH'
        else:
            meta['parents']=[self.parent(rel,directories)]
            url='https://www.googleapis.com/upload/drive/v3/files';method='POST'
        headers.update({'X-Upload-Content-Type':mimetypes.guess_type(path.name)[0] or 'application/octet-stream','X-Upload-Content-Length':str(size)})
        _,response=self.api(url+'?uploadType=resumable&fields='+fields,method,meta,headers)
        session=response.get('Location') or response.get('location')
        if not session or urllib.parse.urlsplit(session).hostname!='www.googleapis.com':raise ValueError('올바르지 않은 업로드 세션입니다.')
        with path.open('rb') as stream:
            offset=0
            while offset<size or (size==0 and offset==0):
                chunk=stream.read(1024*1024)
                content_range=f'bytes {offset}-{offset+len(chunk)-1}/{size}' if size else 'bytes */0'
                h=self.headers();h.update({'Content-Length':str(len(chunk)),'Content-Range':content_range})
                req=urllib.request.Request(session,data=chunk,headers=h,method='PUT')
                try:
                    with urllib.request.urlopen(req,timeout=180) as result:raw=result.read();status=result.status
                except urllib.error.HTTPError as error:
                    if error.code==308:status=308;raw=b''
                    else:raise ValueError(f'Drive 업로드 실패 (HTTP {error.code})') from None
                offset+=len(chunk)
                if status in (200,201):break
                if not chunk:raise ValueError('업로드가 완료되지 않았습니다.')
        result=json.loads(raw)
        if md5(path)!=before or result.get('md5Checksum')!=before:raise ValueError('전송 중 원본이 바뀌었습니다. 다시 동기화하세요.')
        return result

    def download(self,remote,target,expected):
        before=md5(target) if target.exists() else ''
        if before!=expected:raise ValueError('다운로드 전 로컬 파일이 변경됐습니다.')
        target.parent.mkdir(parents=True,exist_ok=True)
        temporary=None
        try:
            with tempfile.NamedTemporaryFile(dir=target.parent,prefix='.drive-',delete=False) as file:
                temporary=Path(file.name)
                req=urllib.request.Request(API+'/'+remote['id']+'?alt=media',headers=self.headers())
                with urllib.request.urlopen(req,timeout=180) as response:
                    while True:
                        chunk=response.read(1024*1024)
                        if not chunk:break
                        file.write(chunk)
            if md5(temporary)!=remote['md5Checksum']:raise ValueError('Drive 다운로드 체크섬이 일치하지 않습니다.')
            with LOCK:
                if (md5(target) if target.exists() else '')!=expected:raise ValueError('다운로드 도중 로컬 파일이 변경됐습니다.')
                temporary.replace(target)
        finally:
            if temporary:temporary.unlink(missing_ok=True)

    def sync(self):
        if not self.lock.acquire(blocking=False):return
        self.busy=True
        changes=[];conflicts=[];blocked=set()
        try:
            root=self.store.files.root
            if not root or not root.is_dir():raise ValueError('학습 폴더를 먼저 연결하세요.')
            remote,dirs=self.listing()  # Complete listing must succeed before mutations.
            local={}
            for file in root.rglob('*'):
                relative=file.relative_to(root)
                if any(part.startswith('.') or part in ('node_modules','build') for part in relative.parts):continue
                if file.is_file() and file.suffix.lower() in EXTENSIONS:
                    rel=unicodedata.normalize('NFC',relative.as_posix());safe(root,rel)
                    if rel in local:raise ValueError('로컬 파일 경로가 중복됩니다.')
                    local[rel]=file
            folder=self.settings['drive_folder']
            with self.store.connect() as db:
                baseline={r['path']:dict(r) for r in db.execute('SELECT * FROM web_drive_baseline WHERE folder=?',(folder,))}
            remote_by_id={r['id']:path for path,r in remote.items()}
            # Preserve file identity for an unambiguous local move (content unchanged).
            hashes={path:md5(file) for path,file in local.items()}
            for old,base in list(baseline.items()):
                remote_path=remote_by_id.get(base['remote_id'])
                if old not in local and remote_path==old:
                    candidates=[p for p,h in hashes.items() if h==base['hash'] and p not in baseline and p not in remote]
                    if len(candidates)>1:
                        blocked.update([old,*candidates]);conflicts.append(old+' (이동 후보가 여러 개임)');continue
                    if len(candidates)==1:
                        new=candidates[0];item=remote[old]
                        if item['md5Checksum']!=base['hash']:
                            blocked.update((old,new));conflicts.append(old+' (이동/원격 수정 충돌)');continue
                        current,headers=self.api(API+'/'+item['id']+'?fields=id,md5Checksum,parents')
                        if current['md5Checksum']!=base['hash']:raise ValueError('Drive 이동 전 내용이 변경됐습니다.')
                        parent=self.parent(new,dirs)
                        params={'fields':'id,name,md5Checksum,parents'}
                        if parent not in current.get('parents',[]):
                            params.update(addParents=parent,removeParents=','.join(current.get('parents',[])))
                        query=urllib.parse.urlencode(params)
                        condition={'If-Match':headers.get('ETag',headers.get('Etag',''))} if headers.get('ETag') or headers.get('Etag') else {}
                        moved,_=self.api(API+'/'+item['id']+'?'+query,'PATCH',{'name':Path(new).name},condition)
                        remote[new]=moved;del remote[old];baseline[new]=baseline.pop(old)
                        remote_by_id[item['id']]=new
                        with self.store.connect() as db:db.execute('UPDATE web_drive_baseline SET path=? WHERE folder=? AND path=?',(new,folder,old))
                        changes.append(new+' · Drive 경로 이동')
                elif remote_path and remote_path!=old and old in local and hashes[old]==base['hash'] and remote_path not in local:
                    target=safe(root,remote_path);target.parent.mkdir(parents=True,exist_ok=True)
                    with LOCK:
                        if md5(local[old])!=base['hash'] or target.exists():raise ValueError('로컬 이동 충돌입니다.')
                        local[old].rename(target)
                    local[remote_path]=target;hashes[remote_path]=hashes.pop(old);del local[old]
                    baseline[remote_path]=baseline.pop(old)
                    with self.store.connect() as db:db.execute('UPDATE web_drive_baseline SET path=? WHERE folder=? AND path=?',(remote_path,folder,old))
                    changes.append(remote_path+' · 로컬 경로 이동')
            for old,base in baseline.items():
                other=remote_by_id.get(base['remote_id'])
                if other and other!=old and old in local:
                    blocked.update((old,other));conflicts.append(old+' (경로 이동 충돌)')
            for rel in sorted(set(local)|set(remote)):
                if rel in blocked: continue
                self.last={'message':'확인·전송 중: '+rel,'changes':list(changes),'conflicts':list(conflicts)}
                lhash=hashes.get(rel);r=remote.get(rel);rhash=r.get('md5Checksum') if r else None;base=baseline.get(rel);bhash=base['hash'] if base else None
                target=safe(root,rel)
                if lhash==rhash:pass
                elif base and (lhash is None or rhash is None):
                    conflicts.append(rel+' (한쪽에서 삭제됨 · 자동 삭제하지 않음)');continue
                elif lhash is None:
                    self.download(r,target,'');lhash=rhash;changes.append(rel+' · 받기')
                elif rhash is None:
                    r=self.upload(local[rel],rel,None,dirs);rhash=lhash;changes.append(rel+' · 올리기')
                elif base and lhash==bhash:
                    self.download(r,target,lhash);lhash=rhash;changes.append(rel+' · 받기')
                elif base and rhash==bhash:
                    r=self.upload(local[rel],rel,r,dirs);rhash=lhash;changes.append(rel+' · 올리기')
                else:
                    conflicts.append(rel+' (양쪽 내용 다름)');continue
                if r and lhash==rhash:
                    with self.store.connect() as db:db.execute('INSERT OR REPLACE INTO web_drive_baseline VALUES (?,?,?,?)',(folder,rel,r['id'],lhash))
            self.store.files.scan()
            self.last={'message':f'동기화 완료 · 변경 {len(changes)} · 확인 필요 {len(conflicts)}','changes':changes,'conflicts':conflicts,'lastSync':time.time()}
        except Exception as error:
            self.last={'message':str(error) if isinstance(error,ValueError) else '동기화 연결에 실패했습니다. 네트워크와 Google 연결을 확인하세요.','changes':changes,'conflicts':conflicts}
        finally:
            self.busy=False;self.lock.release()

    def start_sync(self):
        if not self.busy:threading.Thread(target=self.sync,daemon=True).start()
