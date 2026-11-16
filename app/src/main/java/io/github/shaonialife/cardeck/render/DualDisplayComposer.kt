package io.github.shaonialife.cardeck.render

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Маржа карты информационной панели (потоковые пиксели) — стоимость контракта, общая для фрагмента карты и
 * музыкальной карты, указанная одинаково всеми четырьмя сторонами:
 * Этот класс SPLIT рисует прямоугольник, VirtualLauncher создает размер плитки экрана, макет музыкальной карты GridActivity,
 * Плитка TouchRouter касается сенсорного поля. Раньше 16 были определены/записаны в четырех файлах и полагались
 * только на комментарии для формирования альянсов.
 * будет незаметно смещен после всего лишь одного изменения (поле касания/макет карты/прямоугольник рисования
 * непоследовательны, плохой вкус обзора конвергенции P1).
 */
const val CARD_MARGIN_PX = 16

/**
 * Ширина зазора (потоковые пиксели) на разделенном экране приборной панели — **визуальный** зазор между
 * фрагментом карты и музыкальной карточкой, геометрический контракт тот же.
 * [CARD_MARGIN_PX]: край стороны содержимого плитки = центр маркера — DASH_GAP_PX/2, сторона содержимого музыкальной карты
 * Край   = центр дескриптора + DASH_GAP_PX/2 (унифицированное происхождение VirtualLauncher.dashSplitGeom).
 */
const val DASH_GAP_PX = 24f

/**
 * Дескриптор разделения экрана **ширина касания** (пиксели потока): дескриптор ширины просмотра и липкое поле
 * маршрутизации TouchRouter в соответствии с этим значением,
 * шире, чем [DASH_GAP_PX] – переполнение с обеих сторон (TOUCH-GAP)/2 Нажмите на карту, чтобы расширить сенсорную цель
 * (Рычаг со стороны музыкальной карты расположен поверх нее; дополнительная часть со стороны карты покрыта
 * плитками и невидима, но ее можно осязать——
 * Визуальное и сенсорное наложение слоев, 22 октября 2026 г. Отзывы пользователей: решение «средний шов слишком широкий»).
 */
const val DASH_HANDLE_TOUCH_PX = 60f

/**
 * синтезатор GL с двумя виртуальными экранами: док-экран + экран приложения (собственный/с суперсэмплированием,
 * переключение в зависимости от активности) → объединение вывода с входом кодировщика Surface.
 *
 * Трехсторонняя текстура поверхности   используется в качестве вывода каждого виртуального экрана; поток GL
 * опрашивает флаг нового кадра, и составное изображение перерисовывается, когда доступен новый кадр.
 * Область приложения   одновременно отображает **активный** один из двух экранов приложения (переключение
 * [requestActiveApp]; экран супервыборки).
 * подождите, пока он отрисует первый кадр, прежде чем переключиться, чтобы избежать мигания черным цветом).
 * Содержимое двух экранов приложений потребляется постоянно (текстуры поддерживаются в актуальном состоянии).
 * Входная поверхность передается вызывающей стороне через [onSurfacesReady] для создания VirtualDisplay после
 * того, как EGL/текстура готова.
 */
