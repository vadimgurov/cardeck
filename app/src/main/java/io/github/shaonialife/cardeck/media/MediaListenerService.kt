package io.github.shaonialife.cardeck.media

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import io.github.shaonialife.cardeck.launcher.VirtualLauncher

/**
 * Служба прослушивания уведомлений: используйте права на использование уведомлений для перечисления активных
 * сеансов мультимедиа в системе и предоставления статуса «сейчас воспроизводится» приложениям.
 * (название песни/исполнитель/сейчас играет). Музыкальные карточки (панели мониторинга) отображаются и управляются соответствующим образом.
 *
 * **Почему должны быть права на уведомления**: `MediaSessionManager.getActiveSessions()`, когда нет авторизации
 * все равно вернется в сессию (чтобы можно было получить статус воспроизведения), но `metadata` всегда имеет
 * значение null - это проявляется как "статус воспроизведения меняется,
 * Название песни и исполнитель всегда пусты». Эта комбинация, скорее всего, будет ошибочно воспринята как ошибка
 * синтаксического анализа, но на самом деле разрешения не предоставляются (фактическое тестирование состоялось 18 октября 2026 г.).
 *
 * Путь авторизации  : системные настройки «права на использование уведомлений» или выполнение Shizuku (оболочки).
 * `cmd notification allow_listener <Этот компонент>` (см. [NotificationAccess]).
 */
class MediaListenerService : NotificationListenerService() {
    companion object {
        fun componentName(context: android.content.Context): ComponentName = ComponentName(context, MediaListenerService::class.java)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Соединение означает, что система распознает права использования уведомлений этого приложения (только тогда
        // метаданные будут доступны для чтения).

        // Оба статуса установлены: NotificationAccess используется для определения того, «хотите ли вы повторить попытку авторизации».
        // MediaSessionsState используется копирайтером пользовательского интерфейса, чтобы различать «не
        // воспроизводится» и «невозможно прочитать информацию».

        NotificationAccess.markGranted()
        NotificationAccess.markConnected()
        MediaSessionsState.accessGranted = true
        try {
            manager.addOnActiveSessionsChangedListener(
                sessionListener,
                componentName(this),
            )
            MediaSessionsState.resyncHook = { mainHandler.post { refresh() } }
            update(manager.getActiveSessions(componentName(this)))
            mainHandler.post(periodicRefresh)
        } catch (_: SecurityException) {
            // Уведомление об отзыве прав использования: тихое (система перепривяжет сервис после авторизации)
            MediaSessionsState.accessGranted = false
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        MediaSessionsState.accessGranted = false
        NotificationAccess.markDisconnected()
        // Должно быть очищено: в противном случае на карте сохранится название песни/статус воспроизведения до
        // отключения, и появится сообщение «Воспроизводится + Не воспроизводится».

        // Даже противоречивое отображение «Воспроизведение + невозможно прочитать информацию о воспроизведении»
        // (фактическое измерение 19 октября 2026 г.:

        // После переустановки слушатель не был привязан к системе и карта долго оставалась в старом состоянии).
        MediaSessionsState.publish(null)
        watched.clear()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(periodicRefresh)
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionListener) }
        MediaSessionsState.publish(null)
        super.onDestroy()
    }

