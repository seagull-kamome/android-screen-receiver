# android-screen-receiver

ChromeOS（や Windows）の Chrome の画面共有で、画面を Android 端末へ無線で映すための受信アプリです。使わなくなった Android 端末を、PC の画面の複製を映すモニターとして使えます。

Chrome 標準の「キャスト」（Google Cast）は、Google の認証を受けた機器でしか受信できないため使えません。代わりに、Chrome の画面共有の機能（`getDisplayMedia`）で取得した映像を、Wi-Fi で Android へ送ります。送る側は、**Chrome 拡張機能**（おすすめ）か、Android が配信するページです。

## 仕組み

1. Android アプリが、小さなサーバー（HTTP と WebSocket、ポート 8080）を起動します。
2. Chrome の拡張機能（または、Android が配信するページ）で、「共有を開始」を押すと、Chrome が画面を取得して、WebCodecs で H.264 に圧縮し、WebSocket で Android へ送ります。
3. Android が `MediaCodec` で復号して、全画面に表示します。

WebRTC は使わず、同じ LAN 内を前提にして、WebSocket で、映像を送ります。遅延を増やさないよう、送信側も受信側も、詰まったら、古いフレームを捨てて、キーフレームから再開します。共有している画面やウィンドウの大きさが変わったとき（分割画面など）は、新しい大きさでエンコーダを設定し直して、受信側へ通知します。

画面共有と WebCodecs は、「安全なページ」でしか動きません。Android が配信するページは HTTP なので、そのままでは安全なページと見なされません。**拡張機能のページは、安全なページとして扱われるため、Chrome の設定変更が不要**です。

## 使い方

### Chrome 拡張機能（おすすめ）

初回だけ、拡張機能を入れます。

1. Android でアプリを開きます（待機画面に、接続先と手順が表示されます）。
2. Chrome で、`http://<Android の IP>:8080/extension.zip` をダウンロードします（安全でないダウンロードとしてブロックされたら、「保存」を選びます）。
3. zip を展開します（ChromeOS の「ファイル」アプリでは、zip を右クリックして展開するか、開いて中身をすべて、新しいフォルダへコピーします）。
4. Chrome で `chrome://extensions` を開き、「デベロッパー モード」をオンにして、「パッケージ化されていない拡張機能を読み込む」から、展開したフォルダを選びます。

映すときは、次のようにします。

1. Android でアプリを開きます。
2. Chrome の拡張機能のアイコン（青いモニター）を押します。送信ページが、新しいタブで開きます。
3. 「接続先」に、待機画面に表示された `<IP>:8080` を入力します（最近使った接続先は、記憶されます）。
4. 解像度、フレームレート、ビットレートを選んで、「共有を開始」を押し、共有する画面やウィンドウを選びます。
5. Android の全画面に、映像が表示されます。画面をタップすると、案内の表示を切り替えられます。

### 拡張機能を使わない方法（Chrome の設定が必要）

Chrome に、Android のページを安全なものとして扱わせます。

1. Chrome で `chrome://flags/#unsafely-treat-insecure-origin-as-secure` を開きます。
2. 「Insecure origins treated as secure」の欄に、待機画面に表示された URL（例: `http://192.168.3.229:8080`）を入力して、「有効（Enabled）」にし、Chrome を再起動します。
3. Chrome で、その URL を開いて、「共有を開始」を押します。

Android の IP アドレスが変わるたびに、この欄の値を更新する必要があります。

## ビルド

Nix が使えれば、JDK 17、Gradle、Android SDK 34 を `shell.nix` が用意します。

```sh
nix-shell --run "gradle assembleDebug"
# 出力: app/build/outputs/apk/debug/app-debug.apk
```

端末を USB で接続していれば、`./deploy.sh` で、ビルドからインストール、起動まで行えます。

## テスト（Chrome なしで受信側を確認する）

`tools/test-sender.py` は、ffmpeg で作ったテスト映像（H.264）を、送信側と同じ取り決めで、受信アプリへ送ります。

```sh
tools/run-test.sh 192.168.3.229:8080 1920 1080 30 20   # 宛先、幅、高さ、fps、秒数
tools/run-test.sh 192.168.3.229:8080 1920 1080 30 10 960 1080 10   # 10 秒後に、同じ接続のまま 960×1080 へ切り替える
```

送信スクリプト（`extension/sender.js`）のロジックは、Chrome なしで、模擬した WebCodecs と WebSocket で検査できます（Node が必要）。

```sh
node tools/test-sender-logic.js extension/sender.js                    # Android が配信するページとして
node tools/test-sender-logic.js extension/sender.js 192.168.3.229      # 拡張機能のページとして（接続先の入力値を付ける）
```

画面共有の大きさが途中で変わったときに、エンコーダが設定し直され、引き伸ばされた映像が送られないことを検査します。

## 構成

- `app/`: Android アプリ（受信と表示、HTTP と WebSocket のサーバー）
- `extension/`: Chrome 拡張機能。`sender.js` は、Android が配信するページと共通で、ビルド時に、アプリの `assets` へ複製されます（`extension.zip` も、ビルドで作られます）。
- `tools/`: テスト用の送信ツール

## 通信の取り決め

- 送信側 → 受信側（文字）: `{"type":"config","width":W,"height":H,"fps":F}`（映像を始める前に 1 回。共有している画面やウィンドウの大きさが変わったときも、新しい大きさで送り直す。受信側は、デコーダを作り直す）、`ping`（接続維持）
- 送信側 → 受信側（バイナリ）: `[フラグ 1 バイト（bit0 = キーフレーム）][時刻 8 バイト（ビッグエンディアン、マイクロ秒）][H.264 Annex-B]`
- 受信側 → 送信側（文字）: `{"type":"keyframe"}`（キーフレームの要求）

## 確認の状況と制限

- 受信側（Android）は、テスト映像で、動作を確認しました（UMIDIGI RP03、Android 12。1280×720 と 1920×1080、30fps）。
- ChromeOS の Chrome で、Android のページを「安全なページ」として登録すれば、画面が映ることを確認しました（作者による動作確認。画質と遅延の詳しい評価は、していません）。
- **Chrome 拡張機能は、まだ実際の Chrome で確認していません。** 配信、zip の中身、接続先の解釈、大きさの変化への追従は、検査しています。拡張機能のページから、Android の `ws://` へ接続できるかは、実際の Chrome での確認が必要です。
- 共有しているウィンドウの大きさが変わる場合（分割画面など）に、映像が引き伸ばされる不具合を修正しました（映像の大きさを見て、エンコーダを設定し直します）。送信ページのロジックは、模擬した WebCodecs で検査しました。実際の Chrome での再確認は、これからです。
- 遅延は、測っていません。
- **認証も暗号化もありません**: 同じネットワークにいる誰でも、送信ページを開いたり、映像を送ったりできます。信頼できる Wi-Fi でだけ使ってください。
- 音声は送りません。Android 側の画面のタッチを、PC へ返す機能もありません。
- 解像度は、端末の H.264 デコーダが対応する範囲に限られます。
- 拡張画面（空いた領域を、ウィンドウの置き場にする）ではなく、画面や、ウィンドウの共有（複製）です。

## 利用しているライブラリ

- [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd)（HTTP と WebSocket のサーバー、BSD 3-Clause）

## ライセンス

[MIT License](LICENSE)
