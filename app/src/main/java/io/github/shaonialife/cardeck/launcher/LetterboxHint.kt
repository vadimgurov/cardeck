package io.github.shaonialife.cardeck.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Подсказка «Принудительное горизонтальное отображение экрана не действует»: обнаружение + геометрический расчет
 * + подсказка по рисованию карты.
 *
 * Сценарий   (воспроизведен на реальной машине 18 октября 2026 г.): приложение запускается первым, а после
 * подключения к машине и машине пишется am compat для принудительного горизонтального экрана.
 * `am compat` завершает процесс, но когда система возобновляет выполнение задачи, ActivityRecord все еще
 * настроен на старом вертикальном экране.
 * перестраивается (`displayChangedWithoutRestart=true`), поэтому приложение продолжает работать в вертикальном окне почтового ящика.
 * В настоящее время единственным способом восстановления является принудительная остановка и последующий
 * перезапуск: карточка с подсказкой позволяет пользователю завершить ремонт одним щелчком мыши.
 *
 * Все данные обнаружения   поступают от `dumpsys activity activities` (без значения по умолчанию, без оценки):
 * `bounds=Rect(...)` внутреннее окно почтового ящика (фактическое окно приложения) с четырех сторон
 * `mMaxBounds=Rect(...)` Рамка почтового ящика (доступная область виртуального экрана)
 * Разница между   и двумя моделями заключается в черных границах слева и справа (верхняя и нижняя для моделей с
 * вертикальным экраном). Система также выдает `isLetterboxRunning`
 * и `letterboxReason` соответственно только выдают сообщение "почтовый ящик вызван блокировкой ориентации",
 * не нарушает работу приложений, которые пользователи изначально хотят отображать в вертикальных окнах, а также
 * не сообщает ложных пропорций, например почтовый ящик.
 */

/** Простой целочисленный прямоугольник (чтобы избежать пустой реализации android.graphics.Rect в одиночном тесте JVM). */
internal data class PixelBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Факты, связанные с максимальной активностью определенного экрана в `dumpsys activity activities`. */
internal data class LetterboxActivity(
    val packageName: String,
    /** Форма `пакет/класс`, может быть непосредственно `am start -n`. */
    val component: String,
    val displayId: Int,
    val letterboxed: Boolean,
    val letterboxReason: String?,
    /** Примените прямоугольник окна (`bounds=`). */
    val inner: PixelBounds,
    /** Доступная область экрана — прямоугольная (`mMaxBounds=`; если она отсутствует, используйте размер экрана, указанный вызывающим абонентом). */
    val full: PixelBounds,
)

private val displayRegex = Regex("""^Display #(\d+) \(activities""")
private val topActivityRegex =
    Regex("""topResumedActivity=ActivityRecord\{(\w+) u\d+ (\S+)/(\S+) t\d+\}""")
private val historyRegex =
    Regex("""^\s*\* Hist\s+#\d+: ActivityRecord\{(\w+) u\d+ (\S+)/(\S+) t\d+\}""")
private val boundsRegex = Regex("""^\s+bounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)""")
private val maxBoundsRegex = Regex("""mMaxBounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)""")

/**
 * анализирует факт почтового ящика, определяющий активность виртуального экрана верхнего уровня.
 *
 * @param целевой экран displayId
 * @param дамп необработанных выходных данных `dumpsys activity activities`
 * @param запасной вариантW/[fallbackH] `mMaxBounds` Размер экрана при его отсутствии (факт, удерживаемый вызывающим абонентом,
 * не является постоянным значением по умолчанию — левую и правую черные полосы все равно можно рассчитать)
 */
