package jp.kamome.screenreceiver

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 待機中は、接続のしかた（QR コード、端末の PIN、拡張機能の入手）を表示し、
 * 送信側（Chrome）から映像が届いたら、全画面に表示する。画面をタップすると、案内の表示を切り替える。
 */
class MainActivity : Activity(), SurfaceHolder.Callback, StreamServer.Listener {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: LinearLayout       // 案内の全体（左: 文章とボタン、右: PIN と QR）
    private lateinit var overlayText: TextView
    private lateinit var textColumn: ScrollView
    private lateinit var qrColumn: LinearLayout
    private lateinit var pinView: TextView
    private lateinit var qrView: ImageView

    private val identity by lazy { DeviceIdentity(this) }
    private val extensionId by lazy { ExtensionInfo.id(this) }

    private var server: StreamServer? = null
    private var port = PORTS.first()
    private val player = H264Player(
        onNeedKeyframe = { server?.requestKeyframe() },
        onFirstFrame = { runOnUiThread { overlay.visibility = View.GONE } },
        onFatalError = { runOnUiThread { restartPlayer() } },
    )

    private var surfaceReady = false
    private var streamWidth = 0
    private var streamHeight = 0
    private var senderConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildViews()
        startServer()
        showIdleOverlay()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this).also { it.holder.addCallback(this) }
        root.addView(surfaceView, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))

        // 左: 文章と、共有のボタン
        overlayText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val actions = listOf<Pair<String, () -> Unit>>(
                "拡張機能を共有" to { shareExtension() },
                "接続先を共有" to { shareAddress() },
                "PIN を変更" to { identity.regenerate(); showIdleOverlay() },
            )
            for ((label, action) in actions) {
                addView(Button(this@MainActivity).apply {
                    text = label
                    isAllCaps = false
                    setOnClickListener { action() }
                })
            }
        }
        textColumn = ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(buttons)
                addView(overlayText)
            })
        }

        // 右: 端末の PIN と、送信ページの QR コード
        pinView = TextView(this).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        qrView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val caption = TextView(this).apply {
            text = "カメラで読み取ると、送信ページが開きます"
            setTextColor(Color.LTGRAY)
            textSize = 12f
            gravity = Gravity.CENTER
        }
        qrColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(pinView)
            addView(qrView, LinearLayout.LayoutParams(dp(210), dp(210)))
            addView(caption)
        }

        overlay = LinearLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(textColumn)
            addView(qrColumn)
        }
        applyOrientation()
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // タップで案内の表示を切り替える
        root.setOnClickListener {
            if (overlay.visibility == View.VISIBLE) overlay.visibility = View.GONE
            else { updateOverlayContent(); showOverlay(translucent = streamWidth > 0) }
        }
        overlay.setOnClickListener { if (streamWidth > 0) overlay.visibility = View.GONE }
        // 画面の大きさが変わったら（回転など）、映像の枠を合わせ直す
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitSurface() }
        setContentView(root)
    }

    /** 横向きなら、左右に並べる。縦向きなら、上下に並べる */
    private fun applyOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        overlay.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        textColumn.layoutParams = if (landscape) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        qrColumn.layoutParams = if (landscape) LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT)
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        overlay.requestLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
    }

    private fun startServer() {
        for (p in PORTS) {
            val s = StreamServer(p,
                { name -> try { assets.open(name).use { it.readBytes() } } catch (e: java.io.IOException) { null } },
                { DeviceInfo(identity.name, identity.pin, extensionId) },
                this)
            try {
                s.start(0, false) // 読み取りの時間切れなし（映像が止まっても、接続を保つ）
                server = s
                port = p
                Log.i(TAG, "サーバーを開始: ポート $p")
                return
            } catch (e: java.io.IOException) {
                Log.w(TAG, "ポート $p を使えません", e)
            }
        }
    }

    private fun addresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress ?: "" }
    } catch (e: Exception) { emptyList() }

    /** 送信側が接続する先（IP:ポート）。Wi-Fi につながっていなければ null */
    private fun hostPort(): String? = addresses().firstOrNull()?.let { "$it:$port" }

    // --- 案内 ---
    private fun updateOverlayContent() {
        val host = hostPort()
        val shown = host ?: "(Wi-Fi に接続してください)"
        val status = when {
            streamWidth > 0 -> "受信中 ${streamWidth}×$streamHeight"
            senderConnected -> "送信側が接続しました。映像を待っています…"
            else -> "待機中"
        }
        overlayText.text = buildString {
            appendLine("Screen Receiver    端末名: ${identity.name}")
            appendLine("状態: $status")
            appendLine("接続先: $shown")
            appendLine()
            appendLine("【ChromeOS / Windows の Chrome で映す】")
            appendLine("  1. 右の QR を、カメラで読み取る（または、Chrome で http://$shown/ を開く）")
            appendLine("  2. 開いたページの「端末の PIN」が、右の PIN と同じか、確かめる")
            appendLine("  3. 「拡張機能で映す」→「共有を開始」")
            appendLine()
            appendLine("【拡張機能を、初めて入れるとき】")
            appendLine("  下の「拡張機能を共有」で、近距離共有（Quick Share）などで送るか、開いたページの「拡張機能をダウンロード」から入手します。")
            appendLine("  展開して、chrome://extensions →「デベロッパー モード」をオン →「パッケージ化されていない拡張機能を読み込む」で、展開したフォルダを選びます。")
            appendLine()
            appendLine("【拡張機能を使わない場合】Chrome のフラグ設定が必要です（README を参照）。")
            appendLine()
            appendLine("画面をタップすると、この案内を隠せます。")
        }
        pinView.text = android.text.SpannableString("PIN\n${identity.pin}").apply {
            setSpan(android.text.style.RelativeSizeSpan(0.4f), 0, 3, 0)
            setSpan(android.text.style.RelativeSizeSpan(1.9f), 4, length, 0)
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 4, length, 0)
        }
        pinView.textSize = 24f
        host?.let { qrView.setImageBitmap(QrCode.bitmap("http://$it/", 600)) } ?: qrView.setImageDrawable(null)
    }

    private fun showIdleOverlay() {
        updateOverlayContent()
        showOverlay(translucent = false)
    }

    /** 案内を表示する。映像の再生中は半透明（映像を残す）、待機中は不透明（最後の映像が透けないようにする） */
    private fun showOverlay(translucent: Boolean) {
        overlay.setBackgroundColor(if (translucent) 0xCC000000.toInt() else Color.BLACK)
        overlay.visibility = View.VISIBLE
    }

    // --- 共有（近距離共有など） ---
    private fun shareExtension() {
        try {
            Sharing.shareExtension(this)
        } catch (e: Exception) {
            Log.w(TAG, "拡張機能を共有できません", e)
            overlayText.append("\n共有できませんでした: ${e.message}")
        }
    }

    private fun shareAddress() {
        val host = hostPort() ?: return
        val text = buildString {
            appendLine("Screen Receiver の接続先: $host")
            appendLine("端末: ${identity.name}　PIN: ${identity.pin}")
            appendLine("ブラウザで開く: http://$host/")
            if (extensionId.isNotEmpty()) {
                appendLine("拡張機能で開く（Chrome のアドレス欄に貼り付け）: chrome-extension://$extensionId/sender.html?target=$host&pin=${identity.pin}")
            }
        }.trim()
        Sharing.shareAddress(this, text)
    }

    // --- 映像の枠（縦横比を保って、画面に収める） ---
    private fun fitSurface() {
        val w = streamWidth
        val h = streamHeight
        val rw = root.width
        val rh = root.height
        if (w <= 0 || h <= 0 || rw <= 0 || rh <= 0) return
        val scale = minOf(rw.toFloat() / w, rh.toFloat() / h)
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        val nw = (w * scale).toInt()
        val nh = (h * scale).toInt()
        if (lp.width != nw || lp.height != nh) {
            lp.width = nw
            lp.height = nh
            surfaceView.layoutParams = lp
        }
    }

    private fun startPlayerIfReady() {
        if (!surfaceReady || streamWidth <= 0) return
        try {
            player.start(surfaceView.holder.surface, streamWidth, streamHeight)
            server?.requestKeyframe()
        } catch (e: Exception) {
            Log.e(TAG, "デコーダを開始できません", e)
            overlayText.text = "この端末では、${streamWidth}x$streamHeight の H.264 を復号できませんでした。\n送信側の解像度を下げてください。\n\n${e.message}"
            showOverlay(translucent = false)
        }
    }

    private fun restartPlayer() {
        player.stop()
        startPlayerIfReady()
    }

    // --- SurfaceHolder.Callback ---
    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        startPlayerIfReady()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        player.stop()
    }

    // --- StreamServer.Listener（サーバーのスレッドから呼ばれる） ---
    override fun onSenderConnected() {
        runOnUiThread { senderConnected = true; showIdleOverlay() }
    }

    override fun onSenderDisconnected() {
        runOnUiThread {
            senderConnected = false
            streamWidth = 0
            streamHeight = 0
            player.stop()
            showIdleOverlay()
        }
    }

    override fun onConfig(width: Int, height: Int, fps: Int) {
        Log.i(TAG, "設定を受信: ${width}x$height @${fps}fps")
        runOnUiThread {
            streamWidth = width
            streamHeight = height
            fitSurface()
            restartPlayer()
        }
    }

    override fun onFrame(data: ByteArray, offset: Int, length: Int, ptsUs: Long, key: Boolean) {
        player.feed(data, offset, length, ptsUs, key)
    }

    // --- 全画面表示 ---
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    override fun onDestroy() {
        player.stop()
        server?.stop()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ScreenReceiver"
        val PORTS = listOf(8080, 8081, 8082)
    }
}
