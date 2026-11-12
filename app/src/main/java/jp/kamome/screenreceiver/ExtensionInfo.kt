package jp.kamome.screenreceiver

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** 同梱している Chrome 拡張機能（extension.zip）の情報 */
object ExtensionInfo {
    /**
     * 拡張機能の ID。マニフェストの key（公開鍵）から、Chrome と同じ方法で求める
     * （公開鍵の SHA-256 の先頭 16 バイトを、1 バイトにつき 2 文字、a〜p で表す）。読めなければ空文字
     */
    fun id(context: Context): String = try {
        val manifest = context.assets.open("extension.zip").use { raw ->
            ZipInputStream(raw).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && entry.name != "manifest.json") entry = zip.nextEntry
                if (entry == null) "" else zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        val key = Base64.decode(JSONObject(manifest).getString("key"), Base64.DEFAULT)
        MessageDigest.getInstance("SHA-256").digest(key).take(16)
            .joinToString("") { b -> "${'a' + ((b.toInt() shr 4) and 15)}${'a' + (b.toInt() and 15)}" }
    } catch (e: Exception) {
        // IOException（zip がない）、JSONException（key がない）など。ID が分からないだけなので、案内の一部を出さない
        if (e !is IOException) android.util.Log.w("ScreenReceiver", "拡張機能の ID を求められません", e)
        ""
    }
}
