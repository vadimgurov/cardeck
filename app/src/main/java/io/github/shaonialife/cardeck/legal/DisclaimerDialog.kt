package io.github.shaonialife.cardeck.legal

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.SessionController
import io.github.shaonialife.cardeck.ui.PanelWindow

/**
 * Всплывающее окно с заявлением об отказе от ответственности (две формы):
 *
 * - **Первый запуск (обязательно)**: нельзя отменить (клавиша возврата/внешний щелчок недействительны),
 * «Принять» должно соответствовать обоим требованиям
 * 10-секундный отсчет и «скатился на дно»; «Отказаться от использования» можно нажать сразу (отказ не должен блокироваться).
 * — **Просмотр меню (только чтение)**: обратный отсчет и прокрутка не требуются, есть кнопка «Закрыть».
 *
 * Обратный отсчет   использует **абсолютный срок** ([SystemClock.elapsedRealtime]) вместо обратного отсчета:
 * Когда активность перестраивается из-за поворота экрана, срок может быть прозрачно передан обратно, чтобы продолжить оставшееся время.
 * не будет ждать еще 10 секунд с начала.
 */
object DisclaimerDialog {
    /** Минимальное пребывание до обязательного принятия. */
    const val COUNTDOWN_MS = 10_000L

    private const val WINDOW_HEIGHT_FRACTION = 0.78f

    /**
     * отображает всплывающее окно.
     *
     * @param requireAcceptance true=принудительное принятие при первом запуске; false = меню только для чтения
     * @param Крайний срокПо истеченииРеального времени крайнего срока обратного отсчета (используется только в обязательной форме)
     * @param onDecline Обратный вызов после нажатия кнопки «Отказаться от использования» (только принудительная форма)
     * @param Обратный вызов onDismiss после закрытия любым способом (Активность используется для очистки ссылок)
     */
    fun show(
        activity: Activity,
        requireAcceptance: Boolean,
        deadlineAt: Long = 0L,
        onDecline: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
    ): AlertDialog {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_disclaimer, null)
        val subtitle = view.findViewById<TextView>(R.id.disclaimerSubtitle)
        val scroll = view.findViewById<ScrollView>(R.id.disclaimerScroll)
        val sections = view.findViewById<LinearLayout>(R.id.disclaimerSections)
        val blockHint = view.findViewById<TextView>(R.id.disclaimerBlockHint)
        val btnDecline = view.findViewById<TextView>(R.id.btnDecline)
        val btnAccept = view.findViewById<TextView>(R.id.btnAccept)

        renderSections(activity, sections)

        if (requireAcceptance) {
            subtitle.text = "Пожалуйста, прочтите его полностью, прежде чем использовать его в первый раз. После " +
                "принятия вы можете просмотреть его в любое время в нижней части основного интерфейса."
        } else {
            subtitle.text = "Вы можете в любой момент вернуться к этому заявлению об отказе от ответственности (текущая " +
                "версия v${DisclaimerPrefs.CURRENT_VERSION})."
            btnDecline.visibility = View.GONE
            btnAccept.isEnabled = true
            btnAccept.text = "Выключено"
            blockHint.visibility = View.GONE
        }

        val dialog =
            AlertDialog
                .Builder(activity)
                .setView(view)
                .create()

        // Принудительный режим: ни клавишу возврата, ни внешние нажатия отключить нельзя
        dialog.setCancelable(!requireAcceptance)
        dialog.setCanceledOnTouchOutside(!requireAcceptance)

        var handler: Handler? = null
        var ticker: Runnable? = null

