package io.github.shaonialife.cardeck.bridge

import android.content.Context
import java.util.UUID

/** Настройки моста удаленной отладки (отдельный файл «мост», не смешанный с бизнес-настройками). */
object BridgePrefs {
    private const val FILE = "bridge"
    private const val KEY_URL = "serverUrl"
    private const val KEY_CODE = "pairCode"
    private const val KEY_DEVICE_ID = "deviceId"
    private const val KEY_AUTO = "autoConnect"
    private const val KEY_RESUME = "resumePending"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun serverUrl(context: Context): String = prefs(context).getString(KEY_URL, "") ?: ""

    fun pairCode(context: Context): String = prefs(context).getString(KEY_CODE, "") ?: ""

    fun save(
        context: Context,
        url: String,
        code: String,
    ) {
        prefs(context)
            .edit()
            .putString(KEY_URL, url.trim())
            .putString(KEY_CODE, code.trim())
            .apply()
    }

    /** Собственный стабильный идентификатор устройства (постоянный после первого поколения; сервер различает устройства по нему). */
    fun deviceId(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString().substring(0, 8)
        p.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    /** Приложение автоматически подключается к мосту при его запуске (устанавливается после того, как пользователь нажимает «Подключиться» на странице настроек; снимается при «Отключиться»). */
    fun autoConnect(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, false)

    fun setAutoConnect(
        context: Context,
        value: Boolean,
    ) {
        prefs(context).edit().putBoolean(KEY_AUTO, value).apply()
    }

    /** Положение перед переустановкой: При первом запуске новой версии вопрос «Продолжить вход в режим отладки?» всплывет. */
    fun resumePending(context: Context): Boolean = prefs(context).getBoolean(KEY_RESUME, false)

    // ---- Дифференциальный базовый кэш sha (кодирование версий: естественный сбой и перерасчет после установки новых пакетов) ----

    fun baseApkSha(
        context: Context,
        version: String,
    ): String? {
        val p = prefs(context)
        if (p.getString("baseApkShaVer", "") != version) return null
        return p.getString("baseApkSha", null)
    }

    fun setBaseApkSha(
        context: Context,
        version: String,
        sha: String,
    ) {
        prefs(context)
            .edit()
            .putString("baseApkShaVer", version)
            .putString("baseApkSha", sha)
            .apply()
    }

    fun setResumePending(
        context: Context,
        value: Boolean,
    ) {
        prefs(context).edit().putBoolean(KEY_RESUME, value).apply()
    }
}
