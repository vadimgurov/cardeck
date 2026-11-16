package io.github.shaonialife.cardeck.bridge

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import io.github.shaonialife.cardeck.SessionController
import io.github.shaonialife.cardeck.util.Bg
import io.github.shaonialife.cardeck.util.DebugFileLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Переустановка по замкнутому циклу: сервер отправляет новый APK → загрузите и проверьте → подсказка в плавающем окне → после нажатия
 * **Сначала полностью остановите сеанс, чтобы освободить виртуальный экран**, а затем используйте PackageInstaller.
 *
 * Для   есть жесткое требование остановить сессию и отпустить экран: всплывающее окно подтверждения установки
 * упадет на еще живой виртуальный экран (со стороны автомобиля).
 * Никто не может нажать на мобильный телефон - основная причина «зависания установки», зафиксированная AGENTS.md.
 */
object ReinstallFlow {
    private val main = Handler(Looper.getMainLooper())

    /**
     * Намерение подтверждения установки системы (передается широковещательным сообщением PENDING_USER_ACTION). Честь фактического теста: от
     * BroadcastReceiver напрямую запускает активность, Intent будет перехвачен молча (без исключения,
     * Нет интерфейса, нет уведомления об ошибке) - .rd Никакие разрешения плавающего окна не освобождаются от фонового запуска. Изменить на
     * [BridgeSetupActivity] отправлено со стойки регистрации (24 октября 2026 г.).
     */
    @Volatile
    var pendingConfirmIntent: Intent? = null

    /**
     * На этот раз отправлен идентификатор сеанса установки. Трансляция ОЖИДАЕТСЯ/УСПЕХ отфильтрована им: сегодня
     * несколько неудачных попыток
     * Трансляция устаревшего сеанса       может задерживаться, и намерение подтверждения другого сеанса привело к
     * установке новой версии на старую.
     * «Страница успешной установки» (фактическое фото пользователя 24 октября 2026 г.: я нажал на установку, но
     * увидел старую версию страницы успешной установки + завершенную).
     */
    @Volatile
    var currentInstallSessionId: Int = -1

    /** Установите компонент страницы (абсолютное имя класса, используемое для am/intent; суффикс applicationId не влияет). */
    const val SETUP_ACTIVITY = "io.github.shaonialife.cardeck.bridge.BridgeSetupActivity"

    /**
     * Очищает зависший сеанс установки этого приложения. Старые версии не получают широковещательные сообщения PENDING_USER_ACTION.
     * Когда       (получатель не имеет фильтра намерений), подтвердите, что никто не запускал сеанс, и сеанс всегда
     * будет останавливаться в активном состоянии.
     * также будет блокировать последующие установки (фактический тест 21 октября 2026 г.: две последовательные
     * установки были «успешными», но не удалось установить после зависания сеанса).
     * Вызывается при запуске приложения       и влияет только на созданные вами сеансы.
     */
    fun abandonStaleSessions(context: Context) {
        runCatching {
            val installer = context.packageManager.packageInstaller
            installer.mySessions.forEach { info ->
                runCatching { installer.abandonSession(info.sessionId) }
                DebugFileLog.append(context, "МОСТ: очистка устаревшего сеанса установки ${info.sessionId}")
            }
        }
    }

    fun offer(
        context: Context?,
        url: String,
        sha256: String,
        version: String,
        deltaUrl: String? = null,
        deltaSha256: String? = null,
        baseSha256: String? = null,
        deltaSizeKB: Int? = null,
    ) {
        val ctx = context?.applicationContext ?: return
        if (url.isBlank()) return
        DebugFileLog.append(
            ctx,
            "МОСТ: получена новая версия, нажмите v$version (" +
                if (deltaUrl.isNullOrBlank()) {
                    "полная сумма"
                } else {
                    "Дифференциальный пакет ${deltaSizeKB ?: "?"}KB" +
                        "）"
                },
        )
        Bg.run("bridge-apk-download") {
            // Дифференциальный приоритет: обычно 15 МБ → 0,1–2 МБ (поддерживает сотовый трафик). Неудача на любом этапе приведет
            // Безусловный возврат к полной загрузке - разница заключается только в оптимизации и никогда не блокирует обновления.
            var file: File? = null
            var deltaNote: String? = null
            if (!deltaUrl.isNullOrBlank() && !deltaSha256.isNullOrBlank()) {
                file = tryDelta(ctx, deltaUrl, deltaSha256, baseSha256, version)
                if (file == null) {
                    DebugFileLog.append(ctx, "МОСТ: не удалось выполнить дифференциальное обновление — возврат к полной загрузке")
                } else if (deltaSizeKB != null) {
                    deltaNote = "Дифференциальный пакет ${deltaSizeKB}КБ · Полный пакет ${file.length() / 1024 / 1024} МБ"
                }
            }
            if (file == null) {
                DebugFileLog.append(ctx, "МОСТ: начать загрузку $url")
                // Передача больших пакетов по общедоступной сети может быть прервана на полпути (фактическое измерение слабого
                // сотового сигнала), попробуйте еще раз.

                file = download(ctx, url) ?: download(ctx, url)
                if (file == null) {
                    DebugFileLog.append(ctx, "МОСТ: Ошибка загрузки APK: $url")
                    return@run
                }
                val digest = sha256Of(file)
                if (sha256.isNotBlank() && !digest.equals(sha256, ignoreCase = true)) {
                    DebugFileLog.append(ctx, "МОСТ: проверка APK не удалась (local=$digest Ожидается=$sha256) – установка отклонена")
                    file.delete()
                    return@run
                }
            }
            DebugFileLog.append(
                ctx,
                "МОСТ: новая версия v$version готова (${file.length() / 1024 / 1024} МБ, sha256 ✓" +
                    (deltaNote?.let { "，$it" } ?: "") + "）",
            )
            promptInstallReady(ctx, file, version, deltaNote)
        }
    }

