package jp.kamome.screenreceiver

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * 送信ページ（HTTP）と、映像の受信口（WebSocket `/ws`）を、同じポートで提供する。
 *
 * 通信の取り決め:
 * - 送信側 → 受信側（文字）: `{"type":"config","width":W,"height":H,"fps":F}`（映像を始める前に 1 回）、`ping`（接続維持）
 * - 送信側 → 受信側（バイナリ）: `[フラグ 1 バイト（bit0 = キーフレーム）][時刻 8 バイト（ビッグエンディアン、マイクロ秒）][H.264 Annex-B]`
 * - 受信側 → 送信側（文字）: `{"type":"keyframe"}`（キーフレームの要求）
 */
class StreamServer(
    port: Int,
    private val readAsset: (String) -> ByteArray?,
    private val listener: Listener,
) : NanoWSD(port) {

    interface Listener {
        fun onSenderConnected()
        fun onSenderDisconnected()
        fun onConfig(width: Int, height: Int, fps: Int)
        fun onFrame(data: ByteArray, offset: Int, length: Int, ptsUs: Long, key: Boolean)
    }

    @Volatile private var current: Connection? = null
    private val sender = Executors.newSingleThreadExecutor()

    /** 配信するもの: 送信ページ、送信スクリプト、Chrome 拡張機能（zip） */
    override fun serveHttp(session: IHTTPSession): Response = when (session.uri) {
        "/", "/index.html" -> asset("sender.html", "text/html; charset=utf-8")
        "/sender.js" -> asset("sender.js", "text/javascript; charset=utf-8")
        "/extension.zip" -> asset("extension.zip", "application/zip")
            ?.apply { addHeader("Content-Disposition", "attachment; filename=\"screen-receiver-extension.zip\"") }
        else -> notFound()
    } ?: notFound()

    private fun asset(name: String, mime: String): Response? {
        val bytes = readAsset(name) ?: return null
        return newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())
            .apply { addHeader("Cache-Control", "no-store") }
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "not found")

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = Connection(handshake)

    /** 送信側へ、キーフレームを要求する（ネットワークへの書き込みなので、別のスレッドで行う） */
    fun requestKeyframe() {
        val c = current ?: return
        sender.execute {
            try { c.send("""{"type":"keyframe"}""") } catch (e: IOException) { Log.w(TAG, "キーフレーム要求の送信に失敗", e) }
        }
    }

    private inner class Connection(handshake: IHTTPSession) : WebSocket(handshake) {
        override fun onOpen() {
            // 新しい送信者が来たら、前の送信者は切る（同時に映せるのは 1 つだけ）
            val old = synchronized(this@StreamServer) { current.also { current = this } }
            if (old != null) {
                try { old.close(WebSocketFrame.CloseCode.GoingAway, "別の送信者に置き換えられました", false) } catch (e: IOException) { /* 切れていてもよい */ }
            }
            listener.onSenderConnected()
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            val wasCurrent = synchronized(this@StreamServer) { (current === this).also { if (it) current = null } }
            if (wasCurrent) listener.onSenderDisconnected()
        }

        override fun onMessage(message: WebSocketFrame) {
            if (current !== this) return
            when (message.opCode) {
                WebSocketFrame.OpCode.Binary -> handleBinary(message.binaryPayload)
                WebSocketFrame.OpCode.Text -> handleText(message.textPayload)
                else -> {}
            }
        }

        override fun onPong(pong: WebSocketFrame?) {}

        override fun onException(exception: IOException?) {
            Log.w(TAG, "WebSocket の例外", exception)
        }

        private fun handleBinary(p: ByteArray) {
            if (p.size <= HEADER_SIZE) return
            val key = (p[0].toInt() and 1) != 0
            val ptsUs = ByteBuffer.wrap(p, 1, 8).long
            listener.onFrame(p, HEADER_SIZE, p.size - HEADER_SIZE, ptsUs, key)
        }

        private fun handleText(text: String) {
            if (!text.startsWith("{")) return // "ping" など
            try {
                val json = JSONObject(text)
                if (json.optString("type") == "config") {
                    listener.onConfig(json.getInt("width"), json.getInt("height"), json.optInt("fps", 30))
                }
            } catch (e: Exception) {
                Log.w(TAG, "不正なメッセージ: $text", e)
            }
        }
    }

    private companion object {
        const val TAG = "ScreenReceiver"
        const val HEADER_SIZE = 9
    }
}
