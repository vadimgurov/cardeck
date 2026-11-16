package io.github.shaonialife.cardeck

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.util.DebugFileLog

/**
 * Система безопасности на уровне процесса: неперехваченные исключения в любом потоке только протоколируются, и поток завершается.
 * больше не использует процессор по умолчанию для завершения всего процесса (сеанс соединения охватывает несколько фоновых потоков,
 * Исключение сокета не должно разрушать весь канал и интерфейсную службу).
 * Каждый поток сам по себе должен обрабатываться как можно точнее с помощью try/catch; это последняя линия защиты.
 */
open class SenderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Отметка начальной точки журнала сеанса: файлы журнала накапливаются по всем процессам (верхний предел — 2 МБ).
        // По умолчанию на экспорт берется только этот стартап.

        // Часть после этого. Его необходимо записать сюда (Приложение — самая ранняя точка входа в процесс), позже любого бизнес-журнала.
        // Оно войдет в «историю».
        DebugFileLog.markSessionStart(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Неперехваченное исключение (поток ${thread.name}, процесс поглощен)", throwable)
            // Не вызывайте предыдущее: обработчик по умолчанию уничтожит процесс, чтобы убить все приложение.
        }
        // Установка/удаление/обновление приложения → Кэш общего списка приложений для рабочего стола и настроек автомобиля недействителен.
        val packageFilter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    VirtualLauncher
                        .invalidateAppsCache()
                    Log.i(TAG, "Срок действия кэша манифеста приложения истек (изменения пакета)")
                }
            },
            packageFilter,
        )
    }

    companion object {
        private const val TAG = "CarPlaySender"
    }
}
