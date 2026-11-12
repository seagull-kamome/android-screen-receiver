// 拡張機能の送信ページ: 接続先と選択を記憶する（chrome.storage.local）。
// 送信そのものは sender.js が行う（Android が配信するページと共通）。
(async () => {
  const keys = ['target', 'res', 'fps', 'bps'];
  const get = (id) => document.getElementById(id);

  const saved = await chrome.storage.local.get([...keys, 'recent']);
  for (const k of keys) {
    if (saved[k] != null && get(k)) get(k).value = saved[k];
  }
  for (const k of keys) {
    get(k).addEventListener('change', () => chrome.storage.local.set({ [k]: get(k).value }));
  }

  // 最近使った接続先を、入力欄の候補に出す
  const renderRecent = (recent) => {
    const list = get('recent');
    list.replaceChildren(...recent.map((value) => Object.assign(document.createElement('option'), { value })));
  };
  renderRecent(saved.recent || []);

  // 共有を始めたとき、その接続先を覚える（新しいものを先頭にして、5 件まで）
  get('start').addEventListener('click', async () => {
    const value = get('target').value.trim();
    if (!value) return;
    const { recent = [] } = await chrome.storage.local.get('recent');
    const next = [value, ...recent.filter((v) => v !== value)].slice(0, 5);
    await chrome.storage.local.set({ recent: next, target: value });
    renderRecent(next);
  });
})();
