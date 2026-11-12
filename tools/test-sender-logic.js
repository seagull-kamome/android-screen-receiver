// 送信スクリプト（extension/sender.js）のロジックを、模擬した WebCodecs / WebSocket / 画面で検査する。Chrome は要らない。
//
// 使い方: node tools/test-sender-logic.js [extension/sender.js] [--target 接続先の入力値] [--scenario 名前 | --all]
//   --target を付けると、拡張機能のページ（入力欄 #target がある）として動かし、接続先の解釈も検査する。
//   付けなければ、Android が配信するページ（入力欄なし。location.host へ接続）として動かす。
//   --all は、下の場面をすべて、順に検査する。--scenario は 1 つだけ（既定は trusted）。
//
// 検査すること:
//   (a) 接続した端末の確認（PIN）: 初めて / 前に確認した端末 / PIN が違う / QR のリンクの PIN / 古い受信アプリ
//   (b) 共有している画面の大きさが途中で変わったとき（例: 分割画面でウィンドウの幅が半分になる）、
//       エンコーダが設定し直され、引き伸ばされたフレームが送られず、受信側へ新しい大きさが先に通知される
const fs = require('fs');
const vm = require('vm');
const { spawnSync } = require('child_process');

const args = process.argv.slice(2);
const flag = (name) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : undefined; };
const jsPath = args.find((a) => a.endsWith('.js')) || 'extension/sender.js';
const targetInput = flag('--target');
const scenarioName = flag('--scenario') || 'trusted';

// 場面: env = 模擬する状況、confirm = 確認の欄が出たとき、どちらを押すか、expect = 期待
const SCENARIOS = {
  first:             { env: { pin: '1234' }, confirm: 'yes', expect: { shown: true, warn: false, streams: true, trusted: '1234' } },
  trusted:           { env: { pin: '1234', trusted: '1234' }, expect: { shown: false, streams: true } },
  mismatch_declined: { env: { pin: '1234', trusted: '1111' }, confirm: 'no', expect: { shown: true, warn: true, text: '1111', streams: false, trusted: '1111' } },
  expected_ok:       { env: { pin: '1234', expected: '1234' }, expect: { shown: false, streams: true } },
  expected_wrong:    { env: { pin: '1234', expected: '9999' }, confirm: 'no', expect: { shown: true, warn: true, text: '9999', streams: false } },
  no_hello:          { env: { pin: null }, confirm: 'yes', expect: { shown: true, warn: true, text: '取得できません', streams: true } },
};

if (args.includes('--all')) {
  let failed = 0;
  for (const name of Object.keys(SCENARIOS)) {
    const extra = targetInput === undefined ? [] : ['--target', targetInput];
    const r = spawnSync(process.execPath, [__filename, jsPath, '--scenario', name, ...extra], { encoding: 'utf8' });
    const last = r.stdout.trim().split('\n').filter((l) => l).pop();
    console.log(`[${name}] ${last}`);
    if (r.status !== 0) { failed++; console.log(r.stdout.split('\n').filter((l) => l.startsWith('- ')).join('\n')); }
  }
  console.log(failed ? `\n不合格: ${failed} 件` : '\n合格: すべての場面');
  process.exit(failed ? 1 : 0);
}

const scenario = SCENARIOS[scenarioName];
if (!scenario) { console.error('不明な場面:', scenarioName); process.exit(2); }
const js = fs.readFileSync(jsPath, 'utf8');

const log = [];                 // 起きたことを、順番に記録する
const rec = (...a) => log.push(a.join(' '));

// --- 画面（DOM）の模擬 ---
const els = {};
const $el = (id) => (els[id] ||= { id, textContent: '', disabled: false, value: '', className: '', hidden: false,
  addEventListener(ev, fn) { this['on' + ev] = fn; }, appendChild() {} });
globalThis.document = {
  getElementById: (id) => (id === 'target' && targetInput === undefined ? null : $el(id)),
  createElement: () => ({}),
};
globalThis.window = { isSecureContext: true };
globalThis.location = { host: 'test:8080', origin: 'http://test:8080',
  search: scenario.env.expected ? '?pin=' + scenario.env.expected : '' };