internal fun parseTopLetterboxActivity(
    displayId: Int,
    dump: String,
    fallbackW: Int,
    fallbackH: Int,
): LetterboxActivity? {
    if (dump.isBlank()) return null
    val lines = dump.lineSequence().toList()
    var sectionStart = -1
    var sectionEnd = lines.size
    for (index in lines.indices) {
        val match = displayRegex.find(lines[index]) ?: continue
        if (sectionStart < 0) {
            if (match.groupValues[1].toInt() == displayId) sectionStart = index
        } else {
            sectionEnd = index
            break
        }
    }
    if (sectionStart < 0) return null
    // Берите только целевой сегмент дисплея, чтобы не включать блоки Hist других экранов.
    val section = lines.subList(sectionStart + 1, sectionEnd)
    // Задача верхнего уровня: первая topResumedActivity в сегменте (вывод задач осуществляется сверху вниз)
    var topHash: String? = null
    var topPkg: String? = null
    var topCls: String? = null
    for (line in section) {
        val match = topActivityRegex.find(line) ?: continue
        topHash = match.groupValues[1]
        topPkg = match.groupValues[2]
        topCls = match.groupValues[3]
        break
    }
    // Найдите блок Hist ActivityRecord верхнего уровня: предпочитайте точное совпадение хеша,
    // Совместимо с ПЗУ, когда topResumedActivity опущен, берется первый Hist
    var block = -1
    var header: MatchResult? = null
    for (index in section.indices) {
        val match = historyRegex.find(section[index]) ?: continue
        if (topHash == null || match.groupValues[1] == topHash) {
            block = index
            header = match
            break
        }
    }
    if (block < 0 && topHash == null) {
        for (index in section.indices) {
            val match = historyRegex.find(section[index]) ?: continue
            block = index
            header = match
            break
        }
    }
    if (block < 0) return null
    val pkg = header!!.groupValues[2].ifBlank { topPkg ?: return null }
    val cls = header.groupValues[3].ifBlank { topCls ?: return null }
    if (pkg.isBlank() || cls.isBlank()) return null
    var letterboxed = false
    var reason: String? = null
    var inner: PixelBounds? = null
    var full: PixelBounds? = null
    for (index in block + 1 until section.size) {
        val line = section[index]
        if (historyRegex.containsMatchIn(line) || line.trimStart().startsWith("* Task")) break
        if (line.trimStart().startsWith("Resumed activities")) break
        if (line.contains("isLetterboxRunning=true")) letterboxed = true
        if (reason == null) {
            Regex("""letterboxReason=(\w+)""").find(line)?.let { reason = it.groupValues[1] }
        }
        if (inner == null) {
            boundsRegex.find(line)?.let { match ->
                inner =
                    PixelBounds(
                        match.groupValues[1].toInt(),
                        match.groupValues[2].toInt(),
                        match.groupValues[3].toInt(),
                        match.groupValues[4].toInt(),
                    )
            }
        }
        if (full == null) {
            maxBoundsRegex.find(line)?.let { match ->
                full =
                    PixelBounds(
                        match.groupValues[1].toInt(),
                        match.groupValues[2].toInt(),
                        match.groupValues[3].toInt(),
                        match.groupValues[4].toInt(),
                    )
            }
        }
    }
    val outer = full ?: PixelBounds(0, 0, fallbackW, fallbackH)
    val window = inner ?: outer
    if (outer.width <= 0 || outer.height <= 0) return null
    return LetterboxActivity(
        packageName = pkg,
        component = "$pkg/$cls",
        displayId = displayId,
        letterboxed = letterboxed,
        letterboxReason = reason,
        inner = window,
        full = outer,
    )
}

/**
 * Стоит ли выскакивать подсказка "Принудительное горизонтальное положение экрана не вступает в силу".
 *
 * Одновременно устанавливаются четыре условия: приложение верхнего уровня находится в списке «Прикладная среда»,
 * действительно находится в почтовом ящике,
 * Причина — FIXED_ORIENTATION (блокировка ориентации), и пользователь не отключил этот запрос в этом сеансе.
 */
internal fun shouldShowLetterboxHint(
    activity: LetterboxActivity,
    forceLandscapePackages: Set<String>,
    dismissedPackages: Set<String>,
): Boolean =
    activity.letterboxed &&
        activity.letterboxReason == "FIXED_ORIENTATION" &&
        activity.packageName in forceLandscapePackages &&
        activity.packageName !in dismissedPackages

internal enum class LetterboxSide { LEFT, RIGHT }

/**
 * Выберите, на какую сторону разместить карточку-подсказку: отдайте приоритет левой стороне (возле сиденья
 * водителя) и отступите к правой стороне, если левую сторону разместить невозможно, а правая сторона шире.
 * [neededPx] = минимальная ширина карты + поля с обеих сторон (получено на основе фактического измерения текста, нефиксированный порог).
 * Недостаточно обеих сторон → ноль (черная рамка слишком узкая и больше не отличается от полноэкранного
 * горизонтального экрана, а подсказка будет только сжиматься в шарик).
 */
internal fun chooseLetterboxSide(
    leftPx: Float,
    rightPx: Float,
    neededPx: Float,
): LetterboxSide? =
    when {
        leftPx >= neededPx && leftPx >= rightPx -> LetterboxSide.LEFT
        rightPx >= neededPx -> LetterboxSide.RIGHT
        leftPx >= neededPx -> LetterboxSide.LEFT
        else -> null
    }

