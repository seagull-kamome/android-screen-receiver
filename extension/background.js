// 拡張機能のアイコンを押したら、送信ページを新しいタブで開く。
// ポップアップではなくタブにするのは、画面共有の選択画面が出るとポップアップが閉じてしまうため。
chrome.action.onClicked.addListener(() => {
  chrome.tabs.create({ url: chrome.runtime.getURL('sender.html') });
});
