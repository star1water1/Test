package com.novelcharacter.app.pcbridge

/**
 * PC 화면의 껍데기와 자산 — **코틀린 상수로 둔다.**
 *
 * ## 왜 res/raw가 아니라 상수인가
 *
 * 자산을 읽는 코드가 생기면 그 코드가 ktor 어댑터 쪽으로 가고, 어댑터는 이 프로브가 보지
 * 못하는 자리다(`tools/probe_compile.sh`의 `PCBRIDGE_EXCLUDE`). 상수로 두면 **이 파일이
 * 프로브 범위에 들어오고** 어댑터는 글자를 그대로 내보내는 일만 남는다.
 *
 * ## 자바스크립트는 도메인 로직을 갖지 않는다
 *
 * 설계 문서 5-5의 규약이다. 아래 스크립트가 하는 일은 셋뿐이다 — 받아 온 기술자대로 입력칸을
 * 그리고, 사용자가 적은 **날 글자**를 그대로 돌려보내고, 폰이 돌려준 사유를 띄운다.
 * 쉼표를 쪼개거나 값을 다듬는 자리는 **하나도 없다.** 있으면 그 판정이 두 언어에 살게 되고
 * 둘은 반드시 갈린다(R-50 · R-63).
 *
 * ## 문구는 합니다체다
 *
 * 이 파일의 글자는 화면 문구이므로 `tools/check_text_style.sh`가 읽는다(그 검사는
 * `app/src/main/java`의 코틀린 리터럴을 전수로 본다). 해요체·보조용언 붙여쓰기·금지 용어가
 * 여기에도 그대로 걸린다.
 */
internal object BridgeWeb {

    /**
     * 토큰을 질의로 받는 첫 진입. 스크립트가 그 토큰을 이후 요청에 그대로 싣는다.
     *
     * ## 자산 주소에도 토큰을 박는다 — 실사용에서 물린 자리다 (2026.11.23)
     *
     * 종전에는 이 글자가 상수였고 자산을 `href="app.css"`로 가리켰다. 그러면 브라우저가
     * **질의 없이** `/app.css`를 부르고, [BridgeHandler]는 모든 요청에 토큰을 요구하므로
     * **자산만 401로 막힌다.** 증상이 고약하다 — 껍데기 HTML은 그대로 오므로 화면이 뜨고,
     * 글자도 보이고, 다만 **스타일이 없고 아무것도 동작하지 않는다.** 서버가 꺼진 것도
     * 토큰이 틀린 것도 아니어서 어디를 봐야 할지가 드러나지 않는다.
     *
     * **자산을 토큰에서 면제하지 않은 것은 일부러다.** 면제는 특례를 하나 만드는 일이고,
     * 특례가 생기면 *"어느 경로가 토큰 없이 열리는가"*를 사람이 기억해야 한다. 대신 주소에
     * 토큰을 실어 **모든 요청이 같은 규칙을 지나게** 두었다 — 스크립트가 API와 이미지에
     * 하던 것과 같은 모양이다.
     *
     * 토큰 글자 집합은 영숫자뿐이라([BridgeToken]) 질의에 그대로 담아도 탈이 없다.
     */
    fun html(token: String): String = """<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>캐릭터 편집</title>
<link rel="stylesheet" href="app.css?t=TOKEN">
</head>
<body>
<header>
  <h1>캐릭터 편집</h1>
  <div class="row">
    <label for="novel">작품</label>
    <select id="novel"></select>
    <span id="status" class="status"></span>
  </div>
</header>
<main>
  <nav id="list" aria-label="캐릭터 목록"></nav>
  <section id="editor">
    <p class="empty">왼쪽에서 캐릭터를 고르십시오.</p>
  </section>
</main>
<script src="app.js?t=TOKEN"></script>
</body>
</html>
""".replace(TOKEN_PLACEHOLDER, token)

    /**
     * 자산 주소의 토큰 자리. **보간(`${'$'}`)을 쓰지 않는 이유**는 이 파일의 다른 상수들이
     * 자바스크립트와 CSS라 `${'$'}`가 섞여 있을 수 있고, raw 문자열에서 그것이 전부
     * 보간으로 읽히기 때문이다. 자리 글자를 하나 두고 바꾸는 쪽이 그 함정을 피한다.
     */
    private const val TOKEN_PLACEHOLDER = "TOKEN"

