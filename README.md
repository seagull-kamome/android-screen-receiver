# android-screen-receiver

ChromeOS（や Windows）の Chrome の画面共有で、画面を Android 端末へ無線で映すための受信アプリです。使わなくなった Android 端末を、PC の画面の複製を映すモニターとして使えます。

Chrome 標準の「キャスト」（Google Cast）は、Google の認証を受けた機器でしか受信できないため使えません。代わりに、このアプリが配信するページを Chrome で開き、画面共有の機能（`getDisplayMedia`）で取得した映像を、Wi-Fi で Android へ送ります。

## 仕組み

1. Android アプリが、小さなサーバー（HTTP と WebSocket、ポート 8080）を起動します。
2. Chrome で、Android の URL（`http://<Android の IP>:8080`）を開くと、送信ページが表示されます。
3. 「共有を開始」を押すと、Chrome が画面を取得して、WebCodecs で H.264 に圧縮し、WebSocket で Android へ送ります。
4. Android が `MediaCodec` で復号して、全画面に表示します。

WebRTC は使わず、同じ LAN 内を前提にして、WebSocket で、映像を送ります。遅延を増やさないよう、送信側も受信側も、詰まったら、古いフレームを捨てて、キーフレームから再開します。

## 使い方

### 初回だけ: Chrome の設定
画面共有と WebCodecs は、「安全なページ」でしか動きません。このアプリのページは、HTTP で配信されるので、Chrome に、このページを安全なものとして扱わせる設定が必要です。

1. Chrome で `chrome://flags/#unsafely-treat-insecure-origin-as-secure` を開きます。
2. 「Insecure origins treated as secure」の欄に、アプリの待機画面に表示された URL（例: `http://192.168.3.229:8080`）を入力して、「有効（Enabled）」にします。
3. Chrome を再起動します。

Android の IP アドレスが変わったら、この欄の値も、更新してください（ルーターで、Android に固定の IP を割り当てると、手間が省けます）。

### 映すとき
1. Android でアプリを開きます（待機画面に URL が表示されます）。
2. 同じ Wi-Fi につないだ Chrome で、その URL を開きます。
3. 解像度、フレームレート、ビットレートを選んで、「共有を開始」を押し、共有する画面やウィンドウを選びます。
4. Android の全画面に、映像が表示されます。画面をタップすると、案内の表示を切り替えられます。

## ビルド

Nix が使えれば、JDK 17、Gradle、Android SDK 34 を `shell.nix` が用意します。

```sh
nix-shell --run "gradle assembleDebug"
# 出力: app/build/outputs/apk/debug/app-debug.apk
```

端末を USB で接続していれば、`./deploy.sh` で、ビルドからインストール、起動まで行えます。

## テスト（Chrome なしで受信側を確認する）

`tools/test-sender.py` は、ffmpeg で作ったテスト映像（H.264）を、送信ページと同じ取り決めで、受信アプリへ送ります。

```sh
tools/run-test.sh 192.168.3.229:8080 1920 1080 30 20   # 宛先、幅、高さ、fps、秒数
```

## 通信の取り決め

- 送信側 → 受信側（文字）: `{"type":"config","width":W,"height":H,"fps":F}`（映像を始める前に 1 回）、`ping`（接続維持）
- 送信側 → 受信側（バイナリ）: `[フラグ 1 バイト（bit0 = キーフレーム）][時刻 8 バイト（ビッグエンディアン、マイクロ秒）][H.264 Annex-B]`
- 受信側 → 送信側（文字）: `{"type":"keyframe"}`（キーフレームの要求）

## 確認の状況と制限

- 受信側（Android）は、テスト映像で、動作を確認しました（UMIDIGI RP03、Android 12。1280×720 と 1920×1080、30fps）。
- **実際の Chrome（ChromeOS を含む）の送信ページは、まだ確認していません。** JavaScript の構文の検査までです。
- 遅延は、測っていません。
- **認証も暗号化もありません**: 同じネットワークにいる誰でも、送信ページを開いたり、映像を送ったりできます。信頼できる Wi-Fi でだけ使ってください。
- 音声は送りません。Android 側の画面のタッチを、PC へ返す機能もありません。
- 解像度は、端末の H.264 デコーダが対応する範囲に限られます。
- 拡張画面（空いた領域を、ウィンドウの置き場にする）ではなく、画面や、ウィンドウの共有（複製）です。

## 利用しているライブラリ

- [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd)（HTTP と WebSocket のサーバー、BSD 3-Clause）

## ライセンス

[MIT License](LICENSE)