        if (requireAcceptance) {
            val mainHandler = Handler(Looper.getMainLooper())
            handler = mainHandler
            var scrolledToBottom = false
            SessionController.appendLog(
                "ОТКАЗ ОТ ОТВЕТСТВЕННОСТИ: всплывающее обязательное заявление (обратный отсчет " +
                    "${COUNTDOWN_MS / 1000} секунд + необходимо провести пальцем вниз)" +
                    " remain=${(deadlineAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)}ms",
            )

            fun refresh() {
                // Если макет не завершен, высота и ширина ScrollView равны 0, а «непрокручиваемый» будет считаться «содержимым
                // меньше одного экрана».

                // Таким образом, ScrolledToBottom установлен постоянно (реальная машина показывает, что его можно принять без прокрутки).
                // Прежде чем судить, вы должны дождаться завершения макета: высота>0 засчитывается.
                val layoutReady = scroll.height > 0
                val viewport = (scroll.height - scroll.paddingTop - scroll.paddingBottom).coerceAtLeast(0)
                val contentScrollable = layoutReady && sections.height > viewport
                if (layoutReady && !scroll.canScrollVertically(1)) scrolledToBottom = true
                val now = SystemClock.elapsedRealtime()
                val remainingMs = (deadlineAt - now).coerceAtLeast(0L)
                val remainingSeconds = ((remainingMs + 999L) / 1000L).toInt()
                val countdownDone = remainingMs <= 0L
                val enabled = canAcceptDisclaimer(countdownDone, contentScrollable, scrolledToBottom)
                btnAccept.isEnabled = enabled
                btnAccept.text = if (countdownDone) "принять" else "Принять ($remainingSeconds с)"
                val reason =
                    disclaimerBlockReason(
                        countdownDone = countdownDone,
                        contentScrollable = contentScrollable,
                        scrolledToBottom = scrolledToBottom,
                        remainingSeconds = remainingSeconds,
                    )
                blockHint.text = reason.orEmpty()
                blockHint.visibility = if (reason == null) View.GONE else View.VISIBLE
            }

            val looping =
                object : Runnable {
                    override fun run() {
                        refresh()
                        mainHandler.postDelayed(this, 200L)
                    }
                }
            ticker = looping
            mainHandler.post(looping)

            scroll.setOnScrollChangeListener { _, _, _, _, _ -> refresh() }
            // После завершения макета первого кадра он будет оцениваться повторно (только теперь вы узнаете, превышает ли
            // контент один экран)

            scroll.post { refresh() }

            btnAccept.setOnClickListener {
                if (!btnAccept.isEnabled) return@setOnClickListener
                DisclaimerPrefs.accept(activity)
                dialog.dismiss()
            }
            btnDecline.setOnClickListener {
                dialog.dismiss()
                onDecline?.invoke()
            }
        } else {
            btnAccept.setOnClickListener { dialog.dismiss() }
        }

        // Унифицированное окончание: остановить таймер + уведомить вызывающую сторону о необходимости очистить ссылку
        // (вызывающая сторона, установившая перед этим другой прослушиватель, перезапишет очистку здесь)

        dialog.setOnDismissListener {
            ticker?.let { handler?.removeCallbacks(it) }
            onDismiss?.invoke()
        }

        dialog.setOnShowListener {
            // Ширина 92%, высота 78% высоты экрана; пересчитывается по активности при вращении после регистрации
            PanelWindow.apply(dialog, activity, WINDOW_HEIGHT_FRACTION)
        }
        dialog.show()
        return dialog
    }

    /** Отображение по разделам: жирный заголовок + текст, пробелы между разделами. */
    private fun renderSections(
        activity: Activity,
        container: LinearLayout,
    ) {
        val density = activity.resources.displayMetrics.density
        DisclaimerContent.sections.forEachIndexed { index, section ->
            val title = TextView(activity)
            title.text = section.title
            title.setTextColor(activity.getColor(R.color.m3_primary))
            title.textSize = 13.5f
            title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
            title.setPadding(0, (if (index == 0) 0 else 14 * density).toInt(), 0, (5 * density).toInt())
            container.addView(title)

            val body = TextView(activity)
            body.text = section.body
            body.setTextColor(activity.getColor(R.color.m3_on_var))
            body.textSize = 12.5f
            body.setLineSpacing(4 * density, 1f)
            body.setPadding(0, 0, 0, 0)
            container.addView(
                body,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }
}