    val CSS: String = """
:root { color-scheme: light dark; --line:#d8d8dc; --bg:#fff; --fg:#1a1a1c; --muted:#6a6a70;
        --accent:#2b5fd9; --warn:#a8511a; --warnbg:#fff6ec; }
@media (prefers-color-scheme: dark) {
  :root { --line:#3a3a40; --bg:#17171a; --fg:#eaeaee; --muted:#9a9aa2;
          --accent:#7aa2ff; --warn:#e0a86a; --warnbg:#2a2117; }
}
* { box-sizing:border-box; }
body { margin:0; font:15px/1.6 system-ui, -apple-system, "Segoe UI", sans-serif;
       background:var(--bg); color:var(--fg); }
header { padding:12px 16px; border-bottom:1px solid var(--line); position:sticky; top:0;
         background:var(--bg); z-index:2; }
header h1 { margin:0 0 8px; font-size:17px; font-weight:650; }
.row { display:flex; gap:10px; align-items:center; flex-wrap:wrap; }
.status { color:var(--muted); font-size:13px; }
.status.saved { color:var(--accent); }
.status.failed { color:var(--warn); }
main { display:grid; grid-template-columns:minmax(220px, 300px) 1fr; min-height:calc(100vh - 62px); }
@media (max-width:760px) { main { grid-template-columns:1fr; } }
nav { border-right:1px solid var(--line); overflow-y:auto; max-height:calc(100vh - 62px); }
nav button { display:block; width:100%; text-align:left; padding:9px 14px; border:0;
             background:none; color:inherit; font:inherit; cursor:pointer;
             border-bottom:1px solid var(--line); }
nav button:hover { background:rgba(127,127,127,.10); }
nav button[aria-current="true"] { background:rgba(127,127,127,.16); font-weight:650; }
nav .count { float:right; color:var(--muted); font-size:12px; }
#editor { padding:16px 18px 60px; max-width:900px; }
.empty { color:var(--muted); }
fieldset { border:1px solid var(--line); border-radius:8px; margin:0 0 14px; padding:12px 14px; }
legend { padding:0 6px; font-weight:650; font-size:13px; color:var(--muted); }
.f { margin:0 0 12px; }
.f > label { display:block; font-size:13px; color:var(--muted); margin-bottom:3px; }
.f .req { color:var(--warn); }
.f .desc { font-size:12px; color:var(--muted); margin:2px 0 0; }
input[type=text], input[type=number], select, textarea {
  width:100%; padding:7px 9px; border:1px solid var(--line); border-radius:6px;
  background:var(--bg); color:var(--fg); font:inherit; }
textarea { min-height:90px; resize:vertical; }
.parts { display:flex; gap:8px; }
.parts > div { flex:1; }
.ro { padding:7px 9px; border:1px dashed var(--line); border-radius:6px; color:var(--muted);
      min-height:34px; white-space:pre-wrap; }
.notice { background:var(--warnbg); border:1px solid var(--warn); color:var(--warn);
          border-radius:8px; padding:10px 12px; margin:0 0 14px; font-size:14px; }
.notice h3 { margin:0 0 6px; font-size:14px; }
.notice ul { margin:6px 0 8px 18px; padding:0; }
.notice .acts { display:flex; gap:8px; flex-wrap:wrap; margin-top:8px; }
button.act { padding:7px 12px; border:1px solid var(--accent); color:var(--accent);
             background:none; border-radius:6px; font:inherit; cursor:pointer; }
button.act.primary { background:var(--accent); color:#fff; }
.imgs { display:flex; gap:8px; flex-wrap:wrap; margin:0 0 10px; }
.imgs img { height:140px; width:auto; border-radius:8px; border:1px solid var(--line); }
.bar { position:fixed; bottom:0; left:0; right:0; background:var(--bg);
       border-top:1px solid var(--line); padding:9px 16px; display:flex; gap:9px;
       align-items:center; }
.bar .grow { flex:1; }
"""

