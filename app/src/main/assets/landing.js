'use strict';
// Android が配信する入口のページ用（拡張機能のページでは使わない）。
// QR を読み取って開いたときに、端末名と PIN を見せ、拡張機能が入っていれば、接続先と PIN を渡して開くリンクを出す。
(async () => {
  const $ = (id) => document.getElementById(id);
  let info;
  try {
    info = await (await fetch('/info.json', { cache: 'no-store' })).json();
  } catch (_) {
    return; // 取得できなければ、入口の案内は出さない（送信ページとしては動く）
  }
  $('landing').hidden = false;
  $('devName').textContent = info.name || '(名前なし)';
  $('devPin').textContent = info.pin;

  // 拡張機能のページを、接続先と PIN つきで開くリンク
  const link = 'chrome-extension://' + info.extensionId + '/sender.html'
    + '?target=' + encodeURIComponent(location.host) + '&pin=' + encodeURIComponent(info.pin);
  $('extOpen').href = link;
  $('extLink').value = link;
  $('extLink').addEventListener('focus', () => $('extLink').select());

  // 拡張機能が入っているか。拡張機能が公開しているアイコンを、読み込めるかで調べる
  const probe = new Image();
  probe.onload = () => { $('extInstalled').hidden = false; };
  probe.onerror = () => { $('extMissing').hidden = false; };
  probe.src = 'chrome-extension://' + info.extensionId + '/icons/icon16.png';
})();
