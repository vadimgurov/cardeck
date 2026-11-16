package io.github.shaonialife.cardeck.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterboxHintParseTest {
    /** Фрагмент dumpsys реальной машины (Honor Magic7 Pro, CarPlayMap #511), сохраняются только соответствующие строки. */
    private val realDump =
        """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #511 (activities from top to bottom):
          * Task{d47b965 #34012 type=standard A=10241:com.autonavi.minimap U=0 visible=true visibleRequested=true
          * mode=fullscreen translucent=false embedded=false sz=1}
            isSleeping=false
            topResumedActivity=ActivityRecord{15928233 u0 com.autonavi.minimap/com.autonavi.map.activity.SplashActivity t34012}
            * Hist  #0: ActivityRecord{15928233 u0 com.autonavi.minimap/com.autonavi.map.activity.SplashActivity t34012}
              packageName=com.autonavi.minimap processName=com.autonavi.minimap
              CurrentConfiguration={1.0 460mcc1mnc [zh_CN_#Hans] masculine ldltr sw494dp w494dp h518dp 200dpi nrml compactNeeded port -touch winConfig={ mBounds=Rect(312, 0 - 929, 648) mAppBounds=Rect(312, 0 - 929, 648) mMaxBounds=Rect(0, 0 - 1241, 648) mDisplayRotation=ROTATION_0 mWindowingMode=fullscreen}}
              bounds=Rect(312, 0 - 929, 648)
              overrideOrientation=SCREEN_ORIENTATION_PORTRAIT
              requestedOrientation=SCREEN_ORIENTATION_PORTRAIT
              areBoundsLetterboxed=true
              isLetterboxRunning=true
                letterboxReason=FIXED_ORIENTATION
          Resumed activities in task display areas (from top to bottom):
            Resumed: ActivityRecord{15928233 u0 com.autonavi.minimap/com.autonavi.map.activity.SplashActivity t34012}
        Display #510 (activities from top to bottom):
          * Task{74f1ce1 #33999 type=standard A=10296:io.github.shaonialife.cardeck U=0 visible=true visibleRequested=true
          * mode=fullscreen translucent=true embedded=false sz=1}
            topResumedActivity=ActivityRecord{199877690 u0 io.github.shaonialife.cardeck/.launcher.GridActivity t33999}
            * Hist  #0: ActivityRecord{199877690 u0 io.github.shaonialife.cardeck/.launcher.GridActivity t33999}
              CurrentConfiguration={1.0 460mcc1mnc [zh_CN_#Hans] masculine ldltr sw431dp w825dp h431dp 361dpi nrml long compactNeeded land winConfig={ mBounds=Rect(0, 0 - 1861, 972) mAppBounds=Rect(0, 0 - 1861, 972) mMaxBounds=Rect(0, 0 - 1861, 972) mDisplayRotation=ROTATION_0 mWindowingMode=fullscreen}}
              bounds=Rect(0, 0 - 1861, 972)
              areBoundsLetterboxed=false
              isLetterboxRunning=false
        """.trimIndent()

    @Test
    fun parsesLetterboxRectOfTopActivityOnTargetDisplay() {
        val activity = parseTopLetterboxActivity(511, realDump, 1241, 648)!!
        assertEquals("com.autonavi.minimap", activity.packageName)
        assertEquals("com.autonavi.minimap/com.autonavi.map.activity.SplashActivity", activity.component)
        assertEquals(511, activity.displayId)
        assertTrue(activity.letterboxed)
        assertEquals("FIXED_ORIENTATION", activity.letterboxReason)
        // Внутреннее окно и внешняя рамка взяты из фактически измеренных значений dumpsys (не оценок): 312 пикселей
        // слева и справа соответственно.

        assertEquals(PixelBounds(312, 0, 929, 648), activity.inner)
        assertEquals(PixelBounds(0, 0, 1241, 648), activity.full)
        assertEquals(312, activity.inner.left - activity.full.left)
        assertEquals(312, activity.full.right - activity.inner.right)
    }

    @Test
    fun picksTopActivityOfRequestedDisplayNotFirstSection() {
        val activity = parseTopLetterboxActivity(510, realDump, 1861, 972)!!
        assertEquals("io.github.shaonialife.cardeck", activity.packageName)
        assertFalse(activity.letterboxed)
        assertNull(activity.letterboxReason)
    }

    @Test
    fun fallsBackToDisplaySizeWhenMaxBoundsMissing() {
        val dump =
            """
            Display #509 (activities from top to bottom):
              * Task{aaa #1 type=standard A=10241:com.autonavi.minimap U=0 visible=true visibleRequested=true mode=fullscreen
              * translucent=false embedded=false sz=1}
                topResumedActivity=ActivityRecord{bbb u0 com.autonavi.minimap/.MainActivity t1}
                * Hist  #0: ActivityRecord{bbb u0 com.autonavi.minimap/.MainActivity t1}
                  bounds=Rect(312, 0 - 929, 648)
                  areBoundsLetterboxed=true
                  isLetterboxRunning=true
                    letterboxReason=FIXED_ORIENTATION
            """.trimIndent()
        val activity = parseTopLetterboxActivity(509, dump, 1241, 648)!!
        assertEquals(PixelBounds(0, 0, 1241, 648), activity.full)
        assertEquals(312, activity.inner.left)
    }

    @Test
    fun ignoresDumpWithoutRequestedDisplay() {
        assertNull(parseTopLetterboxActivity(123, realDump, 1241, 648))
        assertNull(parseTopLetterboxActivity(511, "", 1241, 648))
    }

    @Test
    fun ignoresOtherActivityHistBlocks() {
        val dump =
            """
            Display #511 (activities from top to bottom):
              * Task{aaa #1 type=standard A=10241:com.a U=0 visible=true visibleRequested=true mode=fullscreen
              * translucent=false embedded=false sz=1}
                topResumedActivity=ActivityRecord{bbb u0 com.a/.Main t1}
                * Hist  #0: ActivityRecord{bbb u0 com.a/.Main t1}
                  bounds=Rect(0, 0 - 1241, 648)
                  areBoundsLetterboxed=true
                  isLetterboxRunning=true
                    letterboxReason=FIXED_ORIENTATION
                * Hist  #1: ActivityRecord{ccc u0 com.b/.Main t1}
                  bounds=Rect(312, 0 - 929, 648)
                  isLetterboxRunning=true
                    letterboxReason=FIXED_ORIENTATION
            """.trimIndent()
        val activity = parseTopLetterboxActivity(511, dump, 1241, 648)!!
        assertEquals("com.a", activity.packageName)
        assertEquals(PixelBounds(0, 0, 1241, 648), activity.inner)
    }
}

