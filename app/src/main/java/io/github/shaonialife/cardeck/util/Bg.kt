package io.github.shaonialife.cardeck.util

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Общий пул потоков для кратковременных фоновых задач: здесь находятся одноразовые задачи в процессе
 * щелчка/сеанса/опроса пользовательского интерфейса.
 * заменяет новый поток (накладные расходы на создание потока + ненаблюдаемость + риск неконтролируемого накопления).
 *
 * Кэшированный пул  : автоматически перезапускается при простое в течение 60 секунд, создается заново, когда
 * есть задачи и нет простаивающих потоков — поведение эквивалентно новому потоку,
 * Однако пики используются повторно и имеют единые названия. Постоянный цикл (цикл композиции GL/чтение ссылок/цикл принятия и т. д.)
 * сюда не идет: им нужно независимое именование, прерывание и удержание жизненного цикла.
 */
object Bg {
    private val counter = AtomicInteger(0)

    val pool: ExecutorService =
        Executors.newCachedThreadPool { r ->
            Thread(r, "app-bg-${counter.incrementAndGet()}").apply { isDaemon = true }
        }

    /** Отправьте одноразовую фоновую задачу. [name] имя входящего потока (logcat можно найти при блокировке/сбое). */
    fun run(
        name: String,
        block: () -> Unit,
    ) {
        pool.execute {
            Thread.currentThread().name = "app-bg-$name"
            try {
                block()
            } catch (t: Throwable) {
                android.util.Log.w("Bg", "bg task '$name' failed", t)
            }
        }
    }
}
