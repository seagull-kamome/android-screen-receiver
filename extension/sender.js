'use strict';
const $ = (id) => document.getElementById(id);

function log(message) {
  const el = $('log');
  el.textContent = new Date().toLocaleTimeString() + '  ' + message + '\n' + el.textContent.slice(0, 3000);
}

// このページが動くための前提を調べる
function findProblems() {
  const problems = [];
  if (!window.isSecureContext) {
    problems.push('このページは「安全なページ」として扱われていません。Chrome 拡張機能から開くか、'
      + 'Chrome で chrome://flags/#unsafely-treat-insecure-origin-as-secure を開き、「Insecure origins treated as secure」に '
      + location.origin + ' を入力して「有効」にし、Chrome を再起動してください。');
  }
  if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) {
    problems.push('画面共有（getDisplayMedia）が使えません。');
  }
  if (typeof VideoEncoder === 'undefined' || typeof MediaStreamTrackProcessor === 'undefined') {
    problems.push('WebCodecs（VideoEncoder / MediaStreamTrackProcessor）が使えません。Chrome 94 以降が必要です。');
  }
  return problems;
}

const problems = findProblems();
for (const p of problems) {
  const div = document.createElement('div');
  div.className = 'problem';
  div.textContent = p;
  $('problems').appendChild(div);
}
if (problems.length > 0) $('start').disabled = true;

// ---- 送信 ----
let ws = null;
let stream = null;
let encoder = null;
let reader = null;
let running = false;
let needKey = true;       // 次のフレームをキーフレームにする
let frameCount = 0;
let sentBytes = 0;
let sentFrames = 0;
let lastEncodeAt = 0;
let lastFrame = null;     // 最後に届いたフレーム。大きさの確認と、静止した画面での再送に使う
let timers = [];
let options = null;       // 開始時の選択 { maxW, maxH, fps, bitrate }
let curW = 0;             // エンコーダに設定している大きさ
let curH = 0;
let candidate = null;     // 大きさが変わったように見える候補 { w, h, since }
let resizing = false;     // エンコーダの設定を切り替えている最中

const RESIZE_SETTLE_MS = 300;  // 新しい大きさがこの時間続いたら、設定を切り替える（ドラッグ中に何度も切り替えない）
const CODECS = ['avc1.42E033', 'avc1.42E02A', 'avc1.4D4033', 'avc1.640033']; // ベースライン / メイン / ハイ、レベル 4.2〜5.1

