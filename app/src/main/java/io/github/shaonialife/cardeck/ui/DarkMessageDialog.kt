package io.github.shaonialife.cardeck.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.github.shaonialife.cardeck.R

/**
 * Всплывающее окно темного сообщения: заголовок + текст (очень длинный и прокручиваемый) + кнопка действия.
 *
 * **Почему бы не использовать чистый `AlertDialog.Builder.setMessage`**: Системное диалоговое окно
 * `Theme.AppCompat.DayNight` имеет светлую тему, которая отделена от полностью темного пользовательского интерфейса этого приложения.
 * (18 октября 2026 г. представлены скриншоты реального устройства). Here is the self-painted `bg_dialog_panel` panel,
 * соответствует двум всплывающим окнам отказа от ответственности/выбора целевого автомобиля.
 *
 * Правила компоновки  : 1-2 операции располагаются горизонтально вправо; 3 и более операций располагаются
 * вертикально по всей ширине (узкие экраны будут перегружены по горизонтали).
 */
object DarkMessageDialog {
    enum class Emphasis {
        /** Solid accent button (bg_cta_fill). */
        PRIMARY,

        /** Текстовая кнопка (bg_tbtn). */
        TEXT,

        /** Dangerous operation (dark red background). */
        DANGER,
    }

    /**
     * @param ignoreOnClick Указывает, следует ли автоматически закрывать окно после щелчка (по умолчанию — true).
     * Need to keep the pop-up window
     * Передайте false, когда       (например, разрешите пользователю повторить попытку после неудачной проверки).
     */
    data class Action(
        val label: String,
        val emphasis: Emphasis = Emphasis.TEXT,
        val dismissOnClick: Boolean = true,
        val onClick: () -> Unit,
    )

    /**
     * @param Действия расположены в порядке отображения; вправо при горизонтальном расположении, сверху вниз при вертикальном расположении
     * @param отменяемый Возврат ключа/точки снаружи, можно ли закрыть
     * @param onCancel Обратный вызов при закрытии посредством отмены (ноль = все равно)
     */
    fun show(
        activity: Activity,
        title: String,
        message: String,
        actions: List<Action>,
        cancelable: Boolean = true,
        onCancel: (() -> Unit)? = null,
    ): AlertDialog {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_message, null)
        val titleView = view.findViewById<TextView>(R.id.msgTitle)
        val bodyView = view.findViewById<TextView>(R.id.msgBody)
        val scroll = view.findViewById<ScrollView>(R.id.msgScroll)
        val actionRow = view.findViewById<LinearLayout>(R.id.msgActions)
        titleView.text = title
        bodyView.text = message

        val density = activity.resources.displayMetrics.density
        val vertical = actions.size > 2
        actionRow.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        actionRow.gravity = if (vertical) Gravity.END else Gravity.END or Gravity.CENTER_VERTICAL

        val dialog =
            AlertDialog
                .Builder(activity)
                .setView(view)
                .create()

        actions.forEachIndexed { index, action ->
            actionRow.addView(
                buildButton(activity, action, density, vertical, isFirst = index == 0) {
                    action.onClick()
                    if (action.dismissOnClick) dialog.dismiss()
                },
            )
        }

        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        if (cancelable) {
            dialog.setOnCancelListener { onCancel?.invoke() }
        }
        dialog.setOnShowListener {
            // Если текст слишком длинный, он обрезается до 46% высоты экрана и прокручивается внутри; короткая копия
            // соответствует содержимому (фиксированная высота оставляет большое пустое пространство).

            // ClampBody также служит обратным вызовом вращения: когда высота экрана по горизонтали становится меньше, его
            // необходимо снова зажать, иначе он вытянется за пределы экрана.

            val clampBody: (Activity) -> Unit = { ctx ->
                val maxBodyHeight = (ctx.resources.displayMetrics.heightPixels * 0.46f).toInt()
                bodyView.post {
                    val target =
                        if (bodyView.height > maxBodyHeight) {
                            maxBodyHeight
                        } else {
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                    if (scroll.layoutParams.height != target) {
                        scroll.layoutParams =
                            scroll.layoutParams.apply { height = target }
                    }
                }
            }
            // Ширина составляет 92%, а высота адаптивна в зависимости от содержимого; при вращении PanelWindow вызовет
            // ClampBody для перерасчета.

            PanelWindow.apply(dialog, activity, onResize = clampBody)
            clampBody(activity)
        }
        dialog.show()
        return dialog
    }

    private fun buildButton(
        activity: Activity,
        action: Action,
        density: Float,
        vertical: Boolean,
        isFirst: Boolean,
        onClick: () -> Unit,
    ): TextView =
        TextView(activity).apply {
            text = action.label
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            val padH = (16 * density).toInt()
            val padV = (10 * density).toInt()
            setPadding(padH, padV, padH, padV)
            when (action.emphasis) {
                Emphasis.PRIMARY -> {
                    background = activity.getDrawable(R.drawable.bg_cta_fill)
                    setTextColor(activity.getColor(R.color.m3_on_primary))
                }

                Emphasis.DANGER -> {
                    // Опасные действия: темно-красный фон + светло-красный текст (повторное использование неправильного цвета контейнера)
                    background =
                        GradientDrawable().apply {
                            cornerRadius = 999f * density
                            setColor(activity.getColor(R.color.m3_err_container))
                        }
                    setTextColor(activity.getColor(R.color.m3_on_err_container))
                }

                Emphasis.TEXT -> {
                    // При вертикальном расположении второстепенные кнопки также находятся в нижней части капсулы: в противном случае
                    // только основная кнопка представляет собой сплошную полноразмерную капсулу.

                    // Два других элемента представляют собой простой текст, а визуальный вес совершенно несбалансирован (открыты
                    // скриншоты реальных машин)

                    background =
                        activity.getDrawable(
                            if (vertical) R.drawable.bg_btn_tonal else R.drawable.bg_tbtn,
                        )
                    setTextColor(
                        activity.getColor(
                            if (vertical) R.color.m3_on_sec_container else R.color.m3_primary,
                        ),
                    )
                }
            }
            layoutParams =
                if (vertical) {
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            // Вертикальное расположение: оставьте расстояние 6 dp между кнопками (не оставляйте места для первой).
                            if (!isFirst) topMargin = (6 * density).toInt()
                        }
                } else {
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            // Горизонтальное расположение: первый элемент прикрепляется к правому краю, оставляя расстояние 6dp между
                            // последующими элементами.

                            if (!isFirst) marginStart = (6 * density).toInt()
                        }
                }
            setOnClickListener {
                // Точка антисоединения: в обратном вызове обычно необходимо перепрыгнуть/перезапустить процесс/записать
                // системные настройки.

                isEnabled = false
                onClick()
            }
        }
}
