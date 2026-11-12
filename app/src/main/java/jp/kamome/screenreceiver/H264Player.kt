package jp.kamome.screenreceiver

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface

/**
 * H.264（Annex-B）の映像を受け取って、Surface に描画する。
 * 遅延を増やさないことを優先し、復号が詰まったら、そのフレームを捨てて、次のキーフレームまで待つ
 * （待つ間は、onNeedKeyframe で、送信側にキーフレームを要求する）。
 */
class H264Player(
    private val onNeedKeyframe: () -> Unit,
    private val onFirstFrame: () -> Unit,
    private val onFatalError: () -> Unit,
) {
    private val inputLock = Any()
    private val freeInputs = ArrayDeque<Int>()

    @Volatile private var codec: MediaCodec? = null
    @Volatile private var waitingForKey = true
    @Volatile private var firstFrameShown = false
    private var lastKeyRequestMs = 0L

    /** 復号を始める。すでに動いていれば、作り直す */
    fun start(surface: Surface, width: Int, height: Int) {
        stop()
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = リアルタイム
        }
        val c = MediaCodec.createDecoderByType(MIME)
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                synchronized(inputLock) { freeInputs.addLast(index) }
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                // 取り出したらすぐ描画する（時刻は見ない）
                try { codec.releaseOutputBuffer(index, true) } catch (e: IllegalStateException) { return }
                if (!firstFrameShown) { firstFrameShown = true; onFirstFrame() }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.i(TAG, "出力形式: $format")
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.w(TAG, "デコーダのエラー", e)
                onFatalError()
            }
        })
        synchronized(inputLock) { freeInputs.clear() }
        waitingForKey = true
        firstFrameShown = false
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        Log.i(TAG, "デコーダを開始: ${width}x$height")
    }

    fun stop() {
        val c = codec ?: return
        codec = null
        try { c.stop() } catch (e: IllegalStateException) { Log.w(TAG, "stop 失敗", e) }
        c.release()
        synchronized(inputLock) { freeInputs.clear() }
    }

    /** 1 フレーム分（Annex-B）を渡す。data の offset から length バイトが映像 */
    fun feed(data: ByteArray, offset: Int, length: Int, ptsUs: Long, key: Boolean) {
        val c = codec ?: return
        if (waitingForKey && !key) { requestKeyframe(); return }
        val index = synchronized(inputLock) { freeInputs.removeFirstOrNull() }
        if (index == null) {
            // 復号が追いつかない。ここで捨てると、後続のフレームが壊れるので、次のキーフレームまで待つ
            waitingForKey = true
            requestKeyframe()
            return
        }
        try {
            val buffer = c.getInputBuffer(index) ?: return
            buffer.clear()
            if (length > buffer.capacity()) {
                Log.w(TAG, "フレームが大きすぎます: $length > ${buffer.capacity()}")
                waitingForKey = true
                synchronized(inputLock) { freeInputs.addFirst(index) }
                requestKeyframe()
                return
            }
            buffer.put(data, offset, length)
            c.queueInputBuffer(index, 0, length, ptsUs, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            if (key) waitingForKey = false
        } catch (e: IllegalStateException) {
            // 停止処理と重なった。次のフレームで改めて判断する
            Log.w(TAG, "feed 失敗", e)
        }
    }

    private fun requestKeyframe() {
        val now = System.currentTimeMillis()
        if (now - lastKeyRequestMs < 500) return // 要求を連打しない
        lastKeyRequestMs = now
        onNeedKeyframe()
    }

    private companion object {
        const val TAG = "ScreenReceiver"
        const val MIME = "video/avc"
    }
}
