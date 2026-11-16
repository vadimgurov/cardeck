package io.github.shaonialife.cardeck.audio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import io.github.shaonialife.cardeck.shizuku.Shell
import io.github.shaonialife.cardeck.util.DebugFileLog
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * — **прозрачный трамплин** для молчаливого получения токенов MediaProjection (только для автоматического подключения).
 *
 * Зачем это нужно: AudioPlaybackCapture должен иметь экземпляр MediaProjection и токен для Android 14+.
 * — **одиночный** (будет недействителен при остановке захвата). Никто не может нажать «Разрешить зеркалирование
 * экрана» во время автоматического подключения.
 *
 * Два системных врата и меры противодействия:
 * 1. **Всплывающее окно авторизации**: `appops set <pkg> PROJECT_MEDIA allow` создаст SystemUI
 * `MediaProjectionPermissionActivity` напрямую возвращает RESULT_OK (тот же метод, что и в официальных документах Tasker);
 * 2. **Фоновое стартовое действие (BAL)**: выполняется Шизуку
 * `am start -n <pkg>/<activity> --activity-no-animation` Поднимите эту страницу ——оболочка удерживается
 * `START_ACTIVITIES_FROM_BACKGROUND`, не ограничен BAL на стороне приложения; возвращается в случае неудачи
 * `context.startActivity` (требуется `SYSTEM_ALERT_WINDOW`, в случае отсутствия `appops` будет вручен первым).
 *
 * Сама страница  : 1×1, недоступна для касания, фокусировки, без анимации, без недавних задач; закончить сразу после получения результатов.
 *
 * **Одноразовый**: токен немедленно заменяется экземпляром MediaProjection из `CarPlayService`; эта страница не
 * содержит никакого состояния.
 */
class SilentProjectionActivity : Activity() {
    /** Результат авторизации (resultCode + Intent, необходимый для MediaProjection). */
    data class Grant(
        val resultCode: Int,
        val data: Intent,
    )

    private fun complete(
        grant: Grant?,
        error: String?,
    ) {
        val future = pending.getAndSet(null) ?: return
        if (grant != null) {
            future.complete(grant)
        } else {
            future.completeExceptionally(IllegalStateException(error ?: "неизвестная причина"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Когда телефон находится в кармане (экран блокировки + отдых экрана), фоновая активность не возобновляется, а
        // onActivityResult всегда будет

        // Висит; эти два знака позволяют немедленно завершить эту страницу даже на экране блокировки (с темным
        // экраном/экраном истинного дыхания пользователь этого не почувствует).

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        // 1×1, неприкосновенный, не фокусируемый: полностью невидим для пользователя
        window.setLayout(1, 1)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE)
        } catch (t: Throwable) {
            complete(null, "Ошибка createScreenCaptureIntent: $t")
            finishSilently()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE) return
        if (resultCode == RESULT_OK && data != null) {
            complete(Grant(resultCode, data), null)
        } else {
            complete(null, "Авторизация запрещена resultCode=$resultCode")
        }
        finishSilently()
    }

    override fun onDestroy() {
        // Уничтожено до получения результата (отменено пользователем/переработано системой): пусть ожидающая сторона
        // выйдет из строя немедленно, вместо того, чтобы ждать тайм-аута.

        complete(null, "Активность уничтожается до возврата результатов")
        super.onDestroy()
    }

    private fun finishSilently() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val REQUEST_CODE = 10086

        /** Одновременно допускается только один запрос (автоматические соединения являются последовательными). */
        private val pending = AtomicReference<CompletableFuture<Grant>?>(null)

        /**
         * блокирует получение токена (**Должен быть вызван в фоновом потоке**, 2~3 команды Shizuku будут выполнены внутри).
         *
         * @return null = Сбой/Тайм-аут: вызывающему абоненту следует вернуться к режиму «Только push-видео»
         */
        fun request(
            context: Context,
            timeoutMs: Long = 8_000L,
        ): Grant? {
            val app = context.applicationContext
            val future = CompletableFuture<Grant>()
            pending.getAndSet(future)?.completeExceptionally(IllegalStateException("Перезаписан новым запросом"))
            val pkg = app.packageName
            // 1) Разрешить показ экрана: когда SystemUI видит PROJECT_MEDIA=allow, он напрямую возвращает RESULT_OK без всплывающего окна.
            Shell.exec("appops set $pkg PROJECT_MEDIA allow", 5_000L)
            // 2) При отсутствии разрешения на плавающее окно действия, запущенные в фоновых/передних службах, будут
            // заблокированы; темным экранам это тоже нужно.

            if (!Settings.canDrawOverlays(app)) {
                val r = Shell.exec("appops set $pkg SYSTEM_ALERT_WINDOW allow", 5_000L)
                DebugFileLog.append(app, "PROJ: Возврат «отображения поверх других приложений» ok=${r.ok} (${r.output.take(60)})")
            }
            // 3) Разрешение на запись (требуется для AudioPlaybackCapture): просто добавьте его, если вы его еще не предоставили.
            if (app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                val r = Shell.exec("appops set $pkg RECORD_AUDIO allow", 5_000L)
                DebugFileLog.append(app, "PROJ: предоставить разрешение на запись ok=${r.ok}（${r.output.take(60)}）")
            }
            // 4) Используйте оболочку am start, чтобы обойти ограничение активности фонового запуска; в случае неудачи
            // вернитесь к запуску в приложении

            val component = "$pkg/${SilentProjectionActivity::class.java.name}"
            val viaShell = Shell.exec("am start -n $component --activity-no-animation", 8_000L)
            if (!viaShell.ok) {
                DebugFileLog.append(
                    app,
                    "PROJ: запуск не удался (exit=${viaShell.exitCode} ${viaShell.output.take(80)}) - вместо этого " +
                        "используйте startActivity в приложении",
                )
                runCatching {
                    app.startActivity(
                        Intent(app, SilentProjectionActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    )
                }.onFailure {
                    DebugFileLog.append(
                        app,
                        "PROJ: startActivity в приложении также не удалось " +
                            "(${it.javaClass.simpleName}: ${it.message})",
                    )
                }
            }
            return try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (t: Throwable) {
                DebugFileLog.append(
                    app,
                    "PROJ: не удалось автоматически получить токен скринкаста " +
                        "(${t.javaClass.simpleName}: ${t.message}).",
                )
                null
            } finally {
                pending.compareAndSet(future, null)
            }
        }
    }
}
