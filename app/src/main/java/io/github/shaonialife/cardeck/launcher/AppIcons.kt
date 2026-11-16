package io.github.shaonialife.cardeck.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable

/** Инструмент растрового изображения значка приложения (закругленные углы в стиле iOS; общий для сетки/док-станции/страницы настроек рабочего стола). */
object AppIcons {
    /**
     * Drawable → растровое изображение с закругленными углами; [radiusFraction] — соотношение закругленных углов к
     * длине стороны (около 22%).
     *
     * отображается на копии Drawable (constantState.newDrawable; детали сохранения/восстановления границ):
     * Экземпляры в iconCache       совместно хранятся в нескольких интерфейсах, и прямой setBounds будет загрязнять других пользователей ——
     * Основная причина фактического сбоя машины      : после того, как сетка рабочего стола перезаписывает границы
     * общего Drawable в соответствии с его собственным размером значка,
     * Матрица масштабирования того же экземпляра в доке       не соответствует (значок становится меньше и смещается
     * влево, а затем снова появляется после нажатия кнопки «Домой», чтобы вызвать перепривязку сетки).
     */
    fun rounded(
        drawable: Drawable,
        size: Int,
        radiusFraction: Float = 0.22f,
    ): Bitmap {
        if (size <= 0) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = size * radiusFraction
        val path =
            Path().apply {
                addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), radius, radius, Path.Direction.CW)
            }
        val target =
            runCatching { drawable.constantState?.newDrawable()?.mutate() }
                .getOrNull() ?: drawable
        val savedBounds = Rect(target.bounds)
        canvas.save()
        canvas.clipPath(path)
        target.setBounds(0, 0, size, size)
        target.draw(canvas)
        canvas.restore()
        target.bounds = savedBounds
        return bitmap
    }
}
