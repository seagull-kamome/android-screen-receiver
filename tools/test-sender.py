#!/usr/bin/env python3
"""テスト用の送信側（Chrome の代わり）。

ffmpeg で作ったテスト映像（H.264）を、受信アプリへ WebSocket で送る。
送信ページ（sender.html）と同じ取り決めで送るので、受信アプリの表示と復号の確認に使える。

使い方（ffmpeg と websockets が必要）:
  nix-shell -p ffmpeg 'python3.withPackages (ps: [ps.websockets])' --run \\
    'python3 tools/test-sender.py 192.168.3.229:8080 [幅 高さ fps 秒数]'
"""
import asyncio
import json
import struct
import subprocess
import sys

import websockets

AUD = b"\x00\x00\x00\x01\x09"   # アクセスユニット区切り（1 フレームの先頭）
IDR = b"\x00\x00\x01\x65"       # IDR スライス（キーフレーム）


async def listen(ws):
    """受信側からの要求（キーフレーム）を表示する。このテストでは 1 秒ごとにキーフレームを送るので、応じなくてよい"""
    async for message in ws:
        print("受信側から:", message)


async def ping(ws):
    while True:
        await asyncio.sleep(2)
        await ws.send("ping")


async def main(host, width, height, fps, seconds):
    ffmpeg = subprocess.Popen(
        ["ffmpeg", "-loglevel", "error", "-re",
         "-f", "lavfi", "-i", f"testsrc2=size={width}x{height}:rate={fps}",
         "-t", str(seconds),
         "-c:v", "libx264", "-profile:v", "baseline", "-preset", "ultrafast", "-tune", "zerolatency",
         "-bf", "0", "-g", str(fps), "-pix_fmt", "yuv420p",
         "-x264-params", "aud=1:repeat-headers=1",
         "-f", "h264", "-"],
        stdout=subprocess.PIPE)
    loop = asyncio.get_running_loop()
    async with websockets.connect(f"ws://{host}/ws", max_size=None) as ws:
        print(f"接続しました: {width}x{height} @{fps}fps, {seconds} 秒")
        await ws.send(json.dumps({"type": "config", "width": width, "height": height, "fps": fps}))
        tasks = [asyncio.create_task(listen(ws)), asyncio.create_task(ping(ws))]
        buffer = b""
        frames = 0
        keys = 0
        sent_bytes = 0
        while True:
            chunk = await loop.run_in_executor(None, ffmpeg.stdout.read, 65536)
            if not chunk:
                break
            buffer += chunk
            pieces = buffer.split(AUD)
            buffer = AUD + pieces.pop()          # 最後は、まだ途中かもしれない
            for piece in pieces[1:] if pieces and not pieces[0] else pieces:
                frame = AUD + piece
                key = IDR in frame
                header = struct.pack(">BQ", 1 if key else 0, frames * 1_000_000 // fps)
                await ws.send(header + frame)
                frames += 1
                keys += key
                sent_bytes += len(frame)
        for task in tasks:
            task.cancel()
        print(f"送信完了: {frames} フレーム（キーフレーム {keys}）, {sent_bytes / 1e6:.2f} MB")


if __name__ == "__main__":
    host = sys.argv[1]
    width, height, fps, seconds = (int(v) for v in (sys.argv[2:6] if len(sys.argv) >= 6 else (1280, 720, 30, 20)))
    asyncio.run(main(host, width, height, fps, seconds))
