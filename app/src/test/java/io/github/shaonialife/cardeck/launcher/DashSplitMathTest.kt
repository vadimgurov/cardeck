package io.github.shaonialife.cardeck.launcher

import io.github.shaonialife.cardeck.render.CARD_MARGIN_PX
import io.github.shaonialife.cardeck.render.DASH_GAP_PX
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тест геометрической чистоты функции разделенного экрана приборной панели: соотношение разделенного экрана
 * регулируется путем перетаскивания маркера, и выводится геометрия (размер плитки/центр маркера/
 * (запуск музыкальной карты) должны быть одинаковыми во всех направлениях (рисунок композитора, изменение
 * размера экрана карты, поля музыкальной карты, расположение ручки).
 * Исторически сложилось так, что четыре стороны писали голые формулы, что приводило к скрытому несовпадению
 * (аннотация контракта CARD_MARGIN_PX). Вот
 * Вывод   [VirtualLauncher.dashSplitGeom] прибит.
 */
class DashSplitMathTest {
    private val appLen = 1000

    @Test
    fun defaultFractionCentersHandle() {
        // 0,5 Просто сделайте центр ручки = середине области приложения (визуально соответствует старой версии
        // разделения экрана по центральной линии)

        val g = VirtualLauncher.dashSplitGeom(appLen, 0.5f)
        assertEquals(500f, g.splitCenter, 0.001f)
    }

    @Test
    fun fractionIsClamped() {
        assertEquals(0.2f, VirtualLauncher.clampDashSplitFraction(0.05f), 0.001f)
        assertEquals(0.8f, VirtualLauncher.clampDashSplitFraction(0.95f), 0.001f)
        assertEquals(0.6f, VirtualLauncher.clampDashSplitFraction(0.6f), 0.001f)
        // Сторона Geom также зажата (еще один вход, чтобы предотвратить падение карты назад)
        assertEquals(0.2f, VirtualLauncher.dashSplitGeom(appLen, 0.05f).fraction, 0.001f)
        assertEquals(0.8f, VirtualLauncher.dashSplitGeom(appLen, 0.99f).fraction, 0.001f)
    }

    @Test
    fun tileLenMatchesUsableShare() {
        val usable = appLen - CARD_MARGIN_PX * 2 - DASH_GAP_PX.toInt()
        assertEquals((0.2f * usable).toInt(), VirtualLauncher.dashSplitGeom(appLen, 0.2f).tileLen)
        assertEquals((0.8f * usable).toInt(), VirtualLauncher.dashSplitGeom(appLen, 0.8f).tileLen)
    }

    @Test
    fun geometryRelationsHold() {
        // Центр ручки = край плитки + половина ручки; начало музыкальной открытки = край плитки + ручка целиком (средний
        // шов = ширина ручки)

        for (f in listOf(0.2f, 0.35f, 0.5f, 0.66f, 0.8f)) {
            val g = VirtualLauncher.dashSplitGeom(appLen, f)
            assertEquals(DASH_GAP_PX / 2f, g.splitCenter - g.tileEdge, 0.001f)
            assertEquals(DASH_GAP_PX, g.otherStart - g.tileEdge, 0.001f)
        }
    }

    @Test
    fun commitRoundTripsFraction() {
        // Обратный путь фиксации (обработка центрального пикселя потока → масштаб) соответствует прямому перемещению geom:
        // streamPos = смещение главной оси + поле + tileLen + половина маркера → восстановление дроби
        val offset = 126f // Воображаемая ширина дока
        for (f in listOf(0.2f, 0.5f, 0.8f)) {
            val g = VirtualLauncher.dashSplitGeom(appLen, f)
            val streamPos = offset + CARD_MARGIN_PX + g.tileLen + DASH_GAP_PX / 2f
            val derived =
                (streamPos - offset - CARD_MARGIN_PX - DASH_GAP_PX / 2f) /
                    (appLen - CARD_MARGIN_PX * 2 - DASH_GAP_PX.toInt())
            assertEquals(f, derived, 0.001f)
        }
    }
}