    private val manager by lazy {
        getSystemService(android.content.Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    private val sessionListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            update(controllers ?: emptyList())
        }

    /** Сеансы, в которых зарегистрированы обратные вызовы воспроизведения (во избежание дублирования регистрации). */
    private val watched = HashSet<android.media.session.MediaSession.Token>()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Последовательный опрос: некоторые игроки не запускают обратные вызовы/обновление информации о гарантийной карте, когда обратные вызовы потеряны. */
    private val periodicRefresh =
        object : Runnable {
            override fun run() {
                refresh()
                mainHandler.postDelayed(this, 1_000)
            }
        }

    /** Последний ключ извлечения обложки (сессия + название песни + uri обложки) — getBitmap десериализуется каждый раз, и опрос 1s не может быть повторен. */
    private var lastArtKey: String? = null

    /** Кэш растрового изображения обложки (повторное использование одного и того же экземпляра, когда ключ остается неизменным, что обеспечивает стабильность определения равенства NowPlaying). */
    private var cachedArt: android.graphics.Bitmap? = null

    /** В прошлый раз "ключ совпал, но обложка была " +
     "пуста": метаданные в момент переключения песен часто были неполными (сначала шло название песни,
     */
    private var artEmptyAt = 0L

    /** Выделенный одиночный поток для декодирования URI покрытия (последовательный; основной поток не может выполнять ввод/декодирование). */
    private val artExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "media-art").apply { isDaemon = true }
        }

    private fun update(controllers: List<android.media.session.MediaController>) {
        controllers.forEach { controller ->
            if (watched.add(controller.sessionToken)) {
                controller.registerCallback(
                    object : android.media.session.MediaController.Callback() {
                        override fun onPlaybackStateChanged(state: PlaybackState?) {
                            refresh()
                        }

                        override fun onMetadataChanged(metadata: MediaMetadata?) {
                            refresh()
                        }
                    },
                    android.os.Handler(android.os.Looper.getMainLooper()),
                )
            }
        }
        refresh()
    }

    /** Извлеките состояние воспроизведения из активных сеансов: установите приоритет сеанса PLAYING, в противном случае сохраните первый сеанс с метаданными. */
    private fun refresh() {
        val controllers =
            try {
                manager.getActiveSessions(componentName(this))
            } catch (_: SecurityException) {
                emptyList()
            }
        val picked =
            controllers.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: controllers.firstOrNull { it.metadata != null }
        val now =
            picked?.let { controller ->
                val meta = controller.metadata
                val state = controller.playbackState
                val playing = state?.state == PlaybackState.STATE_PLAYING
                val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                MediaSessionsState.NowPlaying(
                    title = title,
                    artist =
                        meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                    playing = playing,
                    art = artFor(controller, meta, title),
                    pkg = controller.packageName,
                    positionMs = state?.position ?: 0L,
                    playbackSpeed = if (playing) (state?.playbackSpeed ?: 1f) else 0f,
                    durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                    anchoredAtMs = android.os.SystemClock.elapsedRealtime(),
                )
            }
        MediaSessionsState.publish(now)
    }

    /**
     * Извлечение обложки  (с кешем): повторное извлечение только при изменении «сессии + названия песни + URI
     * обложки» — растровый ключ извлекается непосредственно из
     * Получены метаданные      ; обложка типа uri (content:// и т. д.) декодируется фоновым потоком, а состояние
     * обложки передается до завершения.
     */
    private fun artFor(
        controller: android.media.session.MediaController,
        meta: MediaMetadata?,
        title: String?,
    ): android.graphics.Bitmap? {
        val artUri =
            meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ART_URI)
        val key = "${controller.sessionToken}/$title/$artUri"
        val now = android.os.SystemClock.elapsedRealtime()
        if (key == lastArtKey) {
            if (cachedArt != null) return cachedArt
            // Ключ не изменился, но обложка пуста: нет повторной попытки в течение 2 секунд (getBitmap требует оплаты),
            // получение после таймаута——

            // Приложение использует этот метод для добавления каверов после вырезания песен (растровый ключ создается с нуля).
            if (now - artEmptyAt < 2_000) return null
        }
        lastArtKey = key
        cachedArt = null
        val direct =
            meta
                ?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (direct != null) {
            cachedArt = scaleArt(direct)
            return cachedArt
        }
        artEmptyAt = now
        if (artUri != null) {
            // Декодирование в выделенном потоке; нет активного обновления после готовности — периодическое обновление в
            // следующем раунде (≤1 с)

            artExecutor.execute {
                val bmp =
                    runCatching {
                        val input =
                            if (artUri.startsWith("http")) {
                                java.net.URL(artUri).openStream()
                            } else {
                                contentResolver.openInputStream(android.net.Uri.parse(artUri))
                            }
                        input?.use { android.graphics.BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                if (bmp != null && key == lastArtKey) {
                    cachedArt = scaleArt(bmp)
                }
            }
        }
        return null
    }

    /** Уменьшите обложку до размера ≤512 пикселей (отображение карты ~120 пикселей, контрольная память). */
    private fun scaleArt(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= 512) return src
        val sample =
            generateSequence(1) { it * 2 }.first { maxSide / it <= 512 }
        return android.graphics.Bitmap
            .createScaledBitmap(src, src.width / sample, src.height / sample, true)
    }
}