class DualDisplayComposer(
    private val output: Surface,
    private val outWidth: Int,
    private val outHeight: Int,
    /** true: док-станция — это нижняя горизонтальная полоса (вертикальный экран автомобиля); false: док-станция — это левая вертикальная полоса (горизонтальный экран автомобиля). */
    private val dockAtBottom: Boolean = false,
) {
    /** Панель мониторинга ↔ Ход перемещения рабочего стола (0 = плитка на месте, 1 = полностью выдвинута влево), управляемая GridActivity со скольжением. */
    @Volatile
    var tileSlideFraction: Float = 0f

    /**
     * значение покрытия бокового края маркера фрагмента карты (пиксель потока, начало координат сверху;
     * горизонтальный экран = правый край фрагмента x, вертикальный экран = фрагмент
     * нижний край y). Когда >0, значение формулы SPLIT заменяется - записывается VirtualLauncher кадр за кадром при
     * перетаскивании маркера разделения экрана.
     * **Изменяйте только рисунок прямоугольника (растягивайте всю текстуру), а не перемещайте буфер или виртуальный
     * экран**; отпустить и совершить это будет реальностью
     * изменить размер. ≤0 = использовать формулу осевой линии по умолчанию. Семантика такая же, как у
     * [tileSlideFraction]: непрерывное визуальное состояние.
     */
    @Volatile
    private var tileEdgeOverride = -1f

    fun setTileEdgeOverride(edgePx: Float) {
        tileEdgeOverride = edgePx
    }

    /**
     * целевая частота кадров (30/60, верхний предел синтезируемого ритма кадров; настройка «Дисплей» переключается в
     * реальном времени во время работы).
     * регулирует частоту 1000/targetFps при появлении нового входного кадра; неподвижное изображение гарантирует
     * частоту кадров 30 кадров в секунду (в кодировщике не может закончиться питание).
     */
    @Volatile
    var targetFps: Int = 30

    companion object {
        private const val TAG = "DualComposer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val TILE_CORNER_RADIUS = 24f

        /** Единичная текстурная матрица, используемая программами 2D-текстур (растровое изображение имеет вершины, расположенные в целевом направлении). */
        private val IDENTITY_ST =
            floatArrayOf(
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
            )
    }

    private val running = AtomicBoolean(false)
    private var glThread: Thread? = null

    /** Готовая защелка для start(): отпустите после того, как GL доставит входную поверхность (обратите внимание, что вы не можете дождаться выхода цикла композиции). */
    @Volatile
    private var startLatch: CountDownLatch? = null

    /** Создан ли экран приложения суперсэмплинга (определяется на основе входящего размера при запуске). */
    @Volatile
    private var hasHiInput = false

    /** Создается ли ввод экрана карты (определяется на основе входящего размера при запуске). */
    @Volatile
    private var hasMapInput = false

    /** Отображает ли текущая область приложения экран с суперсэмплированием (true) или собственный экран (false). Сенсорная маршрутизация подчиняется этому принципу. */
    @Volatile
    private var activeAppHi = false

    /** Целевой экран для переключения (true=экран суперсэмплинга; null=переключать нечего); вырежьте экран и подождите, пока цель выйдет из кадра, чтобы избежать мигания черных/старых кадров. */
    @Volatile
    private var pendingTargetHi: Boolean? = null

    @Volatile
    private var pendingBaseFrame = 0

    @Volatile
    private var pendingSince = 0L

    /**
     * Карта наложения  (координаты экрана вывода, начало координат вверху). Устанавливается вызывающей стороной
     * после того, как растровое изображение сгенерировано фоновым потоком;
     * Поток       GL отвечает за загрузку текстур и их отрисовку на верхнем слое. При касании используются те же
     * координаты, см. [OverlayCard.contains].
     */
    @Volatile
    var overlayCard: OverlayCard? = null
        set(value) {
            field = value
            overlayDirty = true
        }

    @Volatile
    private var overlayDirty = false

    /** Карта, соответствующая текстуре, загружена (доступна только потоку GL). */
    private var uploadedOverlay: OverlayCard? = null

    /** Текстура наложенной карты (0 = еще не создана, доступна только из потока GL). */
    private var overlayTexture = 0

    /** Ввод с супервыборкой (создание потока GL; безопасно и доступно для чтения другими потоками после возврата start()). */
    @Volatile
    private var appHi: Input? = null

    /** Ввод с экрана собственного приложения (то же, что и выше; requestActiveApp считывает номер кадра в качестве критерия переключения). */
    @Volatile
    private var appInput: Input? = null

    /** Ввод экрана карты (создание потока GL). */
    @Volatile
    private var mapInput: Input? = null

    @Volatile
    var onSurfacesReady: ((dock: Surface, app: Surface, appHi: Surface?, map: Surface?) -> Unit)? = null

    @Throws(IllegalStateException::class)
    fun start(
        dockWidthPx: Int,
        dockHeightPx: Int,
        appWidthPx: Int,
        appHeightPx: Int,
        appHiWidthPx: Int = 0,
        appHiHeightPx: Int = 0,
        mapWidthPx: Int = 0,
        mapHeightPx: Int = 0,
    ) {
        if (running.getAndSet(true)) return
        hasHiInput = appHiWidthPx > 0 && appHiHeightPx > 0
        hasMapInput = mapWidthPx > 0 && mapHeightPx > 0
        val ready = CountDownLatch(1)
        startLatch = ready
        var failure: IllegalStateException? = null
        glThread =
            Thread({
                try {
                    runGl(dockWidthPx, dockHeightPx, appWidthPx, appHeightPx, appHiWidthPx, appHiHeightPx, mapWidthPx, mapHeightPx)
                } catch (error: IllegalStateException) {
                    failure = error
                } finally {
                    ready.countDown()
                }
            }, "dual-composer").apply {
                isDaemon = true
                start()
            }
        ready.await()
        failure?.let {
            running.set(false)
            throw it
        }
    }

    /**
     * Переключить отображение области приложения: false=собственный экран, true=экран суперсэмплинга. Оно вступает в
     * силу после того, как целевой экран выходит из кадра (исходное отображение продолжается в течение этого периода).
     * Экран      ): дождитесь «продвижения номера кадра целевого экрана» (первый кадр нового приложения); 600 мс для
     * сохранения (если целевой экран имеет историческое содержимое)
     * плавное переключение); Тайм-аут 2 с (целевой экран должен быть обрезан, даже если кадр не исчез).
     * всегда является родным экраном, когда нет экрана суперсэмплинга (не создан).
     */
    fun requestActiveApp(hi: Boolean) {
        val target = if (!hasHiInput) false else hi
        if (activeAppHi == target && pendingTargetHi == null) return
        val targetInput = if (target) appHi else appInput
        pendingBaseFrame = targetInput?.frameCount ?: 0
        pendingSince = android.os.SystemClock.uptimeMillis()
        pendingTargetHi = target
        if (!hasHiInput) {
            // Нет экрана передискретизации: не нужно ждать, эффект вступает в силу сразу
            activeAppHi = false
            pendingTargetHi = null
        }
    }

    /** Текущий активный экран в области приложения (на этом должна основываться сенсорная маршрутизация/преобразование координат). */
    fun isActiveAppHi(): Boolean = activeAppHi

    /**
     * Статус макета экрана карты (плитки): СКРЫТ = обычный рабочий стол; SPLIT=центральная зона экрана отбора проб отображается на
     * Левая половина области приложения (центр карты ≈ положение автомобиля, семантика обрезки правильная); ПОЛНЫЙ
     * ЭКРАН = вся область приложения.
     * Экран карты       всегда создается в полноэкранном размере (окно приложения стабильно, задачи не переносятся),
     * разделение/полный экран — это просто выборка геометрии.
     */
    enum class TileLayout { HIDDEN, SPLIT, FULLSCREEN }

    @Volatile
    private var tileLayout = TileLayout.HIDDEN

    @Volatile
    private var lastPortraitTileDiag = 0L

    private fun launcherDashHint(): Boolean =
        try {
            VirtualLauncher.dashboardMode
        } catch (_: Throwable) {
            false
        }

    /** Переключить макет плитки карты. */
    fun setTileLayout(layout: TileLayout) {
        tileLayout = layout
    }

    /**
     * Динамическое изменение размера экрана карты, а затем синхронизация размера потребительского буфера.
     * VirtualDisplay.resize меняет только сторону системы
     * Устройство отображения       — значение defaultBufferSize SurfaceTexture по-прежнему фиксируется в старом
     * размере при создании, и проекция будет
     * Содержимое нового размера отображается в верхней левой области старого буфера (содержимое плитки находится
     * слева, ширина имеет только пропорциональное значение, а правая сторона полностью черная).
     */
    fun resizeMapInput(
        width: Int,
        height: Int,
    ) {
        mapInput?.surfaceTexture?.setDefaultBufferSize(width, height)
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        glThread?.interrupt()
        glThread = null
    }

    private class Input(
        val texture: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface,
    ) {
        @Volatile
        var hasNewFrame = false

        /** Был ли сгенерирован хотя бы один кадр (непроизведенный ввод не будет участвовать в отрисовке, чтобы избежать пустых текстур) */
        @Volatile
        var hasContent = false

        /** Совокупное количество созданных кадров (критерий «пройдено кадров» для активного переключения экрана). */
        @Volatile
        var frameCount = 0
    }

    private fun runGl(
        dockW: Int,
        dockH: Int,
        appW: Int,
        appH: Int,
        hiW: Int,
        hiH: Int,
        mapW: Int,
        mapH: Int,
    ) {
        // ---- ЭГЛ ----
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        checkEgl(display != EGL14.EGL_NO_DISPLAY, "eglGetDisplay")
        checkEgl(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1), "eglInitialize")

        val attribs =
            intArrayOf(
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_ALPHA_SIZE,
                8,
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID,
                1,
                EGL14.EGL_NONE,
            )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        checkEgl(
            EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0,
            "eglChooseConfig",
        )
        val context: EGLContext =
            EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
        checkEgl(context != EGL14.EGL_NO_CONTEXT, "eglCreateContext")
        val outputSurface: EGLSurface =
            EGL14.eglCreateWindowSurface(
                display,
                configs[0],
                output,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
        checkEgl(outputSurface != EGL14.EGL_NO_SURFACE, "eglCreateWindowSurface")
        checkEgl(
            EGL14.eglMakeCurrent(display, outputSurface, outputSurface, context),
            "eglMakeCurrent",
        )

        // ----Входная текстура ----
        fun newInput(
            width: Int,
            height: Int,
        ): Input {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            val st = SurfaceTexture(tex[0])
            st.setDefaultBufferSize(width, height)
            val input = Input(tex[0], st, Surface(st))
            st.setOnFrameAvailableListener { input.hasNewFrame = true }
            return input
        }

        val dock = newInput(dockW, dockH)
        val app = newInput(appW, appH).also { appInput = it }
        val hi = if (hasHiInput) newInput(hiW, hiH).also { appHi = it } else null
        val map = if (hasMapInput) newInput(mapW, mapH).also { mapInput = it } else null

        // ---- Шейдер ----
        val program = buildProgram(oes = true)
        GLES20.glUseProgram(program)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        val uSTMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix")
        val uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        val uClipEnabled = GLES20.glGetUniformLocation(program, "uClipEnabled")
        val uClipRect = GLES20.glGetUniformLocation(program, "uClipRect")
        val uClipRadius = GLES20.glGetUniformLocation(program, "uClipRadius")
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        val matrix = FloatArray(16)

        // ---- Наложение шейдера карты (обычная 2D-текстура; предварительный макет растрового изображения с помощью Canvas) ----
        val overlayProgram = buildProgram(oes = false)
        val oaPos = GLES20.glGetAttribLocation(overlayProgram, "aPos")
        val oaTexCoord = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord")
        val ouSTMatrix = GLES20.glGetUniformLocation(overlayProgram, "uSTMatrix")
        val ouTexture = GLES20.glGetUniformLocation(overlayProgram, "uTexture")

        /** Загрузка/обновление текстур наложенных карт (только поток GL; повторная загрузка при изменении ссылки на карту). */
        fun syncOverlayTexture(): OverlayCard? {
            val card =
                overlayCard ?: run {
                    uploadedOverlay = null
                    return null
                }
            if (card === uploadedOverlay && overlayTexture != 0) return card
            if (overlayTexture == 0) {
                val tex = IntArray(1)
                GLES20.glGenTextures(1, tex, 0)
                overlayTexture = tex[0]
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, card.bitmap, 0)
            uploadedOverlay = card
            return card
        }

        /**
         * рисует наложенные карты. [left]/[top]/[right]/[bottom] — координаты выходного экрана (верхнее начало координат),
         * преобразуется здесь в нижнее начало координат GL; первая строка растрового изображения находится вверху,
         * поэтому ось v текстуры противоположна направлению вершины.
         */
        fun drawOverlay(
            card: OverlayCard,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
        ) {
            GLES20.glUseProgram(overlayProgram)
            GLES20.glUniform1i(ouTexture, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUniformMatrix4fv(ouSTMatrix, 1, false, IDENTITY_ST, 0)

            fun px(v: Float) = v / outWidth * 2f - 1f

            fun py(v: Float) = v / outHeight * 2f - 1f
            val yTop = outHeight - top
            val yBottom = outHeight - bottom
            val positions =
                floatBuffer(
                    px(left),
                    py(yBottom),
                    px(right),
                    py(yBottom),
                    px(left),
                    py(yTop),
                    px(right),
                    py(yTop),
                )
            // Растровое изображение v=0 вверху: v=1 для нижней вершины, v=0 для верхней вершины
            val coords = floatBuffer(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
            GLES20.glVertexAttribPointer(oaPos, 2, GLES20.GL_FLOAT, false, 0, positions)
            GLES20.glEnableVertexAttribArray(oaPos)
            GLES20.glVertexAttribPointer(oaTexCoord, 2, GLES20.GL_FLOAT, false, 0, coords)
            GLES20.glEnableVertexAttribArray(oaTexCoord)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisable(GLES20.GL_BLEND)
            // Восстановить состояние основного шейдера (продолжать использовать программу OES для последующих кадров)
            GLES20.glUseProgram(program)
            GLES20.glUniform1i(uTexture, 0)
        }

        /** [u0]/[u1]: интервал выборки текстур по горизонтали (используется для центральной зоны буфера выборки, когда карта разделена экраном). */
        fun drawInputUV(
            input: Input,
            x0: Float,
            x1: Float,
            y0: Float,
            y1: Float,
            u0: Float = 0f,
            u1: Float = 1f,
            cornerRadius: Float = 0f,
        ) {
            input.surfaceTexture.getTransformMatrix(matrix)
            GLES20.glUniformMatrix4fv(uSTMatrix, 1, false, matrix, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, input.texture)
            // Обрезка закругленных углов: шейдер использует gl_FragCoord (нижнее начало GL) для сравнения с uClipRect,
            // Поэтому координаты вершин передаются напрямую (y этой функции — координата GL). Ранее перевернуто на экранные координаты——
            // Симметричные интервалы полновысотного тайла (горизонтального экрана) совпадают друг с другом и не обнажены;
            // область вершин тайла половинной высоты на вертикальном экране

            // Он вообще не пересекается с областью обрезки, и весь блок отбрасывается (первопричина "карточка карты не видна")
            if (cornerRadius > 0f) {
                GLES20.glUniform1i(uClipEnabled, 1)
                GLES20.glUniform4f(uClipRect, x0, y0, x1, y1)
                GLES20.glUniform1f(uClipRadius, cornerRadius)
            } else {
                GLES20.glUniform1i(uClipEnabled, 0)
            }

            // Координаты пикселей → координаты отсечения (начало GL внизу слева; переворот текстуры обрабатывается
            // uSTMatrix, не переворачивайте повторно)

            fun px(v: Float) = v / outWidth * 2f - 1f

            fun py(v: Float) = v / outHeight * 2f - 1f
            val positions =
                floatBuffer(
                    px(x0),
                    py(y0),
                    px(x1),
                    py(y0),
                    px(x0),
                    py(y1),
                    px(x1),
                    py(y1),
                )
            val coords = floatBuffer(u0, 0f, u1, 0f, u0, 1f, u1, 1f)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, positions)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, coords)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun drawInput(
            input: Input,
            x0: Float,
            x1: Float,
            y0: Float,
            y1: Float,
        ) = drawInputUV(input, x0, x1, y0, y1, u0 = 0f, u1 = 1f)

        fun composeFrame(fullRedraw: Boolean) {
            // Экран приложения, который нужно вырезать: вырезается, как только целевой экран выходит за пределы кадра (номер
            // кадра увеличивается / существующий контент резервное копирование 600 мс / резервное копирование по тайм-ауту 2 с)

            pendingTargetHi?.let { targetHi ->
                val nowMs = android.os.SystemClock.uptimeMillis()
                val target = if (targetHi) hi else app
                val advanced = target != null && target.frameCount > pendingBaseFrame
                if (advanced ||
                    (target?.hasContent == true && nowMs - pendingSince >= 600L) ||
                    nowMs - pendingSince >= 2_000L
                ) {
                    activeAppHi = targetHi
                    pendingTargetHi = null
                }
            }
            GLES20.glViewport(0, 0, outWidth, outHeight)
            GLES20.glClearColor(0.04f, 0.07f, 0.09f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            // updateTexImage только для входных данных с новыми кадрами; входные данные с существующим содержимым наследуют
            // старую текстуру (постоянство изображения).

            // Оба экрана приложений продолжают потреблять (неактивный экран тоже остается актуальным, а старые кадры при
            // переключении не мигают).

            if (dock.hasNewFrame) {
                dock.hasNewFrame = false
                dock.hasContent = true
                dock.frameCount++
                dock.surfaceTexture.updateTexImage()
            }
            if (app.hasNewFrame) {
                app.hasNewFrame = false
                app.hasContent = true
                app.frameCount++
                app.surfaceTexture.updateTexImage()
            }
            hi?.let {
                if (it.hasNewFrame) {
                    it.hasNewFrame = false
                    it.hasContent = true
                    it.frameCount++
                    it.surfaceTexture.updateTexImage()
                }
            }
            map?.let {
                if (it.hasNewFrame) {
                    it.hasNewFrame = false
                    it.hasContent = true
                    it.frameCount++
                    it.surfaceTexture.updateTexImage()
                }
            }
            val activeInput = if (activeAppHi) hi else app
            if (dockAtBottom) {
                // Вертикальный экран: горизонтальная полоса дока прикреплена снизу (координата GL y=0 — нижний край), а
                // приложение находится в оставшейся области;

                // На приборной панели карточка с картой расположена в верхней половине области приложения (поверх карты/под
                // музыкой), а область приложения закрывает весь экран.

                if (dock.hasContent) drawInput(dock, 0f, outWidth.toFloat(), 0f, dockH.toFloat())
                if (activeInput?.hasContent == true) {
                    drawInput(activeInput, 0f, outWidth.toFloat(), dockH.toFloat(), outHeight.toFloat())
                }
                val layoutP = tileLayout
                if (map?.hasContent == true && layoutP != TileLayout.HIDDEN) {
                    if (layoutP == TileLayout.FULLSCREEN) {
                        drawInput(map!!, 0f, outWidth.toFloat(), dockH.toFloat(), outHeight.toFloat())
                    } else {
                        // Верхняя половина карты: 16 слева и 16 сверху; пейджер скользит горизонтально → плитка скользит влево (вслед за
                        // рукой)

                        // tileEdgeOverride = боковой край маркера плитки (верхнее начало y, то есть нижний край плитки);
                        // GL y = outHeight — Top y (преобразование нижнего начала координат, не переворачивать — см. аннотацию класса
                        // для соглашения о системе координат)

                        val tileH = (outHeight - dockH) / 2f - CARD_MARGIN_PX * 1.5f
                        val handleEdge =
                            if (tileEdgeOverride > 0f) tileEdgeOverride else CARD_MARGIN_PX + tileH
                        val slide = tileSlideFraction * outWidth
                        val nowMs = android.os.SystemClock.uptimeMillis()
                        if (nowMs - lastPortraitTileDiag > 5_000) {
                            lastPortraitTileDiag = nowMs
                            VirtualLauncher.logSink?.invoke(
                                "КОМПОЗИТОР: Рисунок тайла вертикального экрана tileH=$tileH handleEdge=$handleEdge" +
                                    "slide=$slide frames=${map!!.frameCount}",
                            )
                        }
                        drawInputUV(
                            map!!,
                            CARD_MARGIN_PX - slide,
                            outWidth - CARD_MARGIN_PX - slide,
                            outHeight - handleEdge,
                            (outHeight - CARD_MARGIN_PX).toFloat(),
                            cornerRadius = TILE_CORNER_RADIUS,
                        )
                    }
                } else {
                    // Вертикальная плитка экрана не рисуется: выборка состояния регулирования (5 с), устранение неполадок «карточка
                    // карты не видна»

                    val nowMs = android.os.SystemClock.uptimeMillis()
                    if (nowMs - lastPortraitTileDiag > 5_000) {
                        lastPortraitTileDiag = nowMs
                        VirtualLauncher.logSink?.invoke(
                            "КОМПОЗИТОР: Вертикальная плитка экрана не рисуется макет = $layoutP" +
                                "mapInput=${map != null} hasContent=${map?.hasContent} " +
                                "frames=${map?.frameCount} dash=${launcherDashHint()}",
                        )
                    }
                }
            } else {
                if (activeInput?.hasContent == true) {
                    drawInput(activeInput, dockW.toFloat(), outWidth.toFloat(), 0f, outHeight.toFloat())
                }
                // Карта накладывается поверх области применения (СКРЫТО не отрисовывается):
                //  ПОЛНЫЙ ЭКРАН = вся область приложения; SPLIT = левая половина области карты.
                // Размер экрана карты динамически изменяется в соответствии с макетом с помощью VirtualLauncher (разделенный
                // экран = размер отображения плитки,

                //  Полный экран = размер области приложения), отображение карты приложения на экране — оба макета заполнены
                //  Отображение 1:1 (старая схема «полноразмерный экран + выборка кадра по центральной полосе» была удалена).
                val layout = tileLayout
                if (map?.hasContent == true && layout != TileLayout.HIDDEN) {
                    if (layout == TileLayout.FULLSCREEN) {
                        drawInput(map!!, dockW.toFloat(), outWidth.toFloat(), 0f, outHeight.toFloat())
                    } else {
                        // Форма карты: оставьте края по всему периметру + закруглите углы (как у музыкальных карт).
                        // Значение формулы подстановки tileEdgeOverride(правый край плитки x) при перетаскивании маркера разделенного
                        // экрана:

                        // Измените только рисунок прямоугольников и растяните всю текстуру (не перемещая буфер/виртуальный экран), а
                        // затем измените размер, когда отпустите.

                        // tileSlideFraction: переместите плитку влево/влево по мере скольжения пейджера (визуальное сопровождение).
                        val half = (outWidth - dockW) / 2f
                        val tileW = half - CARD_MARGIN_PX - CARD_MARGIN_PX / 2f
                        val x1 =
                            if (tileEdgeOverride > 0f) {
                                tileEdgeOverride
                            } else {
                                dockW + CARD_MARGIN_PX + tileW
                            }
                        val slide = tileSlideFraction * (outWidth - dockW)
                        drawInputUV(
                            map!!,
                            dockW + CARD_MARGIN_PX - slide,
                            x1 - slide,
                            CARD_MARGIN_PX.toFloat(),
                            (outHeight - CARD_MARGIN_PX).toFloat(),
                            cornerRadius = TILE_CORNER_RADIUS,
                        )
                    }
                }
                // Док рисуется последним (верхний слой): плитка проходит под областью дока, скользя через нее.
                if (dock.hasContent) drawInput(dock, 0f, dockW.toFloat(), 0f, outHeight.toFloat())
            }
            // Наложенная карта рисуется последней (верхний слой, на который не влияют ветки): координаты карты — это
            // координаты выходного экрана (верхнее начало координат).

            syncOverlayTexture()?.let { card ->
                drawOverlay(card, card.left, card.top, card.right, card.bottom)
            }
            if (fullRedraw ||
                overlayDirty ||
                dock.hasContent ||
                app.hasContent ||
                hi?.hasContent == true ||
                map?.hasContent == true
            ) {
                EGL14.eglSwapBuffers(display, outputSurface)
            }
            overlayDirty = false
        }

        // Исходный черный кадр (только чистый экран, не трогайте пустую текстуру) + доставьте ввод на поверхность + отпустите start()
        GLES20.glViewport(0, 0, outWidth, outHeight)
        GLES20.glClearColor(0.04f, 0.07f, 0.09f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(display, outputSurface)
        onSurfacesReady?.invoke(dock.surface, app.surface, hi?.surface, map?.surface)
        startLatch?.countDown()

        // ---- Синтетический цикл ----
        // Ввод новых кадров: дросселирование композиции с целевой частотой кадров (1000/targetFps).
        // Нет нового кадра (интерфейс остается неизменным, например, панель запуска/страница чтения/док-карта):
        // содержимое последнего кадра многократно синтезируется с тактовым сигналом 33 мс——

        // В противном случае кодер не получает входных данных, видеопоток останавливается, а принимающий конечный экран
        // зависает (отображается как «Рабочий стол зависает/неинтерактивен»).

        // 09.10.2026 Фактический тест: касание действительно было отправлено, но не было кадра, отражающего это).
        try {
            var lastComposeAt = System.currentTimeMillis()
            while (running.get()) {
                // Быстрая полоса (регулирование 60 кадров в секунду) управляется только входными данными, которые будут
                // отображаться на экране вывода:

                // док-станция + текущий активный экран приложения (выберите один из приложений/привет) + экран карты, когда
                // отображается плитка.

                // Amap использует экран карты, а приложения с вертикальным экраном используют экран суперсэмплинга — просто
                // глядя на док-станцию или собственный экран, вы их не увидите.

                // Файл со скоростью 60 кадров в секунду блокируется на скорости 30 кадров в секунду с пульсом покоя 33 мс.
                // Новый кадр неактивного экрана не учитывается при определении (экран его не содержит): кадр все равно будет в composeFrame
                // Непрерывное использование (текстуры поддерживаются в актуальном состоянии, чтобы избежать отставания в очереди
                // SurfaceTexture и блокировки фоновых приложений),

                // Но не тратьте ресурсы графического процессора и пропускную способность на невидимый контент.
                val hasNew =
                    overlayDirty ||
                        dock.hasNewFrame ||
                        (if (activeAppHi) appHi else app)?.hasNewFrame == true ||
                        (tileLayout != TileLayout.HIDDEN && mapInput?.hasNewFrame == true)
                val now = System.currentTimeMillis()
                val intervalMs = if (hasNew) 1000L / targetFps.coerceIn(1, 120) else 33L
                if (now - lastComposeAt >= intervalMs) {
                    composeFrame(fullRedraw = false)
                    lastComposeAt = now
                } else {
                    Thread.sleep(8)
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            if (overlayTexture != 0) {
                runCatching { GLES20.glDeleteTextures(1, intArrayOf(overlayTexture), 0) }
                overlayTexture = 0
            }
            runCatching { dock.surface.release() }
            runCatching { app.surface.release() }
            runCatching { dock.surfaceTexture.release() }
            runCatching { app.surfaceTexture.release() }
            map?.let {
                runCatching { it.surface.release() }
                runCatching { it.surfaceTexture.release() }
            }
            hi?.let {
                runCatching { it.surface.release() }
                runCatching { it.surfaceTexture.release() }
            }
            runCatching {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
            }
            runCatching { EGL14.eglDestroySurface(display, outputSurface) }
            runCatching { EGL14.eglDestroyContext(display, context) }
            runCatching { EGL14.eglTerminate(display) }
        }
    }

    /** [oes]=true: выборка внешней текстуры (SurfaceTexture); false: обычная 2D-сэмплинг (растровое изображение обоев). */
    private fun buildProgram(oes: Boolean): Int {
        val fragment =
            if (oes) {
                """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES uTexture;
                uniform int uClipEnabled;
                uniform vec4 uClipRect;
                uniform float uClipRadius;
                void main() {
                    if (uClipEnabled == 1) {
                        vec2 p = gl_FragCoord.xy;
                        vec2 half2 = vec2(uClipRadius);
                        vec2 c = clamp(p, uClipRect.xy + half2, uClipRect.zw - half2);
                        if (distance(p, c) > uClipRadius) discard;
                    }
                    gl_FragColor = texture2D(uTexture, vTexCoord);
                }
                """.trimIndent()
            } else {
                """
                precision mediump float;
                varying vec2 vTexCoord;
                uniform sampler2D uTexture;
                void main() {
                    gl_FragColor = texture2D(uTexture, vTexCoord);
                }
                """.trimIndent()
            }
        val vertex =
            """
            attribute vec2 aPos;
            attribute vec2 aTexCoord;
            uniform mat4 uSTMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
            """.trimIndent()

        fun compile(
            type: Int,
            source: String,
        ): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            // Сбой компиляции должен оставить следы и выдать: продолжить молча = черный экран и нет возможности устранить
            // неполадки (в EGL есть checkEgl, в GLSL этот слой раньше отсутствовал)

            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e(TAG, "Ошибка компиляции GLSL (тип = $type): ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("GLSL compile type=$type")
            }
            return shader
        }

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(program)
        return program
    }

    private fun floatBuffer(vararg values: Float): FloatBuffer =
        ByteBuffer
            .allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    private fun checkEgl(
        ok: Boolean,
        stage: String,
    ) {
        if (!ok) {
            Log.e(TAG, "EGL не удалось: $stage")
            throw IllegalStateException("EGL $stage")
        }
    }
}
