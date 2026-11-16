package io.github.shaonialife.cardeck.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File

/**
 * Хранилище автомобильных обоев: текущие обои = выбранное пользователем изображение (files/wallpaper.jpg) или
 * встроенное изображение по умолчанию.
 * (assets/wallpaper_default.jpg, официальная сжатая версия темных обоев Apple CarPlay).
 *
 * Компоновщик   загружает текстуры GL в виде растровых изображений; перезагружается через уведомление об
 * увеличении [version] после смены обоев.
 */
object WallpaperStore {
    /** Встроенные обои по умолчанию (имя файла в активах). */
    const val DEFAULT_ASSET = "wallpaper_default.jpg"

    private const val USER_FILE = "wallpaper.jpg"

    private val versionCounter =
        java.util.concurrent.atomic
            .AtomicLong(0)

    /** Текущая версия обоев (увеличивается после замены, на основании этого ресивер определяет, нужна ли перезагрузка). */
    val version: Long get() = versionCounter.get()

    // ---- Уведомление об изменении (режим прослушивателя; получатель должен прочитать текущий статус сразу после регистрации) ----

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(Long) -> Unit>()

    /** Зарегистрируйте мониторинг смены обоев (обратный вызов в основном потоке; параметр — номер новой версии). */
    fun addListener(listener: (Long) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Long) -> Unit) {
        listeners.remove(listener)
    }

    fun markChanged() {
        val version = versionCounter.incrementAndGet()
        listeners.forEach { runCatching { it(version) } }
    }

    /** Пользовательский файл обоев (нуль = использовать встроенный файл по умолчанию). */
    fun userWallpaperFile(context: Context): File? = File(context.filesDir, USER_FILE).takeIf { it.exists() && it.length() > 0 }

    /**
     * декодирует текущие обои (вызываются потоком GL; не удается вернуться к встроенным значениям по умолчанию и не
     * удается вернуться к нулю, и синтезатор рисует сплошной цвет).
     * ограничивает длину ребер до [maxSize] (безопасно для памяти; аспектное заполнение отрисовывается компоновщиком пропорционально).
     */
    fun decode(
        context: Context,
        maxSize: Int = 2560,
    ): Bitmap? {
        decodeUser(context, maxSize)?.let { return it }
        return decodeDefault(context, maxSize)
    }

    private fun decodeUser(
        context: Context,
        maxSize: Int,
    ): Bitmap? {
        val file = userWallpaperFile(context) ?: return null
        return runCatching {
            val source = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                if (info.size.width > maxSize || info.size.height > maxSize) {
                    decoder.setTargetSampleSize(
                        maxOf(info.size.width, info.size.height) / maxSize + 1,
                    )
                }
            }
        }.getOrNull()?.takeIf { it.width > 0 && it.height > 0 }
    }

    private fun decodeDefault(
        context: Context,
        maxSize: Int,
    ): Bitmap? =
        runCatching {
            context.assets.open(DEFAULT_ASSET).use { input ->
                val options =
                    BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                        BitmapFactory.decodeStream(input, null, this)
                    }
                context.assets.open(DEFAULT_ASSET).use { stream ->
                    options.inJustDecodeBounds = false
                    options.inSampleSize = sampleSize(options.outWidth, options.outHeight, maxSize)
                    BitmapFactory.decodeStream(stream, null, options)
                }
            }
        }.getOrNull()

    /** Скопируйте выбранное пользователем изображение во внутреннюю память и отметьте изменения (повторное выделение того же изображения также перезапишет его, идемпотентное). */
    fun setFromUri(
        context: Context,
        uri: Uri,
    ): Boolean =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                File(context.filesDir, USER_FILE).outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return false
            markChanged()
            true
        }.getOrDefault(false)

    /** Восстановить встроенные обои по умолчанию. */
    fun resetToDefault(context: Context): Boolean =
        runCatching {
            File(context.filesDir, USER_FILE).delete()
            markChanged()
            true
        }.getOrDefault(false)

    private fun sampleSize(
        width: Int,
        height: Int,
        maxSize: Int,
    ): Int {
        var sample = 1
        while (width / sample > maxSize || height / sample > maxSize) sample *= 2
        return sample
    }

    // ---- Нарезка рабочего стола: растровое изображениеspectFill полнопоточного холста (совместно с GridActivity/DockActivity) ----

    /** Кэш двойного размера (рабочий стол = размер экрана с суперсэмплированием, док-станция = размер потока). */
    private val canvasCache = HashMap<String, Bitmap>()
    private var canvasCacheVersion = -1L

    /** Прослушиватель готовности растрового изображения холста (обратный вызов после первой сборки/изменения версии и перестроения; параметр = номер версии холста). */
    private val canvasReadyListeners = java.util.concurrent.CopyOnWriteArrayList<(Long) -> Unit>()

    fun addCanvasReadyListener(listener: (Long) -> Unit) {
        canvasReadyListeners.add(listener)
    }

    fun removeCanvasReadyListener(listener: (Long) -> Unit) {
        canvasReadyListeners.remove(listener)
    }

    /**
     * использует «полный холст видеопотока» для создания растрового изображения обоев аспектфилла (вызывающая
     * сторона смещает фрагмент в соответствии с его собственным положением в окне).
     * кэшируется по размеру + версии (по одной копии для рабочего стола и дока); вызывается фоновым потоком,
     * возвращаемое растровое изображение доступно только для чтения.
     */
    @Synchronized
    fun aspectFillCanvas(
        context: Context,
        canvasWidth: Int,
        canvasHeight: Int,
    ): Bitmap? {
        if (canvasWidth <= 0 || canvasHeight <= 0) return null
        val key = "${canvasWidth}x$canvasHeight"
        canvasCache[key]?.let { cached ->
            if (canvasCacheVersion == version) return cached
        }
        if (canvasCacheVersion != version) canvasCache.clear()
        val source = decode(context, maxOf(canvasWidth, canvasHeight) * 2) ?: return null
        val bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val scale =
            maxOf(
                canvasWidth.toFloat() / source.width,
                canvasHeight.toFloat() / source.height,
            )
        val drawWidth = source.width * scale
        val drawHeight = source.height * scale
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        // Вы должны использовать «целевой прямоугольник», чтобы перегрузить масштабируемый рисунок — drawBitmap(bmp, x,
        // y, Paint) в соответствии с пикселями исходного изображения.

        // Размер рисунка: если исходное изображение меньше холста (например, фотография на вертикальном экране < ширина
        // холста на горизонтальном экране), оставьте пустым (прозрачный черный) с правой стороны.

        val dest =
            android.graphics.Rect(
                ((canvasWidth - drawWidth) / 2f).toInt(),
                ((canvasHeight - drawHeight) / 2f).toInt(),
                ((canvasWidth - drawWidth) / 2f).toInt() + drawWidth.toInt(),
                ((canvasHeight - drawHeight) / 2f).toInt() + drawHeight.toInt(),
            )
        canvas.drawBitmap(source, null, dest, paint)
        canvasCacheVersion = version
        canvasCache[key] = bitmap
        canvasReadyListeners.forEach { runCatching { it(version) } }
        return bitmap
    }
}