class LetterboxHintPolicyTest {
    private fun activity(
        pkg: String = "com.ss.android.article.news",
        letterboxed: Boolean = true,
        reason: String? = "FIXED_ORIENTATION",
    ) = LetterboxActivity(
        packageName = pkg,
        component = "$pkg/.Main",
        displayId = 510,
        letterboxed = letterboxed,
        letterboxReason = reason,
        inner = PixelBounds(312, 0, 929, 648),
        full = PixelBounds(0, 0, 1241, 648),
    )

    @Test
    fun showsOnlyForForceLandscapePackageWithFixedOrientationLetterbox() {
        val enabled = setOf("com.ss.android.article.news")
        assertTrue(shouldShowLetterboxHint(activity(), enabled, emptySet()))
        // Нет в списке: намерением пользователя являются вертикальные окна, не беспокойтесь.
        assertFalse(shouldShowLetterboxHint(activity(pkg = "com.other"), enabled, emptySet()))
        // Почтовый ящик (соотношение сторон и т. д.) не вызван блокировкой ориентации: это не тот сценарий для этого совета.
        assertFalse(shouldShowLetterboxHint(activity(reason = "BOOK_ASPECT_RATIO"), enabled, emptySet()))
        assertFalse(shouldShowLetterboxHint(activity(letterboxed = false), enabled, emptySet()))
    }

    @Test
    fun respectsUserDismissalForCurrentSession() {
        val enabled = setOf("com.ss.android.article.news")
        assertFalse(
            shouldShowLetterboxHint(
                activity(),
                enabled,
                setOf("com.ss.android.article.news"),
            ),
        )
    }

    @Test
    fun prefLeftSideButFallsBackToRightWhenLeftTooNarrow() {
        // Подходит для левой стороны → Левая сторона (возле сиденья водителя)
        assertEquals(LetterboxSide.LEFT, chooseLetterboxSide(leftPx = 312f, rightPx = 312f, neededPx = 240f))
        // Всего несколько пикселей слева, достаточно справа → Справа
        assertEquals(LetterboxSide.RIGHT, chooseLetterboxSide(leftPx = 8f, rightPx = 312f, neededPx = 240f))
        // Не помещается ни на одну из сторон → не отображается (черная рамка настолько узкая, что ее невозможно отличить
        // от полноэкранного горизонтального экрана)

        assertNull(chooseLetterboxSide(leftPx = 40f, rightPx = 40f, neededPx = 240f))
        // Левая сторона шире, но правой стороны достаточно: все равно отдавайте приоритет левой стороне.
        assertEquals(LetterboxSide.LEFT, chooseLetterboxSide(leftPx = 500f, rightPx = 260f, neededPx = 240f))
    }
}

class LetterboxCardGestureTest {
    @Test
    fun tapOnlyWhenPressStartsAndEndsOnCard() {
        // Пресс падает на карту → карта берет верх
        assertEquals(CardGesture.CONSUME, resolveCardGesture(true, false, true, true))
        // Во время периода нажатия и удержания ход будет проглочен и не будет передан в приложение.
        assertEquals(CardGesture.CONSUME, resolveCardGesture(true, true, true, true))
        // Поднять, пока он находится в карте → считается за одно касание.
        assertEquals(CardGesture.TAP, resolveCardGesture(true, true, false, true))
    }

    @Test
    fun dragOffCardCancelsTapButStillConsumes() {
        // Нажмите, удерживайте и вытащите карту: hit=false при поднятии → проглотите, но не активируйте
        assertEquals(CardGesture.CONSUME, resolveCardGesture(true, true, false, false))
    }

    @Test
    fun passesThroughWhenPressStartsOutsideCard() {
        // Пропущенное нажатие → вернуть нормальную маршрутизацию (вставлено в приложение)
        assertEquals(CardGesture.NOT_HANDLED, resolveCardGesture(true, false, true, false))
        assertEquals(CardGesture.NOT_HANDLED, resolveCardGesture(true, false, false, false))
    }

    @Test
    fun doesNotStealGestureDraggedInFromApp() {
        // Жест приложения выполняется (не на карте при нажатии) и перетаскивается в область карты: перехватить управление невозможно.
        // В противном случае приложение не получит подъема и останется в нажатом состоянии.
        assertEquals(
            CardGesture.NOT_HANDLED,
            resolveCardGesture(true, false, true, true, appGestureActive = true),
        )
    }

    @Test
    fun releasesStuckHoldWhenCardDisappears() {
        // Карта убрана, но жест все еще удерживается (подсказка об обнаружении и очистке): проглотите этот подъем, чтобы
        // не пропустить щелчок приложения.

        assertEquals(CardGesture.CONSUME, resolveCardGesture(false, true, false, false))
    }
}
