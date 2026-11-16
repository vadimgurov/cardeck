package io.github.shaonialife.cardeck.bridge

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.shaonialife.cardeck.ui.DarkMessageDialog
import io.github.shaonialife.cardeck.util.Bg
import java.io.File
import java.lang.ref.WeakReference

/**
 * Страница настроек удаленной отладки  (вторая запись на рабочем столе для удаленного варианта):
 * Введите адрес сервера и код сопряжения → подключиться; отображение статуса подключения; направляйте плавающее
 * окно/разрешения на установку.
 *
 * также служит хостом всплывающих окон отладки: подтверждение установки APK, последующие запросы после
 * переустановки и т. д. **Необходимо использовать обычные всплывающие окна**
 * (DarkMessageDialog) — плавающее окно легко игнорировать (фактические отзывы пользователей). Время работы приложения в фоновом режиме
 * [ReinstallFlow]/[BridgeApp] откройте эту страницу с параметрами (EXTRA_PROMPT), всплывающее окно onCreate/onNewIntent.
 */
class BridgeSetupActivity : AppCompatActivity() {
    private val refresh = Handler(Looper.getMainLooper())
    private val ticker =
        object : Runnable {
            override fun run() {
                // statusLine(): обратный отсчет в реальном времени с «автоматической повторной попыткой после Ns» во время
                // отступления и повторного подключения.

                statusText?.text = "Статус: ${BridgeClient.statusLine()}"
                refresh.postDelayed(this, 1_000)
            }
        }
    private var statusText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Удаленная отладка CarDeck"

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        fun label(text: String): TextView =
            TextView(this).apply {
                this.text = text
                setTextColor(0xFFE0E0E5.toInt())
                textSize = 13f
            }

