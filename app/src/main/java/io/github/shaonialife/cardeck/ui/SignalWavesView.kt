package io.github.shaonialife.cardeck.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import io.github.shaonialife.cardeck.R

/**
 * Визуализация «состояния соединения» карты героя  (восстановление SVG в формате wave of design/phone-ui-proto.html):
 * внешняя пунктирная дорожка + три слоя дуг сигнала Wi-Fi с осью прямо вверху и под углом 45° влево и вправо.
 * три состояния:
 * IDLE статическая серая дуга
 * ПОДКЛЮЧЕНИЕ Импульсное дыхание зеленой дуги (1,5 с, сдвиг фаз) + быстрое орбитальное вращение (2,6 с/оборот)
 * ПОДКЛЮЧЕН Зеленая дуга, медленное дыхание (2,8 с) + медленное орбитальное вращение (9 с/оборот)
 * Координаты эскизного проекта   основаны на размере 210×176 и масштабированы пропорционально фактической ширине.
 */
class SignalWavesView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        enum class State { IDLE, CONNECTING, CONNECTED }

        var state: State = State.IDLE
            private set

        private val density = resources.displayMetrics.density

        private val orbitPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = currentColor(R.color.m3_orbit)
                strokeWidth = 1.6f * density
                pathEffect = DashPathEffect(floatArrayOf(3f * density, 9f * density), 0f)
            }
        private val arcPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }

        // Основа проектного проекта: центр окружности (105,88), трехслойная дуга, радиус 50/68/86, размах -135°..-45°
        // (направление на 12 часов ±45°).

        private val designWidth = 210f
        private val designHeight = 176f
        private val cx = 105f
        private val cy = 88f
        private val radii = floatArrayOf(50f, 68f, 86f)
        private val arcWidths = floatArrayOf(2.6f, 2.4f, 2.4f)

        private var animator: ValueAnimator? = null
        private var orbitRotation = 0f

        fun setState(next: State) {
            if (state == next) return
            state = next
            startAnimation()
            invalidate()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            startAnimation()
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            animator = null
            super.onDetachedFromWindow()
        }

        private fun startAnimation() {
            animator?.cancel()
            orbitRotation = 0f
            val idle = state == State.IDLE
            if (idle) {
                invalidate()
                return
            }
            val period = if (state == State.CONNECTING) 1_500L else 2_800L
            val newAnim =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = period
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { a ->
                        arcPhase = (a.animatedValue as Float) * 2f * Math.PI.toFloat()
                        val playMs = a.currentPlayTime.toLong()
                        val orbitPeriod = if (state == State.CONNECTING) 2_600L else 9_000L
                        orbitRotation = (playMs % orbitPeriod) * 360f / orbitPeriod
                        invalidate()
                    }
                }
            animator = newAnim
            post(newAnim::start)
        }

        private var arcPhase = 0f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Масштабирование по **более короткой стороне**: если карта героя скрыта на горизонтальном экране,
            // масштабирование только по ширине создаст дугу.

            // Вид переполнения (высота всего 70 dp, ширина по-прежнему на половину экрана). После взятия min график всегда
            // вписывается в представление.

            val scale = minOf(width / designWidth, height / designHeight)
            val centerX = width / 2f
            // Вертикально по центру: центр чертежного чертежного круга 88/176 находится точно посередине, и после
            // масштабирования он также будет центрирован.

            val centerY = height / 2f

            canvas.save()
            canvas.rotate(orbitRotation, centerX, centerY)
            canvas.drawCircle(centerX, centerY, radii[2] * scale, orbitPaint)
            canvas.restore()

            val baseColor =
                when (state) {
                    State.IDLE -> currentColor(R.color.m3_wave_idle)
                    State.CONNECTING -> currentColor(R.color.m3_wave_connecting)
                    State.CONNECTED -> currentColor(R.color.m3_wave_connected)
                }
            for (i in radii.indices) {
                // Сдвиг фаз: 0,25 с/слой при подключении, 0,4 с/слой при подключении; IDLE не меняет свет и тьму
                val offset = if (state == State.CONNECTING) i * 0.25f else i * 0.4f
                val alpha =
                    if (state == State.IDLE) {
                        1f
                    } else {
                        0.625f + 0.375f * kotlin.math.sin(arcPhase - offset * 2f * Math.PI.toFloat())
                    }
                arcPaint.color = baseColor
                arcPaint.alpha = (alpha.coerceIn(0.15f, 1f) * 255).toInt()
                arcPaint.strokeWidth = arcWidths[i] * scale
                canvas.drawArc(
                    centerX - radii[i] * scale,
                    centerY - radii[i] * scale,
                    centerX + radii[i] * scale,
                    centerY + radii[i] * scale,
                    -135f,
                    90f,
                    false,
                    arcPaint,
                )
            }
        }

        private fun currentColor(id: Int) =
            androidx.core.content.ContextCompat
                .getColor(context, id)
    }