    /**
     * дифференциальный путь: патч → используйте этот исходный каталог процесса для базовой сборки → строгая проверка продукта sha256.
     * возвращает ноль = любой сбой (вызывающая сторона откатывает всю сумму).
     */
    private fun tryDelta(
        ctx: Context,
        deltaUrl: String,
        deltaSha256: String,
        baseSha256: String?,
        version: String,
    ): File? {
        if (baseSha256.isNullOrBlank()) return null
        val patch =
            download(ctx, deltaUrl) ?: run {
                DebugFileLog.append(ctx, "МОСТ: не удалось загрузить дифференциальный пакет.")
                return null
            }
        val patchDigest = sha256Of(patch)
        if (!patchDigest.equals(deltaSha256, ignoreCase = true)) {
            DebugFileLog.append(ctx, "МОСТ: дифференциальная проверка пакетов не удалась (local=$patchDigest ожидается=$deltaSha256)")
            patch.delete()
            return null
        }
        val base = File(ctx.applicationInfo.sourceDir)
        val out = File(ctx.cacheDir, "bridge-update-delta.apk")
        val error = DeltaPatch.apply(patch, base, out)
        if (error != null) {
            DebugFileLog.append(ctx, "МОСТ: Ошибка дифференциального приложения ($error) - v$version")
            out.delete()
            patch.delete()
            return null
        }
        val patchKB = patch.length() / 1024
        patch.delete()
        DebugFileLog.append(
            ctx,
            "МОСТ: Дифференциальное применение выполнено успешно (исправление ${patchKB}KB + " +
                "базовая версия → ${out.length() / 1024 / 1024}MB) — v$version",
        )
        return out
    }

    /**
     * Подтверждение установки после готовности новой версии: **Обычное всплывающее окно** (DarkMessageDialog),
     * плавающее окно не требуется ——
     * Плавающее окно легко игнорировать, а цель клика мала (реальные отзывы пользователей). Когда на переднем плане
     * нет собственной активности
     * вызывает страницу настроек с параметрами, которая воспроизводится в onCreate/onNewIntent.
     */
    private fun promptInstallReady(
        ctx: Context,
        file: File,
        version: String,
        deltaNote: String? = null,
    ) {
        main.post {
            val activity = BridgeSetupActivity.resumed()
            if (activity != null) {
                activity.promptInstall(file, version, deltaNote)
            } else {
                runCatching {
                    ctx.startActivity(
                        Intent()
                            .setClassName(ctx.packageName, SETUP_ACTIVITY)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("prompt", "install")
                            .putExtra("apkPath", file.absolutePath)
                            .putExtra("version", version)
                            .putExtra("deltaNote", deltaNote),
                    )
                }
            }
        }
    }

    fun installNow(
        ctx: Context,
        file: File,
    ) {
        BridgePrefs.setResumePending(ctx, true)
        // Сначала остановите сеанс, чтобы освободить все виртуальные экраны (включая те, которые принадлежат
        // UserService): в противном случае появится всплывающее окно подтверждения установки системы.

        // Он попадает на виртуальный экран сбоку автомобиля и не может быть подтвержден на мобильном телефоне.
        runCatching { SessionController.disconnect(ctx) }
        Bg.run("bridge-install") {
            try {
                val installer = ctx.packageManager.packageInstaller
                // Устаревшие сеансы (зависшие в состоянии ОЖИДАНИЕ из-за предыдущих неудачных попыток) будут блокировать
                // последующие установки, их поздние установки

                // Трансляция статуса также будет сериализована — очищайте старую сессию перед каждой отправкой.
                abandonStaleSessions(ctx)
                // Сначала вы должны создатьSession, чтобы получить идентификатор сеанса, а затем openSession - напрямую openSession(0)
                // Вызовет SecurityException «Вызывающий абонент не имеет доступа к сеансу 0»
                // (21 октября 2026 г. реальный компьютерный тест: при нажатии на плавающее окно ответа нет, и установка не запускается)
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val sessionId = installer.createSession(params)
                currentInstallSessionId = sessionId
                val session = installer.openSession(sessionId)
                session.openWrite("apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                // Намеренная трансляция неявного действия: InstallReceiver в этом пакете (exported=false)
                val intent =
                    Intent("io.github.shaonialife.cardeck.BRIDGE_INSTALL_RESULT")
                        .setPackage(ctx.packageName)
                val sender =
                    PendingIntent.getBroadcast(
                        ctx,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                session.commit(sender.intentSender)
                session.close()
                DebugFileLog.append(ctx, "МОСТ: Установка начата (ожидание подтверждения системы)")
            } catch (error: Exception) {
                DebugFileLog.append(ctx, "МОСТ: Не удалось начать установку – ${error.message}")
            }
        }
    }

    private fun download(
        ctx: Context,
        url: String,
    ): File? {
        return try {
            val target = URL(url)
            // Установите приоритет загрузки путем привязки к сотовой сети: при отправке новой версии во время сеанса сеть по
            // умолчанию для процесса находится в точке доступа автомобиля.

            // (Нет Интернета), маршрут по умолчанию HttpURLConnection должен завершиться неудачей
            val connection =
                (
                    BridgeClient
                        .internetNetwork()
                        ?.let { net -> runCatching { net.openConnection(target) }.getOrNull() }
                        ?: target.openConnection()
                ) as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) return null
            val file = File(ctx.cacheDir, "bridge-update.apk")
            connection.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file
        } catch (error: Exception) {
            DebugFileLog.append(ctx, "МОСТ: Исключение загрузки – ${error.message}")
            null
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
