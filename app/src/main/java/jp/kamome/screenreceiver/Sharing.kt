package jp.kamome.screenreceiver

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Android の共有メニュー（近距離共有 / Quick Share など）で、拡張機能と接続先を、他の端末へ送る */
object Sharing {
    /** 拡張機能（zip）を送る */
    fun shareExtension(activity: Activity) {
        val dir = File(activity.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "screen-receiver-extension.zip")
        activity.assets.open("extension.zip").use { input -> file.outputStream().use { input.copyTo(it) } }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.share", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Screen Receiver 送信用の Chrome 拡張機能")
            putExtra(Intent.EXTRA_TEXT,
                "展開して、chrome://extensions →「デベロッパー モード」→「パッケージ化されていない拡張機能を読み込む」で、展開したフォルダを選びます。")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("", uri) // 共有先が、clipData を見て、読み取りの許可を判断する場合がある
        }
        activity.startActivity(Intent.createChooser(send, "拡張機能を共有（近距離共有など）"))
    }

    /** 接続先と PIN（と、拡張機能で開くリンク）を、文章として送る */
    fun shareAddress(activity: Activity, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Screen Receiver の接続先")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        activity.startActivity(Intent.createChooser(send, "接続先を共有（近距離共有など）"))
    }
}
