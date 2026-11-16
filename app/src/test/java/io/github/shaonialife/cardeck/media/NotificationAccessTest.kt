package io.github.shaonialife.cardeck.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Определение права на использование уведомлений (чистая функция): система объединяет несколько прослушивателей
 * в длинную строку с помощью `:`.
 * необходимо сравнивать точно при вводе - использование `contains(имя пакета)` будет неправильно оценено другими
 * приложениями с «тот же префиксом».
 */
class NotificationAccessTest {
    private val spec = "io.github.shaonialife.cardeck/io.github.shaonialife.cardeck.media.MediaListenerService"

    @Test
    fun detectsOwnComponentAmongOthers() {
        val enabled =
            "com.google.android.projection.gearhead/GearheadListenerService:" +
                "$spec:com.hihonor.health/HnNotificationListenerService"
        assertTrue(NotificationAccess.isGrantedIn(enabled, spec))
    }

    @Test
    fun detectsWhenOnlyEntry() {
        assertTrue(NotificationAccess.isGrantedIn(spec, spec))
    }

    @Test
    fun rejectsWhenAbsent() {
        assertFalse(NotificationAccess.isGrantedIn("com.other.app/OtherService", spec))
        assertFalse(NotificationAccess.isGrantedIn("", spec))
        assertFalse(NotificationAccess.isGrantedIn(null, spec))
    }

    @Test
    fun doesNotMatchByPrefixOnly() {
        // Старое имя пакета — это еще одна авторизация с тем же префиксом: его нельзя считать авторизованным только
        // потому, что оно содержится в строке.

        // (Самая простая ошибка после смены имени пакета — старая запись все еще в списке, а новый пакет фактически не авторизован)
        val staleOldPackage =
            "io.github.shaonialife.cardeck/io.github.shaonialife.cardeck.media.OtherService"
        assertFalse(NotificationAccess.isGrantedIn(staleOldPackage, spec))
    }

    /**
     * "В списке авторизации" не означает "привязан системой": после переустановки в списке включенных этот компонент все еще присутствует.
     * Но сегмент Live отсутствует → метаданные всегда равны нулю, и на карте отображается сообщение «Невозможно
     * прочитать информацию о воспроизведении».
     * (фактическое измерение 19 октября 2026 г.). Должен быть распознан только сегмент Live.
     */
    @Test
    fun liveSectionDecidesWhetherMetadataIsReadable() {
        val dump =
            """
            Notification listeners:
              Allowed notification listeners:
                $spec (user: 0 isPrimary: true)
              All notification listeners (2) enabled for current profiles:
                ComponentInfo{$spec}
              Live notification listeners (1):
                ComponentInfo{android/com.android.server.SystemNotificationListener} (user -1)
              Snoozed notification listeners (0):
            """.trimIndent()
        // Он есть в списке авторизации, но не в сегменте Live → все еще не привязан (должна быть запущена перепривязка)
        assertFalse(NotificationAccess.isLiveIn(dump, spec))
    }

    @Test
    fun liveSectionIsDetectedOnceBound() {
        val dump =
            """
            Live notification listeners (2):
              ComponentInfo{android/com.android.server.SystemNotificationListener} (user -1)
              ComponentInfo{$spec} (user 0): android.service.notification.INotificationListener${'$'}Stub${'$'}Proxy@abc
            Snoozed notification listeners (0):
            """.trimIndent()
        assertTrue(NotificationAccess.isLiveIn(dump, spec))
    }

    @Test
    fun missingLiveSectionIsNotTreatedAsBound() {
        assertFalse(NotificationAccess.isLiveIn("no such section", spec))
    }
}