// 接続先（host:port）。拡張機能のページには入力欄（#target）があり、その値を使う。
// Android が配信するページには入力欄がないので、そのページの配信元（location.host）を使う
function targetHost() {
  const input = document.getElementById('target');
  if (!input) return location.host;
  const host = input.value.trim().replace(/^[a-z]+:\/\//i, '').replace(/\/.*$/, '');
  if (!host) throw new Error('接続先（Android の IP アドレス）を入力してください');
  return /:\d+$/.test(host) ? host : host + ':8080';
}

let hello = null;         // 受信側（Android）が、接続の直後に知らせる { name, pin }
let helloWaiters = [];
let deviceLabel = '';     // 接続先の表示（端末名と PIN）

function openSocket() {
  return new Promise((resolve, reject) => {
    const s = new WebSocket('ws://' + targetHost() + '/ws');
    s.binaryType = 'arraybuffer';
    s.onopen = () => resolve(s);
    s.onerror = () => reject(new Error('Android に接続できません'));
    s.onclose = () => { if (running) { log('接続が切れました'); stop(); } };
    s.onmessage = (e) => {
      if (typeof e.data !== 'string') return;
      try {
        const m = JSON.parse(e.data);
        if (m.type === 'keyframe') needKey = true;
        else if (m.type === 'hello') { hello = m; for (const w of helloWaiters.splice(0)) w(m); }
      } catch (_) { /* 無視 */ }
    };
  });
}

// 受信側からの hello（端末名と PIN）を待つ。古い受信アプリは送らないので、時間切れなら null
function waitHello(ms = 2500) {
  if (hello) return Promise.resolve(hello);
  return new Promise((resolve) => {
    helloWaiters.push(resolve);
    setTimeout(() => resolve(hello), ms);
  });
}

// 確認の欄を出して、「共有する」か「やめる」かを待つ
function ask(text, warn) {
  return new Promise((resolve) => {
    const box = $('confirm');
    $('confirmText').textContent = text;
    box.className = warn ? 'confirm warn' : 'confirm';
    box.hidden = false;
    const done = (value) => {
      box.hidden = true;
      $('confirmYes').onclick = null;
      $('confirmNo').onclick = null;
      resolve(value);
    };
    $('confirmYes').onclick = () => done(true);
    $('confirmNo').onclick = () => done(false);
  });
}

// 接続した端末が、狙った端末かを確かめる。
// - QR や共有リンクから開いたとき（?pin=）に、その PIN と一致すれば、そのまま進む
// - 前に確認した端末（同じ接続先で、PIN が同じ）も、そのまま進む
// - それ以外（初めて、または PIN が違う）は、Android の画面の PIN と見比べて、確認してもらう
async function confirmDevice(host, info) {
  const expected = new URLSearchParams(location.search).get('pin');
  const storageKey = 'trusted:' + host;
  let trusted = null;
  try { trusted = localStorage.getItem(storageKey); } catch (_) { /* 使えなくてもよい */ }
  if (info && info.pin && (info.pin === expected || info.pin === trusted)) return true;

  let text;
  let warn = true;
  if (!info || !info.pin) {
    text = '接続した端末の PIN を取得できませんでした（古い受信アプリかもしれません）。\nこのまま共有しますか？';
  } else if (expected && info.pin !== expected) {
    text = '【注意】QR や共有リンクで受け取った PIN（' + expected + '）と、接続した端末の PIN（' + info.pin + '）が違います。\n'
      + '別の端末につながっている可能性があります。Android の画面の PIN を確かめてください。';
  } else if (trusted && info.pin !== trusted) {
    text = '【注意】この接続先（' + host + '）の PIN が、前回（' + trusted + '）と違います（今回: ' + info.pin + '）。\n'
      + '別の端末に変わった可能性があります。Android の画面の PIN を確かめてください。';
  } else {
    warn = false;
    text = '接続した端末: ' + (info.name || '（名前なし）') + '（' + host + '）\n端末の PIN: ' + info.pin + '\n'
      + 'Android の画面に表示されている PIN と同じなら、「この端末へ共有」を押してください。';
  }
  const ok = await ask(text, warn);
  if (ok && info && info.pin) {
    try { localStorage.setItem(storageKey, info.pin); } catch (_) { /* 使えなくてもよい */ }
  }
  return ok;
}

async function pickEncoderConfig(base) {
  for (const hw of ['prefer-hardware', 'no-preference']) {
    for (const codec of CODECS) {
      const cfg = { ...base, codec, hardwareAcceleration: hw };
      const result = await VideoEncoder.isConfigSupported(cfg);
      if (result.supported) return result.config;
    }
  }
  throw new Error('この環境では、H.264 で圧縮できません');
}

// フレームの大きさを、上限に収まる偶数の大きさにする（縦横比は保つ。H.264 は偶数の大きさが必要）
function fitSize(width, height) {
  const scale = Math.min(1, options.maxW / width, options.maxH / height);
  return {
    w: Math.max(2, Math.floor(width * scale / 2) * 2),
    h: Math.max(2, Math.floor(height * scale / 2) * 2),
  };
}

function encoderConfigFor(w, h) {
  return pickEncoderConfig({
    width: w, height: h, bitrate: options.bitrate, framerate: options.fps,
    latencyMode: 'realtime', avc: { format: 'annexb' },
  });
}

function sendConfig(w, h) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'config', width: w, height: h, fps: options.fps }));
  }
}

function sendChunk(chunk) {
  if (!ws || ws.readyState !== WebSocket.OPEN) return;
  const buf = new ArrayBuffer(9 + chunk.byteLength);
  const view = new DataView(buf);
  view.setUint8(0, chunk.type === 'key' ? 1 : 0);
  view.setBigInt64(1, BigInt(Math.round(chunk.timestamp)));
  chunk.copyTo(new Uint8Array(buf, 9));
  ws.send(buf);
  sentBytes += buf.byteLength;
  sentFrames++;
}

function encodeFrame(frame) {
  const key = needKey || (frameCount % (options.fps * 2) === 0); // 2 秒ごと、または要求されたとき
  encoder.encode(frame, { keyFrame: key });
  if (key) needKey = false;
  frameCount++;
  lastEncodeAt = performance.now();
}

// 共有している画面（ウィンドウ）の大きさが変わったとき、エンコーダを新しい大きさに設定し直す。
// 設定し直さないと、エンコーダが、新しい大きさのフレームを、設定の大きさへ引き伸ばしてしまう
// （例: 分割画面でウィンドウの幅が半分になったとき、横いっぱいに引き伸ばされる）
async function reconfigure(w, h) {
  resizing = true;
  try {
    await encoder.flush();                 // 古い大きさのフレームを、すべて送り出してから切り替える
    const config = await encoderConfigFor(w, h);
    encoder.configure(config);
    curW = w;
    curH = h;
    needKey = true;
    sendConfig(w, h);                      // 受信側へ、新しい大きさを知らせる（受信側はデコーダを作り直す）
    log('大きさが変わりました: ' + w + '×' + h);
  } finally {
    resizing = false;
    candidate = null;
  }
}

// フレームが、いまのエンコーダの大きさと合っているか。合わなければ、大きさが落ち着くのを待って、設定し直す
function sizeMatches(frame) {
  const t = fitSize(frame.displayWidth, frame.displayHeight);
  if (t.w === curW && t.h === curH) { candidate = null; return true; }
  if (resizing) return false;
  const now = performance.now();
  if (!candidate || candidate.w !== t.w || candidate.h !== t.h) {
    candidate = { w: t.w, h: t.h, since: now };
    return false;
  }
  if (now - candidate.since >= RESIZE_SETTLE_MS) reconfigure(t.w, t.h).catch(fatal);
  return false;
}

function fatal(e) {
  if (!running) return;
  log('エラー: ' + e.message);
  stop();
}

