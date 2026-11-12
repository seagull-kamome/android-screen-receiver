package jp.kamome.screenreceiver

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.SecureRandom

/**
 * この端末を見分けるための PIN（4 桁。端末ごとに保存する）と、端末名。
 * 「接続した先が、狙った端末か」を、画面の PIN と見比べて確かめるためのもので、認証ではない。
 */
class DeviceIdentity(private val context: Context) {
    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)

    val pin: String
        get() = prefs.getString(KEY_PIN, null) ?: regenerate()

    /** PIN を作り直す。送信側が覚えていた PIN と違うので、次の接続では、確認の画面が出る */
    fun regenerate(): String {
        val pin = "%04d".format(SecureRandom().nextInt(10000))
        prefs.edit().putString(KEY_PIN, pin).apply()
        return pin
    }

    val name: String
        get() = Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private companion object {
        const val KEY_PIN = "pin"
    }
}

/** 送信側へ知らせる、この端末の情報（接続の直後の hello と、/info.json） */
data class DeviceInfo(val name: String, val pin: String, val extensionId: String)
