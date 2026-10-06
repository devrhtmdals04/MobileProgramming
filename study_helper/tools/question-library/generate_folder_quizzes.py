"""Curated, source-checked practice sets. No network calls or changes to original materials.
PDF text inputs are extracted with PDFKit; leadership uses locally reviewed OCR.
"""
from pathlib import Path
import argparse,json,unicodedata
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--root',type=Path,required=True,help='원본 학습 폴더')
parser.add_argument('--output',type=Path,required=True,help='새 결과 폴더 (기존 결과 덮어쓰기 금지)')
parser.add_argument('--pdf-text-root',type=Path,required=True,help='검토한 PDF 추출 텍스트 폴더')
args=parser.parse_args()
ROOT=args.root.resolve();OUT=args.output.resolve();PDF=args.pdf_text_root.resolve()
if OUT.exists():parser.error('출력 폴더가 이미 있습니다. 새 경로를 지정하세요.')
if not ROOT.is_dir() or not PDF.is_dir():parser.error('원본 및 PDF 텍스트 폴더를 확인하세요.')
manifest=[]
def source(folder, hint, pdf=False):
    base=PDF if pdf else ROOT/folder
    matches=[p for p in base.rglob('*') if p.is_file() and hint in unicodedata.normalize('NFC',p.name)]
    assert len(matches)==1,(folder,hint,matches)
    p=matches[0]
    return str(p),unicodedata.normalize('NFC',p.read_text())
def mc(prompt,correct,a,b,quote,explanation,kind='concept'):
    return dict(type=kind,prompt=prompt,choices=[correct,a,b],correctIndex=0,sourceQuote=quote,explanation=explanation)
def written(prompt,answer,criteria,quote):
    return dict(type='written',prompt=prompt,modelAnswer=answer,rubric=criteria,sourceQuote=quote,explanation='모범 답안의 핵심과 내 설명을 채점 기준에 따라 비교하세요.')
def emit(folder,title,src,questions):
    path,text=src
    for i,q in enumerate(questions):
        q['id']=f'q{i+1}'
        assert q['sourceQuote'] in text,(folder,i,q['sourceQuote'])
        if q['type']!='written':
            # Balance stored answers; runtime still shuffles choices.
            shift=i%3
            q['choices']=q['choices'][-shift:]+q['choices'][:-shift] if shift else q['choices']
            q['correctIndex']=shift
    heading=next((line[2:].strip() for line in text.splitlines() if line.startswith('# ')),title+' · 원문 발췌')[:60]
    doc=dict(schemaVersion=2,title=title,subject=folder.split('/')[0],sourceNoteId='',sourceNoteTitle=heading,questions=questions)
    dest=OUT/folder/'문제/2026-09-29_폴더복습.quiz.json';dest.parent.mkdir(parents=True,exist_ok=True)
    dest.write_text(json.dumps(doc,ensure_ascii=False,indent=2)+'\n')
    manifest.append(dict(folder=folder,file=str(dest.relative_to(OUT)),source=path,questions=len(questions)))

