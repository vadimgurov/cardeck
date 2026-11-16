package io.github.shaonialife.cardeck.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.github.shaonialife.cardeck.util.DebugFileLog

/**
 * Перетаскиваемое плавающее окно подсказки отладки (TYPE_APPLICATION_OVERLAY, разрешения доступны для темного экрана).
 *
 * Операция основного потока (Handler(main)); текст + дополнительная кнопка (например, «Установить»/«Продолжить отладку»);
 * По умолчанию   автоматически исчезнет через несколько секунд. Когда кнопка удерживается, она останется там до
 * тех пор, пока не будет нажата. Перетащите, чтобы изменить координаты WindowManager.
 */
object BridgeOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var overlay: View? = null

    fun show(
        context: Context?,
        text: String,
        buttonText: String? = null,
        dismissMillis: Long = 8_000,
        onClick: (() -> Unit)? = null,
    ) {
        val ctx = context?.applicationContext ?: return
        main.post {
            if (!android.provider.Settings.canDrawOverlays(ctx)) {
                DebugFileLog.append(ctx, "МОСТ: нет разрешения на плавающее окно, запрос на отмену: $text")
                return@post
            }
            hideLocked()
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val row =
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background =
                        GradientDrawable().apply {
                            cornerRadius = 28f
                            setColor(0xE6141416.toInt())
                            setStroke(2, 0x663DD6C4)
                        }
                    setPadding(40, 24, 40, 24)
                }
            val label =
                TextView(ctx).apply {
                    this.text = text
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    maxWidth = 720
                }
            row.addView(label)
            if (buttonText != null) {
                val button =
                    TextView(ctx).apply {
                        this.text = buttonText
                        setTextColor(0xFF3DD6C4.toInt())
                        textSize = 15f
                        setPadding(32, 12, 8, 12)
                        setOnClickListener {
                            hideLocked()
                            onClick?.invoke()
                        }
                    }
                row.addView(button)
            }

            val params =
                WindowManager
                    .LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT,
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = 80
                        y = 260
                    }
            attachDrag(row, params, wm)
            runCatching {
                wm.addView(row, params)
                overlay = row
            }
            if (buttonText == null && dismissMillis > 0) {
                main.postDelayed({
                    if (overlay === row) hideLocked()
                }, dismissMillis)
            }
        }
    }

    private fun hideLocked() {
        val view = overlay ?: return
        overlay = null
        val ctx = view.context
        runCatching {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(
        view: View,
        params: WindowManager.LayoutParams,
        wm: WindowManager,
    ) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (moved || dx * dx + dy * dy > 100) {
                        moved = true
                        params.x = startX + dx
                        params.y = startY + dy
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                    true
                }

                else -> false
            }
        }
    }
}
