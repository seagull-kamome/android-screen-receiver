// 送信スクリプト（extension/sender.js）のロジックを、模擬した WebCodecs / WebSocket で検査する。Chrome は要らない。
//
// 使い方: node tools/test-sender-logic.js [extension/sender.js] [接続先の入力値]
//   接続先の入力値を付けると、拡張機能のページ（入力欄 #target がある）として動かし、接続先の解釈も検査する。
//   付けなければ、Android が配信するページ（入力欄なし。location.host へ接続）として動かす。
//
// 検査すること: 共有している画面の大きさが途中で変わったとき（例: 分割画面でウィンドウの幅が半分になる）、
//   (1) エンコーダが設定し直される (2) 切り替える前に、古い大きさのフレームが送り出される
//   (3) 引き伸ばされたフレームが送られない (4) 受信側へ、新しい大きさが先に通知される
const fs = require('fs');
const vm = require('vm');
const js = fs.readFileSync(process.argv[2] || 'extension/sender.js', 'utf8');
const targetInput = process.argv[3];     // 拡張機能のページを模擬するときの、接続先の入力値

const log = [];                 // 起きたことを、順番に記録する
const rec = (...a) => log.push(a.join(' '));

// --- DOM の模擬 ---
const els = {};
const $el = (id) => (els[id] ||= { id, textContent: '', disabled: false, value: '', className: '',
  addEventListener(ev, fn) { this['on' + ev] = fn; }, appendChild() {} });
globalThis.document = { getElementById: $el, createElement: () => ({}) };
globalThis.window = { isSecureContext: true };
globalThis.location = { host: 'test:8080', origin: 'http://test:8080' };
els.res = { value: '1920x1080' }; els.fps = { value: '30' }; els.bps = { value: '8' };
if (targetInput !== undefined) els.target = { value: targetInput };   // 入力欄があれば、拡張機能のページ
// getElementById は、模擬した要素がなければ空の要素を作る。入力欄がない場合は、null を返す
globalThis.document.getElementById = (id) => (id === 'target' ? (els.target || null) : $el(id));
els.log = { textContent: '' }; els.problems = { appendChild() {} };

// --- WebSocket の模擬 ---
class FakeWS {
  constructor(url) { rec('ws-open', url); this.readyState = 1; this.bufferedAmount = 0; setTimeout(() => this.onopen && this.onopen(), 0); FakeWS.last = this; }
  send(data) {
    if (typeof data === 'string') { if (data !== 'ping') rec('ws-text', data); }
    else rec('ws-frame', 'key=' + new DataView(data).getUint8(0), 'bytes=' + data.byteLength, 'enc=' + lastEncodedSize.join('x'));
  }
  close() { this.readyState = 3; }
}
FakeWS.OPEN = 1;
globalThis.WebSocket = FakeWS;

// --- VideoEncoder の模擬（設定と違う大きさのフレームは、引き伸ばされる＝この検査では "distorted" と記録する） ---
let cfgSize = [0, 0];
let lastEncodedSize = [0, 0];
class FakeEncoder {
  constructor(init) { this.output = init.output; this.state = 'unconfigured'; this.encodeQueueSize = 0; }
  configure(cfg) { this.state = 'configured'; cfgSize = [cfg.width, cfg.height]; rec('encoder.configure', cfg.width + 'x' + cfg.height); }
  encode(frame, opts) {
    const distorted = frame.displayWidth !== cfgSize[0] || frame.displayHeight !== cfgSize[1];
    if (distorted) rec('DISTORTED', frame.displayWidth + 'x' + frame.displayHeight, 'into', cfgSize.join('x'));
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
const mkFrame = ([w, h]) => ({ displayWidth: w, displayHeight: h, timestamp: 0,
  clone() { return mkFrame([w, h]); }, close() {} });
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
  getDisplayMedia: async () => ({
    getVideoTracks: () => [{ getSettings: () => ({ width: 1920, height: 1080 }), addEventListener() {} , stop() {} }],
    getTracks: () => [{ stop() {} }],
  }) } } });

vm.runInThisContext(js);
(async () => {
  rec('--- start');
  els.start.onclick();
  await new Promise((r) => setTimeout(r, 2800));
  vm.runInThisContext('stop()');

  const out = log.filter((l) => !l.startsWith('ws-frame'));
  const frames = log.filter((l) => l.startsWith('ws-frame'));
  console.log(out.join('\n'));
  const bySize = {};
  for (const f of frames) { const m = f.match(/enc=(\S+)/)[1]; bySize[m] = (bySize[m] || 0) + 1; }
  console.log('送信したフレーム数（エンコーダの大きさ別）:', JSON.stringify(bySize));

  // --- 検査 ---
  const fail = [];
  const expectedUrl = targetInput === undefined ? 'ws://test:8080/ws' : 'ws://' + (/^[a-z]+:\/\//i.test(targetInput) || /:\d+/.test(targetInput) ? targetInput.replace(/^[a-z]+:\/\//i, '').replace(/\/.*$/, '') : targetInput + ':8080') + '/ws';
  const opened = log.find((l) => l.startsWith('ws-open'));
  if (!opened || opened !== 'ws-open ' + expectedUrl) fail.push('接続先が違います: ' + opened + '（期待: ws-open ' + expectedUrl + '）');
  const configs = log.filter((l) => l.startsWith('ws-text'));
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
  console.log(fail.length ? '\n不合格:\n- ' + fail.join('\n- ') : '\n合格: 大きさの変化に追従し、引き伸ばしは送られませんでした');
  process.exit(fail.length ? 1 : 0);
})();