        val urlInput =
            EditText(this).apply {
                hint = "wss://example.trycloudflare.com или ws://192.168.1.2:8787"
                setText(BridgePrefs.serverUrl(this@BridgeSetupActivity))
                imeOptions = EditorInfo.IME_ACTION_NEXT
                setSingleLine()
                // Цвет текста темы по умолчанию — черный, который не виден в темных окнах (проверено пользователями), и ему
                // должен быть явно задан яркий цвет.

                setTextColor(0xFFE0E0E5.toInt())
                setHintTextColor(0xFF8A9099.toInt())
            }
        val codeInput =
            EditText(this).apply {
                hint = "6-значный код сопряжения (отображается на консоли сервера)"
                setText(BridgePrefs.pairCode(this@BridgeSetupActivity))
                setSingleLine()
                setTextColor(0xFFE0E0E5.toInt())
                setHintTextColor(0xFF8A9099.toInt())
            }
        val connect =
            Button(this).apply {
                text = "подключить"
                setOnClickListener {
                    BridgePrefs.save(
                        this@BridgeSetupActivity,
                        urlInput.text.toString(),
                        codeInput.text.toString(),
                    )
                    BridgePrefs.setAutoConnect(this@BridgeSetupActivity, true)
                    // Разрешения для приостановленных окон отображаются плавно (от этого зависит окно подсказки)
                    if (!Settings.canDrawOverlays(this@BridgeSetupActivity)) {
                        runCatching {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:$packageName"),
                                ),
                            )
                        }
                    }
                    Bg.run("bridge-start") { BridgeClient.start(this@BridgeSetupActivity) }
                }
            }
        val disconnect =
            Button(this).apply {
                text = "Отключить"
                setOnClickListener {
                    BridgePrefs.setAutoConnect(this@BridgeSetupActivity, false)
                    BridgeClient.stop()
                }
            }
        val openMain =
            Button(this).apply {
                text = "Откройте основной интерфейс"
                setOnClickListener {
                    runCatching {
                        startActivity(
                            Intent().setClassName(
                                packageName,
                                "io.github.shaonialife.cardeck.MainActivity",
                            ),
                        )
                    }
                }
            }
        val installPerm =
            Button(this).apply {
                text = "Предоставьте разрешение «Установить неизвестные приложения»."
                visibility =
                    if (packageManager.canRequestPackageInstalls()) View.GONE else View.VISIBLE
                setOnClickListener {
                    runCatching {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:$packageName"),
                            ),
                        )
                    }
                }
            }
        statusText =
            TextView(this).apply {
                text = "Статус: ${BridgeClient.statusLine()}"
                setTextColor(0xFF3DD6C4.toInt())
                textSize = 14f
            }

        val note =
            TextView(this).apply {
                text =
                    "Описание:\n" +
                    "· Это приложение и ежедневная версия (с тем же именем без суффикса «удаленная отладка») независимы " +
                    "друг от друга и требуют авторизации один раз." +
                    "Шизуку и права на уведомления\n" +
                    "· После подключения машина будет постоянно отправлять текущий журнал (включая имя точки " +
                    "доступа/адрес Bluetooth и т. д.) на сервер отладки." +
                    "Только для устранения неполадок\n" +
                    "· Сервер отладки может выполнять только команды белого списка (инициировать/отключить, статус запроса)."
                setTextColor(0xFFE0E0E5.toInt())
                textSize = 13f
                setLineSpacing(0f, 1.3f)
            }

        listOf(
            label("Адрес сервера отладки"),
            urlInput,
            label("код сопряжения"),
            codeInput,
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
                addView(connect)
                addView(disconnect)
                addView(openMain)
            },
            installPerm,
            statusText!!,
            label(""),
            note,
        ).forEach { view -> root.addView(view) }

        handlePromptIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePromptIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumedRef = WeakReference(this)
        refresh.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        if (resumedRef?.get() === this) resumedRef = null
        refresh.removeCallbacks(ticker)
    }

    /** При его открытии с параметрами появится соответствующее обычное всплывающее окно (подтверждение/переустановка установки продолжается таким образом в фоновом режиме приложения). */
    private fun handlePromptIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_PROMPT)) {
            "install" -> {
                val path = intent.getStringExtra("apkPath") ?: return
                val version = intent.getStringExtra("version") ?: "?"
                promptInstall(File(path), version, intent.getStringExtra("deltaNote"))
            }
            "resume" -> promptResume()
            "confirm" -> {
                // Фронтальная пересылка подтверждения установки: окно подтверждения системы будет вызвано непосредственно из
                // фонового приемника.

                // Уважайте тихий перехват (без исключений и без интерфейса), он обязательно вступит в силу при запуске
                // активности на переднем плане.

                val confirm = ReinstallFlow.pendingConfirmIntent
                if (confirm == null) {
                    android.widget.Toast
                        .makeText(this, "Срок действия подтверждения установки истек. Нажмите еще раз.", android.widget.Toast.LENGTH_LONG)
                        .show()
                } else {
                    ReinstallFlow.pendingConfirmIntent = null
                    runCatching { startActivity(confirm) }
                        .onFailure {
                            android.widget.Toast
                                .makeText(
                                    this,
                                    "Не удалось подтвердить установку при запуске: " +
                                        "${it.message}",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                        }
                }
            }
        }
    }

    /** Подтверждение установки новой версии (обычное всплывающее окно; форма плавающего окна отклонена по отзывам пользователей). */
    fun promptInstall(
        file: File,
        version: String,
        deltaNote: String? = null,
    ) {
        if (!file.exists()) return
        val sizeText = deltaNote ?: "Полный пакет ${file.length() / 1024 / 1024} МБ"
        DarkMessageDialog.show(
            this,
            title = "Установите и отладьте новую версию",
            message =
                "v$version ($sizeText) загружен и проверен. \n" +
                    "Установка отключит текущее соединение автомобиля с машиной, и после завершения вам будет задан " +
                    "вопрос, продолжать ли отладку.",
            actions =
                listOf(
                    DarkMessageDialog.Action("Отмена") {},
                    DarkMessageDialog.Action(
                        "Установка",
                        emphasis = DarkMessageDialog.Emphasis.PRIMARY,
                    ) {
                        ReinstallFlow.installNow(this, file)
                    },
                ),
        )
    }

    /** Продолжение запроса после переустановки. */
    fun promptResume() {
        DarkMessageDialog.show(
            this,
            title = "Перейти в режим отладки?",
            message = "Отладочная версия обновлена. После продолжения вы автоматически подключитесь обратно к серверу отладки.",
            actions =
                listOf(
                    DarkMessageDialog.Action("Поговорим об этом позже") {
                        BridgePrefs.setResumePending(this@BridgeSetupActivity, false)
                    },
                    DarkMessageDialog.Action(
                        "продолжать",
                        emphasis = DarkMessageDialog.Emphasis.PRIMARY,
                    ) {
                        BridgePrefs.setResumePending(this@BridgeSetupActivity, false)
                        Bg.run("bridge-start") { BridgeClient.start(this@BridgeSetupActivity) }
                    },
                ),
        )
    }

    companion object {
        private const val EXTRA_PROMPT = "prompt"

        /** Возобновленный в настоящее время экземпляр (слабое предотвращение утечки ссылок): для отладки всплывающих окон требуется узел активности на переднем плане. */
        @Volatile
        private var resumedRef: WeakReference<BridgeSetupActivity>? = null

        fun resumed(): BridgeSetupActivity? = resumedRef?.get()

        /** Переадресация внешнего интерфейса подтверждения установки: перенесите страницу настроек во внешний интерфейс, после чего запустится интерфейс подтверждения системы. */
        fun startForConfirm(context: android.content.Context) {
            runCatching {
                context.startActivity(
                    Intent()
                        .setClassName(context.packageName, ReinstallFlow.SETUP_ACTIVITY)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_PROMPT, "confirm"),
                )
            }
        }

        /** Предоставляет фоновую сцену приложения с параметрами для открытия этой страницы и всплывающего запроса. */
        fun startForResume(context: android.content.Context) {
            runCatching {
                context.startActivity(
                    Intent()
                        .setClassName(context.packageName, ReinstallFlow.SETUP_ACTIVITY)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_PROMPT, "resume"),
                )
            }
        }
    }
}
