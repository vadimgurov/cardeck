package io.github.shaonialife.cardeck.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuGuidanceTest {
    @Test
    fun notRunningTellsUserToStartService() {
        val guidance = ShizukuGate.guidanceOf(ShizukuGate.State.NOT_RUNNING)
        assertTrue(guidance.title.contains("не запущена"))
        // Должно быть задано исполняемое действие (для запуска откройте приложение Shizuku), иначе у пользователя не
        // будет возможности запуститься.

        assertEquals("Открыть Shizuku", guidance.primaryLabel)
        assertTrue(guidance.message.contains("Запустить"))
    }

    @Test
    fun unauthorizedTellsUserToGrant() {
        val guidance = ShizukuGate.guidanceOf(ShizukuGate.State.UNAUTHORIZED)
        assertTrue(guidance.title.contains("не разрешено"))
        assertEquals("Запросить разрешение", guidance.primaryLabel)
        assertTrue(guidance.message.contains("Разрешить"))
    }

    @Test
    fun notInstalledHasNoFakeAction() {
        val guidance = ShizukuGate.guidanceOf(ShizukuGate.State.NOT_INSTALLED)
        assertTrue(guidance.title.contains("установите"))
        // Не должно отображаться вымышленная кнопка перехода/загрузки, если она не установлена.
        assertNull(guidance.primaryLabel)
        assertTrue(guidance.message.contains("Shizuku"))
    }

    @Test
    fun eachStateExplainsConsequenceOthersDoNotAllLookSame() {
        // Три состояния копирайтинга должны отличаться друг от друга: смешанные в одно предложение «Шизуку не готова»
        // пользователь не может определить установку/запуск/авторизацию

        val titles =
            listOf(
                ShizukuGate.State.NOT_INSTALLED,
                ShizukuGate.State.NOT_RUNNING,
                ShizukuGate.State.UNAUTHORIZED,
            ).map { ShizukuGate.guidanceOf(it).title }
        assertEquals(titles.size, titles.toSet().size)
        // Три состояния неготовности должны объяснить последствия «соединения не удастся» (в противном случае
        // пользователь думает, что может подключиться)

        listOf(
            ShizukuGate.State.NOT_RUNNING,
            ShizukuGate.State.UNAUTHORIZED,
        ).forEach {
            assertTrue(ShizukuGate.guidanceOf(it).message.contains("не будет работать"))
        }
    }

    @Test
    fun readyHasGuidanceObject() {
        val guidance = ShizukuGate.guidanceOf(ShizukuGate.State.READY)
        assertNotNull(guidance)
        assertNull(guidance.primaryLabel)
    }
}
