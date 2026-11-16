package io.github.shaonialife.cardeck.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Ручка перетаскивания разделенного экрана приборной панели: трехточечная полоса индикатора в среднем шве (три
 * точки расположены вертикально на горизонтальном экране, три точки расположены горизонтально на вертикальном экране).
 *
 * Нажмите и перетащите, чтобы настроить разделение экрана карты/музыкальной карты – перетаскивание только
 * [VirtualLauncher.previewDashSplit]
 * Текстура карты   растянута и визуализирована (не перемещайте виртуальный экран). Только после выпуска
 * [commitDashSplit] можно будет добиться настоящего изменения размера.
 *
 * Жесткие соглашения в два касания (пересмотр плана 21 октября 2026 г.):
 * — требуется DOWN `requestDisallowInterceptTouchEvent(true)`: хост — ViewPager2
 * домашняя страница  , иначе горизонтальное перетаскивание будет убрано пейджером как перелистывание страницы;
 * — rawX/rawY для математики смещения: положение маркера меняется при перетаскивании каждого кадра, а getX
 * является самоссылающимся относительно самого себя.
 * Перемещение пальца за пределы диапазона маркера не влияет на владение событием (Android привязан к приемнику DOWN потоком указателей).
 */
class DashSplitHandle(
    context: Context,
) : View(context) {
    /** true=вертикальный экран (перетащите вверх и вниз, перетащите y); false = горизонтальный экран (перетащите влево и вправо, перетащите x). */
    private var verticalSplit = false

    /** (Центр ручки·пикселя экрана приложения, установлен ли он); обратный вызов должен быть зафиксирован только в том случае, если установлено значение = true. */
    private var onDrag: ((Float, Boolean) -> Unit)? = null

    private var downCenter = 0f
    private var downRaw = 0f
    private var dragging = false

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgRect = RectF()

    fun configure(
        verticalSplit: Boolean,
        onDrag: (Float, Boolean) -> Unit,
    ) {
        this.verticalSplit = verticalSplit
        this.onDrag = onDrag
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val raw = if (verticalSplit) event.rawY else event.rawX
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downRaw = raw
                downCenter = VirtualLauncher.dashHandleCenterScreen()
                dragging = downCenter >= 0
                isPressed = true
                invalidate()
                dragging
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                onDrag?.invoke(downCenter + (raw - downRaw), false)
                true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                if (!dragging) return false
                dragging = false
                isPressed = false
                invalidate()
                // CANCEL также завершается в текущей точке приземления: фиксация идемпотентна и ограничена, а также согласована
                // и предсказуема с поведением UP.

                onDrag?.invoke(downCenter + (raw - downRaw), true)
                true
            }

            else -> false
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        if (isPressed) {
            val inset = 6f * density
            bgRect.set(inset, inset, width - inset, height - inset)
            bgPaint.color = 0x14FFFFFF
            canvas.drawRoundRect(bgRect, bgRect.height() / 2f, bgRect.height() / 2f, bgPaint)
        }
        dotPaint.color = if (isPressed) 0xFFFFFFFF.toInt() else 0x80FFFFFF.toInt()
        val r = 2.5f * density
        val gap = 10f * density
        val cx = width / 2f
        val cy = height / 2f
        // Случайная полоса трехточечного направления: вертикальный экран = горизонтальная полоса → горизонтальные точки;
        // горизонтальный экран = вертикальная полоса → вертикальные точки

        // (Два раунда исправлений 22 октября 2026 г.: первая версия не имеет ветвей и всегда расположена вертикально, а
        // во второй версии ветки перевернуты)

        if (verticalSplit) {
            canvas.drawCircle(cx, cy, r, dotPaint)
            canvas.drawCircle(cx - gap, cy, r, dotPaint)
            canvas.drawCircle(cx + gap, cy, r, dotPaint)
        } else {
            canvas.drawCircle(cx, cy, r, dotPaint)
            canvas.drawCircle(cx, cy - gap, r, dotPaint)
            canvas.drawCircle(cx, cy + gap, r, dotPaint)
        }
    }
}
