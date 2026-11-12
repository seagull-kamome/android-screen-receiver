#!/usr/bin/env bash
# deploy.sh から nix-shell の中で呼ばれる。単独では実行しない
set -euo pipefail
PKG=jp.kamome.screenreceiver

gradle --no-daemon -q assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -n $PKG/.MainActivity
adb shell sleep 3
if adb shell dumpsys window | grep mFocusedApp | grep -q $PKG; then
  echo 'OK: アプリが前面にあります'
else
  echo 'ERROR: アプリが前面にありません（端末がロック中かもしれません）'; exit 1
fi
