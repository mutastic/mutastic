(function(){
'use strict';
// Copy as prompt. build.mjs --prompt turns it on.
var PROMPT = false;

var PIP = '<svg viewBox="0 0 100 100" aria-hidden="true"><g transform="translate(0 -2.5)"><ellipse cx="34" cy="73" rx="11" ry="7" fill="#43a047"/><ellipse cx="66" cy="73" rx="11" ry="7" fill="#43a047"/><path d="M6 25 A44 44 0 0 0 94 25 Z" fill="#2e7d32"/><path d="M11 25 A39 39 0 0 0 89 25 Z" fill="#a3d97a"/><path d="M15 25 A35 35 0 0 0 85 25 Z" fill="#f55252"/><rect x="15" y="24" width="70" height="2.2" rx="1.1" fill="#ff8f8f"/><circle cx="37" cy="42" r="5.6" fill="#16201a"/><circle cx="63" cy="42" r="5.6" fill="#16201a"/><circle cx="39.2" cy="39.8" r="1.9" fill="#fff"/><circle cx="65.2" cy="39.8" r="1.9" fill="#fff"/><ellipse cx="30" cy="50" rx="5" ry="3" fill="#ffb3ba"/><ellipse cx="70" cy="50" rx="5" ry="3" fill="#ffb3ba"/><path d="M45 49 Q50 54.5 55 49" fill="none" stroke="#16201a" stroke-width="2.8" stroke-linecap="round"/></g></svg>';
var DIR_ICON = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="var(--link)" stroke-width="2" stroke-linejoin="round" aria-hidden="true"><path d="M3 6h6l2 2h10v11H3z"/></svg>';
var FILE_ICON = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="var(--muted)" stroke-width="2" stroke-linejoin="round" aria-hidden="true"><path d="M6 3h8l4 4v14H6z"/><path d="M14 3v4h4"/></svg>';

// Status words, as mutastic's cards say them. ORDER is the order chips and counts show.
var ST = {
  Survived:     {word:'missed',       sym:'✗', ink:'--survInk', pill:'--survPill', bar:'--barS'},
  NoCoverage:   {word:'not covered',  sym:'○', ink:'--ncInk',   pill:'--ncPill',   bar:'--ncBar'},
  Killed:       {word:'caught',       sym:'✓', ink:'--killInk', pill:'--killPill', bar:'--killBar'},
  Timeout:      {word:'timed out',    sym:'◷', ink:'--killInk', pill:'--killPill', bar:'--killBar'},
  RuntimeError: {word:'crashed',      sym:'!', ink:'--ignInk',  pill:'--ignPill',  bar:'--ignBar'},
  CompileError: {word:"didn't build", sym:'⊘', ink:'--ignInk',  pill:'--ignPill',  bar:'--ignBar'},
  Ignored:      {word:'skipped',      sym:'–', ink:'--ignInk',  pill:'--ignPill',  bar:'--ignBar'},
  Pending:      {word:'not run',      sym:'·', ink:'--ignInk',  pill:'--ignPill',  bar:'--ignBar'}
};
var ORDER = ['Survived','NoCoverage','Killed','Timeout','RuntimeError','CompileError','Ignored','Pending'];
var SAY = { Survived:'Ran, changed, and every test still passed', NoCoverage:'No test runs the line', Killed:'A test failed, as it should', Timeout:'The tests hung, which counts as caught',
  RuntimeError:'The tests crashed; out of the score', CompileError:"The change didn't build; out of the score", Ignored:'Skipped on purpose; out of the score', Pending:'Not run yet' };
// An id mutastic made. Commands are built only from ids that look like one.
var SAFE_ID = /^m[0-9a-f]{8,16}$/;

function dict(){ return Object.create(null); }
function esc(s){ return String(s == null ? '' : s).replace(/[&<>"']/g, function(c){ return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]; }); }
// Backticks in mutastic's words mark code.
function words(s){ return esc(s).replace(/`([^`]+)`/g, function(_, c){
  return c.length <= 24 ? '<code class="nb">' + c + '</code>' : '<code class="br">' + c.replace(/([.(,])/g, '$1<wbr>') + '</code>'; }); }
function cap(s){ s = String(s || ''); return s.charAt(0).toUpperCase() + s.slice(1); }
function str(v){ return typeof v === 'string' ? v : ''; }
function int(v, d){ v = Number(v); return isFinite(v) && v >= 1 ? Math.floor(v) : d; }
function obj(v){ return v && typeof v === 'object' && !Array.isArray(v) ? v : {}; }
function strs(v){ return Array.isArray(v) ? v.filter(function(x){ return typeof x === 'string'; }) : []; }
function href(h){ return esc(h.split('/').map(encodeURIComponent).join('/').replace(/^%23/, '#')); }
function mHref(m){ return '#/m/' + esc(encodeURIComponent(m.id)); }
function fileRoute(path){ return '#/file/' + path.split('/').map(encodeURIComponent).join('/'); }
function fileHref(path){ return esc(fileRoute(path)); }
function pill(st){ var m = ST[st]; return '<span class="pill" style="color:var(' + m.ink + ');background:var(' + m.pill + ')">' + m.sym + ' ' + m.word + '</span>'; }
function short(path){ return path.split('/').pop(); }
function say(text){ var s = document.getElementById('said'); s.textContent = ''; setTimeout(function(){ s.textContent = text; }, 30); }
// A value the tests passed, as code: strings quoted, the rest as JSON writes them.
function val(v){ return typeof v === 'string' ? JSON.stringify(v) : JSON.stringify(v) || String(v); }

// ---------- syntax ----------
// A small tokenizer for the languages mutastic mutates: comments, strings, numbers, keywords,
// annotations and attributes, type names, Rust's macros and lifetimes. One pass over the whole
// file, so a comment or string that spans lines is coloured on each of them.
function set(s){ var o = dict(); s.split(' ').forEach(function(w){ o[w] = 1; }); return o; }
var KW = {
  java: set('abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while var record yield sealed permits true false null'),
  kotlin: set('as break class continue do else false for fun if in interface is null object package return super this throw true try typealias typeof val var when while by catch constructor finally get import init set where abstract annotation companion const data enum external final inline inner internal lateinit open operator out override private protected public sealed suspend vararg'),
  rust: set('as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while union')
};
var COMMENT = /\/\/[^\n]*|\/\*[\s\S]*?(?:\*\/|$)/y;
var NUMBER = /(?:0[xX][\da-fA-F_]+|0[bB][01_]+|\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][+-]?\d+)?)[A-Za-z\d_]*/y;
var LEX = {
  jvm: [['tk-com', COMMENT], ['tk-str', /"""[\s\S]*?(?:"""|$)|"(?:\\.|[^"\\\n])*"?|'(?:\\.|[^'\\\n])*'?/y], ['tk-an', /@[A-Za-z_][\w.]*/y],
    ['tk-num', NUMBER], ['id', /[A-Za-z_$][\w$]*/y], ['', /[^A-Za-z_$\d"'\/@\n]+/y]],
  rust: [['tk-com', COMMENT], ['tk-str', /b?r(#*)"[\s\S]*?(?:"\1|$)/y], ['tk-str', /b?"(?:\\[\s\S]|[^"\\])*"?/y],
    ['tk-str', /b?'(?:\\(?:x[\da-fA-F]{2}|u\{[\da-fA-F]+\}|.)|[^\\'\n])'/y], ['tk-lt', /'[A-Za-z_]\w*/y], ['tk-an', /#!?\[[^\]\n]*\]?/y],
    ['tk-num', NUMBER], ['id', /[A-Za-z_]\w*/y], ['', /[^A-Za-z_\d"'\/#\n]+/y]]
};
function langOf(path){
  var e = (path.match(/\.([A-Za-z]+)$/) || [])[1];
  return e === 'rs' ? 'rust' : e === 'kt' || e === 'kts' ? 'kotlin' : e === 'java' ? 'java' : null;
}
// The source's lines, each a list of [class, text] pieces.
function tokens(src, lang){
  var lines = [[]];
  function put(cls, text){
    text.split('\n').forEach(function(p, k){
      if (k) lines.push([]);
      if (!p) return;
      var l = lines[lines.length - 1], last = l[l.length - 1];
      if (last && last[0] === cls) last[1] += p; else l.push([cls, p]);
    });
  }
  var kw = KW[lang];
  if (!kw){ put('', src); return lines; }
  var rust = lang === 'rust', pats = rust ? LEX.rust : LEX.jvm, i = 0, n = src.length;
  while (i < n){
    var cls = '', t = src.charAt(i);
    for (var k = 0; k < pats.length; k++){
      var re = pats[k][1]; re.lastIndex = i; var hit = re.exec(src);
      if (hit && hit[0]){ cls = pats[k][0]; t = hit[0]; break; }
    }
    if (cls === 'id'){
      cls = kw[t] ? 'tk-kw' : /^[A-Z][A-Za-z\d_]*[a-z]/.test(t) ? 'tk-ty' : '';
      if (rust && src.charAt(i + t.length) === '!' && src.charAt(i + t.length + 1) !== '='){ cls = 'tk-mc'; t += '!'; }
    }
    put(cls, t);
    i += t.length;
  }
  return lines;
}
// A line's pieces as HTML, the code units [from, to) marked.
function lineHtml(segs, from, to){
  var out = '', pos = 0, open = false, marking = from != null && to > from;
  (segs || []).forEach(function(s){
    var a = pos, b = pos + s[1].length, cuts = [a];
    pos = b;
    if (marking && from > a && from < b) cuts.push(from);
    if (marking && to > a && to < b) cuts.push(to);
    cuts.push(b);
    for (var k = 0; k + 1 < cuts.length; k++){
      var x = cuts[k], y = cuts[k + 1], inMark = marking && x >= from && y <= to;
      if (inMark && !open){ out += '<mark>'; open = true; } else if (!inMark && open){ out += '</mark>'; open = false; }
      var piece = esc(s[1].slice(x - a, y - a));
      out += s[0] ? '<span class="' + s[0] + '">' + piece + '</span>' : piece;
    }
  });
  return out + (open ? '</mark>' : '');
}
function lit(f){ return f.lit || (f.lit = tokens(f.source, f.lang)); }

// ---------- the report ----------
var R, RUN, FILES, ALL, BYID, TESTS, ROOT, MUTATORS, ROWS;
var state = { theme: null, filter: {Survived:true, NoCoverage:true}, mutator: '', treeOnlyMissed: false, q: '', mq: '', tq: '', lineOpen: dict(), missedShown: 50, mutantsShown: 100, testsShown: 100 };
try { state.theme = localStorage.getItem('mutastic-theme'); } catch (e) { /* storage off: the system theme */ }

function node(path){ return { path: path, dirs: dict(), files: [], ms: [], c: null }; }
function load(report){
  R = obj(report); RUN = obj(R.mutastic);
  FILES = []; ALL = []; BYID = dict(); ROOT = node(''); ROWS = dict();
  var files = obj(R.files), mutators = dict();
  Object.keys(files).sort().forEach(function(path){
    var f = obj(files[path]), file = { path: path, lang: langOf(path), source: str(f.source), mutants: [], lines: null, lit: null };
    (Array.isArray(f.mutants) ? f.mutants : []).forEach(function(raw){
      raw = obj(raw);
      var loc = obj(raw.location), s = obj(loc.start), e = obj(loc.end), id = raw.id == null ? '' : String(raw.id);
      if (!id || BYID[id]) return;
      var line = int(s.line, 1), eline = Math.max(line, int(e.line, line));
      var m = { id: id, file: path, f: file, line: line, col: int(s.column, 1), eline: eline, ecol: int(e.column, int(s.column, 1)),
        status: ST[raw.status] ? raw.status : 'Pending', mutator: str(raw.mutatorName) || 'mutant',
        replacement: typeof raw.replacement === 'string' ? raw.replacement : null, description: str(raw.description) || str(raw.mutatorName),
        reason: str(raw.statusReason), coveredBy: strs(raw.coveredBy), killedBy: strs(raw.killedBy), x: obj(raw.mutastic) };
      mutators[m.mutator] = 1;
      file.mutants.push(m); BYID[id] = m;
    });
    file.mutants.sort(function(a, b){ return a.line - b.line || a.col - b.col; });
    file.c = counts(file.mutants);
    FILES.push(file);
    var cur = ROOT, segs = path.split('/');
    for (var k = 0; k < segs.length - 1; k++) cur = cur.dirs[segs[k]] || (cur.dirs[segs[k]] = node(segs.slice(0, k + 1).join('/')));
    cur.files.push(file);
  });
  (function fill(nd){
    Object.keys(nd.dirs).sort().forEach(function(d){ nd.ms = nd.ms.concat(fill(nd.dirs[d])); });
    nd.files.forEach(function(f){ nd.ms = nd.ms.concat(f.mutants); });
    nd.c = counts(nd.ms);
    return nd.ms;
  })(ROOT);
  FILES.forEach(function(f){ ALL = ALL.concat(f.mutants); });
  MUTATORS = Object.keys(mutators).sort();
  // Tests: from testFiles, then any named only in coveredBy and killedBy.
  TESTS = dict();
  function test(id){ return TESTS[id] || (TESTS[id] = { id: id, name: id, file: '', loc: null, killed: [], reached: [], through: [] }); }
  var tf = obj(R.testFiles);
  Object.keys(tf).forEach(function(path){ (Array.isArray(obj(tf[path]).tests) ? tf[path].tests : []).forEach(function(t){
    t = obj(t); var id = str(t.id); if (!id) return;
    var o = test(id); o.name = str(t.name) || id; o.file = path; o.loc = obj(obj(t.location).start).line ? t.location : null; }); });
  ALL.forEach(function(m){
    m.coveredBy.forEach(function(t){ var o = test(t); o.reached.push(m); if (m.status === 'Survived') o.through.push(m); });
    m.killedBy.forEach(function(t){ test(t).killed.push(m); });
  });
}
function counts(ms){ var c = {}; ORDER.forEach(function(k){ c[k] = 0; }); ms.forEach(function(m){ c[m.status]++; }); return c; }
// The schema's score: caught (and timed out) over everything that ran or wasn't reached.
function scoreOf(c){ var d = c.Killed + c.Timeout, u = c.Survived + c.NoCoverage; return d + u ? Math.round(1000 * d / (d + u)) / 10 : null; }
function th(){ var t = obj(R.thresholds); return { high: typeof t.high === 'number' ? t.high : 80, low: typeof t.low === 'number' ? t.low : 60 }; }
function band(s){ var t = th(); return s == null ? ['--muted','--track'] : s >= t.high ? ['--high','--barK'] : s >= t.low ? ['--mid','--barN'] : ['--low','--barS']; }
function fmt(s){ return s == null ? 'n/a' : s.toFixed(1) + '%'; }
function shown(m){ var f = state.filter; return (f.all || f[m.status]) && (!state.mutator || m.mutator === state.mutator); }
function navSet(){ return ALL.filter(shown); }
function linesOf(f){ return f.lines || (f.lines = f.source.split('\n')); }
function testName(t){ var h = t.indexOf('#'); if (h > 0){ var c = t.slice(0, h); return c.slice(c.lastIndexOf('.') + 1) + t.slice(h); } return t; }
function testShort(t){ var i = Math.max(t.lastIndexOf('#'), t.lastIndexOf('::'), t.lastIndexOf('.')); return i >= 0 ? t.slice(i + (t.charAt(i) === ':' ? 2 : 1)) : t; }
// "Tests to write" holds what the tests let through: changes they ran and still passed (missed), and
// changes no test reaches (not covered). Both count against the score.
function toWrite(m){ return m.status === 'Survived' || m.status === 'NoCoverage'; }
// `discount()`, from the method the report names: `Pricing.discount()`, `Pricing::discount()`.
function methodName(m){ var s = str(m.x.method), i = Math.max(s.lastIndexOf('.'), s.lastIndexOf('::')); return i >= 0 ? s.slice(i + (s.charAt(i) === ':' ? 2 : 1)) : s; }
function headline(m){
  if (m.status === 'Survived' && m.reason) return cap(m.reason);
  if (m.status === 'NoCoverage') return methodName(m) ? 'No test reaches `' + methodName(m) + '`' : 'No test reaches line ' + m.line;
  return cap(m.description);
}
// The test to write, in a sentence: the card's fix line, or for a change no test reaches, a test
// that gets there.
function fixOf(m){
  if (str(m.x.fix)) return m.x.fix;
  if (m.status === 'NoCoverage') return methodName(m) ? 'add a test that calls `' + methodName(m) + '`' : 'add a test that runs line ' + m.line;
  return 'write a test whose answer changes with this change in';
}
// What keeps a score below 100%, in words: `2 missed, 1 no test reaches`.
function gapLine(c){
  var parts = [];
  if (c.Survived) parts.push(c.Survived + ' missed');
  if (c.NoCoverage) parts.push(c.NoCoverage + (c.Survived ? '' : c.NoCoverage === 1 ? ' change' : ' changes') + ' no test reaches');
  return parts.join(', ');
}
// Why a scope with nothing to write may still not be all caught.
function nothingToWrite(c){
  var rest = [], out = c.Ignored + c.CompileError + c.Pending;
  if (c.Timeout) rest.push(c.Timeout + ' timed out, which counts as caught');
  if (c.RuntimeError) rest.push(c.RuntimeError + ' crashed, which is out of the score');
  if (out) rest.push(out + (c.Ignored === out ? ' skipped' : ' skipped or not built') + ', out of the score');
  return 'None here.' + (rest.length ? ' ' + cap(rest.join('; ')) + '.' : ' Pip approves.');
}
function label(m){
  var out = [];
  if (m.status === 'Ignored'){ var k = m.reason.split(':')[0]; if (/^(generated|equivalent|unviable|arid|user|ignored)$/.test(k)) out.push(k); }
  if (str(m.x.nearUnsafe)) out.push('near unsafe');
  if (str(m.x.failureOrigin) && m.status === 'Killed') out.push('failed in ' + m.x.failureOrigin);
  return out;
}
function labs(m){ return label(m).map(function(l){ return '<span class="lab">' + esc(l) + '</span>'; }).join(''); }
function nodeAt(dir){
  var cur = ROOT;
  if (!dir) return cur;
  dir.split('/').every(function(s){ cur = cur && cur.dirs[s]; return !!cur; });
  return cur || null;
}

// ---------- commands ----------
// A path as one shell word.
function arg(s){ return /^[A-Za-z0-9_\/.:@%+=,-]+$/.test(s) ? s : "'" + s.replace(/'/g, "'\\''") + "'"; }
function where(){ var d = str(RUN.runDir); return d ? ' ' + arg(d) : ''; }
function commands(m){
  if (!SAFE_ID.test(m.id)) return null;
  return { verify: 'mutastic verify ' + m.id + where(), run: 'mutastic run --only=' + m.id + where(),
    ignore: 'mutastic ignore ' + m.id + ' --reason="<why>"' + where() };
}
function operatorDocs(m){
  var base = str(RUN.docsBase), page = str(obj(RUN.operatorDocs)[m.mutator]);
  if (!/^https:\/\/[^\s"'<>]+\/$/.test(base) || !page || /^[a-z]+:|^\/\/|[\s"'<>]/i.test(page)) return null;
  return base + page.replace(/^\//, '');
}

// ---------- routing ----------
function route(){
  var h; try { h = decodeURIComponent(location.hash.slice(1) || '/'); } catch (e) { h = '/'; }
  var p = h.split('/').filter(Boolean);
  if (p[0] === 'm' && BYID[p.slice(1).join('/')]) return { view: 'mutant', m: BYID[p.slice(1).join('/')] };
  if (p[0] === 'file') return { view: 'file', path: p.slice(1).join('/') };
  if (p[0] === 'dir') return { view: 'files', dir: p.slice(1).join('/') };
  if (p[0] === 'missed') return { view: 'missed' };
  if (p[0] === 'mutants') return { view: 'mutants' };
  if (p[0] === 'tests') return { view: 'tests' };
  return { view: 'files', dir: '' };
}
function go(h){ location.hash = h; }
var app = document.getElementById('app');
function render(){
  if (!R){ renderEmpty(); return; }
  var r = route();
  crumbs(r); tabs(r);
  if (r.view === 'mutant') renderMutant(r.m);
  else if (r.view === 'file') renderFile(FILES.filter(function(f){ return f.path === r.path; })[0]);
  else if (r.view === 'missed') renderMissed();
  else if (r.view === 'mutants') renderMutants();
  else if (r.view === 'tests') renderTests();
  else renderFiles(r.dir);
}
function crumbs(r){
  var parts = [['#/', 'All files']];
  if (r.view === 'missed') parts.push([null, 'Tests to write']);
  else if (r.view === 'mutants') parts.push([null, 'Mutants']);
  else if (r.view === 'tests') parts.push([null, 'Tests']);
  else {
    var path = r.view === 'files' ? r.dir : r.view === 'file' ? r.path : r.m.file, segs = path ? path.split('/') : [];
    segs.forEach(function(s, i){
      var to = segs.slice(0, i + 1).join('/'), isFile = i === segs.length - 1 && r.view !== 'files';
      parts.push([isFile ? fileHref(to) : href('#/dir/' + to), s]);
    });
    // Long paths: the first and the last two.
    if (parts.length > 5) parts = [parts[0], [null, '…']].concat(parts.slice(-2));
    if (r.view === 'mutant') parts.push([null, r.m.id]);
  }
  var html = '<a class="brand" href="#/" aria-label="mutastic report, all files">' + PIP + '<span>mutastic</span></a>';
  parts.forEach(function(p, i){
    var last = i === parts.length - 1;
    if (i) html += '<span class="sep" aria-hidden="true">/</span>';
    html += last || !p[0] ? '<span class="cur"' + (last ? ' aria-current="page"' : '') + '>' + esc(p[1]) + '</span>' : '<a href="' + p[0] + '">' + esc(p[1]) + '</a>';
  });
  document.getElementById('crumbs').innerHTML = html;
}
function tabs(r){
  var c = ROOT.c, mv = r.view === 'mutant';
  var t = [['#/', 'Files', FILES.length, r.view === 'files' || r.view === 'file' || (mv && !toWrite(r.m))],
    ['#/missed', 'Tests to write', c.Survived + c.NoCoverage, r.view === 'missed' || (mv && toWrite(r.m))],
    ['#/mutants', 'Mutants', ALL.length, r.view === 'mutants'],
    ['#/tests', 'Tests', Object.keys(TESTS).length, r.view === 'tests']];
  document.getElementById('tabs').innerHTML = t.map(function(x){ return '<a href="' + x[0] + '"' + (x[3] ? ' aria-current="page"' : '') + '>' + x[1] + ' <span class="n">' + x[2] + '</span></a>'; }).join('');
}

// ---------- pieces ----------
function stack(c){
  var segs = [['Killed','✓',c.Killed + c.Timeout,'--barK','#fff'],['Survived','✗',c.Survived,'--stackS','#fff'],['NoCoverage','○',c.NoCoverage,'--barN','#16201a'],
    ['Ignored','–',c.Ignored + c.RuntimeError + c.CompileError + c.Pending,'--barI','var(--text)']].filter(function(s){ return s[2]; });
  var said = segs.map(function(s){ return s[2] + ' ' + (s[0] === 'Ignored' ? 'skipped or out of the score' : ST[s[0]].word); }).join(', ');
  return '<div class="stack" role="img" aria-label="' + esc(said) + '">' + segs.map(function(s){
    return '<div style="flex:' + s[2] + ' 1 0;background:var(' + s[3] + ');color:' + s[4] + '" aria-hidden="true">' + s[1] + ' ' + s[2] + '</div>'; }).join('') + '</div>';
}
// A round watermelon: the rind as Pip's, the flesh red where the tests don't check and green where
// they do, the green sweeping clockwise from twelve o'clock as the score rises.
function melon(s, size){
  var p = Math.max(0, Math.min(1, (s || 0) / 100)), flesh = '';
  if (p >= 1) flesh = '<circle cx="60" cy="60" r="45" fill="var(--melonCheck)"/>';
  else if (p > 0){
    var a = 2 * Math.PI * p, x = (60 + 45 * Math.sin(a)).toFixed(2), y = (60 - 45 * Math.cos(a)).toFixed(2);
    flesh = '<path d="M60 60 L60 15 A45 45 0 ' + (p > 0.5 ? 1 : 0) + ' 1 ' + x + ' ' + y + ' Z" fill="var(--melonCheck)" stroke="var(--melonCut)" stroke-width="2" stroke-linejoin="round"/>';
  }
  var said = s == null ? 'No score: nothing ran.' : 'Mutation score ' + s + ' percent. Green is what your tests check; red is what they don\'t.';
  return '<svg viewBox="0 0 120 120" width="' + size + '" height="' + size + '" role="img" aria-label="' + esc(said) + '">' +
    '<circle cx="60" cy="60" r="58" fill="#2e7d32"/><circle cx="60" cy="60" r="53" fill="#a3d97a"/><circle cx="60" cy="60" r="49" fill="var(--melonPith)"/>' +
    '<circle cx="60" cy="60" r="45" fill="#f55252"/>' + flesh + '</svg>';
}
function gauge(c, name){
  var s = scoreOf(c);
  return '<section class="card pad gauge" aria-label="Score">' + melon(s, 168) +
    '<b style="color:var(' + band(s)[0] + ')">' + fmt(s) + '</b><span style="font-weight:600;overflow-wrap:anywhere">' + esc(name) + '</span>' +
    (s != null && s < 100 && gapLine(c) ? '<span class="gapline">' + esc(gapLine(c)) + '</span>' : '') +
    '<span class="cap">Green is what your tests check. Red is what they don\'t.</span></section>';
}
function todoText(m){ return words(cap(fixOf(m))); }
function todo(ms, cur, scope, scopeName){
  var miss = ms.filter(toWrite);
  if (!miss.length) return '<section class="card pad"><h2 style="margin-bottom:8px">Tests to write</h2><p class="cap" style="margin:0">' + nothingToWrite(counts(ms)) + '</p></section>';
  var start = 0;
  if (cur){ var k = miss.indexOf(cur); if (k > 5) start = k - 3; }
  var some = miss.slice(start, start + 6);
  return '<section class="card pad" aria-labelledby="todo-h"><h2 id="todo-h" style="margin-bottom:10px">Tests to write</h2>' + copyPair(scope, miss.length, scopeName, true) + '<ol class="todo" style="margin-top:12px">' +
    some.map(function(m){ return '<li><a href="' + mHref(m) + '"' + (m === cur ? ' aria-current="true"' : '') + '><span class="box" aria-hidden="true"></span><span><span class="w">' + esc(short(m.file)) + ':' + m.line + '</span><br><span class="t">' + todoText(m) + '</span></span></a></li>'; }).join('') +
    '</ol>' + (miss.length > some.length ? '<p class="cap" style="margin:10px 0 0"><a href="#/missed">See every test to write</a></p>' : '') + '</section>';
}
function chips(ms, onlyPresent){
  var c = counts(ms), f = state.filter;
  var out = ORDER.filter(function(k){ return c[k] || !onlyPresent && k === 'Survived'; }).map(function(k){
    return '<button type="button" class="chip" data-st="' + k + '" aria-pressed="' + (!f.all && !!f[k]) + '"><span aria-hidden="true">' + ST[k].sym + '</span> ' + cap(ST[k].word) + ' <span class="n">' + c[k] + '</span></button>'; });
  out.push('<button type="button" class="chip" data-st="all" aria-pressed="' + !!f.all + '">All <span class="n">' + ms.length + '</span></button>');
  return '<div class="chips" role="group" aria-label="Which mutants to show">' + out.join('') + '</div>';
}
function mutatorPick(ms){
  var here = dict(); ms.forEach(function(m){ here[m.mutator] = (here[m.mutator] || 0) + 1; });
  var names = MUTATORS.filter(function(k){ return here[k] || k === state.mutator; });
  if (names.length < 2 && !state.mutator) return '';
  return '<label class="pick">Operator <select id="mutator"><option value="">Any</option>' + names.map(function(k){
    return '<option value="' + esc(k) + '"' + (k === state.mutator ? ' selected' : '') + '>' + esc(k) + ' (' + (here[k] || 0) + ')</option>'; }).join('') + '</select></label>';
}
function verdict(s){ var t = th(); return s == null ? 'Nothing ran.' : s >= t.high ? 'Your tests check most of what they run.' : s >= t.low ? 'Some of it is hollow.' : 'Much of what your tests run goes unchecked.'; }
function runVerdict(){
  if (typeof RUN.passed !== 'boolean' || typeof RUN.threshold !== 'number') return '';
  return RUN.passed ? ' The run passed its ' + RUN.threshold + '% threshold.' : ' The run is under its ' + RUN.threshold + '% threshold.';
}
function keysCard(){
  var f = state.filter, only = !f.all && Object.keys(f).length === 2 && f.Survived && f.NoCoverage;
  return '<p class="keys"><kbd>j</kbd> <kbd>k</kbd> next and previous ' + (only ? 'test to write' : 'mutant shown') + ' · <kbd>Esc</kbd> back · <kbd>/</kbd> find</p>'; }
function mrow(m, withFile){
  return '<a class="mrow ' + m.status + '" href="' + mHref(m) + '">' + pill(m.status) + labs(m) + '<span class="d">' + words(toWrite(m) ? headline(m) : m.description) + '</span><span class="id">' + esc(m.id) + '</span>' +
    (withFile ? '<span class="at">' + esc(m.file) + ':' + m.line + '</span>' : '') + '</a>';
}
function moreBtn(id){ return '<button type="button" class="btn more" id="' + id + '">Show more</button>'; }
// Re-renders with the caret where it was in the search box that changed.
function searchBox(id, key){
  var box = document.getElementById(id);
  box.addEventListener('input', function(){ state[key] = box.value; var pos = box.selectionStart; render(); var n = document.getElementById(id); n.focus(); n.setSelectionRange(pos, pos); });
}

// ---------- views ----------
function renderEmpty(){
  document.getElementById('crumbs').innerHTML = '<a class="brand" href="#/">' + PIP + '<span>mutastic</span></a>';
  document.getElementById('tabs').innerHTML = '';
  app.innerHTML = '<div class="card empty">' + PIP + '<h1 style="font-size:28px">No report in this page</h1><p>Drop a <code>mutation.json</code> here, or pick one below.</p></div>';
}
function renderFiles(dir){
  var nd = nodeAt(dir);
  if (!nd){ app.innerHTML = '<div class="card empty">' + PIP + '<p>That folder isn\'t in this report.</p><a href="#/">All files</a></div>'; return; }
  var c = nd.c, s = dir ? scoreOf(c) : typeof RUN.score === 'number' ? RUN.score : scoreOf(c), q = state.q.trim().toLowerCase();
  var rows = [];
  // Paths break only after a slash; a file found by search shows its folder under its name.
  function nameHtml(name, inDir){ return '<span class="nm">' + esc(name).replace(/\//g, '/<wbr>') + (inDir ? '<span class="in">' + esc(inDir).replace(/\//g, '/<wbr>') + '</span>' : '') + '</span>'; }
  function row(name, to, cc, isDir, depth, inDir){
    if (state.treeOnlyMissed && !cc.Survived && !cc.NoCoverage) return;
    var sc = scoreOf(cc), b = band(sc), total = ORDER.reduce(function(t, k){ return t + cc[k]; }, 0);
    rows.push('<tr class="' + (isDir ? 'dir' : 'file') + '"><td style="padding-left:' + (12 + depth * 20) + 'px"><a class="name" href="' + to + '">' + (isDir ? DIR_ICON : FILE_ICON) + nameHtml(name, inDir) + '</a></td>' +
      '<td class="sc"><span class="bar"><span aria-hidden="true"><span style="width:' + (sc || 0) + '%;background:var(' + b[1] + ')"></span></span><b style="color:var(' + b[0] + ')">' + fmt(sc) + '</b></span></td>' +
      '<td class="num" style="color:' + (cc.Survived ? 'var(--survInk);font-weight:700' : 'var(--muted)') + '">' + cc.Survived + '</td>' +
      '<td class="num hide-s" style="color:' + (cc.NoCoverage ? 'var(--ncInk)' : 'var(--muted)') + '">' + cc.NoCoverage + '</td>' +
      '<td class="num hide-s">' + (cc.Killed + cc.Timeout) + '</td>' +
      '<td class="num hide-s" style="color:var(--muted)">' + (cc.Ignored + cc.RuntimeError + cc.CompileError + cc.Pending) + '</td>' +
      '<td class="num hide-s">' + total + '</td></tr>');
  }
  if (q){
    FILES.filter(function(f){ return (!dir || f.path.indexOf(dir + '/') === 0) && f.path.toLowerCase().indexOf(q) >= 0; }).forEach(function(f){
      row(short(f.path), fileHref(f.path), f.c, false, 0, f.path.slice(0, f.path.length - short(f.path).length)); });
  } else {
    // Folders' rows don't change while the page is open: built once for each folder and setting.
    var key = dir + '\n' + state.treeOnlyMissed;
    if (ROWS[key] == null){
      (function walk(at, depth){
        Object.keys(at.dirs).sort().forEach(function(d){
          var ch = at.dirs[d], name = d, only;
          // a/b/c chains of folders with one folder and no files fold into one row
          while (!ch.files.length && (only = Object.keys(ch.dirs)).length === 1){ name += '/' + only[0]; ch = ch.dirs[only[0]]; }
          row(name + '/', href('#/dir/' + ch.path), ch.c, true, depth);
          if (depth < 1 || dir) walk(ch, depth + 1);
        });
        at.files.forEach(function(f){ row(short(f.path), fileHref(f.path), f.c, false, depth); });
      })(nd, 0);
      ROWS[key] = rows.join('');
    }
    rows = [ROWS[key]];
  }
  var title = dir ? short(dir) : 'All files';
  app.innerHTML = '<div class="cols"><div class="main">' +
    '<div class="hero"><div><h1>' + esc(title) + '</h1><div class="path">' + esc(dir || (str(R.projectRoot) ? short(str(R.projectRoot).replace(/[\\\/]+$/, '').replace(/\\/g, '/')) : '')) + '</div><p class="lede">' + verdict(s) + (dir ? '' : runVerdict()) + ' Each miss is a test you haven\'t written yet.</p></div>' +
    '<div class="score">' + melon(s, 56) + '<b style="color:var(' + band(s)[0] + ')">' + fmt(s) + '</b><span style="color:var(--muted)">mutation score' +
    (s != null && s < 100 && gapLine(c) ? '<br><span class="gapline">' + esc(gapLine(c)) + '</span>' : '') + '</span></div></div>' +
    stack(c) +
    '<div class="toolbar"><label for="q">Find a file</label><input id="q" type="search" placeholder="e.g. ' + esc(FILES[0] ? short(FILES[0].path).split('.')[0] : '') + '" value="' + esc(state.q) + '">' +
    '<button type="button" class="chip" id="onlyMissed" aria-pressed="' + state.treeOnlyMissed + '"><span aria-hidden="true">✗</span> Only files with misses</button></div>' +
    '<div class="card" style="overflow-x:auto"><table class="tbl"><caption class="sr">Files and folders, with their scores</caption><thead><tr><th scope="col">File or folder</th><th scope="col">Score</th><th scope="col" class="num" title="Missed"><span aria-hidden="true">✗</span><span class="sr">Missed</span></th><th scope="col" class="num hide-s" title="Not covered"><span aria-hidden="true">○</span><span class="sr">Not covered</span></th><th scope="col" class="num hide-s" title="Caught"><span aria-hidden="true">✓</span><span class="sr">Caught</span></th><th scope="col" class="num hide-s" title="Skipped"><span aria-hidden="true">–</span><span class="sr">Skipped</span></th><th scope="col" class="num hide-s">All</th></tr></thead><tbody>' +
    (rows.join('') || '<tr><td colspan="7" class="empty">No file matches.</td></tr>') + '</tbody></table></div>' +
    '</div><aside>' + statusCard(c, nd.ms.length) + todo(nd.ms, null, dir ? 'dir:' + dir : 'all', dir ? short(dir) + '/' : 'all files') + keysCard() + '</aside></div>';
  searchBox('q', 'q');
  document.getElementById('onlyMissed').addEventListener('click', function(){ state.treeOnlyMissed = !state.treeOnlyMissed; render(); });
}
function statusCard(c, n){
  return '<section class="card pad" aria-labelledby="st-h"><h2 id="st-h" style="margin-bottom:10px">' + n + ' mutants</h2><ul class="todo">' + ORDER.filter(function(k){ return c[k]; }).map(function(k){
    var inner = '<span class="pill" style="color:var(' + ST[k].ink + ');background:var(' + ST[k].pill + ')" aria-hidden="true">' + ST[k].sym + '</span><span style="flex:1"><b>' + cap(ST[k].word) + '</b> <span class="mono">' + c[k] + '</span><br><span class="cap">' + SAY[k] + '</span></span>';
    return '<li>' + (k === 'Survived' || k === 'NoCoverage' ? '<a href="#/missed">' + inner + '</a>' : '<div class="row">' + inner + '</div>') + '</li>'; }).join('') + '</ul></section>';
}

// A file's source, in chunks of lines. A long file's chunks are filled as they near the screen.
var CHUNK = 200, chunkWatch = null;
function renderFile(f){
  if (!f){ app.innerHTML = '<div class="card empty">' + PIP + '<p>That file isn\'t in this report.</p><a href="#/">All files</a></div>'; return; }
  var lines = linesOf(f), byLine = dict(), span = dict();
  f.mutants.forEach(function(m){
    (byLine[m.line] = byLine[m.line] || []).push(m);
    // The gutter shows each line a mutant's change spans, the worst status on top.
    for (var l = m.line; l <= Math.min(m.eline, m.line + 500); l++) (span[l] = span[l] || []).push(m);
  });
  function bar(ms){ var c = counts(ms); return c.Survived ? 'var(--barS)' : c.NoCoverage ? 'var(--ncBar)' : (c.Killed + c.Timeout) ? 'var(--killBar)' : ms.length ? 'var(--ignBar)' : 'transparent'; }
  function lineAt(i){
    var no = i + 1, here = byLine[no] || [], across = span[no] || [], c = counts(here), mk = '';
    if (here.length){
      var parts = ORDER.filter(function(k){ return c[k]; }).map(function(k){ return '<span style="color:var(' + ST[k].ink + ')">' + ST[k].sym + c[k] + '</span>'; }).join(' ');
      var said = ORDER.filter(function(k){ return c[k]; }).map(function(k){ return c[k] + ' ' + ST[k].word; }).join(', ');
      mk = '<button type="button" data-line="' + no + '" aria-expanded="' + !!state.lineOpen[f.path + ':' + no] + '" aria-label="Line ' + no + ': ' + said + '">' + parts + '</button>';
    }
    var html = '<div class="ln' + (counts(across).Survived ? ' s' : '') + '" id="L' + no + '"><span class="no">' + no + '</span><span class="gb" style="background:' + bar(across) + '"></span><span class="tx">' + (lineHtml(lit(f)[i]) || ' ') + '</span><span class="mk">' + mk + '</span></div>';
    var open = state.lineOpen[f.path + ':' + no], vis = here.filter(function(m){ return open || shown(m); });
    return html + (vis.length ? '<div class="inl">' + vis.map(function(m){ return mrow(m); }).join('') + '</div>' : '');
  }
  function chunkHtml(k){ var out = ''; for (var i = k * CHUNK; i < Math.min(lines.length, (k + 1) * CHUNK); i++) out += lineAt(i); return out; }
  var n = Math.ceil(lines.length / CHUNK), big = lines.length > 10 * CHUNK, out = [];
  for (var k = 0; k < n; k++){
    if (!big || k === 0) out.push('<div class="chunk">' + chunkHtml(k) + '</div>');
    else {
      var rowsIn = 0;
      for (var l = k * CHUNK + 1; l <= Math.min(lines.length, (k + 1) * CHUNK); l++) rowsIn += (byLine[l] || []).filter(shown).length;
      out.push('<div class="chunk" data-k="' + k + '" style="height:' + Math.round(Math.min(CHUNK, lines.length - k * CHUNK) * 24.3 + rowsIn * 48) + 'px"></div>');
    }
  }
  app.innerHTML = '<div class="cols"><div class="main"><div class="hero"><div><h1>' + esc(short(f.path)) + '</h1><div class="path">' + esc(f.path) + '</div></div></div>' +
    '<section class="card" aria-label="Source"><div class="srcbar"><div class="chips">' + chips(f.mutants, true) + mutatorPick(f.mutants) + '</div><span class="cap">Select a line\'s markers to see every mutant on it.</span></div>' +
    '<div class="src">' + out.join('') + '</div></section></div>' +
    '<aside>' + gauge(f.c, short(f.path)) + todo(f.mutants, null, 'file:' + f.path, short(f.path)) + keysCard() + '</aside></div>';
  if (chunkWatch) chunkWatch.disconnect();
  chunkWatch = null;
  if (big){
    var fillChunk = function(el){ if (!el.hasAttribute('data-k')) return; el.innerHTML = chunkHtml(+el.getAttribute('data-k')); el.removeAttribute('data-k'); el.style.height = ''; };
    var pending = app.querySelectorAll('.chunk[data-k]');
    if ('IntersectionObserver' in window){
      chunkWatch = new IntersectionObserver(function(es){ es.forEach(function(e){ if (e.isIntersecting){ fillChunk(e.target); chunkWatch.unobserve(e.target); } }); }, { rootMargin: '1500px 0px' });
      pending.forEach(function(el){ chunkWatch.observe(el); });
    } else pending.forEach(fillChunk);
    app.fillAll = function(){ app.querySelectorAll('.chunk[data-k]').forEach(fillChunk); };
  }
}
window.addEventListener('beforeprint', function(){ if (app.fillAll) app.fillAll(); });
app.addEventListener('click', function(e){
  var b = e.target.closest('.mk button'), r = route(); if (!b || r.view !== 'file') return;
  var k = r.path + ':' + b.getAttribute('data-line'); state.lineOpen[k] = !state.lineOpen[k];
  var y = window.scrollY; render(); window.scrollTo(0, y);
  var again = app.querySelector('.mk button[data-line="' + b.getAttribute('data-line') + '"]'); if (again) again.focus({preventScroll: true});
});

function renderMissed(){
  var all = ALL.filter(toWrite), miss = all.filter(function(m){ return !state.mutator || m.mutator === state.mutator; });
  var some = miss.slice(0, state.missedShown);
  app.innerHTML = '<div class="cols"><div class="main"><div class="hero"><div><h1>Tests to write</h1><p class="lede">Each is a change your tests let through: they ran it and still passed, or no test reaches it. Write the test, then prove it with <code>mutastic verify</code>.</p></div>' + (miss.length ? '<div>' + copyPair(state.mutator ? 'all:' + state.mutator : 'all', miss.length, state.mutator ? 'what ' + state.mutator + ' made' : 'all files') + '</div>' : '') + '</div>' +
    (all.length ? '<div class="toolbar">' + mutatorPick(all) + '</div>' : '') +
    (miss.length ? '<div class="list">' + some.map(function(m){
      return '<a class="miss" href="' + mHref(m) + '"><span class="h">' + words(headline(m)) + '</span><span class="w">' + esc(m.id) + '</span><span class="w wide">' + (m.status === 'NoCoverage' ? pill(m.status) + ' ' : '') + esc(m.file) + ':' + m.line + ' · ' + words(m.description) + '</span>' +
        '<span class="f">' + todoText(m) + '</span></a>'; }).join('') + '</div>' +
      (miss.length > some.length ? moreBtn('more') : '')
      : '<div class="card empty">' + PIP + '<p>' + (all.length ? 'Nothing here from ' + esc(state.mutator) + '.' : 'Nothing got through: your tests reach every change, and caught each one they ran.') + '</p></div>') +
    '</div><aside>' + gauge(ROOT.c, 'All files') + keysCard() + '</aside></div>';
  var b = document.getElementById('more'); if (b) b.addEventListener('click', function(){ state.missedShown += 100; render(); });
}

// Every mutant, by status and operator, across the files: the file view's filters, for the whole run.
function renderMutants(){
  var q = state.mq.trim().toLowerCase(), inPath = ALL.filter(function(m){ return !q || m.file.toLowerCase().indexOf(q) >= 0; });
  var list = inPath.filter(shown), some = list.slice(0, state.mutantsShown), last = null, body = '';
  some.forEach(function(m){ if (m.file !== last){ body += '<h2 class="group"><a href="' + fileHref(m.file) + '">' + esc(m.file) + '</a></h2>'; last = m.file; } body += mrow(m); });
  app.innerHTML = '<div class="solo"><div class="hero"><div><h1>Mutants</h1><p class="lede">Every change mutastic made, by what became of it and which operator made it.</p></div></div>' +
    '<div class="toolbar">' + chips(inPath, true) + mutatorPick(inPath) + '</div>' +
    '<div class="toolbar"><label for="mq">Only files matching</label><input id="mq" type="search" value="' + esc(state.mq) + '" placeholder="e.g. ' + esc(FILES[0] ? short(FILES[0].path).split('.')[0] : '') + '"></div>' +
    '<p class="cap" role="status">' + list.length + ' of ' + ALL.length + ' mutants shown</p>' +
    (list.length ? '<div class="list">' + body + '</div>' : '<div class="card empty"><p>No mutant matches.</p></div>') +
    (list.length > some.length ? moreBtn('more') : '') + '</div>';
  searchBox('mq', 'mq');
  var b = document.getElementById('more'); if (b) b.addEventListener('click', function(){ state.mutantsShown += 200; render(); });
}

// Where a test starts in its file: its location, or a declaration found by its name.
function testLine(t){
  if (t.loc) return { line: int(t.loc.start.line, 1), guessed: false };
  var src = str(obj(obj(R.testFiles)[t.file]).source), name = testShort(t.name);
  if (!src || !name) return null;
  var re = new RegExp('\\b(void|fun|fn)\\s+`?' + name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '`?\\s*[(<]');
  var ls = src.split('\n');
  for (var i = 0; i < ls.length; i++) if (re.test(ls[i])) return { line: i + 1, guessed: true };
  return null;
}
// A test's source: its annotations, then its declaration to the brace that closes it.
var testLit = dict();
function testSource(t){
  var src = str(obj(obj(R.testFiles)[t.file]).source), at = testLine(t);
  if (!src || !at) return '';
  var lt = testLit[t.file] || (testLit[t.file] = tokens(src, langOf(t.file))), ls = src.split('\n');
  var first = at.line - 1, end = first, depth = 0, opened = false;
  while (first > 0 && /^\s*(@|#\[)/.test(ls[first - 1])) first--;
  for (; end < ls.length && end < first + 60; end++){
    lt[end].forEach(function(s){ if (s[0]) return; for (var k = 0; k < s[1].length; k++){ var ch = s[1].charAt(k); if (ch === '{'){ depth++; opened = true; } else if (ch === '}') depth--; } });
    if (opened && depth <= 0) break;
  }
  var out = [];
  for (var i = first; i <= Math.min(end, ls.length - 1); i++) out.push('<div><span class="no">' + (i + 1) + '</span>' + (lineHtml(lt[i]) || ' ') + '</div>');
  return '<div class="frame" aria-label="The test\'s source, ' + esc(t.file) + ' line ' + (first + 1) + '">' + out.join('') + '</div>';
}
function renderTests(){
  var q = state.tq.trim().toLowerCase();
  var list = Object.keys(TESTS).map(function(k){ return TESTS[k]; }).filter(function(t){ return !q || t.id.toLowerCase().indexOf(q) >= 0; })
    .sort(function(a, b){ return b.through.length - a.through.length || b.killed.length - a.killed.length || (a.id < b.id ? -1 : 1); });
  var some = list.slice(0, state.testsShown);
  app.innerHTML = '<div class="solo"><div class="hero"><div><h1>Tests</h1><p class="lede">What each test caught, and what it ran but let through. The ones that let the most through come first.</p></div></div>' +
    '<div class="toolbar"><label for="tq">Find a test</label><input id="tq" type="search" value="' + esc(state.tq) + '" placeholder="e.g. ' + esc(list[0] ? testShort(list[0].id) : '') + '"></div>' +
    '<div class="card"><div class="thead" aria-hidden="true"><span>Test</span><span>✓ Caught</span><span>Reached</span><span>✗ Let through</span></div>' +
    some.map(function(t){
      return '<details class="trow" data-t="' + esc(t.id) + '"><summary aria-label="' + esc(testShort(t.id) + ': caught ' + t.killed.length + ', reached ' + t.reached.length + ', let through ' + t.through.length) + '"><span class="tn">' + esc(testShort(t.id)) + '<small>' + esc(t.file || t.id) + '</small></span>' +
        '<span class="cnt">' + t.killed.length + '</span><span class="cnt">' + t.reached.length + '</span><span class="cnt" style="color:' + (t.through.length ? 'var(--survInk);font-weight:700' : 'var(--muted)') + '">' + t.through.length + '</span></summary><div class="body"></div></details>'; }).join('') +
    (list.length ? '' : '<div class="empty">No test matches.</div>') + '</div>' +
    (list.length > some.length ? moreBtn('more') : '') +
    '<p class="cap">Only the first test to catch a mutant is named, so a test can reach a mutant another test caught.</p></div>';
  app.querySelectorAll('details.trow').forEach(function(d){ d.addEventListener('toggle', function(){
    if (!d.open || d.getAttribute('data-done')) return; d.setAttribute('data-done', '1');
    var t = TESTS[d.getAttribute('data-t')], through = t.through.slice(0, 50), killed = t.killed.slice(0, 50), link = testHref(t.id);
    d.querySelector('.body').innerHTML = testSource(t) + (link ? '<p class="cap"><a href="' + esc(link.href) + '">Open the test</a></p>' : '') +
      (through.length ? '<h3 class="cap">Ran these and still passed</h3>' + through.map(function(m){ return mrow(m, true); }).join('') : '') +
      (killed.length ? '<h3 class="cap">Caught these</h3>' + killed.map(function(m){ return mrow(m, true); }).join('') : '') +
      ((t.through.length > 50 || t.killed.length > 50) ? '<p class="cap">Showing the first 50 of each.</p>' : '') +
      (!through.length && !killed.length ? '<p class="cap">It reached mutants other tests caught first.</p>' : '');
  }); });
  searchBox('tq', 'tq');
  var b = document.getElementById('more'); if (b) b.addEventListener('click', function(){ state.testsShown += 200; render(); });
}

// Links to a test in the person's own checkout, only when this page was opened from disk inside
// the project the report names (projectRoot): a report downloaded from CI shows plain names.
var EDITORS = { vscode: 'VS Code', idea: 'IntelliJ', file: 'File' };
var editor = 'vscode';
try { editor = EDITORS[localStorage.getItem('mutastic-editor')] ? localStorage.getItem('mutastic-editor') : 'vscode'; } catch (e) { /* storage off: VS Code */ }
function linkRoot(){
  var root = str(R.projectRoot);
  if (!/^(\/|[A-Za-z]:[\\\/])/.test(root)) return null;
  root = root.replace(/\\/g, '/').replace(/\/+$/, '');
  var here; try { here = decodeURIComponent(location.pathname).replace(/^\/([A-Za-z]:)/, '$1'); } catch (e) { return null; }
  return location.protocol === 'file:' && here.indexOf(root + '/') === 0 ? root : null;
}
function testHref(id){
  var root = linkRoot(), t = TESTS[id];
  if (!root || !t || !t.file || !/\.(java|kt|kts|rs|groovy|scala)$/.test(t.file) || /(^|\/)\.\.(\/|$)/.test(t.file)) return null;
  var abs = root + '/' + t.file, at = testLine(t), line = at ? at.line : 1;
  var to = editor === 'idea' ? 'idea://open?file=' + encodeURIComponent(abs) + '&line=' + line
    : editor === 'file' ? 'file://' + encodeURI(abs) : 'vscode://file' + encodeURI(abs) + ':' + line;
  return { href: to, guessed: !at || at.guessed };
}
function editorPick(){
  return '<label class="pick">Open in <select id="editor">' +
    Object.keys(EDITORS).map(function(k){ return '<option value="' + k + '"' + (k === editor ? ' selected' : '') + '>' + EDITORS[k] + '</option>'; }).join('') + '</select></label>';
}
document.addEventListener('change', function(e){
  if (e.target.id === 'editor'){ editor = e.target.value; try { localStorage.setItem('mutastic-editor', editor); } catch (err) { /* this page only */ } render(); }
  if (e.target.id === 'mutator'){ state.mutator = e.target.value; render(); var s = document.getElementById('mutator'); if (s) s.focus(); }
});
app.addEventListener('click', function(e){
  var b = e.target.closest('.chip[data-st]'); if (!b) return;
  var k = b.getAttribute('data-st');
  if (k === 'all') state.filter = {all: true};
  else { var f = state.filter.all ? {} : Object.assign({}, state.filter); if (f[k]) delete f[k]; else f[k] = true; if (!Object.keys(f).length) f = {all: true}; state.filter = f; }
  render();
  var again = app.querySelector('.chip[data-st="' + k + '"]'); if (again) again.focus();
});

// ---------- Copy as prompt ----------
// The misses as mutastic's agent format writes them, under its rules, for any coding agent. Only
// what the report has goes in; a missing field is left out, never filled.
var RULES = [
  'Add or change tests only. Never change production code to make a mutant go away: tell the person.',
  'Write each test in the test class its card names, from what the code should do.',
  'Check the value the card\'s test expects against what the code should do. If they disagree, say so and ask: don\'t pin a bug.',
  'A fix counts when its `mutastic verify` passes. Don\'t move on until it does.',
  'If a mutant\'s case can\'t happen in practice, or the change is one no caller can see, don\'t contrive a test: propose ignoring it, for a person to accept, as the kind it is (equivalent, unreachable, telemetry, deprecated or accepted-risk), with the reason in two sentences: the one case where it differs, and why that can\'t happen.'
];
var TASK_LIST = 'Work through them one at a time: first make a task list with one item per mutant, grouped by file, then for each write its test, run its `mutastic verify`, and mark it done before starting the next.';
// The line with `replacement` spliced over the location, or null when that wouldn't be code. Older
// reports put the description there (remove-conditional in Java), which would splice a sentence in.
function spliced(m, src){
  var rep = m.replacement;
  var ok = rep != null && m.eline === m.line && m.ecol >= m.col && rep !== m.description && rep.indexOf('`') < 0 &&
    !/[\r\n]/.test(rep) && !/\b(is always|becomes|instead|removed|no longer)\b/i.test(rep);
  var after = ok ? src.slice(0, m.col - 1) + rep + src.slice(m.ecol - 1) : null;
  return after === src ? null : after;
}
function inputsSeen(x){
  var inputs = obj(x.inputs);
  return Object.keys(inputs).filter(function(k){ return Array.isArray(inputs[k]); }).map(function(k){ return k + ' = ' + inputs[k].map(val).join(', '); }).join('; ');
}
function agentCard(m, n){
  var x = m.x, src = linesOf(m.f)[m.line - 1] || '', out = [], c = commands(m), st = obj(x.suggestedTest), ev = strs(x.evidence);
  out.push(n + '. ' + ST[m.status].word + ' ' + m.id + ': ' + (m.status === 'Survived' ? m.reason || m.description : headline(m)));
  out.push('   at ' + m.file + ':' + m.line);
  var after = spliced(m, src);
  if (after != null){ out.push('   - ' + src.trim()); out.push('   + ' + after.trim()); }
  else out.push('   change: ' + m.description);
  if (m.coveredBy.length) out.push('   covered by: ' + m.coveredBy.map(testName).join(', ') + '; all still pass with the change');
  ev.forEach(function(e){ out.push('   evidence: ' + e); });
  if (inputsSeen(x) && !ev.some(function(e){ return /^inputs seen/.test(e); })) out.push('   evidence: inputs seen: ' + inputsSeen(x));
  out.push('   fix: ' + fixOf(m));
  if (str(st.code)){
    out.push('   test to add in ' + str(st.testClass) + ':');
    st.code.split('\n').forEach(function(l){ out.push('     ' + l); });
    out.push("     // check today's value is the one you intend: the test pins what the code does now");
  }
  if (c){ out.push('   done when: ' + c.verify + ' passes'); out.push('   reproduce: ' + c.run); }
  return out.join('\n');
}
function prompt(ms){
  var one = ms.length === 1;
  return (one ? 'Write the test this mutant asks for. It is a small change mutastic made to the code that no test caught: behaviour no test checks.'
              : 'Write the tests these mutants ask for. Each is a small change mutastic made to the code that no test caught: behaviour no test checks.') +
    ' A missed one ran and every test still passed; a not covered one no test reaches.' +
    '\n\nRules:\n' + (one ? RULES : RULES.concat([TASK_LIST])).map(function(r){ return '- ' + r; }).join('\n') + '\n\n' +
    ms.map(function(m, i){ return agentCard(m, i + 1); }).join('\n\n') + '\n';
}
// "Copy" beside "Copy as prompt": the same misses, one for a person and one for an agent.
function copyPair(which, n, scopeName, primary){
  var what = n === 1 && !scopeName ? 'this one' : (n === 1 ? 'the test to write' : 'the ' + n + ' tests to write') + (scopeName ? ' in ' + scopeName : '');
  var list = '<button type="button" class="btn' + (primary && !PROMPT ? ' primary' : '') + '" data-list="' + esc(which) + '" title="As a list, for an issue or a PR" aria-label="Copy ' + esc(what) + ' as a list"><span class="lbl">Copy</span></button>';
  if (!PROMPT) return '<div class="pair">' + list + '</div>';
  var text = 'Copy as prompt' + (scopeName ? ' · ' + scopeName : '');
  return '<div class="pair">' + list + '<button type="button" class="btn' + (primary ? ' primary' : '') + '" data-prompt="' + esc(which) + '" title="For your coding agent: ' + esc(what) + ', with mutastic\'s rules"><span class="lbl">' + esc(text) + '</span></button></div>';
}
function missSet(w){
  var miss = ALL.filter(toWrite);
  if (w === 'all') return miss;
  if (w.indexOf('all:') === 0) return miss.filter(function(m){ return m.mutator === w.slice(4); });
  if (w.indexOf('file:') === 0) return miss.filter(function(m){ return m.file === w.slice(5); });
  if (w.indexOf('dir:') === 0) return miss.filter(function(m){ return m.file.indexOf(w.slice(4) + '/') === 0; });
  return BYID[w] ? [BYID[w]] : [];
}
// Characters that hide or reorder text (bidi controls, zero-width characters), terminal escapes
// and other control characters, written as visible escapes in whatever is copied: pasted, the text
// shows everything it holds.
function neutral(text){
  return text.replace(/[\u0000-\u0008\u000b-\u001f\u007f-\u009f\u061c\u200b-\u200f\u202a-\u202e\u2060-\u2064\u2066-\u2069\ufeff]/g, function(c){
    return '\\u{' + c.charCodeAt(0).toString(16).toUpperCase().padStart(4, '0') + '}'; });
}
// The tests to write as a list, a line each, for an issue, a PR or a chat: `- lib.rs:62 - add a test
// where …`. A file goes by its name, or its path where two in the list share a name.
function issues(ms){
  var paths = dict();
  ms.forEach(function(m){ (paths[short(m.file)] = paths[short(m.file)] || dict())[m.file] = 1; });
  return ms.map(function(m){
    var fix = fixOf(m), name = Object.keys(paths[short(m.file)]).length > 1 ? m.file : short(m.file);
    return '- ' + name + ':' + m.line + ' - ' + fix.charAt(0).toLowerCase() + fix.slice(1);
  }).join('\n') + '\n';
}
function copy(text, b){
  var lbl = b.querySelector('.lbl') || b, was = lbl.textContent;
  function done(ok){ lbl.textContent = ok ? 'Copied' : 'Copy failed'; say(ok ? 'Copied' : 'Copy failed'); setTimeout(function(){ lbl.textContent = was; }, 1600); }
  try { navigator.clipboard.writeText(text).then(function(){ done(true); }, function(){ done(false); }); } catch (err) { done(false); }
}
document.addEventListener('click', function(e){
  var b = e.target.closest('[data-prompt]');
  if (b){ var ms = missSet(b.getAttribute('data-prompt')); if (ms.length) copy(neutral(prompt(ms)), b); return; }
  b = e.target.closest('[data-list]');
  if (b){ var ls = missSet(b.getAttribute('data-list')); if (ls.length) copy(neutral(issues(ls)), b); return; }
  b = e.target.closest('[data-copy]');
  if (b) copy(b.getAttribute('data-copy'), b);
});
function copyBtn(text, what, cls){ return '<button type="button"' + (cls ? ' class="' + cls + '"' : '') + ' data-copy="' + esc(text) + '" aria-label="Copy ' + esc(what) + '"><span class="lbl">Copy</span></button>'; }

// ---------- one mutant ----------
// The lines around the change, the change marked; a change over many lines shows its ends.
function frame(m){
  var lines = linesOf(m.f), lt = lit(m.f), out = [], from = Math.max(1, m.line - 3), to = Math.min(lines.length, m.eline + 3);
  function lead(n){ var t = lines[n - 1] || ''; return t.length - t.replace(/^\s+/, '').length; }
  function range(n){
    if (n < m.line || n > m.eline) return [null, null];
    var a = n === m.line ? m.col - 1 : lead(n), b = n === m.eline ? m.ecol - 1 : Infinity;
    return [a, b];
  }
  for (var n = from; n <= to; n++){
    if (to - from > 30 && n === m.line + 10){ out.push('<div class="dim"><span class="no">⋮</span></div>'); n = m.eline - 4; continue; }
    var r = range(n), inside = n >= m.line && n <= m.eline;
    out.push('<div' + (inside ? '' : ' class="dim"') + '><span class="no">' + n + '</span>' + (lineHtml(lt[n - 1], r[0], r[1]) || ' ') + '</div>');
  }
  return '<div class="frame" aria-label="Source around line ' + m.line + ', the change marked">' + out.join('') + '</div>';
}
function change(m){
  var src = linesOf(m.f)[m.line - 1] || '', after = spliced(m, src);
  if (after != null) return '<div class="diff" aria-label="The change"><div class="m">- ' + esc(src.trim()) + '</div><div class="p">+ ' + esc(after.trim()) + '</div></div>';
  if (m.eline === m.line && m.ecol > m.col){
    var lead = src.length - src.replace(/^\s+/, '').length, c0 = Math.max(m.col - 1, lead);
    return '<div class="diff" aria-label="The change"><div class="m wrapd">- ' + esc(src.slice(lead, c0)) + '<mark>' + esc(src.slice(c0, m.ecol - 1)) + '</mark>' + esc(src.slice(m.ecol - 1).replace(/\s+$/, '')) + '</div></div>' +
      '<p style="margin:-12px 0 0">' + words(cap(m.description)) + '.</p>';
  }
  return '<p style="margin:0">' + words(cap(m.description)) + '.</p>';
}
function why(m){
  var x = m.x, inputs = inputsSeen(x);
  if (m.status === 'Survived'){
    var ev = strs(x.evidence).filter(function(e){ return !(inputs && /^inputs seen/.test(e)); });
    return '<section class="sec"><h2>Why it got through</h2>' + ev.map(function(e){ return '<p>' + words(cap(e)) + '.</p>'; }).join('') +
      '<p>' + (m.coveredBy.length ? 'Every test that ran it still passed with the change in.' : 'No test ran it.') + '</p></section>';
  }
  if (m.status === 'Killed') return '<section class="sec"><h2>How it was caught</h2><p>' + (m.killedBy.length ? '<code>' + esc(testShort(m.killedBy[0])) + '</code> failed' : 'A test failed') + (m.reason ? ' with <code>' + esc(m.reason) + '</code>' : '') + '.</p></section>';
  if (m.status === 'NoCoverage') return '<section class="sec"><h2>Why it counts as a miss</h2><p>No test runs this line, so nothing could catch the change.</p></section>';
  if (m.status === 'Timeout') return '<section class="sec"><h2>Why it counts as caught</h2><p>' + words(cap(m.reason || 'the tests didn\'t finish in time')) + '. A change that hangs the tests is one they notice.</p></section>';
  if (m.status === 'Ignored') return '<section class="sec"><h2>Why it was skipped</h2><p>' + words(cap(m.reason || 'skipped')) + '.</p><p class="cap">Skipped mutants are out of the score.</p></section>';
  if (m.status === 'RuntimeError') return '<section class="sec"><h2>Why it\'s out of the score</h2><p>' + words(cap(m.reason || 'the tests crashed')) + '. A crash that may be undefined behaviour isn\'t a catch.</p></section>';
  return m.reason ? '<section class="sec"><h2>Why</h2><p>' + words(cap(m.reason)) + '.</p></section>' : '';
}
function suggestion(m){
  var x = m.x, st = obj(x.suggestedTest), fix = toWrite(m) ? fixOf(m) : '';
  if (str(st.code)){
    return '<section class="sec"><h2>Test to add</h2>' + (fix ? '<p class="help">' + words(cap(fix)) + '.</p>' : '') +
      '<div class="cmd col"><div class="head"><span class="cap">in <code>' + esc(st.testClass) + '</code></span>' + copyBtn(st.code, 'the test', 'btn') + '</div><pre class="code">' + esc(st.code) + '</pre></div>' +
      (str(st.call) ? '<p class="cap">Today <code>' + esc(st.call) + '</code> gives <code>' + esc(st.today) + '</code>; with the mutant, <code>' + esc(st.withMutant) + '</code>. Check today\'s answer is the one you mean: the test pins what the code does now.</p>' : '') + '</section>';
  }
  return fix ? '<section class="sec"><h2>What to test</h2><p class="help">' + words(cap(fix)) + '.</p></section>' : '';
}
function inputsSec(m){
  var inputs = obj(m.x.inputs), want = obj(obj(m.x.suggestedTest).arguments), names = Object.keys(inputs).filter(function(k){ return Array.isArray(inputs[k]); });
  if (!names.length) return '';
  return '<section class="sec"><h2>Inputs the tests used</h2><div class="inputs">' + names.map(function(p){
    var seen = inputs[p].map(val), w = p in want ? val(want[p]) : null;
    return '<div><code class="p">' + esc(p) + '</code>' + seen.map(function(v){ return '<code>' + esc(v) + '</code>'; }).join('') +
      (w != null && seen.indexOf(w) < 0 && m.status === 'Survived' ? '<span class="cap">none is</span><code class="want">' + esc(w) + '</code>' : '') + '</div>'; }).join('') + '</div></section>';
}
function reached(m){
  var tests = m.coveredBy.slice(0, 12), links = tests.map(testHref), killer = m.killedBy[0];
  return '<section class="sec"><h2>Tests that ran it' + (links.some(Boolean) ? '<span style="flex:1"></span>' + editorPick() : '') + '</h2>' + (tests.length ? '<ul class="tests">' + tests.map(function(t, i){
      var nm = esc(testName(t)).replace(/(::|#)/g, '$1<wbr>').replace(/_/g, '_<wbr>'), l = links[i];
      var mark = t === killer ? '<span style="color:var(--killInk)" aria-label="caught it">✓ </span>' : m.status === 'Survived' ? '<span style="color:var(--survInk)" aria-label="passed with the change">✗ </span>' : '';
      return '<li title="' + esc(t) + '">' + mark + (l ? '<a href="' + esc(l.href) + '">' + nm + '</a>' : nm) + '</li>'; }).join('') + '</ul>' +
      (m.coveredBy.length > 12 ? '<p class="cap">and ' + (m.coveredBy.length - 12) + ' more</p>' : '') +
      (m.status === 'Survived' ? '<p class="cap">✗ passed with the change in.</p>' : '')
    : '<p class="cap">None.</p>') + '</section>';
}
function commandsSec(m){
  var c = commands(m);
  if (!c || (m.status !== 'Survived' && m.status !== 'NoCoverage')) return '';
  return '<section class="sec"><h2>Done when this passes</h2><div class="cmd"><code>' + esc(c.verify) + '</code>' + copyBtn(c.verify, 'the verify command') + '</div>' +
    '<div class="cmds"><span class="k">Run it again</span><div class="cmd"><code>' + esc(c.run) + '</code>' + copyBtn(c.run, 'the run command') + '</div>' +
    '<span class="k">Or ignore it</span><div class="cmd"><code>' + esc(c.ignore) + '</code>' + copyBtn(c.ignore, 'the ignore command') + '</div></div></section>';
}
function about(m){
  var docs = operatorDocs(m), method = str(m.x.method);
  return '<section class="sec"><h2>About this change</h2><p><code>' + esc(m.mutator) + '</code>: ' + words(m.description) + (method ? ', in <code>' + esc(method) + '</code>' : '') + '.</p>' +
    (docs ? '<p><a href="' + esc(docs) + '" target="_blank" rel="noopener">How this operator works</a></p>' : '') + '</section>';
}
function alsoHere(m){
  var o = m.f.mutants.filter(function(x){ return x.line === m.line && x !== m; });
  return o.length ? '<section class="sec"><h2>Also on line ' + m.line + '</h2><div class="list">' + o.map(function(x){ return mrow(x); }).join('') + '</div></section>' : '';
}
function renderMutant(m){
  var nav = navSet(), k = nav.indexOf(m), scope;
  if (k < 0){ nav = ALL.filter(function(x){ return x.status === m.status; }); k = nav.indexOf(m); scope = ST[m.status].word; }
  else scope = state.filter.all ? 'mutant' : ORDER.filter(function(s){ return state.filter[s]; }).map(function(s){ return ST[s].word; }).join(' or ');
  var prev = nav[(k - 1 + nav.length) % nav.length], next = nav[(k + 1) % nav.length];
  var head = '<div class="dd-head ' + m.status + '">' + pill(m.status) + labs(m) +
    '<span class="mono" style="font-size:12px;color:var(--muted)">' + esc(m.mutator) + '</span><span class="grow"></span>' + (toWrite(m) ? copyPair(m.id, 1) : '') +
    (nav.length > 1 ? '<span class="nav"><a class="btn sq" href="' + mHref(prev) + '" aria-label="Previous ' + esc(scope) + '" aria-keyshortcuts="k">↑</a><span class="pos">' + (k + 1) + ' of ' + nav.length + '</span><a class="btn sq" href="' + mHref(next) + '" aria-label="Next ' + esc(scope) + '" aria-keyshortcuts="j">↓</a></span>' : '') + '</div>';
  app.innerHTML = '<div class="cols"><div class="main"><article class="card" aria-labelledby="dd-t">' + head + '<div class="dd-body">' +
    '<div><h1 class="dd-title" id="dd-t">' + words(headline(m)) + '</h1><div class="where"><a href="' + fileHref(m.file) + '">' + esc(m.file) + '</a>:' + m.line + ' · ' + esc(m.id) + '</div></div>' +
    frame(m) + change(m) + why(m) + suggestion(m) + inputsSec(m) + reached(m) + commandsSec(m) + about(m) + alsoHere(m) + '</div></article></div>' +
    '<aside>' + gauge(m.f.c, short(m.file)) + todo(m.f.mutants, m, 'file:' + m.file, short(m.file)) + keysCard() + '</aside></div>';
}

// ---------- keys, theme, opening a file ----------
document.addEventListener('keydown', function(e){
  if (!R || e.metaKey || e.ctrlKey || e.altKey) return;
  var tag = (e.target.tagName || '').toLowerCase();
  if (tag === 'input' || tag === 'textarea' || tag === 'select'){ if (e.key === 'Escape') e.target.blur(); return; }
  var r = route();
  if (e.key === '/'){ var q = document.getElementById('q') || document.getElementById('tq') || document.getElementById('mq'); if (q){ e.preventDefault(); q.focus(); } return; }
  if (e.key === 'Escape'){ if (r.view === 'mutant') go(fileRoute(r.m.file)); else if (r.view === 'file') go('#/'); return; }
  var dir = e.key === 'j' || e.key === 'n' || (r.view === 'mutant' && e.key === 'ArrowRight') ? 1 : e.key === 'k' || e.key === 'p' || (r.view === 'mutant' && e.key === 'ArrowLeft') ? -1 : 0;
  var nav = dir ? navSet() : [];
  if (!nav.length) return;
  e.preventDefault();
  var cur = r.view === 'mutant' ? nav.indexOf(r.m) : -1;
  if (r.view === 'file' && cur < 0){ // from a file, start at its first one
    var inFile = nav.filter(function(m){ return m.file === r.path; }); if (inFile.length){ go('#/m/' + encodeURIComponent((dir > 0 ? inFile[0] : inFile[inFile.length - 1]).id)); return; }
  }
  var nx = cur < 0 ? (dir > 0 ? 0 : nav.length - 1) : (cur + dir + nav.length) % nav.length;
  go('#/m/' + encodeURIComponent(nav[nx].id));
});
var lastView = '';
window.addEventListener('hashchange', function(){
  render();
  var v = location.hash;
  if (v.indexOf('#/m/') === 0 || lastView.indexOf('#/m/') !== 0) window.scrollTo(0, 0);
  lastView = v;
  app.focus({preventScroll: true});
});

function dark(){ return state.theme ? state.theme === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches; }
function applyTheme(){
  var root = document.documentElement, d = dark();
  if (state.theme) root.setAttribute('data-theme', state.theme); else root.removeAttribute('data-theme');
  var b = document.getElementById('theme');
  b.innerHTML = d ? '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>Light'
    : '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/></svg>Dark';
  b.setAttribute('aria-label', d ? 'Switch to light mode' : 'Switch to dark mode');
}
document.getElementById('theme').addEventListener('click', function(){
  state.theme = dark() ? 'light' : 'dark';
  try { localStorage.setItem('mutastic-theme', state.theme); } catch (e) { /* this page only */ }
  applyTheme();
});
try { matchMedia('(prefers-color-scheme: dark)').addEventListener('change', applyTheme); } catch (e) { /* an old browser: the theme at load */ }

function when(s){ var d = new Date(str(s)); return isNaN(d) ? '' : d.toISOString().slice(0, 16).replace('T', ' ') + ' UTC'; }
function meta(){
  var fw = obj(R.framework), name = str(fw.name) || 'mutastic', version = str(fw.version), tc = obj(RUN.toolchain), t = th();
  var edition = str(RUN.edition), started = when(RUN.startedAt);
  document.getElementById('ver').textContent = name + (version ? ' ' + version : '') + (edition ? ' · ' + edition + ' edition' : '') + (started ? ' · ' + started : '');
  document.getElementById('foot').textContent = 'Generated by ' + name + (version ? ' ' + version : '') +
    (str(tc.name) ? ' with ' + tc.name + (str(tc.version) ? ' ' + tc.version : '') : '') + (started ? ', started ' + started : '') +
    ' · mutation-testing report schema v' + (str(R.schemaVersion) || '2') + ' · thresholds ' + t.low + ' / ' + t.high;
  var root = str(R.projectRoot).replace(/[\\\/]+$/, '').replace(/\\/g, '/');
  document.title = (root ? short(root) + ' · ' : '') + 'mutastic report';
}
function reset(){ state.lineOpen = dict(); state.q = state.mq = state.tq = ''; state.mutator = ''; testLit = dict(); }
function openText(text){
  var parsed;
  try { parsed = JSON.parse(text); } catch (e) { parsed = null; }
  if (!parsed || typeof parsed !== 'object' || !parsed.files){ say('That file isn\'t a mutation.json this page can read.'); alert('That file isn\'t a mutation.json this page can read.'); return; }
  reset(); load(parsed); meta(); if (location.hash === '#/') render(); else location.hash = '#/';
}
document.getElementById('pick').addEventListener('change', function(e){ var f = e.target.files[0]; if (f) f.text().then(openText); });
document.addEventListener('dragover', function(e){ e.preventDefault(); document.body.classList.add('dragging'); });
document.addEventListener('dragleave', function(e){ if (!e.relatedTarget) document.body.classList.remove('dragging'); });
document.addEventListener('drop', function(e){ e.preventDefault(); document.body.classList.remove('dragging'); var f = e.dataTransfer.files[0]; if (f) f.text().then(openText); });

applyTheme();
try {
  var raw = document.getElementById('mutation-report');
  raw = raw ? raw.textContent.trim() : '';
  if (raw.charAt(0) === '{') load(JSON.parse(raw));
} catch (e) { R = null; if (window.console) console.error(e); }
if (R) meta();
render();
})();
