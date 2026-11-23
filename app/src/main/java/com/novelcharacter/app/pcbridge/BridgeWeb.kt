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
/* **float이 아니라 flex다.** float이면 이름이 길어 줄바꿈될 때 개수가 어정쩡한 자리에
   남는다(`이그드라실 이르을라카샤피넬루네`가 실제로 그랬다). */
nav button { display:flex; gap:10px; align-items:baseline; justify-content:space-between;
             width:100%; text-align:left; padding:9px 14px; border:0;
             background:none; color:inherit; font:inherit; cursor:pointer;
             border-bottom:1px solid var(--line); }
nav .name { min-width:0; overflow-wrap:anywhere; }
nav button:hover { background:rgba(127,127,127,.10); }
nav button[aria-current="true"] { background:rgba(127,127,127,.16); font-weight:650; }
nav .count { flex:none; color:var(--muted); font-size:12px; white-space:nowrap; }
/* **너비 상한을 두지 않는다.** 넓은 화면을 쓰자고 시작한 일에서 한 열로 900px만 쓰면
   앞뒤가 안 맞는다 — 커스텀 필드가 90개라 한 열이면 끝없이 스크롤한다. */
#editor { padding:16px 18px 72px; }
/* 화면이 넓으면 저절로 열이 늘고 좁으면 한 열이 된다. 칸 하나의 최소 너비(260px)가
   그 판정을 하므로 폭마다 규칙을 적지 않아도 된다. */
.grid { display:grid; grid-template-columns:repeat(auto-fill, minmax(260px, 1fr)); gap:0 18px; }
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
/* 입력칸 공통 규칙(width:100%)이 머리말의 작품 고르개까지 먹어 화면 전체로 늘어났다.
   거기는 한 줄에 상태 표시와 나란히 서는 자리라 제 몫만 차지해야 한다. */
header select { width:auto; min-width:220px; max-width:420px; }
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
/* 관계 한 줄. **단추를 flex로 떼어 둔다** — 머리말 목록에서 배운 그 자리다(긴 이름이
   줄바꿈되면 float은 단추를 어정쩡한 데 남긴다). `min-width:0`이 없으면 flex 칸이
   줄어들지 않아 긴 이름이 단추를 밀어낸다. */
.rels { display:flex; flex-direction:column; gap:8px; }
.rel { display:flex; gap:12px; align-items:flex-start; justify-content:space-between;
       border:1px solid var(--line); border-radius:8px; padding:9px 11px; }
.rel .who { min-width:0; flex:1; overflow-wrap:anywhere; }
.rel .who .head { font-weight:650; }
.rel .meta { color:var(--muted); font-size:13px; margin-top:2px; }
.rel .acts { flex:none; display:flex; gap:6px; }
/* 고칠 수 없는 줄은 테두리로도 드러낸다 — 읽기 전용 칸(.ro)과 같은 표기다. */
.rel.ro { border-style:dashed; }
/* 편집 폼에는 **너비 상한을 두지 않는다.** 1단계가 편집 폼에 900px를 박아 2000px 모니터의
   절반을 비운 그 자리를 되풀이하지 않는다 — 칸 최소 너비가 열 수를 정한다. */
.rel-form { border:1px solid var(--accent); border-radius:8px; padding:11px 12px; margin-top:8px; }
.rel-form .acts { display:flex; gap:8px; flex-wrap:wrap; margin-top:4px; }
.rel-head { display:flex; gap:10px; align-items:baseline; justify-content:space-between;
            flex-wrap:wrap; margin:0 0 10px; }
.rel-msg { background:var(--warnbg); border:1px solid var(--warn); color:var(--warn);
           border-radius:6px; padding:8px 10px; margin:0 0 10px; font-size:14px; }