/** Глобальный статус «воспроизведения» (общий в этом процессе; музыкальная карта GridActivity отслеживает рендеринг). */
object MediaSessionsState {
    data class NowPlaying(
        val title: String?,
        val artist: String?,
        val playing: Boolean,
        val art: android.graphics.Bitmap? = null,
        /** Приложение, которому принадлежит медиа-сеанс (нажмите на обложку, чтобы вернуться к воспроизводимому в данный момент приложению). */
        val pkg: String? = null,
        /** Позиция воспроизведения в момент выборки (мс). */
        val positionMs: Long = 0L,
        /** Скорость воспроизведения (0 при паузе – без экстраполяции). */
        val playbackSpeed: Float = 1f,
        val durationMs: Long = 0L,
        /** Привязка SystemClock.elapsedRealtime для выборки позиции. */
        val anchoredAtMs: Long = 0L,
    ) {
        /** Предполагаемая позиция воспроизведения во время рендеринга (экстраполируется в соответствии со скоростью во время воспроизведения, ограниченной длительностью). */
        fun displayPositionAt(elapsedRealtimeMs: Long): Long {
            if (durationMs <= 0) return 0
            val base =
                if (playing && anchoredAtMs > 0) {
                    positionMs + ((elapsedRealtimeMs - anchoredAtMs) * playbackSpeed).toLong()
                } else {
                    positionMs
                }
            return base.coerceIn(0, durationMs)
        }
    }

    @Volatile
    var nowPlaying: NowPlaying? = null
        private set

    /**
     * Уведомляет, предоставлены ли права использования.
     *
     * **`metadata` всегда равен нулю**, если он не авторизован (сам сеанс все еще виден, а статус воспроизведения меняется как обычно),
     * Карта       должна различать «действительно не играет» и «играет, но информация не может быть прочитана» соответственно.
     * В противном случае копия будет вводить в заблуждение, так как «информация о носителе не подключена».
     */
    @Volatile
    var accessGranted: Boolean = false

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun publish(value: NowPlaying?) {
        val old = nowPlaying
        nowPlaying = value
        // Позиция меняется каждую секунду (нижний опрос 1 с) — журнал смотрит только на ключ содержимого, чтобы избежать
        // пролистывания экрана;

        // слушатели по-прежнему уведомляют каждый раз (пользовательский интерфейс соответствующим образом обновляет текст выполнения)
        val contentChanged =
            old?.title != value?.title ||
                old?.artist != value?.artist ||
                old?.playing != value?.playing ||
                old?.art !== value?.art
        if (contentChanged) {
            VirtualLauncher.logSink?.invoke(
                "MEDIA: " + (
                    value?.let { "${it.title ?: "?"} - ${it.artist ?: "?"} playing=${it.playing}" }
                        ?: "Нет активных сессий"
                ),
            )
        }
        // Содержимое не изменилось (при опросе обновляется только привязка позиции). Нет уведомлений: прогресс в
        // секундах обновляется каждую секунду с помощью mediaTick на стороне пользовательского интерфейса.

        // ——Раньше постоянная смена привязанныхAtMs приводила к полному уведомлению слушателя каждую секунду, формируя
        // двусторонний повторный рендеринг с помощью mediaTick.

        if (!contentChanged) return
        listeners.forEach { runCatching { it() } }
    }

    /** Перехватчик ресинхронизации (устанавливается при подключении службы): вызывается после оптимистичного обновления пользовательского интерфейса для получения реального состояния. */
    @Volatile
    var resyncHook: (() -> Unit)? = null

    fun requestResync() {
        resyncHook?.invoke()
    }
}