$el('res').value = '1920x1080'; $el('fps').value = '30'; $el('bps').value = '8';
if (targetInput !== undefined) $el('target').value = targetInput;
$el('confirm').hidden = true;

// --- 記憶（localStorage）の模擬 ---
const hostKey = 'trusted:' + (targetInput === undefined ? 'test:8080'
  : (/:\d+/.test(targetInput) ? targetInput.replace(/^[a-z]+:\/\//i, '').replace(/\/.*$/, '') : targetInput + ':8080'));
const store = {};
if (scenario.env.trusted) store[hostKey] = scenario.env.trusted;
globalThis.localStorage = { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = v; } };

// --- 確認の欄: 出たら、場面に決められた方を、押す ---
let confirmShown = false;
let confirmWarn = false;
let confirmText = '';
setInterval(() => {
  const box = els.confirm;
  if (box.hidden === false && !confirmShown) {
    confirmShown = true;
    confirmWarn = box.className.includes('warn');
    confirmText = els.confirmText.textContent;
    rec('confirm-shown', confirmWarn ? 'warn' : 'normal');
    setTimeout(() => (scenario.confirm === 'no' ? els.confirmNo.onclick() : els.confirmYes.onclick()), 20);
  }
}, 5);

// --- WebSocket の模擬（接続の直後に、hello を送る） ---
class FakeWS {
  constructor(url) {
    rec('ws-open', url);
    this.readyState = 1; this.bufferedAmount = 0;
    setTimeout(() => this.onopen && this.onopen(), 0);
    if (scenario.env.pin) {
      setTimeout(() => this.onmessage && this.onmessage({ data: JSON.stringify({ type: 'hello', name: 'TestDevice', pin: scenario.env.pin }) }), 5);
    }
    FakeWS.last = this;
  }
  send(data) {
    if (typeof data === 'string') { if (data !== 'ping') rec('ws-text', data); }
    else rec('ws-frame', 'key=' + new DataView(data).getUint8(0), 'bytes=' + data.byteLength, 'enc=' + lastEncodedSize.join('x'));
  }
  close() { this.readyState = 3; rec('ws-close'); }
}
FakeWS.OPEN = 1;
globalThis.WebSocket = FakeWS;

// --- VideoEncoder の模擬（設定と違う大きさのフレームは、引き伸ばされる＝この検査では DISTORTED と記録する） ---
let cfgSize = [0, 0];
let lastEncodedSize = [0, 0];
class FakeEncoder {
  constructor(init) { this.output = init.output; this.state = 'unconfigured'; this.encodeQueueSize = 0; }
  configure(cfg) { this.state = 'configured'; cfgSize = [cfg.width, cfg.height]; rec('encoder.configure', cfg.width + 'x' + cfg.height); }
  encode(frame, opts) {
    if (frame.displayWidth !== cfgSize[0] || frame.displayHeight !== cfgSize[1]) {
      rec('DISTORTED', frame.displayWidth + 'x' + frame.displayHeight, 'into', cfgSize.join('x'));
    }
    lastEncodedSize = cfgSize;
    this.output({ type: opts && opts.keyFrame ? 'key' : 'delta', timestamp: 0, byteLength: 100, copyTo() {} });
  }
  flush() { rec('encoder.flush'); return new Promise((r) => setTimeout(r, 20)); }
  close() { this.state = 'closed'; }
  static async isConfigSupported(cfg) { return { supported: true, config: cfg }; }
}
globalThis.VideoEncoder = FakeEncoder;

// --- 画面共有の模擬（1920x1080 を 20 フレーム、そのあと 960x1080 を 40 フレーム。30fps） ---
const script = [...Array(20).fill([1920, 1080]), ...Array(40).fill([960, 1080])];
const mkFrame = ([w, h]) => ({ displayWidth: w, displayHeight: h, timestamp: 0, clone() { return mkFrame([w, h]); }, close() {} });
globalThis.MediaStreamTrackProcessor = class {
  constructor() {
    let i = 0;
    this.readable = { getReader: () => ({
      read: () => new Promise((resolve) => setTimeout(() => {
        resolve(i < script.length ? { value: mkFrame(script[i++]), done: false } : { done: true });
      }, 33)),
      cancel() {},
    }) };
  }
};
Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { mediaDevices: {
  getDisplayMedia: async () => { rec('getDisplayMedia'); return {
    getVideoTracks: () => [{ getSettings: () => ({ width: 1920, height: 1080 }), addEventListener() {}, stop() {} }],
    getTracks: () => [{ stop() {} }],
  }; } } } });