button.act.danger { border-color:var(--warn); color:var(--warn); }
button.act[disabled] { opacity:.5; cursor:default; }
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
// 관계는 **제 끝점으로 바로 저장된다** — 캐릭터 폼의 저장과 섞지 않으므로 state.dirty를
// 건드리지 않는다. 편집 중인 줄(relEditing)은 null이면 닫힘, 0이면 '더하기', 그 밖은 그 id다.
// `draft`는 **되돌아온 요청의 날 값**이다. 관문이 막으면 폼을 그 값으로 다시 그린다 —
// 없으면 사유만 띄우고 적은 것을 전부 잃어, 고칠 경로가 아니라 다시 적을 경로가 된다.
var rels = { rows:[], types:[], candidates:[], min:1, max:10, arrows:{},
             editing:null, busy:false, draft:null };

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
      b.appendChild(el('span', { class:'name' }, c.name || '(이름 없음)'));
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
    rels = { rows:[], types:[], candidates:[], min:1, max:10, arrows:{},
           editing:null, busy:false, draft:null };
    markCurrent(id);
    render(d);
    setStatus('');
    return loadRelationships();
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
  var baseGrid = el('div', { class:'grid' });
  baseGrid.appendChild(textField('name', '이름', d.name));
  baseGrid.appendChild(textField('lastName', '성', d.lastName));
  baseGrid.appendChild(textField('firstName', '이름(별도)', d.firstName));
  baseGrid.appendChild(textField('anotherName', '이명', d.anotherName));
  baseGrid.appendChild(textField('tags', '태그', d.tags));
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
  baseGrid.appendChild(nf);
  base.appendChild(baseGrid);
  box.appendChild(base);

  // **관계를 기본 바로 뒤에 둔다.** 커스텀 필드가 90개인 표본에서 맨 아래에 두면 사실상
  // 숨은 자리가 된다 — 일일이 확인하지 않으면 존재를 알 수 없는 데이터를 두지 않는다(원칙 04).
  var relBox = el('fieldset', { id:'rels' });
  relBox.appendChild(el('legend', null, '관계'));
  relBox.appendChild(el('p', { class:'rel-empty' }, '관계를 불러오는 중입니다.'));
  box.appendChild(relBox);

  var groups = {}, order = [];
  d.fields.forEach(function (f) {
    if (!groups[f.group]) { groups[f.group] = []; order.push(f.group); }
    groups[f.group].push(f);
  });
  order.forEach(function (g) {
    var fs = el('fieldset'); fs.appendChild(el('legend', null, g));
    var grid = el('div', { class:'grid' });
    groups[g].forEach(function (f) { grid.appendChild(fieldNode(f)); });
    fs.appendChild(grid);
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

// ───── 관계 ─────
// **판정은 여기 없다.** 겹치는가 · 같은 뜻인가 · 세력 자동 관계인가 · 강도가 범위인가는
// 전부 폰이 가린다(RelationshipRules). 이 아래가 하는 일은 받은 기술자대로 그리고,
// 사용자가 적은 날 값을 돌려보내고, 폰이 돌려준 사유를 띄우는 것뿐이다.

function loadRelationships() {
  if (state.id === null) return Promise.resolve();
  // **어느 캐릭터의 요청인지 들고 간다.** 불러오는 사이에 사용자가 다른 캐릭터를 고르면
  // 늦게 온 응답이 엉뚱한 사람의 관계를 그린다 — 가장 최근 요청의 결과만 반영한다
  // (이 저장소가 순위·검색에서 세운 그 규칙이다).
  var forId = state.id;
  return api('/api/relationships/' + forId).then(function (r) {
    if (!r.ok) return null;
    return r.json();
  }).then(function (d) {
    if (state.id !== forId) return;
    var box = document.getElementById('rels');
    if (!box) return;
    if (!d) { renderRelError(box); return; }
    rels.rows = d.relationships || [];
    rels.types = d.types || [];
    rels.candidates = d.candidates || [];
    rels.min = d.minIntensity;
    rels.max = d.maxIntensity;
    rels.arrows = d.arrows || {};
    renderRelationships();
  }).catch(function () {
    if (state.id !== forId) return;
    var box = document.getElementById('rels');
    if (box) renderRelError(box);
  });
}

function renderRelError(box) {
  box.textContent = '';
  box.appendChild(el('legend', null, '관계'));
  box.appendChild(el('p', { class:'rel-empty' }, '관계를 읽지 못했습니다. 다시 고르면 불러옵니다.'));
}

function renderRelationships(message) {
  var box = document.getElementById('rels');
  if (!box) return;
  box.textContent = '';
  box.appendChild(el('legend', null, '관계'));

  var head = el('div', { class:'rel-head' });
  head.appendChild(el('span', { class:'meta' }, '관계 ' + rels.rows.length + '개'));
  var add = el('button', { type:'button', class:'act' }, '관계 더하기');
  // 고를 수 있는 상대가 없으면 **단추를 열어 두고 사유를 적는다** — 눌러 보고 알게 하지 않는다.
  if (!rels.candidates.length) {
    add.setAttribute('disabled', 'disabled');
    add.setAttribute('title', '같은 세계관에 다른 인물이 없습니다.');
  } else {
    add.addEventListener('click', function () {
      rels.editing = 0; rels.draft = null; renderRelationships();
    });
  }
  head.appendChild(add);
  box.appendChild(head);

  if (message) box.appendChild(el('p', { class:'rel-msg' }, message));

  if (rels.editing === 0) box.appendChild(relForm(null));

  if (!rels.rows.length) {
    box.appendChild(el('p', { class:'rel-empty' }, '맺은 관계가 없습니다.'));
    return;
  }

  var list = el('div', { class:'rels' });
  rels.rows.forEach(function (row) {
    if (rels.editing === row.id) { list.appendChild(relForm(row)); return; }
    list.appendChild(relRow(row));
  });
  box.appendChild(list);
}

// **"못 찾았다"와 "이름이 비었다"를 가른다.** 둘을 한 문구로 접으면 이름만 비어 있는
// 인물을 사라진 것처럼 말하게 된다 — 사유는 기술자가 이미 갈라 보내 준다.
function relName(row) {
  if (row.otherCharacterName) return row.otherCharacterName;
  return row.notice === 'missingOther' ? '(상대를 찾지 못했습니다)' : '(이름 없음)';
}

function relRow(row) {
  var w = el('div', { class:'rel' + (row.readOnly ? ' ro' : '') });
  var who = el('div', { class:'who' });
  var name = relName(row);
  who.appendChild(el('p', { class:'head' }, row.arrow + ' ' + name));
  var meta = row.type + ' · 강도 ' + row.intensity;
  who.appendChild(el('p', { class:'meta' }, meta));
  if (row.description) who.appendChild(el('p', { class:'meta' }, row.description));
  // 고칠 수 없는 줄은 **사유를 적는다** — 단추만 빼면 왜 없는지 알 수 없다.
  var why = null;
  if (row.notice === 'faction') {
    why = row.factionName
      ? "'" + row.factionName + "' 소속에서 자동으로 생긴 관계입니다. 앱의 세력 화면에서 소속을 고칩니다."
      : '세력 소속에서 자동으로 생긴 관계입니다. 앱의 세력 화면에서 소속을 고칩니다.';
  } else if (row.notice === 'missingOther') {
    why = '상대 쪽 인물을 찾지 못해 PC에서 고치지 않습니다.';
  }
  if (why) who.appendChild(el('p', { class:'desc' }, why));
  // 고칠 수는 있지만 알아 둘 것 — 단추를 막지 않고 먼저 알린다.
  if (row.advisory === 'crossUniverse') {
    who.appendChild(el('p', { class:'desc' },
      '상대가 지금은 다른 세계관에 있습니다. 고치는 것은 거절되며, 지우거나 앱에서 작품을 되돌릴 수 있습니다.'));
  }
  w.appendChild(who);

  if (!row.readOnly) {
    var acts = el('div', { class:'acts' });
    var edit = el('button', { type:'button', class:'act' }, '고치기');
    edit.addEventListener('click', function () {
      rels.editing = row.id; rels.draft = null; renderRelationships();
    });
    var del = el('button', { type:'button', class:'act danger' }, '지우기');
    del.addEventListener('click', function () { relDelete(row, false); });
    acts.appendChild(edit); acts.appendChild(del);
    w.appendChild(acts);
  }
  return w;
}

// `placeholder`가 있으면 **빈 첫 자리를 둔다** — 없으면 브라우저가 첫 후보를 저절로 고르고,
// 사용자가 아무것도 고르지 않았는데 그 사람과 관계가 맺어진다.
function relSelect(id, label, options, selected, placeholder) {
  var w = el('div', { class:'f' });
  w.appendChild(el('label', { for:id }, label));
  var sel = el('select', { id:id });
  if (placeholder) sel.appendChild(el('option', { value:'' }, placeholder));
  options.forEach(function (o) {
    var op = el('option', { value:o.value }, o.label);
    if (String(o.value) === String(selected)) op.setAttribute('selected', 'selected');
    sel.appendChild(op);
  });
  w.appendChild(sel);
  return w;
}

// 폼의 **시작 값**: 되돌아온 날 값이 있으면 그것, 없으면 저장된 행, 없으면 기본값.
function relFormValues(row) {
  if (rels.draft) {
    var d = rels.draft;
    return {
      other: d.otherCharacterId || '',
      type: d.type,
      desc: d.description,
      intensity: (d.intensity === null || d.intensity === undefined) ? '' : d.intensity,
      dir: d.bidirectional ? 'both' : (d.outgoing ? 'out' : 'in')
    };
  }
  if (row) {
    return {
      other: row.otherCharacterId,
      type: row.type,
      desc: row.description,
      intensity: row.intensity,
      dir: row.bidirectional ? 'both' : (row.outgoing ? 'out' : 'in')
    };
  }
  return { other:'', type:'', desc:'', intensity:'5', dir:'both' };
}

function relForm(row) {
  var editing = !!row;
  var v0 = relFormValues(row);
  var box = el('div', { class:'rel-form' });
  box.appendChild(el('p', { class:'meta' }, editing ? '관계를 고칩니다.' : '관계를 더합니다.'));

  var grid = el('div', { class:'grid' });

  var people = rels.candidates.map(function (c) {
    return { value:c.id, label:c.novelTitle ? c.name + ' (' + c.novelTitle + ')' : c.name };
  });
  // 고치는 중이고 상대가 후보에 없으면(세계관이 바뀌었다) **그 상대를 목록에 남긴다** —
  // 없으면 첫 후보가 조용히 골라져 저장이 상대를 바꿔 버린다.
  if (editing && !people.some(function (p) { return String(p.value) === String(row.otherCharacterId); })) {
    people.unshift({ value:row.otherCharacterId, label:relName(row) });
  }
  grid.appendChild(relSelect(
    'rel_other', '상대', people, v0.other, editing ? null : '고르십시오'
  ));

  var types = rels.types.map(function (t) { return { value:t, label:t }; });
  grid.appendChild(relSelect(
    'rel_type', '유형', types, v0.type, editing ? null : '고르십시오'
  ));

  var dir = [
    { value:'both', label:'양방향 ' + (rels.arrows.both || '') },
    { value:'out', label:'이 인물에서 상대로 ' + (rels.arrows.out || '') },
    { value:'in', label:'상대에서 이 인물로 ' + (rels.arrows.in || '') }
  ];
  grid.appendChild(relSelect('rel_dir', '방향', dir, v0.dir));

  var iw = el('div', { class:'f' });
  iw.appendChild(el('label', { for:'rel_intensity' }, '강도 (' + rels.min + '~' + rels.max + ')'));
  var inten = el('input', { type:'number', id:'rel_intensity', min:rels.min, max:rels.max, step:'1' });
  inten.value = v0.intensity;
  iw.appendChild(inten);
  grid.appendChild(iw);

  var dw = el('div', { class:'f' });
  dw.appendChild(el('label', { for:'rel_desc' }, '설명'));
  var desc = el('input', { type:'text', id:'rel_desc' });
  desc.value = v0.desc;
  dw.appendChild(desc);
  grid.appendChild(dw);

  box.appendChild(grid);

  var acts = el('div', { class:'acts' });
  var save = el('button', { type:'button', class:'act primary' }, '저장');
  save.addEventListener('click', function () { relSubmit(editing ? row.id : null); });
  var cancel = el('button', { type:'button', class:'act' }, '취소');
  cancel.addEventListener('click', function () {
    rels.editing = null; rels.draft = null; renderRelationships();
  });
  acts.appendChild(save); acts.appendChild(cancel);
  box.appendChild(acts);
  return box;
}

function relBody() {
  function v(id) { var n = document.getElementById(id); return n ? n.value : ''; }
  var dir = v('rel_dir');
  var raw = v('rel_intensity');
  return {
    otherCharacterId: Number(v('rel_other')),
    type: v('rel_type'),
    description: v('rel_desc'),
    // **빈 칸을 숫자로 메우지 않는다** — 폰이 "적지 않았다"로 읽고 사유를 돌려준다.
    intensity: raw === '' ? null : Number(raw),
    bidirectional: dir === 'both',
    outgoing: dir !== 'in'
  };
}

function relSubmit(relId) {
  if (rels.busy || state.id === null) return;
  rels.busy = true;
  setStatus('관계를 저장하는 중입니다.');
  // 보낸 날 값을 들고 있는다 — 관문이 막으면 폼을 이것으로 다시 그린다.
  rels.draft = relBody();
  var path = '/api/relationship/' + state.id + (relId ? '/' + relId : '');
  api(path, {
    method:'POST',
    headers:{ 'Content-Type':'application/json' },
    body: JSON.stringify(rels.draft)
  }).then(function (r) {
    return r.json().then(function (d) { return { ok:r.ok, body:d }; });
  }).then(function (res) {
    rels.busy = false;
    if (res.ok) {
      rels.editing = null;
      rels.draft = null;
      setStatus(relId ? '관계를 고쳤습니다.' : '관계를 더했습니다.', 'saved');
      return loadRelationships();
    }
    // 사유는 **적은 값을 살려 둔 채** 보여 준다(rels.draft) — 바로 고쳐 다시 보낼 수 있다.
    setStatus('확인이 필요합니다.', 'failed');
    renderRelationships(relMessage(res.body));
  }).catch(function () {
    rels.busy = false;
    setStatus('폰과 연결이 끊겼습니다. 케이블이나 와이파이를 확인해 주십시오.', 'failed');
  });
}

// 사유를 **고칠 자리가 보이는 문구**로 바꾼다. 폰이 보내는 `what`은 사유 코드라
// 그대로 띄우면 개발자 표기가 화면에 나간다.
var REL_WHAT = {
  otherCharacterId: '상대를 골라 주십시오.',
  type: '관계 유형을 골라 주십시오.',
  intensity: '관계 강도를 적어 주십시오.',
  json: '보낸 내용을 읽지 못했습니다.'
};

function relMessage(body) {
  if (!body) return '관계를 저장하지 못했습니다.';
  var text = (body.what && REL_WHAT[body.what]) || body.message || '관계를 저장하지 못했습니다.';
  var b = body.blocking;
  if (b) {
    text = text + ' (' + b.arrow + ' ' + (b.otherCharacterName || '?') + ' · ' + b.type + ')';
  }
  return text;
}

function relDelete(row, confirmed) {
  if (rels.busy || state.id === null) return;
  rels.busy = true;
  api('/api/relationship/' + state.id + '/' + row.id + '/delete', {
    method:'POST',
    headers:{ 'Content-Type':'application/json' },
    body: JSON.stringify({ resolve:{ delete: !!confirmed } })
  }).then(function (r) {
    return r.json().then(function (d) { return { ok:r.ok, body:d }; });
  }).then(function (res) {
    rels.busy = false;
    if (res.ok) {
      setStatus('관계를 지웠습니다.', 'saved');
      rels.editing = null;
      rels.draft = null;
      return loadRelationships();
    }
    if (res.body && res.body.gate === 'relationshipDelete') {  // BridgeRelationships.GATE_DELETE
      showRelDeleteConfirm(row, res.body.historyCount || 0);
      return;
    }
    setStatus('확인이 필요합니다.', 'failed');
    renderRelationships(relMessage(res.body));
  }).catch(function () {
    rels.busy = false;
    setStatus('폰과 연결이 끊겼습니다. 케이블이나 와이파이를 확인해 주십시오.', 'failed');
  });
}

// **삭제는 확인을 받는다**(설계 9-4). 관계 변화 이력은 외래키가 CASCADE라 관계를 지우면
// 함께 사라지고, PC 화면에 되돌릴 경로가 없으므로 그 수를 먼저 보여 준다.
function showRelDeleteConfirm(row, historyCount) {
  var box = document.getElementById('rels');
  if (!box) return;
  var text = relName(row) + ' · ' + row.type + ' 관계를 지웁니다.';
  if (historyCount > 0) {
    text = text + ' 관계 변화 이력 ' + historyCount + '건도 함께 사라지며 되돌릴 수 없습니다.';
  } else {
    text = text + ' 되돌릴 수 없습니다.';
  }
  var n = noticeBox(text, [
    { label:'지웁니다', primary:true, run:function () { relDelete(row, true); } },
    // 그대로 두면 **상태 표시도 되돌린다** — '확인이 필요합니다'가 남아 있으면
    // 아무것도 기다리지 않는 화면이 기다리는 것처럼 보인다.
    { label:'그대로 둡니다', run:function () { setStatus(''); renderRelationships(); } }
  ]);
  box.insertBefore(n, box.firstChild.nextSibling);
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
