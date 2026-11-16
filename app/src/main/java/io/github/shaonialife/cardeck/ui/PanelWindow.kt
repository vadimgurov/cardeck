package io.github.shaonialife.cardeck.ui

import android.app.Activity
import android.app.Dialog
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import java.lang.ref.WeakReference

/**
 * Размер всплывающего окна саморисующейся панели унифицирован: ширина фиксирована на уровне 92% ширины экрана, а
 * высоте можно задать пропорцию.
 *
 * **Необходимо пересчитать после поворота**: эти размеры рассчитываются один раз в соответствии с текущей
 * ориентацией экрана, когда отображается всплывающее окно, и ориентация изменилась.
 * сохранит ширину экрана по вертикали (только среднюю половину экрана по горизонтали) или превысит ширину экрана без перерасчета.
 * Тот, кто показан, зарегистрирован [register] (слабая ссылка, не содержит утечек; неверные записи удаляются при следующем пересчете).
 */
object PanelWindow {
    /** Отношение ширины панели к ширине экрана (в соответствии с выбором целевого автомобиля и машины и отказом от ответственности). */
    const val WIDTH_FRACTION = 0.92f

    private data class Entry(
        val dialog: WeakReference<AlertDialog>,
        /** null = WRAP_CONTENT (короткое всплывающее окно соответствует содержимому). */
        val heightFraction: Float?,
        /** Дополнительный пересчет после поворота (например, область прокрутки текста перезажимается под новую высоту экрана). */
        val onResize: ((Activity) -> Unit)? = null,
    )

    private val showing = mutableListOf<Entry>()

    /**
     * устанавливает размер окна и регистрирует его (для унифицированного пересчета с помощью onConfigurationChanged действия).
     *
     * @param heightFraction null = WRAP_CONTENT
     * @param onResize Дополнительный обратный вызов после поворота (в этот момент размер окна был изменен в новом направлении)
     */
    fun apply(
        dialog: AlertDialog,
        activity: Activity,
        heightFraction: Float? = null,
        onResize: ((Activity) -> Unit)? = null,
    ) {
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        sizeTo(dialog, activity, heightFraction)
        register(dialog, heightFraction, onResize)
    }

    /** Пересчитать все всплывающие окна, которые все еще отображаются после изменения ориентации/размера экрана. */
    fun resizeShowing(activity: Activity) {
        showing.removeAll { it.dialog.get()?.isShowing != true }
        showing.forEach { entry ->
            val dialog = entry.dialog.get() ?: return@forEach
            sizeTo(dialog, activity, entry.heightFraction)
            entry.onResize?.invoke(activity)
        }
    }

    private fun register(
        dialog: AlertDialog,
        heightFraction: Float?,
        onResize: ((Activity) -> Unit)?,
    ) {
        showing.removeAll { it.dialog.get() == null || it.dialog.get() == dialog }
        showing.add(Entry(WeakReference(dialog), heightFraction, onResize))
    }

    private fun sizeTo(
        dialog: Dialog,
        activity: Activity,
        heightFraction: Float?,
    ) {
        val metrics = activity.resources.displayMetrics
        val width = (metrics.widthPixels * WIDTH_FRACTION).toInt()
        val height =
            if (heightFraction == null) {
                ViewGroup.LayoutParams.WRAP_CONTENT
            } else {
                (metrics.heightPixels * heightFraction).toInt()
            }
        dialog.window?.setLayout(width, height)
    }
}