    /**
     * 화면 동작. 날 글자를 보내고 사유를 띄우는 일만 한다.
     *
     * **템플릿 리터럴(백틱)을 쓰지 않는다** — 코틀린 raw 문자열 안에서 `$`가 보간으로 읽히기
     * 때문이다. 이어 붙이기로 적으면 그 함정이 아예 없다.
     */
    val JS: String = """
'use strict';
var TOKEN = new URLSearchParams(location.search).get('t') || '';
var state = { id:null, fields:[], novelId:null, resolve:{}, dirty:false, saving:false };

function api(path, opts) {
  var sep = path.indexOf('?') >= 0 ? '&' : '?';
  return fetch(path + sep + 't=' + encodeURIComponent(TOKEN), opts || {});
}
function setStatus(text, cls) {
  var el = document.getElementById('status');
  el.textContent = text; el.className = 'status' + (cls ? ' ' + cls : '');
}
function el(tag, attrs, text) {
  var n = document.createElement(tag);
  if (attrs) for (var k in attrs) { if (attrs[k] !== null) n.setAttribute(k, attrs[k]); }
  if (text !== undefined && text !== null) n.textContent = text;
  return n;
}

// ───── 목록 ─────
function loadNovels() {
  return api('/api/novels').then(function (r) { return r.json(); }).then(function (d) {
    var sel = document.getElementById('novel');
    sel.appendChild(el('option', { value:'' }, '전체'));
    d.novels.forEach(function (n) { sel.appendChild(el('option', { value:n.id }, n.title)); });
    sel.addEventListener('change', function () { loadList(sel.value); });
  });
}
function loadList(novelId) {
  var q = novelId ? '/api/characters?novel=' + encodeURIComponent(novelId) : '/api/characters';
  return api(q).then(function (r) { return r.json(); }).then(function (d) {
    var nav = document.getElementById('list');
    nav.textContent = '';
    if (!d.characters.length) { nav.appendChild(el('p', { class:'empty' }, '캐릭터가 없습니다.')); return; }
    d.characters.forEach(function (c) {
      var b = el('button', { type:'button', 'data-id':c.id });
      b.appendChild(el('span', null, c.name || '(이름 없음)'));
      b.appendChild(el('span', { class:'count' }, '채운 칸 ' + c.filledCount));
      b.addEventListener('click', function () { openCharacter(c.id); });
      nav.appendChild(b);
    });
  });
}
function markCurrent(id) {
  var bs = document.querySelectorAll('#list button');
  for (var i = 0; i < bs.length; i++) {
    bs[i].setAttribute('aria-current', String(bs[i].getAttribute('data-id') === String(id)));
  }
}

// ───── 편집 화면 ─────
function openCharacter(id) {
  return api('/api/character/' + id).then(function (r) {
    if (!r.ok) { setStatus('캐릭터를 읽지 못했습니다.', 'failed'); return null; }
    return r.json();
  }).then(function (d) {
    if (!d) return;
    state = { id:d.id, fields:d.fields, novelId:d.novelId, resolve:{}, dirty:false, saving:false };
    markCurrent(id);
    render(d);
    setStatus('');
  });
}

function render(d) {
  var box = document.getElementById('editor');
  box.textContent = '';

  if (d.blockedImageCount > 0) {
    box.appendChild(noticeBox('앱 저장소 밖에 있는 그림 ' + d.blockedImageCount +
      '장은 PC에서 보이지 않습니다. 파일 자체는 그대로 있습니다.', []));
  }
  if (d.images && d.images.length) {
    var strip = el('div', { class:'imgs' });
    d.images.forEach(function (imgId) {
      strip.appendChild(el('img', { src:'/api/image/' + d.id + '/' + imgId + '?t=' + encodeURIComponent(TOKEN), alt:'' }));
    });
    box.appendChild(strip);
  }

  var base = el('fieldset'); base.appendChild(el('legend', null, '기본'));
  base.appendChild(textField('name', '이름', d.name));
  base.appendChild(textField('lastName', '성', d.lastName));
  base.appendChild(textField('firstName', '이름(별도)', d.firstName));
  base.appendChild(textField('anotherName', '이명', d.anotherName));
  base.appendChild(textField('tags', '태그', d.tags));
  var nf = el('div', { class:'f' });
  nf.appendChild(el('label', { for:'f_novelId' }, '작품'));
  var nsel = el('select', { id:'f_novelId' });
  nsel.appendChild(el('option', { value:'' }, '없음'));
  d.novels.forEach(function (n) {
    var o = el('option', { value:n.id }, n.title);
    if (String(n.id) === String(d.novelId)) o.setAttribute('selected', 'selected');
    nsel.appendChild(o);
  });
  nsel.addEventListener('change', touch);
  nf.appendChild(nsel);
  base.appendChild(nf);
  box.appendChild(base);

  var groups = {}, order = [];
  d.fields.forEach(function (f) {
    if (!groups[f.group]) { groups[f.group] = []; order.push(f.group); }
    groups[f.group].push(f);
  });
  order.forEach(function (g) {
    var fs = el('fieldset'); fs.appendChild(el('legend', null, g));
    groups[g].forEach(function (f) { fs.appendChild(fieldNode(f)); });
    box.appendChild(fs);
  });

  var memo = el('fieldset'); memo.appendChild(el('legend', null, '메모'));
  var mf = el('div', { class:'f' });
  var ta = el('textarea', { id:'f_memo' }); ta.value = d.memo || '';
  ta.addEventListener('input', touch);
  mf.appendChild(ta); memo.appendChild(mf); box.appendChild(memo);

  box.appendChild(bottomBar());
}

function textField(id, label, value) {
  var w = el('div', { class:'f' });
  w.appendChild(el('label', { for:'f_' + id }, label));
  var i = el('input', { type:'text', id:'f_' + id });
  i.value = value || '';
  i.addEventListener('input', touch);
  w.appendChild(i);
  return w;
}

function fieldNode(f) {
  var w = el('div', { class:'f', 'data-fid':f.id });
  var lab = el('label', { for:'fd_' + f.id }, f.name);
  if (f.required) lab.appendChild(el('span', { class:'req' }, ' *'));
  w.appendChild(lab);

  if (f.kind === 'readonly') {
    w.appendChild(el('div', { class:'ro' }, f.display || ''));
    var why = f.notice === 'calculated' ? '자동 계산 필드이므로 PC에서 고치지 않습니다.'
            : f.notice === 'unknownType' ? '앱이 모르는 종류의 필드입니다. 값은 그대로 두었습니다.'
            : null;
    if (why) w.appendChild(el('p', { class:'desc' }, why));
  } else if (f.kind === 'choice') {
    var s = el('select', { id:'fd_' + f.id });
    s.appendChild(el('option', { value:'' }, '비움'));
    f.options.forEach(function (o) {
      var op = el('option', { value:o }, o);
      if (o === f.value) op.setAttribute('selected', 'selected');
      s.appendChild(op);
    });
    s.addEventListener('change', touch);
    w.appendChild(s);
  } else if (f.kind === 'parts') {
    var box = el('div', { class:'parts' });
    f.parts.forEach(function (p, i) {
      var cell = el('div');
      var inp = el('input', { type:p.numeric ? 'number' : 'text', id:'fd_' + f.id + '_' + i,
                              placeholder:p.hint, step:'any' });
      inp.value = p.value || '';
      inp.addEventListener('input', touch);
      cell.appendChild(inp);
      box.appendChild(cell);
    });
    w.appendChild(box);
  } else {
    var t = el('input', { type:f.kind === 'numeric' ? 'number' : 'text', id:'fd_' + f.id, step:'any' });
    t.value = f.value || '';
    t.addEventListener('input', touch);
    w.appendChild(t);
  }
  if (f.description) w.appendChild(el('p', { class:'desc' }, f.description));
  return w;
}

function bottomBar() {
  var bar = el('div', { class:'bar' });
  var save = el('button', { type:'button', class:'act primary' }, '저장');
  save.addEventListener('click', function () { submit(false); });
  var next = el('button', { type:'button', class:'act' }, '저장하고 다음');
  next.addEventListener('click', function () { submit(true); });
  var skip = el('button', { type:'button', class:'act' }, '건너뛰기');
  skip.addEventListener('click', function () { goNext(); });
  bar.appendChild(save); bar.appendChild(next); bar.appendChild(skip);
  bar.appendChild(el('span', { class:'grow' }));
  return bar;
}

function touch() { state.dirty = true; setStatus('저장하지 않은 변경이 있습니다.'); }

// ───── 저장 ─────
function collect() {
  var fields = {};
  state.fields.forEach(function (f) {
    if (f.readOnly) return;
    if (f.kind === 'parts') {
      var parts = [];
      f.parts.forEach(function (p, i) {
        var n = document.getElementById('fd_' + f.id + '_' + i);
        parts.push(n ? n.value : '');
      });
      fields[f.id] = parts;
    } else {
      var n2 = document.getElementById('fd_' + f.id);
      fields[f.id] = n2 ? n2.value : '';
    }
  });
  function v(id) { var n = document.getElementById('f_' + id); return n ? n.value : ''; }
  var novelRaw = v('novelId');
  return {
    name: v('name'), lastName: v('lastName'), firstName: v('firstName'),
    anotherName: v('anotherName'), tags: v('tags'), memo: v('memo'),
    novelId: novelRaw ? Number(novelRaw) : null,
    fields: fields,
    resolve: state.resolve
  };
}

function submit(advance) {
  if (state.saving || state.id === null) return;
  state.saving = true;
  setStatus('저장하는 중입니다.');
  api('/api/character/' + state.id, {
    method:'POST',
    headers:{ 'Content-Type':'application/json' },
    body: JSON.stringify(collect())
  }).then(function (r) {
    return r.json().then(function (d) { return { ok:r.ok, status:r.status, body:d }; });
  }).then(function (res) {
    state.saving = false;
    if (res.ok) {
      state.dirty = false; state.resolve = {};
      var extra = '';
      if (res.body.preserved > 0) extra = ' 화면에 없는 값 ' + res.body.preserved + '개는 그대로 두었습니다.';
      setStatus('저장했습니다.' + extra, 'saved');
      loadList(document.getElementById('novel').value);
      if (advance) goNext();
      return;
    }
    if (res.body && res.body.gate) { showGate(res.body); return; }
    setStatus('저장하지 못했습니다. ' + ((res.body && res.body.message) || ''), 'failed');
  }).catch(function () {
    state.saving = false;
    setStatus('폰과 연결이 끊겼습니다. 케이블이나 와이파이를 확인해 주십시오.', 'failed');
  });
}

function noticeBox(text, actions) {
  var n = el('div', { class:'notice' });
  n.appendChild(el('p', null, text));
  if (actions && actions.length) {
    var acts = el('div', { class:'acts' });
    actions.forEach(function (a) {
      var b = el('button', { type:'button', class:'act' + (a.primary ? ' primary' : '') }, a.label);
      b.addEventListener('click', a.run);
      acts.appendChild(b);
    });
    n.appendChild(acts);
  }
  return n;
}

function showGate(body) {
  var box = document.getElementById('editor');
  var n;
  if (body.gate === 'restricted') {
    var list = el('ul');
    (body.violations || []).forEach(function (v) {
      list.appendChild(el('li', null, v.field + ': ' + v.tokens.join(', ')));
    });
    n = noticeBox('값 목록에 없는 항목이 있습니다. 목록에 넣고 저장하거나, 입력을 고치십시오.', [
      { label:'목록에 넣고 저장', primary:true, run:function () {
          state.resolve.restricted = 'add'; submit(false); } },
      { label:'입력 고치기', run:function () { n.remove(); } }
    ]);
    n.insertBefore(list, n.firstChild.nextSibling);
  } else if (body.gate === 'ageLinkage') {
    var c = body.conflict || {};
    n = noticeBox('나이와 출생 연도가 맞지 않습니다. 적은 나이는 ' + c.inputAge +
      '이고, 기준 연도 ' + c.currentStdYear + '에서 계산한 나이는 ' + c.expectedAge +
      '입니다. 앱에서 고치십시오.', [
      { label:'확인', primary:true, run:function () { n.remove(); } }
    ]);
  } else if (body.gate === 'crossUniverse') {
    n = noticeBox('작품을 바꾸면 세계관이 달라져 일부 값과 세력 소속이 사라집니다. ' +
      (body.summary || ''), [
      { label:'그대로 옮기기', primary:true, run:function () {
          state.resolve.crossUniverse = true; submit(false); } },
      { label:'취소', run:function () { n.remove(); } }
    ]);
  } else {
    n = noticeBox('저장하지 못했습니다.', []);
  }
  box.insertBefore(n, box.firstChild);
  setStatus('확인이 필요합니다.', 'failed');
}

// ───── 보충 ─────
function goNext() {
  var novelId = document.getElementById('novel').value;
  var q = '/api/supplement/next?after=' + encodeURIComponent(state.id || '') +
          (novelId ? '&novel=' + encodeURIComponent(novelId) : '');
  api(q).then(function (r) { return r.json(); }).then(function (d) {
    if (d.nextId === null) { setStatus('마지막 캐릭터입니다.', 'saved'); return; }
    openCharacter(d.nextId).then(function () {
      setStatus('남은 캐릭터 ' + d.remaining + '명');
    });
  });
}

window.addEventListener('beforeunload', function (e) {
  if (state.dirty) { e.preventDefault(); e.returnValue = ''; }
});

loadNovels().then(function () { return loadList(''); });
"""
}