internal enum class LetterboxCardState { NORMAL, BUSY, FAILED }

/**
 * Решение жеста  для карты-подсказки (чистая функция, единственная точка принятия решения TouchRouter).
 *
 * Карта   рисуется на черной рамке и никогда не будет внедрена в приложение; после нажатия и удара весь жест
 * является эксклюзивным для карты:
 * — Поднятая точка все еще находится на карточке → считается за один клик;
 * - Вынуть карту и затем поднять ее → Отмена (во избежание случайных касаний);
 * — не пропускайте переходы в приложение, удерживая нажатой кнопку (иначе оно будет перетаскивать список ниже).
 */
internal enum class CardGesture { NOT_HANDLED, CONSUME, TAP }

internal fun resolveCardGesture(
    cardVisible: Boolean,
    holding: Boolean,
    down: Boolean,
    hit: Boolean,
    appGestureActive: Boolean = false,
): CardGesture =
    when {
        // Карта убрана: независимо от того, была ли она нажата ранее, вернитесь к обычной маршрутизации.
        !cardVisible -> if (holding) CardGesture.CONSUME else CardGesture.NOT_HANDLED
        // При нажатии и удержании: все движения в период нажатия будут проглочены; при подъеме щелчок будет определяться
        // тем, находится ли пресс еще в карте.

        holding && down -> CardGesture.CONSUME
        holding -> if (hit) CardGesture.TAP else CardGesture.CONSUME
        // Не нажато: Вступит во владение только «на карте в момент нажатия». Перетащите жест приложения в область
        // карточки во время его выполнения.

        // Не принимать - иначе приложение не получит подъема и останется в нажатом состоянии (зависнет).
        down && hit && !appGestureActive -> CardGesture.CONSUME
        else -> CardGesture.NOT_HANDLED
    }

/**
 * Рисунок подсказки  (растровое изображение за кадром → композитор, когда текстура вставлена в черную рамку).
 *
 * Все размеры   конвертируются в зависимости от плотности холста; **Минимальная ширина измеряется по
 * фактическому тексту** (текст кнопки + отступы),
 * не имеет жестко запрограммированного порогового значения, такого как «черные рамки размером меньше N пикселей
 * не будут отображаться» — они не будут отображаться, если они слишком узкие для размещения текста.
 */
internal object LetterboxCardPainter {
    private const val MAX_WIDTH_DP = 200f
    private const val MARGIN_DP = 6f
    private const val PAD_H_DP = 8f
    private const val PAD_V_DP = 10f
    private const val GAP_DP = 7f

    /** Высота кнопки сходится синхронно с размером шрифта (32dp + 9,5sp сделает кнопку пустой). */
    private const val BUTTON_H_DP = 28f
    private const val RADIUS_DP = 10f
    private const val TITLE_SP = 11.5f
    private const val BODY_SP = 9.5f

    /** Размер шрифта кнопки такой же, как и у основного текста: 11,5sp, что более привлекательно, чем заголовок на реальном устройстве (фактические отзывы пользователей от 18 октября 2026 г.). */
    private const val BUTTON_SP = 9.5f
    private const val BUTTON_PAD_H_DP = 8f

    fun marginPx(density: Float): Float = MARGIN_DP * density

    /** Максимальная ширина карты (независимо от того, насколько широка черная рамка, она не заполнит ее, чтобы она не превратилась в огромный рекламный щит в большой черной рамке). */
    fun maxWidthPx(density: Float): Float = MAX_WIDTH_DP * density

    /** Минимальная ширина карты: определяется измеренной шириной копии кнопки и заголовка. */
    fun minWidthPx(density: Float): Float {
        val button = textPaint(BUTTON_SP * density, Color.WHITE, bold = true)
        val buttonW = button.measureText("принудительный перезапуск") + 2f * BUTTON_PAD_H_DP * density
        val title = textPaint(TITLE_SP * density, Color.WHITE, bold = true)
        // В заголовке должно быть не менее 5 китайских иероглифов (более узкие будут разбиты на отдельные символы и разрывы строк).
        val titleW = title.measureText("Горизонтальный экран не действует")
        return 2f * PAD_H_DP * density + maxOf(buttonW, titleW)
    }

