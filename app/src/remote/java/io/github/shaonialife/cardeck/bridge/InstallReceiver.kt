package io.github.shaonialife.cardeck.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import io.github.shaonialife.cardeck.util.DebugFileLog

/**
 * Когда  PackageInstaller получает результат: STATUS_PENDING_USER_ACTION, система подтверждает намерение.
 * Перетащите   на главный экран телефона (в этот момент сеанс остановлен, виртуальный экран освобожден, и
 * всплывающее окно больше не будет опускаться в сторону автомобиля).
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        // Распознавайте только сеанс, отправленный на этот раз: поздняя трансляция устаревшего сеанса приведет к тому,
        // что намерение подтверждения будет помещено в старый пакет.

        if (ReinstallFlow.currentInstallSessionId != -1 && sessionId != ReinstallFlow.currentInstallSessionId) {
            DebugFileLog.append(
                context,
                "МОСТ: игнорировать различные трансляции сеансов (session=$sessionId " +
                    "current=${ReinstallFlow.currentInstallSessionId})",
            )
            return
        }
        DebugFileLog.append(context, "МОСТ: Статус установки status=$status msg=$message")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm =
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                DebugFileLog.append(context, "МОСТ: требуется подтверждение пользователя → ${confirm.component}")
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ReinstallFlow.pendingConfirmIntent = confirm
                // Используйте только один путь для внешней переадресации: прямой запуск получателя + внешняя переадресация
                // дважды запустят одно и то же подтверждение.

                // На самом деле установщик Honor оказался в странном состоянии (реальный снимок «страницы успешной версии старой
                // версии» от 24 октября 2026 г.).

                // Разрешение плавающего окна (исключение фонового запуска) включено пользователем, и интерфейсная переадресация
                // должна быть доступна.

                BridgeSetupActivity.startForConfirm(context)
            }

            PackageInstaller.STATUS_SUCCESS -> {
                DebugFileLog.append(context, "МОСТ: Установка прошла успешно.")
                // После завершения установки откройте страницу настроек и спросите «продолжить отладку». Замкнутый контур
                // постоянно находится под рукой.

                BridgeSetupActivity.startForResume(context)
            }

            else ->
                DebugFileLog.append(
                    context,
                    "МОСТ: установка не завершена status=$status msg=$message",
                )
        }
    }
}
