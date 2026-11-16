package io.github.shaonialife.cardeck.launcher.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `am compat` не прошел классификационный тест.
 *
 * Эта категория определяет «предпочтение, размещать ли заказ»: постоянный отказ (ограничение на сборку
 * пользователя), размещение разрешает только следующее соединение.
 * тщетно запускает applyAll, и на панели отображается «Открыто», но система фактически не вступает в силу;
 * временные неисправности противоположны и должны быть сохранены.
 * Пользователь желает попробовать еще раз. Измеренный образец взят из реальных результатов пользовательской
 * сборки Redmi Android 13 от 18 октября 2026 г.
 */
class CompatFailureClassifyTest {
    /** Часто установленные сторонние приложения отклоняются в пользовательских сборках (реальный результат этой проблемы). */
    @Test
    fun nonDebuggableOnUserBuildIsUnsupported() {
        assertEquals(
            AppOrientationCompat.WriteResult.UNSUPPORTED,
            classifyCompatFailure("Cannot override a change on a non-debuggable app and user build."),
        )
    }

    /** Другая формулировка отклонения, когда целью является объект по умолчанию для этого ChangeId. */
    @Test
    fun defaultChangeOnUserBuildIsUnsupported() {
        assertEquals(
            AppOrientationCompat.WriteResult.UNSUPPORTED,
            classifyCompatFailure("Cannot override a default enabled/disabled change on a user build."),
        )
    }

    /**
     * **Встроенное ПЗУ не регистрирует ChangeId** (фактические результаты измерений Redmi Android 13).
     *
     * Это настоящая причина, почему «Honor может это изменить, а Redmi не может»: эти два ChangeId являются элементами настройки OEM.
     * Honor зарегистрирован (392), а Redmi не зарегистрирован (173). Если незарегистрированный `am compat` будет выдан первым
     * `is not known yet` Внимание, затем отклоните с формулировкой пользовательской сборки.
     */
    @Test
    fun unregisteredChangeIdIsUnsupported() {
        assertEquals(
            AppOrientationCompat.WriteResult.UNSUPPORTED,
            classifyCompatFailure(
                "Warning! Change 265451093 is not known yet. Enabling/disabling it could have no effect.\n" +
                    "Cannot override a change on a non-debuggable app and user build.",
            ),
        )
        // Только строки с предупреждениями (некоторые ПЗУ возвращают только предупреждения) также постоянно наказываются.
        assertEquals(
            AppOrientationCompat.WriteResult.UNSUPPORTED,
            classifyCompatFailure("Warning! Change 265464455 is not known yet."),
        )
    }

    /** Шизуку не подключается: временный сбой, можно попробовать еще раз. */
    @Test
    fun shizukuNotConnectedIsTemporary() {
        assertEquals(
            AppOrientationCompat.WriteResult.FAILED,
            classifyCompatFailure("Shizuku Не подключено"),
        )
    }

    /** Тайм-аут/процесс остановлен: временный сбой. */
    @Test
    fun emptyOrTimeoutIsTemporary() {
        assertEquals(AppOrientationCompat.WriteResult.FAILED, classifyCompatFailure(""))
        assertEquals(
            AppOrientationCompat.WriteResult.FAILED,
            classifyCompatFailure("Не удалось выполнить команду：timeout"),
        )
    }

    /** Успешный вывод не следует рассматривать как постоянное отклонение (Защита: вызывающая сторона классифицирует только в случае неудачи). */
    @Test
    fun successTextIsNotPermanent() {
        assertEquals(
            AppOrientationCompat.WriteResult.FAILED,
            classifyCompatFailure("Enabled change 265451093 for com.example.app."),
        )
    }

    /**
     * Порог версии : эти два ChangeId вошли в AOSP только с **Android 14/SDK 34**.
     * (`android.content.pm.ActivityInfo`). В ActivityInfo Android 13 и более ранних версий
     * не имеет класса ориентации ChangeId (фактическое измерение A13=0, A14=9), поэтому младшая версия
     * не нужно запускать dumpsys, чтобы определить, что он не поддерживается.
     *
     * Сравнение фактических измерений      : Redmi K40 (SDK 33) не поддерживает его; Honor Magic7 Pro (SDK 37) поддерживает это.
     */
    @Test
    fun minSupportedSdkIsAndroid14() {
        assertEquals(34, AppOrientationCompat.MIN_SUPPORTED_SDK)
    }
}