    /**
     * генерирует растровое изображение карточки приглашения; [widthPx] определяется шириной черной границы.
     * Возвращает ноль, если ширина не соответствует тексту или высота не соответствует кнопке (вызывающая сторона не
     * отображается соответствующим образом).
     */
    fun render(
        density: Float,
        widthPx: Int,
        maxHeightPx: Int,
        state: LetterboxCardState,
    ): Bitmap? {
        if (widthPx <= 0 || maxHeightPx <= 0) return null
        val width = widthPx.toFloat()
        val textWidth = width - 2f * PAD_H_DP * density
        if (textWidth <= 0f) return null
        if (width < minWidthPx(density)) return null
        val (titleText, bodyText, buttonText) =
            when (state) {
                LetterboxCardState.NORMAL ->
                    Triple(
                        "Горизонтальный экран не " +
                            "действует",
                        "Все еще вертикальное окно",
                        "принудительный перезапуск",
                    )
                LetterboxCardState.BUSY -> Triple("Перезапуск", "Откроется в альбомной ориентации", "пожалуйста, подождите")
                LetterboxCardState.FAILED -> Triple("Все еще вертикальный экран", "Может не поддерживаться", "Выключено")
            }
        val titlePaint = textPaint(TITLE_SP * density, TITLE_COLOR, bold = true)
        val bodyPaint = textPaint(BODY_SP * density, BODY_COLOR, bold = false)
        val title = staticLayout(titleText, titlePaint, textWidth.toInt())
        val body = staticLayout(bodyText, bodyPaint, textWidth.toInt())
        val height =
            (
                2f * PAD_V_DP * density + title.height + GAP_DP * density +
                    body.height + GAP_DP * density + BUTTON_H_DP * density
            ).toInt()
        if (height > maxHeightPx) return null
        val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = RADIUS_DP * density
        val bounds = RectF(0f, 0f, widthPx.toFloat(), height.toFloat())
        canvas.drawRoundRect(bounds, radius, radius, fillPaint(BG_COLOR))
        canvas.drawRoundRect(bounds, radius, radius, strokePaint(BORDER_COLOR, density))
        var y = PAD_V_DP * density
        canvas.save()
        canvas.translate(PAD_H_DP * density, y)
        title.draw(canvas)
        canvas.restore()
        y += title.height + GAP_DP * density
        canvas.save()
        canvas.translate(PAD_H_DP * density, y)
        body.draw(canvas)
        canvas.restore()
        y += body.height + GAP_DP * density
        val buttonW = width - 2f * PAD_H_DP * density
        val buttonH = BUTTON_H_DP * density
        val buttonRect =
            RectF(PAD_H_DP * density, y, PAD_H_DP * density + buttonW, y + buttonH)
        val enabled = state == LetterboxCardState.NORMAL
        canvas.drawRoundRect(
            buttonRect,
            buttonH / 2f,
            buttonH / 2f,
            fillPaint(if (enabled) ACCENT_COLOR else DISABLED_FILL),
        )
        val buttonPaint =
            textPaint(
                BUTTON_SP * density,
                if (enabled) BUTTON_TEXT_COLOR else DISABLED_TEXT,
                bold = true,
            ).apply {
                // drawText по умолчанию выравнивает по левому краю: если CENTER не установлен, вся строка начинается с
                // buttonRect.centerX(),

                // Кнопка переполнения справа (скриншот реальной машины, 18 октября 2026 г.)
                textAlign = Paint.Align.CENTER
            }
        val metrics = buttonPaint.fontMetrics
        val textY =
            buttonRect.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(buttonText, buttonRect.centerX(), textY, buttonPaint)
        return bitmap
    }

    private fun staticLayout(
        text: String,
        paint: TextPaint,
        width: Int,
    ): StaticLayout =
        StaticLayout
            .Builder
            .obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()

    private fun textPaint(
        sizePx: Float,
        color: Int,
        bold: Boolean,
    ): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    private fun fillPaint(color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }

    private fun strokePaint(
        color: Int,
        density: Float,
    ): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            this.color = color
        }

    // Темная карта + янтарный акцентный цвет, соответствующий темному стилю дока; достаточный контраст на черном крае
    private const val BG_COLOR = 0xF0171C22.toInt()
    private const val BORDER_COLOR = 0x33FFFFFF
    private const val ACCENT_COLOR = 0xFFFFC24B.toInt()
    private const val TITLE_COLOR = 0xFFFFC24B.toInt()
    private const val BODY_COLOR = 0xB3FFFFFF.toInt()
    private const val BUTTON_TEXT_COLOR = 0xFF1B1405.toInt()
    private const val DISABLED_FILL = 0x33FFFFFF
    private const val DISABLED_TEXT = 0xB3FFFFFF.toInt()
}