f='AI와정보보안/01주차';src=source(f,'1주차_정보보안_요약.md')
emit(f,'AI와정보보안 1주차 · CIA와 AI 자산',src,[
mc('허가받지 않은 사람이 성적표를 열람했다. 직접 침해된 요소는?','기밀성','무결성','가용성','인가된 사용자만 정보 자산에 접근하도록 보호','허가 없는 열람은 기밀성 침해다.'),
mc('수강신청 시스템을 필요할 때 사용할 수 없다. 직접 침해된 요소는?','가용성','기밀성','무결성','필요한 시점에 정보와 시스템을 정상적으로 이용할 수 있도록 보장','필요할 때 서비스를 이용할 수 있는지가 가용성이다.'),
mc('공개 저장소에 API Key를 올렸을 때 직접적인 위험은?','키 권한으로 서비스를 무단 호출할 수 있다','모델의 모든 가중치가 자동으로 공개된다','저장된 모든 데이터가 자동으로 삭제된다','공개 저장소에 노출되면 타인이 정상 사용자로 가장해 서비스를 무단 호출할 수 있다.','키가 제공하는 권한이 악용될 수 있으며 모든 데이터 공개·삭제가 자동으로 일어나는 것은 아니다.'),
written('기밀성과 무결성을 각각 열람·변경 사례로 설명하세요.','허가 없는 성적표 열람은 기밀성 침해이고, 허가 없는 점수 변경은 무결성 침해다.',['열람과 기밀성을 연결한다','변경과 무결성을 연결한다'],'노출 → 기밀성 / 변조 → 무결성 / 이용 불가 → 가용성')])
f='AI와정보보안/02주차';src=source(f,'2주차_시스템보안_요약.md')
emit(f,'AI와정보보안 2주차 · 인증과 권한',src,[
mc('비밀번호는 어떤 인증 기준에 해당하는가?','지식 기반','소유 기반','생체 기반','| 지식 기반 | 알고 있는 것 | 비밀번호 |','비밀번호는 사용자가 알고 있는 정보다.'),
mc('UNIX 권한 rw- r-- r--의 숫자 표기는?','644','755','655','rw- r-- r-- → (4+2) (4) (4) → 644','읽기 4, 쓰기 2, 실행 1을 각 범주별로 합한다.','calculation'),
mc('로그인 시 세션 ID를 새로 발급하는 방어와 가장 직접적으로 관련된 공격은?','세션 고정 공격','서비스 거부 공격','파일 암호화','로그인 성공 시 세션 ID 재발급이 핵심 방어','공격자가 미리 정한 세션 ID가 인증 후에도 사용되는 것을 막는다.'),
written('인증과 인가의 차이를 설명하세요.','인증은 신원을 확인하고, 인가는 확인된 주체가 무엇을 할 수 있는지 권한을 결정한다.',['인증을 신원 확인으로 설명한다','인가를 권한 부여와 연결한다'],'| **Authentication — 인증** | 신원 확인 | 누구인가? |\n| **Authorization — 인가** | 권한 부여 | 무엇을 할 수 있는가? |')])
f='AI와정보보안/03주차';src=source(f,'3주차_네트워크보안_요약.md')
emit(f,'AI와정보보안 3주차 · 주소와 TCP',src,[
mc('한 시스템에서 데이터를 받을 응용 프로그램을 구분하는 것은?','포트','전원 전압','화면 해상도','포트는 한 시스템 안에서 어느 응용 프로그램으로 데이터를 전달할지 구분한다.','IP로 목적지를 찾은 뒤 포트로 응용 프로그램을 구분한다.'),
mc('TCP 연결 설정의 순서는?','SYN → SYN+ACK → ACK','FIN → ACK → FIN','ACK → FIN → SYN','SYN → SYN+ACK → ACK','TCP는 세 단계 핸드셰이크로 연결을 설정한다.'),
mc('일반적인 2계층 스위치가 학습하는 대응 관계는?','포트와 MAC 주소','파일명과 확장자','URL과 비밀번호','포트와 MAC 주소의 대응 관계','로컬 링크의 프레임 전달에 MAC 주소 학습을 활용한다.'),
written('DoS와 DDoS의 공통점과 차이를 설명하세요.','둘 다 서비스 이용을 방해하며, DDoS는 분산된 여러 시스템이 동시에 공격한다.',['서비스 이용 방해라는 공통점을 설명한다','DDoS의 분산된 공격 주체를 설명한다'],'다수의 분산된 시스템이 동시에 공격하는 서비스 거부 공격.')])
f='AI와정보보안/공통';src=source(f,'인증 퀴즈 결과.md')
emit(f,'AI와정보보안 공통 · 인증 복습',src,[
mc('스마트카드나 OTP를 보유한 사실로 인증하는 유형은?','소유 기반 인증','지식 기반 인증','생체 기반 인증','**질문:** 스마트카드나 OTP처럼 사용자가 소유한 것을 이용하는 인증 유형은?','스마트카드나 OTP는 소유 기반 인증 수단이다.'),
mc('강의 퀴즈에서 UNIX의 기본 관리자 계정은?','root','guest','sa','**대표 정답:** `root`','UNIX의 관리자 계정으로 root를 구분한다.'),
mc('강의 퀴즈에서 Windows의 기본 관리자 계정은?','Administrator','root','nobody','**대표 정답:** `Administrator`','강의 퀴즈는 Windows와 UNIX의 관리자 계정을 구분한다.'),
written('정상 사용자의 세션 토큰을 탈취한 뒤 나중에 동일 토큰을 전송하는 공격을 설명하세요.','세션 재사용 공격이다. 이미 인증된 토큰을 다시 보내 사용자의 권한을 악용한다.',['세션 재사용 공격이라는 이름을 쓴다','동일 토큰을 재전송한다는 특징을 설명한다'],'**대표 정답:** `세션 재사용 공격`')])
f='디지털시스템입문/01주차';src=source(f,'1주차.md')
emit(f,'디지털시스템입문 1주차 · 회로 기초',src,[
mc('전류의 의미는?','단위 시간당 이동하는 전하량','단위 길이당 저항','저장된 에너지의 총량','전류는 단위 시간당 이동하는 전하량이다.','전류는 전하량의 시간 변화율이다.'),
mc('10 V가 걸린 2 Ω 저항의 전류는?','5 A','20 A','0.2 A',r'V=IR,\qquad I=\frac{V}{R},\qquad R=\frac{V}{I}','I=V/R=10/2=5 A이다.','calculation'),
mc('한 노드에 2 A와 3 A가 들어오고 하나의 가지로만 나간다. 나가는 전류는?','5 A','1 A','6 A','들어오는 전류의 합 = 나가는 전류의 합','KCL에 따라 2+3=5 A이다.','calculation'),
written('KCL과 KVL이 각각 어떤 보존 원리와 관련되는지 설명하세요.','KCL은 전하량 보존에 따라 노드의 전류 합을, KVL은 에너지 보존에 따라 폐회로의 전압 합을 다룬다.',['KCL과 전하량 보존을 연결한다','KVL과 에너지 보존을 연결한다'],'전하량 보존에 따라 한 노드에서 전류의 대수합은 0이다.')])
f='디지털시스템입문/02주차';src=source(f,'2주차.md')
emit(f,'디지털시스템입문 2주차 · RC와 RL',src,[
mc('이상적인 커패시터를 직류 정상상태에서 어떻게 취급하는가?','개방회로','단락회로','전류가 무한히 증가하는 소자','직류 정상상태에서는 개방회로(Open Circuit)','전압이 일정하면 dv/dt=0이므로 전류가 0이다.'),
mc('RC 회로에서 R=2 kΩ, C=10 µF일 때 시정수는?','20 ms','200 ms','2 ms',r'\boxed{\tau=RC}','2000×10×10⁻⁶=0.02 s=20 ms이다.','calculation'),
mc('인덕턴스가 일정한 RL 자연응답에서 저항을 키우면?','전류가 더 빠르게 감소한다','전류가 더 느리게 감소한다','항상 전류가 증가한다','$R$이 클수록 전류가 빠르게 감소한다.','RL의 시정수 L/R이 작아져 감소가 빨라진다.'),
written('저항 증가가 RC와 RL의 감소 속도에 반대 영향을 주는 이유는?','RC는 τ=RC이므로 느려지고, RL은 τ=L/R이므로 빨라진다. 다른 소자 값은 일정하다고 가정한다.',['RC에서 시정수가 증가함을 설명한다','RL에서 시정수가 감소함을 설명한다'],'| 시정수 | $\\tau=RC$ | $\\tau=L/R$ |')])
f='디지털시스템입문/03주차';src=source(f,'Digital_systems_3_diode.pdf.txt',True)
emit(f,'디지털시스템입문 3주차 · 반도체와 다이오드',src,[
mc('PN 접합 다이오드의 p측 단자는?','애노드(anode)','캐소드(cathode)','베이스(base)',"다이오드의 p-side 를 ‘anode’, n-side 를 ‘cathode’ 라 함.",'p측은 애노드, n측은 캐소드다.'),
mc('다이오드에 순방향 바이어스를 가하는 조건은?','애노드 전위가 캐소드보다 높다','캐소드 전위가 애노드보다 높다','두 단자를 항상 접지한다','어노드 전위가 캐소드 전위보다 높게 인가','순방향 바이어스는 애노드 전위를 높이는 방향이다.'),
mc('강의에서 n형 실리콘을 만들 때 첨가하는 불순물은?','5족 원소','3족 원소','불순물을 전혀 넣지 않음','Si 에 5족 원소 (P, As, Sb, Bi ) 불순물을 첨가한 반도체','5족 불순물은 전자 캐리어를 제공한다.'),
written('PN 접합의 애노드·캐소드와 순방향 전위 관계를 설명하세요.','p측이 애노드, n측이 캐소드이며 순방향에서는 애노드 전위가 더 높다.',['p측/애노드와 n측/캐소드를 구분한다','순방향 전위 관계를 설명한다'],"다이오드의 p-side 를 ‘anode’, n-side 를 ‘cathode’ 라 함.")])
f='모바일프로그래밍/공통';src=source(f,'02_코틀린 문법 다루기_rev2.pdf.txt',True)
emit(f,'모바일프로그래밍 공통 · Kotlin 기초',src,[
mc('실행 중 값을 다시 대입할 지역 변수를 선언할 때 사용할 키워드는?','var','val','package','var로 선언한 변수는 프로그램 실행 중 여러 번 값을 변경하거나 재할당이 가능함','var는 재할당 가능한 변수를 선언한다.'),
mc('크기 5인 배열에서 세 번째 원소의 인덱스는?','2','3','5','첫 번째 값은 [0], 세 번\n째 값은 [2]로 접근','0부터 세면 세 번째 위치는 인덱스 2다.','calculation'),
mc('Kotlin에서 단일 문자를 표현하는 자료형은?','Char','Boolean','Double','단일 문자를 저장할 때 사용되는 자료형','Char는 작은따옴표로 표현하는 단일 문자 자료형이다.'),
written('var와 val의 재할당 가능 여부를 비교하세요.','var는 재할당 가능하고 val은 한 번 초기화한 뒤 다른 값을 재할당할 수 없다.',['var의 재할당 가능성을 설명한다','val의 재할당 제한을 설명한다'],'var과 val 두 가지 키워드 중 하나를 선택하여 사용\n 변수 변경 가능 여부에 따라 선택이 달라짐')])
f='생성형AI를활용한마케팅/01주차';src=source(f,'w01-OT-AI시대의창직과1인기업_요약.md')
emit(f,'생성형AI를활용한마케팅 1주차 · 문제와 서비스',src,[
mc('수업에서 강조하는 목표와 가장 가까운 것은?','구체적인 문제를 해결하는 작은 서비스를 기획·제작·배포하기','도구 이름을 많이 암기하기','처음부터 모든 사용자를 위한 거대한 플랫폼 만들기','구체적인 문제를 해결하는 작은 서비스를 직접 기획·제작·배포하는 것이 수업의 목표다.','문제 해결과 실제 결과물이 핵심이다.'),
mc('AX 업무 흐름의 첫 단계는?','업무 진단','성과 확인','솔루션 도입','**업무 진단:** 누가 어떤 일을 반복하는지 관찰한다.','반복 업무와 현재 작업 방식을 먼저 이해한다.'),
mc('자료에서 첫 버전을 작게 시작하는 이유와 맞는 설명은?','우선 작동하게 만들고 사용자가 생긴 뒤 확장한다','품질을 판단할 필요가 없어진다','사용자 없이 모든 기능을 완성해야 한다','우선 작동하게 만들고, 사용자가 생긴 뒤 기능을 확장한다.','작은 작동 제품에서 시작해 실제 사용을 확인한다.'),
written('서비스 아이디어에서 입력과 출력을 명확히 해야 하는 이유를 설명하세요.','사용자가 무엇을 제공하고 어떤 결과를 얻는지 분명해져 서비스의 가치와 범위를 설명할 수 있다.',['사용자의 입력을 구체화한다','제공할 결과와 가치를 연결한다'],'무엇을 넣고 무엇을 받는지 한 문장으로 설명할 수 있다.')])
f='시스템프로그래밍/02주차';src=source(f,'04-값-복사와-포인터.md')
emit(f,'시스템프로그래밍 2주차 · 값과 포인터',src,[
mc('int y=42; int x=y; 이후 y=100;을 실행하면 x는?','42','100','컴파일할 수 없다','따라서 복사한 뒤 `y`를 변경해도 `x`에는 영향을 주지 않는다.','x에는 복사 시점의 값이 별도로 저장되어 있다.'),
mc('int y=42; int *x=&y;에서 x에 저장되는 것은?','y의 주소','정숫값 42의 복사본','항상 NULL','`x`에는 정숫값의 복사본이 아니라 `y`가 저장된 주소가 들어간다.','포인터 변수는 원본이 있는 주소를 저장한다.'),
mc('int y=42; int *x=&y; 이후 *x=100;을 실행하면 y는?','100','42','항상 0','반대로 포인터를 역참조하여 값을 변경하면 원본 `y`가 변경된다.','*x가 y를 가리키므로 y의 값이 바뀐다.'),
written('값 복사와 포인터 저장은 원본이 바뀔 때 어떻게 다른가요?','값 복사본은 기존 값을 유지하지만 포인터를 역참조하면 원본의 바뀐 값을 읽는다.',['값 복사본의 독립성을 설명한다','역참조가 원본을 읽음을 설명한다'],'값 복사는 그 순간의 값을 별도로 저장하고, 포인터는 원본 변수에 계속 접근할 수 있는 주소를 저장한다.')])
f='시스템프로그래밍/03주차';src=source(f,'07-음수와-부호-확장.md')
emit(f,'시스템프로그래밍 3주차 · 2의 보수와 부호 확장',src,[
mc('8비트 11111111을 unsigned로 해석하면?','255','-1','127','| `1111 1111` | 255 | -1 |','unsigned에서는 모든 자리값을 양수로 더해 255가 된다.','calculation'),
mc('8비트 11111111을 signed 2의 보수로 해석하면?','-1','255','-127','2의 보수에서는 0이 하나이며, 모든 비트가 1인 값은 -1이다.','255-256=-1이다.','calculation'),
mc('음수인 작은 signed 정수의 값을 유지하며 더 큰 signed 정수로 옮기는 방법은?','상위에 최상위 비트를 반복한다','항상 상위에 0을 채운다','모든 비트를 뒤집기만 한다','값을 유지하기 위해 최상위 비트를 왼쪽으로 반복','음수의 부호 확장은 상위 비트를 1로 채운다.'),
written('8비트 -128을 32비트 signed 정수로 옮길 때 상위에 0을 채우면 안 되는 이유는?','0으로 채우면 +128로 해석된다. 값 -128을 유지하려면 최상위 비트 1을 상위에 반복해야 한다.',['0 확장 시 +128이 됨을 설명한다','1로 부호 확장해야 함을 설명한다'],'0을 채우면 `00000000 00000000 00000000 10000000`이 되어 +128로 값이 달라진다.')])
f='시스템프로그래밍/04주차';src=source(f,'08-동적-메모리-할당.md')
emit(f,'시스템프로그래밍 4주차 · 동적 할당',src,[
mc('할당받은 모든 바이트를 0으로 설정하는 함수는?','calloc','malloc','free','| `calloc(n, size)` | n개 × size바이트 할당 | 모든 바이트를 0으로 설정 |','calloc은 바이트를 0으로 초기화한다. 모든 자료형의 의미상 0까지 일반화하지 않는다.'),
mc('양수 크기로 realloc 호출이 실패했을 때 기존 할당은?','유지된다','자동으로 해제된다','반드시 새 주소로 옮겨진다','실패하면 `NULL`을 반환하고 기존 할당은 유지된다.','실패 시 기존 포인터로 정리하거나 계속 사용할 수 있다.'),
mc('동적 버퍼를 관리할 때 프로그램이 추적해야 하는 것은?','사용 중인 원소 수와 확보한 용량','포인터 앞 메모리의 비공개 메타데이터','항상 고정된 최대 입력 크기','사용 중인 원소 수(length)','논리적 사용량과 확보 용량은 프로그램이 관리해야 한다.'),
written('p = realloc(p, n)보다 임시 포인터를 사용하는 이유를 설명하세요.','실패하면 NULL이 반환되므로 바로 p에 넣으면 기존 할당 주소를 잃을 수 있다. 임시 포인터로 성공 여부를 확인한 후 p를 갱신한다.',['실패 시 NULL이 반환됨을 설명한다','기존 주소 보존과 성공 후 갱신을 설명한다'],'`p = realloc(p, new_size)`로 바로 대입하면 실패 시 기존 할당 주소를 잃을 수 있다.')])
f='세계화와글로벌이슈/02주차';src=source(f,'배달치킨가격분석과_글로벌이슈.pdf.txt',True)
emit(f,'세계화와글로벌이슈 2주차 · 공급망과 가격',src,[
mc('자료가 배달치킨 가격 사례로 설명하려는 핵심 개념은?','세계화의 상호의존성','국가 간 완전한 경제적 단절','모든 가격의 단일 원인','세계화의 상호의존성','일상 소비재도 글로벌 공급망과 여러 비용에 연결되어 있다.'),
mc('달러 결제 수입품의 원화 비용을 높일 수 있는 변화는?','원화 가치 하락','원화 가치 상승만','환율과 관계없이 항상 비용 0','지속적인 원화 가치\n절하는 원자재 가격 상승분을 곱절로 증폭시켰습니다.','같은 달러 가격이라도 원화 가치가 하락하면 원화 결제 비용이 커진다.'),
mc('이 자료에서 이중가격제의 의미는?','매장 가격과 배달앱 주문 가격을 다르게 받는 것','모든 주문을 두 번 결제하는 것','두 국가가 같은 통화를 쓰는 것','매장 가격보다 배달앱 주문 가격을 1,000~2,000원 더 비싸게 받는 이중가격','판매 경로에 따라 가격을 달리하는 구조를 설명한다.'),
written('일상 소비재 가격을 한 기업의 결정만으로 설명하기 어려운 이유는?','수입 원재료, 환율, 물류, 플랫폼 비용 등이 연결되어 최종 가격에 영향을 주기 때문이다.',['두 가지 이상의 비용 요인을 제시한다','요인들이 최종 가격에 연결됨을 설명한다'],'글로벌 공\n급망, 지정학적 리스크, 플랫폼 경제의 연쇄 효과')])
f='섬김의리더십/02주차';src=source(f,'leadership-ocr.txt',True)
emit(f,'섬김의리더십 2주차 · 섬김과 권위',src,[
mc('자료에서 섬김의 리더십이 바탕으로 삼는 것은?','인간 존중','명령에 대한 무조건 복종','구성원 간 경쟁만','인간 존중을 바탕으로 섬기고 봉사하는 자세로','인간 존중과 후원을 통해 구성원의 잠재력을 이끈다.'),
mc('자료의 섬김의 리더 행동에 해당하는 것은?','팀원의 의견 존중','상명하복만 요구','지시와 통제만 강화','팀원의 의견 존중','섬김의 리더는 팀원의 의견을 존중한다.'),
mc('자료가 섬김의 리더십과 연결한 조합은?','소통·신뢰·권한 위임','명령·규율·복종','침묵·불신·권한 독점','소통, 신뢰, 권한 위임','자료는 함께 성장하는 섬김의 리더에게 소통·신뢰·권한 위임을 연결한다.'),
written('섬김의 리더가 구성원의 잠재력을 이끌어 내는 방법을 설명하세요.','인간 존중을 바탕으로 구성원을 후원·지지하고, 의견을 존중하며 소통과 권한 위임으로 성장을 돕는다.',['인간 존중과 후원·지지를 언급한다','의견 존중 또는 소통·권한 위임의 행동을 제시한다'],'구성원들을 후원하고 지지함으로써 잠재력을 이끌어 내는 지도력.')])
# No invented quiz for an empty directory or a link-only/personal-idea note.
skipped=[{'folder':'생성형AI를활용한마케팅/공통','reason':'아이디어 한 줄 및 제출 링크만 있음'},
 {'folder':'섬김의리더십/공통','reason':'봉사 후보 링크만 있음'},
 {'folder':'웹프로그래밍/공통','reason':'학습 자료 없음'},
 {'folder':'Study Helper','reason':'기존 동기화 사본이 모인 폴더로 과목별 원본과 중복 출제하지 않음'}]
report={'created':manifest,'existing':[{'folder':'디지털시스템입문/04주차','questions':10,'file':'문제/2026-09-28_BJT_기초10문항.quiz.json'}],'skipped':skipped}
OUT.mkdir(parents=True,exist_ok=True)
(OUT/'manifest.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
lines=['# 폴더별 복습 문제','',f'새 문제 {len(manifest)}묶음, {sum(x["questions"] for x in manifest)}문항. 기존 BJT 10문항 유지.','',
 '원본을 읽고 작성한 기본 복습용 문제다. 각 문항에 원문 근거를 포함한다. 서술형은 답안 비교용이며 자동 채점하지 않는다.','',
 '| 폴더 | 문항 | 근거 자료 |','|---|---:|---|']
for row in manifest: lines.append(f'| {row["folder"]} | {row["questions"]} | {Path(row["source"]).name} |')
lines+=['','## 생성하지 않은 폴더','']+[f'- {x["folder"]}: {x["reason"]}' for x in skipped]
(OUT/'README.md').write_text('\n'.join(lines)+'\n')
print(f'Created {len(manifest)} sets / {sum(x["questions"] for x in manifest)} questions; all source quotes matched.')