vm.runInThisContext(js);
(async () => {
  els.start.onclick();
  await new Promise((r) => setTimeout(r, scenario.env.pin ? 2800 : 5200));   // 古い受信アプリの場合は、hello の待ち（2.5 秒）が加わる
  vm.runInThisContext('stop()');

  const frames = log.filter((l) => l.startsWith('ws-frame'));
  const bySize = {};
  for (const f of frames) { const m = f.match(/enc=(\S+)/)[1]; bySize[m] = (bySize[m] || 0) + 1; }
  const streams = log.includes('getDisplayMedia');
  const e = scenario.expect;

  // --- 検査 ---
  const fail = [];
  const expectedHost = hostKey.slice('trusted:'.length);
  const opened = log.find((l) => l.startsWith('ws-open'));
  if (opened !== 'ws-open ws://' + expectedHost + '/ws') fail.push('接続先が違います: ' + opened + '（期待: ws://' + expectedHost + '/ws）');

  if (confirmShown !== e.shown) fail.push('確認の欄が、期待と違います（出た: ' + confirmShown + '、期待: ' + e.shown + '）');
  if (e.shown && confirmWarn !== e.warn) fail.push('確認の欄の警告が、期待と違います（警告: ' + confirmWarn + '、期待: ' + e.warn + '）');
  if (e.text && !confirmText.includes(e.text)) fail.push('確認の文に「' + e.text + '」が含まれません: ' + confirmText.replace(/\n/g, ' / '));
  if (streams !== e.streams) fail.push('共有を始めたかが、期待と違います（始めた: ' + streams + '、期待: ' + e.streams + '）');
  if (e.trusted !== undefined && store[hostKey] !== e.trusted) fail.push('記憶された PIN が、期待と違います: ' + store[hostKey] + '（期待: ' + e.trusted + '）');
  if (!e.streams && !log.includes('ws-close')) fail.push('共有をやめたのに、接続が閉じられていません');

  if (e.streams) {      // 大きさの変化への追従
    const configs = log.filter((l) => l.startsWith('ws-text') && l.includes('"type":"config"'));
    if (configs.length !== 2) fail.push('設定メッセージが 2 回ではありません: ' + configs.length);
    else {
      if (!configs[0].includes('"width":1920') || !configs[0].includes('"height":1080')) fail.push('最初の設定が違います: ' + configs[0]);
      if (!configs[1].includes('"width":960') || !configs[1].includes('"height":1080')) fail.push('2 回目の設定が違います: ' + configs[1]);
    }
    if (log.some((l) => l.startsWith('DISTORTED'))) fail.push('引き伸ばされたフレームが送られました');
    const iFlush = log.indexOf('encoder.flush');
    const iCfg2 = log.findIndex((l) => l === 'encoder.configure 960x1080');
    if (iFlush < 0 || iCfg2 < 0 || iFlush > iCfg2) fail.push('flush が、設定し直す前に呼ばれていません');
    const iMsg2 = log.findIndex((l) => l.startsWith('ws-text') && l.includes('"width":960'));
    const firstNew = log.findIndex((l, i) => i > iCfg2 && l.startsWith('ws-frame') && l.includes('enc=960x1080'));
    if (firstNew >= 0 && iMsg2 > firstNew) fail.push('新しい大きさの映像が、設定メッセージより先に送られました');
    if (!bySize['960x1080']) fail.push('新しい大きさのフレームが、1 つも送られていません');
  }

  console.log(fail.length ? '不合格:\n' + fail.map((f) => '- ' + f).join('\n')
    : '合格' + (e.streams ? '（大きさの変化にも追従: ' + JSON.stringify(bySize) + '）' : '（共有を始めなかった）'));
  process.exit(fail.length ? 1 : 0);
})();
