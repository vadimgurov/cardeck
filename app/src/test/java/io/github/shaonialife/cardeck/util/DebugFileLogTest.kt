package io.github.shaonialife.cardeck.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * фрагмент журнала «этот запуск»: по умолчанию экспорт выполняется только для текущего сеанса, и исторические раунды не могут быть смешаны.
 * Границы   взяты из реальных сценариев: файлы старой версии без маркеров, маркеры появляются несколько раз, файлы просто обрезаны на 2 МБ.
 */
class DebugFileLogTest {
    @Test
    fun keepsOnlyContentAfterLastMarker() {
        val text =
            "old line 1\n" +
                "old line 2\n" +
                "[12:00:00.000] ${DebugFileLog.SESSION_MARKER}\n" +
                "previous run line\n" +
                "[13:00:00.000] ${DebugFileLog.SESSION_MARKER}\n" +
                "current line A\ncurrent line B\n"

        val slice = DebugFileLog.currentSessionSlice(text)

        assertTrue(slice.contains("current line A"))
        assertTrue(slice.contains("current line B"))
        // Ни предыдущий раунд, ни более ранний контент не могут появиться (сама отметка не должна оставаться в тексте)
        assertTrue(!slice.contains("previous run line"))
        assertTrue(!slice.contains("old line 1"))
        assertTrue(!slice.contains(DebugFileLog.SESSION_MARKER))
    }

    @Test
    fun fallsBackToWholeTextWithoutMarker() {
        val text = "line 1\nline 2\n"
        assertEquals(text, DebugFileLog.currentSessionSlice(text))
    }

    @Test
    fun emptyFileStaysEmpty() {
        assertEquals("", DebugFileLog.currentSessionSlice(""))
    }

    @Test
    fun markerAsLastLineYieldsEmptySlice() {
        // Отметка только что прописана и других логов пока нет: срез пуст и слой экспорта вернется в буфер интерфейса.
        val text = "[13:00:00.000] ${DebugFileLog.SESSION_MARKER}\n"
        assertEquals("\n", DebugFileLog.currentSessionSlice(text))
    }
}