async function pump() {
  const minInterval = 1000 / options.fps * 0.8;
  let lastAccepted = 0;
  while (running) {
    const { value: frame, done } = await reader.read();
    if (done || !frame) break;
    try {
      if (lastFrame) lastFrame.close();
      lastFrame = frame.clone();
      // 大きさが変わった直後は、設定が切り替わるまで送らない（引き伸ばされた映像を送らない）
      if (!sizeMatches(frame)) continue;
      const now = performance.now();
      const tooFast = now - lastAccepted < minInterval;
      const congested = ws.bufferedAmount > 1500000 || encoder.encodeQueueSize > 4;
      if (tooFast || congested) continue; // 古い映像を溜めない（遅延を増やさない）
      lastAccepted = now;
      encodeFrame(frame);
    } finally {
      frame.close();
    }
  }
}

async function start() {
  const [maxW, maxH] = $('res').value.split('x').map(Number);
  options = { maxW, maxH, fps: Number($('fps').value), bitrate: Number($('bps').value) * 1000000 };
  $('start').disabled = true;
  try {
    ws = await openSocket();
    const info = await waitHello();
    if (!(await confirmDevice(targetHost(), info))) {
      log('共有を取りやめました（接続先の端末を確認できませんでした）');
      stop();
      return;
    }
    deviceLabel = info && info.pin ? (info.name || '') + ' PIN ' + info.pin : '';
    if (deviceLabel) log('接続先: ' + deviceLabel);
    stream = await navigator.mediaDevices.getDisplayMedia({
      video: { width: { ideal: maxW }, height: { ideal: maxH }, frameRate: { ideal: options.fps } },
      audio: false,
    });
    const track = stream.getVideoTracks()[0];
    track.addEventListener('ended', () => { log('共有が停止されました'); stop(); });
    const settings = track.getSettings();
    const first = fitSize(settings.width || maxW, settings.height || maxH);

    encoder = new VideoEncoder({
      output: sendChunk,
      error: (e) => { log('エンコーダのエラー: ' + e.message); stop(); },
    });
    const config = await encoderConfigFor(first.w, first.h);
    encoder.configure(config);
    curW = first.w;
    curH = first.h;
    log('共有を開始: ' + first.w + '×' + first.h + ' @' + options.fps + 'fps, ' + (options.bitrate / 1000000) + 'Mbps, ' + config.codec
      + (config.hardwareAcceleration ? ' (' + config.hardwareAcceleration + ')' : ''));

    running = true;
    needKey = true;
    frameCount = 0;
    sentBytes = 0;
    sentFrames = 0;
    candidate = null;
    resizing = false;
    sendConfig(first.w, first.h);
    $('stop').disabled = false;

    reader = new MediaStreamTrackProcessor({ track }).readable.getReader();
    pump().catch(fatal);

    timers.push(setInterval(() => { if (ws && ws.readyState === WebSocket.OPEN) ws.send('ping'); }, 2000));
    // 画面が静止していても、大きさの変化の確定や、キーフレームの要求に応えられるよう、最後のフレームを見直す
    timers.push(setInterval(() => {
      if (!running || !lastFrame || !encoder || encoder.state !== 'configured') return;
      if (!sizeMatches(lastFrame)) return;
      if (performance.now() - lastEncodeAt < 500) return;
      if (ws.bufferedAmount > 1500000 || encoder.encodeQueueSize > 4) return;
      const copy = lastFrame.clone();
      try { encodeFrame(copy); } finally { copy.close(); }
    }, 250));
    let lastBytes = 0, lastFrames = 0;
    timers.push(setInterval(() => {
      $('stat').textContent = '送信中 → ' + (deviceLabel ? deviceLabel + '  ' : '') + curW + '×' + curH + ': ' + (sentFrames - lastFrames) + ' fps, '
        + (((sentBytes - lastBytes) * 8) / 1000000).toFixed(1) + ' Mbps';
      lastBytes = sentBytes; lastFrames = sentFrames;
    }, 1000));
  } catch (e) {
    log('開始できませんでした: ' + e.message);
    stop();
  }
}

function stop() {
  const wasRunning = running;
  running = false;
  for (const t of timers) clearInterval(t);
  timers = [];
  try { if (reader) reader.cancel(); } catch (_) { /* 無視 */ }
  reader = null;
  if (stream) for (const t of stream.getTracks()) t.stop();
  stream = null;
  try { if (encoder && encoder.state !== 'closed') encoder.close(); } catch (_) { /* 無視 */ }
  encoder = null;
  if (lastFrame) { lastFrame.close(); lastFrame = null; }
  try { if (ws) ws.close(); } catch (_) { /* 無視 */ }
  ws = null;
  curW = curH = 0;
  candidate = null;
  resizing = false;
  hello = null;
  helloWaiters = [];
  deviceLabel = '';
  $('confirm').hidden = true;
  $('start').disabled = problems.length > 0;
  $('stop').disabled = true;
  $('stat').textContent = wasRunning ? '停止しました' : '待機中';
}

$('start').addEventListener('click', start);
$('stop').addEventListener('click', stop);
