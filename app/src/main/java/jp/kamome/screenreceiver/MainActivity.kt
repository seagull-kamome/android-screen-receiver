package jp.kamome.screenreceiver

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 待機中は、接続のしかた（送信ページの URL と、Chrome の設定）を表示し、
 * 送信側（Chrome）から映像が届いたら、全画面に表示する。画面をタップすると、案内の表示を切り替える。
 */
class MainActivity : Activity(), SurfaceHolder.Callback, StreamServer.Listener {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: TextView

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

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this).also { it.holder.addCallback(this) }
        root.addView(surfaceView, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        overlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            textSize = 14f
            setPadding(48, 48, 48, 48)
        }
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // タップで案内の表示を切り替える
        root.setOnClickListener {
            if (overlay.visibility == View.VISIBLE) overlay.visibility = View.GONE
            else showOverlay(translucent = streamWidth > 0)
        }
        overlay.setOnClickListener { if (streamWidth > 0) overlay.visibility = View.GONE }
        // 画面の大きさが変わったら（回転など）、映像の枠を合わせ直す
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitSurface() }
        setContentView(root)
    }

    private fun startServer() {
        for (p in PORTS) {
            val s = StreamServer(p, { name ->
                try { assets.open(name).use { it.readBytes() } } catch (e: java.io.IOException) { null }
            }, this)
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

    private fun showIdleOverlay() {
        val urls = addresses().map { "http://$it:$port" }
        val first = urls.firstOrNull() ?: "(Wi-Fi に接続してください)"
        val host = first.removePrefix("http://")
        overlay.text = buildString {
            appendLine("Screen Receiver")
            appendLine(if (senderConnected) "状態: 送信側が接続しました。映像を待っています…" else "状態: 待機中    接続先: $host")
            appendLine()
            appendLine("【おすすめ】Chrome 拡張機能（Chrome の設定変更が不要）")
            appendLine("  初回: Chrome で $first/extension.zip をダウンロードして展開し（ブロックされたら「保存」）、")
            appendLine("        chrome://extensions →「デベロッパー モード」をオン →「パッケージ化されていない拡張機能を読み込む」→ 展開したフォルダを選ぶ")
            appendLine("  以降: 拡張機能のアイコンを押す → 接続先に「$host」を入力 →「共有を開始」")
            appendLine()
            appendLine("【拡張機能を使わない場合】Chrome の設定が必要です")
            appendLine("  chrome://flags/#unsafely-treat-insecure-origin-as-secure に「$first」を入力して有効にし、Chrome を再起動 → Chrome で $first を開く")
            appendLine()
            appendLine("画面をタップすると、この案内を隠せます。")
        }
        showOverlay(translucent = false)
    }

    /** 案内を表示する。映像の再生中は半透明（映像を残す）、待機中は不透明（最後の映像が透けないようにする） */
    private fun showOverlay(translucent: Boolean) {
        overlay.setBackgroundColor(if (translucent) 0xCC000000.toInt() else Color.BLACK)
        overlay.visibility = View.VISIBLE
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
            overlay.text = "この端末では、${streamWidth}x$streamHeight の H.264 を復号できませんでした。\n送信側の解像度を下げてください。\n\n${e.message}"
            overlay.visibility = View.VISIBLE
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
