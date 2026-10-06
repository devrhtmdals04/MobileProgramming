'use strict';
const $ = id => document.getElementById(id);
const token = document.querySelector('meta[name="study-token"]').content;
const state = { lessons: [], activePdf: null, returnGuide: null, subject: null, lesson: null, tab: 'summary', busy: false, job: null, controller: null, editing: false, answers: null, quizQuestions: [], result: null };
// Official model pages checked 2026-10-05. Task labels are app recommendations.
const MODEL_PRESETS = [
  {id:'gpt-5.4-mini', name:'GPT-5.4 Mini', tag:'기본 추천', description:'일반 강의 PDF 요약과 복습 문제 생성의 출발점으로 추천합니다.', input:'0.75', output:'4.50'},
  {id:'gpt-5.6-luna', name:'GPT-5.6 Luna', tag:'비용 절약', description:'핵심 개념 정리와 기초 문제를 적은 비용으로 여러 번 만들 때 추천합니다.', input:'0.20', output:'1.20'},
  {id:'gpt-5.6-terra', name:'GPT-5.6 Terra', tag:'품질 중시', description:'개념 간 비교와 적용 문제 등에서 비용보다 답변의 완성도를 더 중시할 때 추천합니다.', input:'2.00', output:'12.00'},
  {id:'gpt-5.6-sol', name:'GPT-5.6 Sol', tag:'복잡한 자료', description:'수식·회로·복합 사례처럼 여러 조건을 함께 해석해야 하는 자료에 추천합니다.', input:'4.00', output:'20.00'},
];
function selectedModel() { return $('model').value === 'custom' ? $('customModel').value.trim() : $('model').value; }
function showModelInfo() {
  const preset = MODEL_PRESETS.find(m => m.id === $('model').value);
  $('customModelLabel').hidden = !!preset;
  $('customModel').required = !preset;
  if (!preset) { $('modelInfo').textContent = 'PDF 입력과 Responses API를 지원하는 모델 ID를 입력하세요. 이 모델의 요금은 공식 문서에서 확인해 주세요.'; return; }
  $('modelInfo').innerHTML = `<strong>${esc(preset.tag)}</strong><p>${esc(preset.description)}</p><div class="model-prices"><span>입력 <b>$${preset.input}</b></span><span>출력 <b>$${preset.output}</b></span></div><a href="https://developers.openai.com/api/docs/models/${preset.id}" target="_blank" rel="noopener noreferrer">공식 모델 정보 ↗</a>`;
}
function setModel(model) {
  if (MODEL_PRESETS.some(m => m.id === model)) { $('model').value = model; $('customModel').value = ''; }
  else { $('model').value = 'custom'; $('customModel').value = model; }
  showModelInfo();
}
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function notice(message, error = false) { $('notice').hidden = !message; $('notice').textContent = message; $('notice').className = error ? 'error' : ''; }
async function api(path, data) {
  const response = await fetch(path, data === undefined ? {} : { method: 'POST', headers: {'Content-Type':'application/json','X-Study-Token':token}, body: JSON.stringify(data) });
  const result = await response.json();
  if (!response.ok) throw Error(result.error || '요청을 처리하지 못했어요.');
  return result;
}
function action(fn) { return async event => { try { await fn(event); } catch (e) { notice(e.message, true); } }; }
function inline(value) {
  return esc(value).replace(/\(자료 (\d+)(?: · PDF (\d+)쪽)?\)/g, (match,n,page)=>{const source=state.lesson?.sources?.[Number(n)-1];return source?`<button class="text-button source-link" data-source="${esc(source.id)}" data-source-page="${page?Number(page):1}">자료 ${Number(n)}${page?' · PDF '+Number(page)+'쪽':''} ↗</button>`:match;}).replace(/`([^`]+)`/g, '<code>$1</code>').replace(/\*\*(.+?)\*\*/g,'<strong>$1</strong>').replace(/\(PDF\s*(\d+)쪽\)/g, (_, n) => `<button class="text-button page-link" data-page="${Number(n)}">PDF ${Number(n)}쪽 ↗</button>`);
}
// Deliberately small Markdown renderer: all source HTML is escaped, and no remote assets load.
function markdown(source) {
  const lines = String(source).replace(/\r/g,'').split('\n'); let html = '', i = 0;
  const cells = row => row.trim().replace(/^\||\|$/g,'').split('|').map(x => x.trim());
  while (i < lines.length) {
    const line = lines[i];
    if (!line.trim()) { i++; continue; }
    if (/^\s*```/.test(line)) {
      let code = ''; i++; while (i < lines.length && !/^\s*```/.test(lines[i])) code += lines[i++]+'\n'; i++;
      html += `<pre><code>${esc(code)}</code></pre>`; continue;
    }
    const heading = /^(#{1,6})\s+(.+)$/.exec(line);
    if (heading) { const n = heading[1].length; html += `<h${n}>${inline(heading[2])}</h${n}>`; i++; continue; }
    if (i+1 < lines.length && line.includes('|') && /^\s*\|?\s*:?-{3,}/.test(lines[i+1])) {
      const headers = cells(line); i += 2; let rows = '';
      while (i < lines.length && lines[i].includes('|') && lines[i].trim()) rows += '<tr>'+cells(lines[i++]).map(c => `<td>${inline(c)}</td>`).join('')+'</tr>';
      html += `<div class="table-scroll"><table><thead><tr>${headers.map(h => `<th>${inline(h)}</th>`).join('')}</tr></thead><tbody>${rows}</tbody></table></div>`; continue;
    }
    if (/^\s*([-*+] |\d+[.)] )/.test(line)) {
      const ordered = /^\s*\d/.test(line); let items = '';
      while (i < lines.length && /^\s*([-*+] |\d+[.)] )/.test(lines[i])) items += `<li>${inline(lines[i++].replace(/^\s*([-*+] |\d+[.)] )/,''))}</li>`;
      html += `<${ordered?'ol':'ul'}>${items}</${ordered?'ol':'ul'}>`; continue;
    }
    if (/^>/.test(line)) { html += `<blockquote>${inline(line.replace(/^>\s?/,''))}</blockquote>`; i++; continue; }
    if (/^\s*[-*_]{3,}\s*$/.test(line)) { html += '<hr>'; i++; continue; }
    html += `<p>${inline(line)}</p>`; i++;
  }
  return html;
}
function empty(title, message) { return `<div class="empty"><div class="mark">✧</div><h2>${esc(title)}</h2><p>${esc(message)}</p></div>`; }
async function refresh() {
  const result = await api('/api/state'); state.lessons = result.lessons;
  setModel(result.model); $('keyDot').textContent = result.keyReady ? '연결 준비됨' : '키 미설정';
  $('keyStatus').textContent = result.keyReady ? 'API 키가 설정되어 있습니다. 서버를 종료하면 메모리에서 사라집니다.' : '아직 API 키가 없습니다. 자료 가져오기와 저장된 노트 읽기는 사용할 수 있어요.';
  renderLibrary();
}
function subjectName(l) { return l.subject || '미분류'; }
function matchingLessons() {
  const needle = $('search').value.trim().toLowerCase();
  return state.lessons.filter(l => [l.title,l.filename,l.subject,l.unit,l.excerpt].join(' ').toLowerCase().includes(needle));
}
function renderLibrary() {
  $('lessonCount').textContent = new Set(state.lessons.map(subjectName)).size;
  const groups = [...new Set(state.lessons.map(subjectName))].sort((a,b)=>a.localeCompare(b,'ko',{numeric:true}));
  $('subjectNames').innerHTML = groups.filter(x=>x!=='미분류').map(x=>`<option value="${esc(x)}"></option>`).join('');
  $('library').innerHTML = groups.map(name => `<button class="lesson ${state.subject===name?'active':''}" data-subject="${esc(name)}" ${state.busy?'disabled':''}><span class="icon">과목</span><span><strong>${esc(name)}</strong><small>자료 ${state.lessons.filter(l=>subjectName(l)===name).length}개</small></span></button>`).join('');
  if (!state.lesson) renderDashboard();
}
function renderDashboard() {
  $('workspace').hidden = true;
  $('welcome').hidden = state.lessons.length > 0;
  $('dashboard').hidden = !state.lessons.length;
  const matching = matchingLessons();
  const rows = matching.filter(l=>state.subject===null || subjectName(l)===state.subject);
  $('crumb').textContent = state.subject || '내 과목';
  $('dashboardTitle').textContent = state.subject || '내 과목';
  $('dashboardDescription').textContent = state.subject ? '주차별 자료를 읽고, 정리하고, 복습하세요.' : '파일 속에 있던 수업을 한눈에. 과목을 선택해 공부를 이어가세요.';
  $('overview').innerHTML = `<span>자료 <b>${rows.length}</b></span><span>노트 <b>${rows.filter(l=>l.has_summary).length}</b></span><span>복습 <b>${rows.reduce((n,l)=>n+l.attempt_count,0)}회</b></span><span>남은 오답 <b>${rows.reduce((n,l)=>n+l.wrong_count,0)}</b></span>`;
  $('allSubjects').hidden = state.subject === null;
  const status = $('statusFilter').value;
  const filtered = rows.filter(l=>status==='all'||(status==='unorganized'&&!l.has_summary)||(status==='ready'&&l.has_summary)||(status==='wrong'&&l.wrong_count));
  const names = [...new Set(filtered.map(subjectName))].sort((a,b)=>a.localeCompare(b,'ko',{numeric:true}));
  $('subjectCards').hidden = state.subject !== null;
  $('subjectCards').innerHTML = names.map((name,i)=>{
    const items = filtered.filter(l=>subjectName(l)===name);
    return `<button class="subject-card accent-${i%4}" data-subject="${esc(name)}"><span class="eyebrow">SUBJECT / ${String(i+1).padStart(2,'0')}</span><h2>${esc(name)}</h2><p>자료 ${items.length} · 노트 ${items.filter(l=>l.has_summary).length} · 오답 ${items.reduce((n,l)=>n+l.wrong_count,0)}</p><span class="card-link">수업 자료 보기 ↗</span></button>`;
  }).join('') || empty('조건에 맞는 과목이 없어요','검색어나 학습 상태를 바꿔 보세요.');
  $('materialCards').hidden = state.subject === null;
  const units = [...new Set(filtered.map(l=>l.unit||'주차 미지정'))].sort((a,b)=>a.localeCompare(b,'ko',{numeric:true}));
  function card(l) { return `<button class="material-card ${l.is_guide?'guide-card':''}" data-id="${esc(l.id)}"><div class="card-meta"><span>${l.is_guide?(l.video_based?'영상 기반 정리':'수업 통합 정리'):l.has_pdf?'PDF':l.filename.toLowerCase().endsWith('.json')?'QUIZ':'NOTE'}</span><span>${l.has_summary?'노트 있음':'정리 전'}</span></div><h3>${esc(l.title)}</h3><p>${esc(l.excerpt || (l.has_quiz?'문제 모음이 준비되어 있어요.':'자료를 읽고 첫 정리본을 만들어 보세요.'))}</p><div class="card-footer"><span>${l.has_quiz?'문제 있음':'문제 없음'} · 복습 ${l.attempt_count}회</span><span>${l.wrong_count?'오답 '+l.wrong_count+'개':'열기 ↗'}</span></div></button>`; }
  $('materialCards').innerHTML = units.map(unit=>{
    const items=filtered.filter(l=>(l.unit||'주차 미지정')===unit), guides=items.filter(l=>l.is_guide), originals=items.filter(l=>!l.is_guide);
    return `<section class="unit-section"><h2>${esc(unit)} <small>${items.length}개 자료</small></h2>${guides.length?`<div class="guide-grid">${guides.map(card).join('')}</div>${originals.length?`<details class="original-materials"><summary>원본 자료 ${originals.length}개 보기</summary><div class="material-grid">${originals.map(card).join('')}</div></details>`:''}`:`<div class="material-grid">${originals.map(card).join('')}</div>`}</section>`;
  }).join('') || empty('조건에 맞는 자료가 없어요','검색어나 학습 상태를 바꿔 보세요.');
}
function openSubject(name=null) {
  if (state.busy) return;
  if (state.editing && !confirm('저장하지 않은 수정 내용이 있어요. 이동할까요?')) return;
  state.subject=name; state.lesson=null; state.editing=false; renderLibrary();
}
function pdfId(l=state.lesson) { return state.activePdf || (l?.has_pdf?l.id:l?.sources?.find(s=>s.has_pdf)?.id); }
function latestWrongs() {
  if (!state.lesson?.quiz) return [];
  const latest = new Map();
  for (const attempt of state.lesson.attempts || []) for (const result of attempt.results) {
    const q = state.lesson.quiz.questions.find(q => q.id === result.questionId);
    if (q && JSON.stringify(q) === JSON.stringify(result.question) && !latest.has(q.id)) latest.set(q.id, result);
  }
  return [...latest.values()].filter(r => !r.correct).map(r => r.question);
}
async function selectLesson(id, force = false) {
  if (state.busy) return;
  if (!force && state.editing && !confirm('저장하지 않은 수정 내용이 있어요. 이동할까요?')) return;
  state.lesson = await api('/api/lessons/'+encodeURIComponent(id)); state.editing = false; state.result = null;
  state.activePdf=null;
  state.subject=subjectName(state.lesson); $('dashboard').hidden=true;
  if ((state.tab==='pdf'&&!pdfId())||(state.tab==='original'&&!state.lesson.original_text)) state.tab='summary';
  $('welcome').hidden = true; $('workspace').hidden = false; renderLesson(); renderLibrary();
}
function renderLesson() {
  const l = state.lesson; if (!l) return;
  $('classify').textContent = l.file_backed ? '실제 파일·폴더 이동' : '분류 저장';
  $('subjectInput').disabled = !!l.file_backed; $('unitInput').disabled = !!l.file_backed;
  if (l.missing_files?.length) notice('원본 파일 확인 필요: '+l.missing_files.join(', '), true);
  $('openFolder').hidden = !l.local_folder;
  $('openFolder').title = l.local_folder || '';
  $('title').textContent = l.title; $('crumb').textContent = l.title;
  $('fileMeta').textContent = l.is_guide?`${l.subject} · ${l.unit} · ${l.video_based?'영상 기반 정리':'수업 통합 정리'}`:`${l.has_pdf?'PDF STUDY':'STUDY NOTE'} · ${l.filename}`;
  $('guideSources').hidden=!l.sources?.length;
  $('guideSources').innerHTML=(l.sources||[]).map((source,i)=>`<button class="quiet" data-source="${esc(source.id)}">자료 ${i+1} · ${esc(source.filename)} ↗</button>`).join('');
  $('returnGuide').hidden=!state.returnGuide || state.returnGuide===l.id;
  $('subjectInput').value=l.subject; $('unitInput').value=l.unit; $('sourcePath').textContent=l.local_folder || l.source_path || l.filename;
  document.querySelector('[data-tab="original"]').hidden=!l.original_text;
  $('wrongCount').textContent = latestWrongs().length;
  $('summarize').textContent = l.summary ? '✦ 요약 다시 만들기' : '✦ 요약 만들기';
  document.querySelector('[data-tab="pdf"]').disabled = !pdfId();
  $('stats').textContent = l.attempts.length ? `${l.attempts.length}회 복습 · 미해결 ${latestWrongs().length}문제` : '아직 풀이 기록이 없어요.';
  renderTab();
}
function renderTab() {
  if (!state.lesson) return;
  const l = state.lesson;
  document.querySelectorAll('[data-tab]').forEach(b => b.classList.toggle('active',b.dataset.tab === state.tab));
  $('document').hidden = state.tab !== 'summary' || state.editing;
  $('editor').hidden = !state.editing || state.tab !== 'summary';
  $('edit').hidden = state.tab !== 'summary' || state.editing || !l.summary;
  $('originalText').hidden=state.tab!=='original'; $('originalText').textContent=l.original_text||'';
  $('pdf').hidden = state.tab !== 'pdf'; $('questions').hidden = !['quiz','review'].includes(state.tab);
  $('paperLabel').textContent = ({summary:'STUDY NOTE',pdf:'ORIGINAL DOCUMENT',original:'ORIGINAL TEXT',quiz:'PRACTICE',review:'REVIEW AGAIN'})[state.tab];
  if (state.tab === 'summary') $('document').innerHTML = l.summary ? markdown(l.summary) : empty('첫 요약을 기다리고 있어요','오른쪽의 요약 만들기를 누르면 핵심이 정리됩니다.');
  if (state.tab === 'pdf' && pdfId() && !$('pdf').src.includes('/pdf/'+pdfId())) $('pdf').src = '/pdf/'+pdfId();
  if(state.tab==='pdf') $('paperLabel').textContent=l.sources?.find(s=>s.id===pdfId())?.filename || 'ORIGINAL DOCUMENT';
  if (['quiz','review'].includes(state.tab)) renderQuiz();
}
function setBusy(busy) {
  state.busy = busy;
  for (const id of ['folderImport','home','classify','backToSubject','import','welcomeImport','settingsOpen','summarize','generateQuiz','edit','export','count','difficulty','written']) $(id).disabled = busy;
  document.querySelectorAll('[data-tab]').forEach(b => b.disabled = busy || (b.dataset.tab==='pdf'&&!pdfId()));
  $('cancel').hidden = !busy; $('progress').hidden = !busy; renderLibrary();
}
async function importFile(file) {
  if (!file) return;
  if (state.editing && !confirm('수정 중인 노트를 저장하지 않고 가져올까요?')) return;
  if (file.size > 12*1024*1024) throw Error('12MB 이하의 자료를 선택해 주세요.');
  notice('자료를 컴퓨터에 저장하고 있어요…'); setBusy(true);
  try {
    const data = {filename:file.name};
    if (file.name.toLowerCase().endsWith('.pdf')) {
      data.base64 = await new Promise((resolve,reject) => { const reader = new FileReader(); reader.onload = () => resolve(reader.result.split(',')[1]); reader.onerror = () => reject(Error('파일을 읽지 못했어요.')); reader.readAsDataURL(file); });
    } else data.content = await file.text();
    const result = await api('/api/import',data); setBusy(false); state.tab='summary';
    await refresh(); await selectLesson(result.id,true); notice('자료를 가져왔어요. 이 컴퓨터에 저장됩니다.');
  } finally { setBusy(false); }
}
async function generate(kind) {
  if (!state.lesson) return;
  if (state.editing) throw Error('수정한 노트를 먼저 저장해 주세요.');
  if ((kind==='summary'&&state.lesson.summary || kind==='quiz'&&state.lesson.quiz) && !confirm('새 결과가 완성되면 현재 '+(kind==='summary'?'요약':'문제 모음')+'을 교체할까요? 실패하거나 중지하면 기존 자료는 유지됩니다.')) return;
  state.job = crypto.randomUUID(); state.controller = new AbortController(); setBusy(true); notice('');
  state.tab = kind==='summary'?'summary':'quiz'; state.result=null; renderTab();
  let output='', finished=false;
  $('progressText').textContent='자료를 읽고 있어요…';
  if (kind==='quiz') $('questions').innerHTML=empty('자료에서 문제를 만들고 있어요','완성되면 문제를 풀고 정답과 근거를 확인할 수 있습니다.');
  try {
    const response = await fetch('/api/generate',{method:'POST',headers:{'Content-Type':'application/json','X-Study-Token':token},body:JSON.stringify({id:state.lesson.id,job:state.job,action:kind,count:Number($('count').value),difficulty:$('difficulty').value,written:$('written').checked}),signal:state.controller.signal});
    if (!response.ok) throw Error((await response.json()).error);
    const reader=response.body.getReader(), decoder=new TextDecoder(); let buffer='';
    function event(line) {
      if (!line.trim()) return;
      const data=JSON.parse(line);
      if(data.type==='delta') { output+=data.text; $('progressText').textContent=kind==='summary'?'요약을 정리하고 있어요…':'문제와 해설을 만들고 있어요…'; if(kind==='summary') $('document').innerHTML=markdown(output); }
      if(data.type==='error') throw Error(data.text);
      if(data.type==='done') finished=true;
    }
    while(true) { const {done,value}=await reader.read(); if(done) break; buffer+=decoder.decode(value,{stream:true}); let i; while((i=buffer.indexOf('\n'))>=0) { event(buffer.slice(0,i)); buffer=buffer.slice(i+1); } }
    if(buffer.trim()) event(buffer);
    if(!finished) throw Error('생성이 중지되었거나 완료되지 않았어요. 기존 자료는 유지됩니다.');
    notice(kind==='summary'?'요약 노트를 저장했어요. 읽고 수정하거나 문제를 만들어 보세요.':'문제 모음을 저장했어요. 풀고 나서 정답과 근거를 확인해 보세요.');
  } catch(e) { notice(e.name==='AbortError'?'생성을 중지했어요. 기존 자료는 유지됩니다.':e.message,e.name!=='AbortError'); }
  finally { state.job=null; state.controller=null; setBusy(false); await refresh(); await selectLesson(state.lesson.id,true); }
}
function feedback(q, result) {
  return `<div class="feedback ${result.correct?'':'wrong'}"><strong>${result.correct?'✓ 이해했어요':'다시 살펴볼 문제'}</strong><p>정답 · ${esc(q.modelAnswer || q.choices[q.correctIndex])}</p><p>${inline(q.explanation)}</p><small>근거 · ${esc(q.sourceQuote)}</small></div>`;
}
function renderQuiz() {
  const l=state.lesson;
  if(!l.quiz) { $('questions').innerHTML=empty('배운 내용을 확인해 볼까요?','오른쪽에서 문항 수와 난이도를 선택하고 문제를 만들어 보세요.'); return; }
  const questions=state.result?state.quizQuestions:(state.tab==='review'?latestWrongs():l.quiz.questions);
  state.quizQuestions=questions;
  if(!questions.length) { $('questions').innerHTML=empty('남아 있는 오답이 없어요','문제를 풀면 헷갈렸던 내용을 여기서 다시 볼 수 있어요.'); return; }
  const result=state.result;
  $('questions').innerHTML=`<div class="quiz-toolbar"><h3>${state.tab==='review'?'오답 다시 풀기':'차근차근 문제 풀기'} · ${questions.length}문항</h3><button id="quizExport" class="text-button">JSON 내보내기 ↓</button></div>`+
    (result?`<div class="score">${result.results.filter(r=>r.correct).length} / ${result.results.length} <small>이해한 문제 · 서술형은 자기평가</small></div>`:'')+
    questions.map((q,i)=>`<article class="q-card" data-question="${esc(q.id)}"><div class="q-meta">QUESTION ${String(i+1).padStart(2,'0')} · ${q.type==='written'?'서술형':'객관식'}</div><h3>${esc(q.prompt)}</h3>`+
      (q.type==='written'?`<textarea data-written="${esc(q.id)}" aria-label="${i+1}번 답안" rows="4" placeholder="내 말로 설명해 보세요." ${result?'disabled':''}>${result?esc(result.results.find(r=>r.questionId===q.id)?.answer):''}</textarea><details><summary>모범 답안과 비교하기</summary><p>${esc(q.modelAnswer)}</p><ul>${q.rubric.map(c=>`<li>${esc(c)}</li>`).join('')}</ul></details>${!result?`<label class="choice"><input type="radio" name="q${i}" value="yes"> 핵심을 설명할 수 있어요</label><label class="choice"><input type="radio" name="q${i}" value="no"> 다시 복습할게요</label>`:''}`:
        q.choices.map((c,j)=>`<label class="choice"><input type="radio" name="q${i}" value="${j}" ${result?'disabled':''} ${result?.results.find(r=>r.questionId===q.id)?.answer===j?'checked':''}><span>${j+1}. ${esc(c)}</span></label>`).join(''))+
      (result?feedback(q,result.results.find(r=>r.questionId===q.id)):'')+'</article>').join('')+
    `<div class="button-row"><button id="submitQuiz" class="primary">${result?'다시 풀기':'답안 제출'}</button></div>`;
  $('quizExport').onclick=()=>download(l.title+'.quiz.json',JSON.stringify(l.quiz,null,2),'application/json');
  $('submitQuiz').onclick=action(async()=>{
    if(state.result) {state.result=null;renderQuiz();return;}
    const answers={};
    questions.forEach((q,i)=>{const checked=document.querySelector(`input[name="q${i}"]:checked`);if(!checked)throw Error(`${i+1}번 문제의 답${q.type==='written'?'과 자기평가':''}을 선택해 주세요.`); answers[q.id]=q.type==='written'?{text:document.querySelectorAll('[data-written]')[questions.slice(0,i).filter(x=>x.type==='written').length].value,correct:checked.value==='yes'}:Number(checked.value);});
    $('submitQuiz').disabled=true;
    try {state.result=await api('/api/attempt',{id:l.id,answers});state.lesson=await api('/api/lessons/'+l.id);await refresh();renderLesson();notice('풀이 결과를 저장했어요. 틀린 문제는 오답 복습에 모아두었습니다.');} finally {if($('submitQuiz'))$('submitQuiz').disabled=false;}
  });
}
function download(filename, content, type='text/markdown') { const url=URL.createObjectURL(new Blob([content],{type}));const a=document.createElement('a');a.href=url;a.download=filename;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000); }
$('import').onclick=$('welcomeImport').onclick=()=>{ $('file').value='';$('file').click(); };
$('file').onchange=action(e=>importFile(e.target.files[0]));
$('search').oninput=renderLibrary;
$('library').onclick=e=>{const b=e.target.closest('[data-subject]');if(b)openSubject(b.dataset.subject);};
document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>{if(state.editing){notice('수정 중인 노트를 저장하거나 취소해 주세요.',true);return;}state.tab=b.dataset.tab;state.result=null;renderTab();});
$('summarize').onclick=action(()=>generate('summary'));$('generateQuiz').onclick=action(()=>generate('quiz'));
$('cancel').onclick=action(async()=>{if(state.job){const result=await api('/api/cancel',{job:state.job});if(result.cancelled)state.controller?.abort();}});
$('edit').onclick=()=>{state.editing=true;$('editTitle').value=state.lesson.title;$('editBody').value=state.lesson.summary;renderTab();};
$('editCancel').onclick=()=>{state.editing=false;renderTab();};
$('save').onclick=action(async()=>{await api('/api/save',{id:state.lesson.id,title:$('editTitle').value,summary:$('editBody').value,fileRevision:state.lesson.file_revision});await refresh();await selectLesson(state.lesson.id,true);notice('수정한 노트를 저장했어요.');});
$('export').onclick=()=>{const l=state.lesson;if(['quiz','review'].includes(state.tab)&&l.quiz)download(l.title+'.quiz.json',JSON.stringify(l.quiz,null,2),'application/json');else if(l.summary)download(l.title+'.md','# '+l.title+'\n\n'+l.summary);else notice('먼저 요약 노트를 만들어 주세요.');};
$('model').onchange=showModelInfo;
$('settingsOpen').onclick=()=>{$('apiKey').value='';$('settings').showModal();};$('settingsClose').onclick=()=>$('settings').close();
$('settingsForm').onsubmit=action(async e=>{e.preventDefault();const data={model:selectedModel()};if($('apiKey').value.trim())data.key=$('apiKey').value.trim();await api('/api/settings',data);$('apiKey').value='';$('settings').close();await refresh();notice('API 설정을 적용했어요.');});
$('clearKey').onclick=action(async()=>{await api('/api/settings',{key:'',model:selectedModel()});$('apiKey').value='';await refresh();});
document.addEventListener('click',action(async e=>{
  if(state.busy)return;
  const sourceButton=e.target.closest('[data-source]');
  if(sourceButton) {
    if(state.editing){notice('수정 중인 노트를 먼저 저장하거나 취소해 주세요.',true);return;}
    const source=state.lesson?.sources?.find(s=>s.id===sourceButton.dataset.source);if(!source)return;
    if(source.has_pdf){state.activePdf=source.id;state.tab='pdf';renderTab();$('pdf').src='/pdf/'+source.id+'#page='+(sourceButton.dataset.sourcePage||1);}
    else{state.returnGuide=state.lesson.id;state.tab='original';await selectLesson(source.id);}
    return;
  }
  const page=e.target.closest('[data-page]');
  if(page&&pdfId()){if(state.editing)return;state.tab='pdf';renderTab();$('pdf').src='/pdf/'+pdfId()+'#page='+page.dataset.page;}
}));
$('returnGuide').onclick=action(async()=>{if(state.returnGuide){state.tab='summary';await selectLesson(state.returnGuide);}});
window.addEventListener('beforeunload',e=>{if(state.busy||state.editing){e.preventDefault();e.returnValue='';}});
refresh().catch(e=>notice('로컬 서버에 연결하지 못했어요. 실행 파일로 서버를 켜 주세요. '+e.message,true));

$('home').onclick=$('allSubjects').onclick=()=>openSubject();
$('backToSubject').onclick=()=>openSubject(state.subject);
$('subjectCards').onclick=e=>{const b=e.target.closest('[data-subject]');if(b)openSubject(b.dataset.subject);};
$('materialCards').onclick=action(e=>{const b=e.target.closest('[data-id]');if(b){state.tab='summary';return selectLesson(b.dataset.id);}});
$('statusFilter').onchange=renderDashboard;
$('search').oninput=()=>{if(!state.busy&&!state.editing){state.lesson=null;renderLibrary();}};
$('classify').onclick=action(async()=>{
  if(state.lesson.file_backed){showMove();return;}
  await api('/api/classify',{id:state.lesson.id,subject:$('subjectInput').value,unit:$('unitInput').value});
  await selectLesson(state.lesson.id,true);await refresh();notice('수업 분류를 저장했어요. 원본 폴더는 변경되지 않습니다.');
});
$('folderImport').onclick=()=>{if(state.editing){notice('수정 중인 노트를 먼저 저장해 주세요.',true);return;}$('folderFiles').value='';$('folderFiles').click();};
$('folderFiles').onchange=action(async e=>{
  const files=[...e.target.files];
  const supported=files.filter(f=>/\.(pdf|md|txt|json)$/i.test(f.name) && !f.webkitRelativePath.split('/').some(p=>p.startsWith('.')||['node_modules','build','dist'].includes(p)));
  if(supported.length>1000)throw Error('자료가 너무 많아요. 과목 폴더 단위로 가져와 주세요.');
  let count=0;const errors=[];setBusy(true);
  try {
    for(const file of supported) {
      notice(`수업 자료를 모으고 있어요… ${++count}/${supported.length}`);
      try {
        if(file.size>12*1024*1024)throw Error('12MB 초과');
        const data={filename:file.name,sourcePath:file.webkitRelativePath};
        if(/\.pdf$/i.test(file.name))data.base64=await new Promise((resolve,reject)=>{const r=new FileReader();r.onload=()=>resolve(r.result.split(',')[1]);r.onerror=()=>reject(Error('읽기 실패'));r.readAsDataURL(file);});
        else data.content=await file.text();
        await api('/api/import',data);
      }catch(error){errors.push(file.name+': '+error.message);}
    }
  }finally{setBusy(false);state.lesson=null;state.subject=null;await refresh();}
  notice(`${count-errors.length}개 자료 반영 · 제외 ${files.length-supported.length}개 · 실패 ${errors.length}개. 같은 경로·내용은 중복 추가하지 않습니다. 폴더 가져오기는 사본 저장이며 실시간 동기화는 아닙니다.`+(errors.length?' '+errors.slice(0,3).join(' / '):''),!!errors.length);
});

$('openFolder').onclick = action(async () => { await api('/api/open-folder', {id:state.lesson.id}); });

function showMove() {
  const paths = new Set(state.lesson.file_paths || []);
  for (const path of [...paths]) { const parts=path.split('/'); if(parts.length>2) paths.add(parts.slice(0,-1).join('/')); }
  $('moveSource').innerHTML=[...paths].map(p=>`<option value="${esc(p)}">${esc(p)}</option>`).join('');
  $('moveDestination').value=$('moveSource').value; $('moveDialog').showModal();
}
$('moveSource').onchange=()=>{$('moveDestination').value=$('moveSource').value;};
$('moveCancel').onclick=()=>$('moveDialog').close();
$('moveForm').onsubmit=action(async event=>{event.preventDefault();await api('/api/library/move',{source:$('moveSource').value,destination:$('moveDestination').value});$('moveDialog').close();await refresh();await selectLesson(state.lesson.id,true);notice('실제 경로와 서재 연결을 변경했습니다.');});
async function driveStatus() {
  const d=await api('/api/drive/status');
  $('driveStatus').textContent=(d.connected?'연결됨 · '+d.folderName:'Google 로그인 필요')+' · '+(d.busy?'전송 중':d.message);
  $('driveSync').disabled=!d.connected||d.busy;
  $('driveConnect').textContent=d.connected?'Google 다시 연결':'Google 연결';
  $('driveDetails').textContent=[d.libraryRoot,...(d.changes||[]),...(d.conflicts||[]),d.libraryStatus?.error||''].filter(Boolean).join('\n');
}
$('driveConnect').onclick=action(async()=>{const tab=window.open('about:blank','_blank');try{const d=await api('/api/drive/connect',{});if(tab)tab.location.href=d.url;else window.location.href=d.url;}catch(e){if(tab)tab.close();throw e;}});
$('driveSync').onclick=action(async()=>{await api('/api/drive/sync',{});await driveStatus();});
$('scanLibrary').onclick=action(async()=>{await api('/api/library/scan',{});await refresh();if(state.lesson&&!state.editing)await selectLesson(state.lesson.id,true);notice('학습 폴더 변경사항을 반영했습니다.');});
setInterval(async()=>{try{await driveStatus();if(!state.busy&&!state.editing&&!$('moveDialog').open){await refresh();if(state.lesson&&['summary','original','pdf'].includes(state.tab)){const next=await api('/api/lessons/'+encodeURIComponent(state.lesson.id));if(JSON.stringify(next.file_revision)!==JSON.stringify(state.lesson.file_revision)||next.unit!==state.lesson.unit||JSON.stringify(next.missing_files)!==JSON.stringify(state.lesson.missing_files)){state.lesson=next;renderLesson();}}}}catch(e){/* Keep the last view during transient connection failures. */}},10000);
driveStatus().catch(()=>{});
