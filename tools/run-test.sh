#!/usr/bin/env bash
# テスト映像を受信アプリへ送る（Chrome の代わり）。使い方: tools/run-test.sh 192.168.3.229:8080 [幅 高さ fps 秒数]
cd "$(dirname "$0")/.."
export TMPDIR=$PWD/.tmp
exec nix-shell -p ffmpeg 'python3.withPackages (ps: [ps.websockets])' --run "python3 tools/test-sender.py $*"
