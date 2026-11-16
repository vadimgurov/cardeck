package io.github.shaonialife.cardeck.replay

import org.json.JSONObject
import java.io.File

/**
 * Загрузчик устройства воспроизведения модели  (JSONL). Каталог по умолчанию gitignore - при свежем клонировании без исправлений
 * Тест   автоматически пропускается (см. сканирование каталога каждого теста Replay). После локального извлечения это ворота регрессии.
 */
class CarFixture(
    val name: String,
    val car: String,
    val source: String,
) {
    class BtRound(
        val index: Int,
        val outcome: String,
        val durationMs: Long,
        val rx: ByteArray,
        val recordedTx: ByteArray,
        /** Бюджет RAW усекает конечный остаточный кадр (уже обрезанный при извлечении). */
        val rxTruncated: Boolean,
        /** Только когда полный кадр распознавания (0x1D01) сохраняется (истинно), можно сделать полное утверждение фазы распознавания. */
        val rxHasIdent: Boolean,
    )

    val btRounds = ArrayList<BtRound>()
    val tunnelRx = ArrayList<Pair<Int, ByteArray>>() // номер сообщения → тело CSM
    val tunnelTxIds = ArrayList<Int>()

    companion object {
        fun loadDirectory(): List<CarFixture> {
            // src/test/resources — это путь к классам при запуске теста Gradle; каталог исходного кода выводится из местоположения
            // (Динамические приборы не нужно импортировать в jar, и их проще всего читать в соответствии с файловой системой)
            val roots =
                listOf(
                    File("src/test/resources/carplay/fixtures"),
                    File("app/src/test/resources/carplay/fixtures"),
                )
            val dir = roots.firstOrNull { it.isDirectory } ?: return emptyList()
            return dir
                .listFiles { f -> f.extension == "jsonl" }
                .orEmpty()
                .sortedBy { it.name }
                .mapNotNull { file ->
                    runCatching { parse(file) }.getOrElse {
                        println("REPLAY: fixture Не удалось выполнить синтаксический анализ ${file.name}: $it")
                        null
                    }
                }
        }

        private fun parse(file: File): CarFixture {
            val lines = file.readLines().filter { it.isNotBlank() }
            val meta = JSONObject(lines.first())
            val fixture =
                CarFixture(
                    name = file.nameWithoutExtension,
                    car = meta.getJSONObject("meta").optString("car", "unknown"),
                    source = meta.getJSONObject("meta").optString("source", file.name),
                )
            lines.drop(1).forEach { line ->
                val obj = JSONObject(line)
                when (obj.optString("layer")) {
                    "bt" ->
                        fixture.btRounds.add(
                            BtRound(
                                index = obj.getInt("round"),
                                outcome = obj.optString("outcome", "identified"),
                                durationMs = obj.optLong("durationMs", 0),
                                rx = hex(obj.getString("rxHex")),
                                recordedTx = hex(obj.optString("txHex", "")),
                                rxTruncated = obj.optBoolean("rxTruncated", false),
                                rxHasIdent = obj.optBoolean("rxHasIdent", false),
                            ),
                        )

                    "tunnel" ->
                        if (obj.optString("dir") == "rx") {
                            fixture.tunnelRx.add(obj.getInt("id") to hex(obj.getString("hex")))
                        } else {
                            fixture.tunnelTxIds.add(obj.getInt("id"))
                        }
                }
            }
            return fixture
        }

        private fun hex(text: String): ByteArray =
            ByteArray(text.length / 2) { i ->
                text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
    }
}
