import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 Соглашение о номере версии *: `MAJOR.MINOR.PATCH` (семантическая версия).
 *
 * `versionCode` **производен** от `versionName` вместо заполнения двух мест вручную — если они не синхронизированы, обновление будет недействительным.
 * Классическая причина (система смотрит только на код версии, если он слишком мал, пользователь не сможет установить новый пакет, и подсказки не будет).
 * 0.x представляет собой экспериментальную стадию; только эту строку нужно изменить перед релизом.
 *
 * CI (`.github/workflows/android-ci.yml`) будет передаваться при каждом нажатии
 * `-Pcardeck.buildNumber=<github.run_number>`, получите `0.1.13.<N>` / `versionCode = base*10000+N`
Пакет **официальной подписи**  * (см. [buildNumber] ниже) не нужно отправлять на склад.
 */
val appVersionName = "0.1.28"
val appVersionCode =
    appVersionName
        .split(".")
        .let { (major, minor, patch) -> major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt() }

/**
 Номер предварительной сборки * (CI отправляет `-Pcardeck.buildNumber=<run_number>` для каждой отправки).
 *
 * - `versionName` добавить `.N`: `0.1.13` → `0.1.13.42`;
 * - `versionCode` Оставьте младшие 4 бита для номера сборки: `113` → `1_130_042`.
 *
 * Эффект: каждое нажатие **монотонно увеличивающегося** под одной и той же базой может напрямую перезаписать и установить предыдущую версию; базовые обновления до следующего
При применении патча  * (`0.1.14` → `1_140_000`) он автоматически обгонит все сборки этого патча (требуется N < 10000).
 * возвращается к чистой базе (`1_130_000`), если он не передан, локальные/разветвленные сборки по-прежнему доступны.
 *
 * **Если вы передадите номер сборки, у вас должно быть официальное хранилище ключей**: в противном случае этот пакет будет транслироваться как «официальная версия».
 * Если в будущем пакет, подписанный сертификатом отладки, будет заменен официальным сертификатом, всем пользователям придется удалить и переустановить его.
 */
val buildNumberText =
    providers.gradleProperty("cardeck.buildNumber").orNull?.takeIf { it.isNotBlank() }
val buildNumber = buildNumberText?.toIntOrNull()
check(buildNumberText == null || buildNumber != null) {
    "cardeck.buildNumber Должно быть целое число（получил \"$buildNumberText\"）"
}
check(buildNumber == null || buildNumber in 1..9_999) {
    "cardeck.buildNumber должно быть в 1..9999（versionCode низкого 4 Бит слева для номера сборки），текущий=$buildNumber"
}
val effectiveVersionName = if (buildNumber == null) appVersionName else "$appVersionName.$buildNumber"
val effectiveVersionCode =
    if (buildNumber == null) appVersionCode * 10_000 else appVersionCode * 10_000 + buildNumber

/**
 Конфигурация подписи выпуска *: считывается из `keystore.properties` вне репозитория (исключено из .gitignore).
Если файл  * отсутствует, релиз возвращает **подпись отладки** — только для форкеров/локальной проверки;
Предварительная сборка  * CI (с `-Pcardeck.buildNumber`) завершится сбоем и не создаст «официальную версию», подписанную отладкой.
 *
 * Утечка ключа = любой может подписать ваше обновление, поэтому хранилище ключей и пароль никогда не сохраняются.
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps =
    Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null

// Предварительным/CI-сборкам (с номерами сборок) запрещено попадать в сигнатуру отладки: лучше провалить сборку, чем
// «Официальная версия», которую нельзя перезаписать и установить официальным пакетом, будет выпущена в будущем.
check(buildNumber == null || hasReleaseKeystore) {
    "Отправлено -Pcardeck.buildNumber но нет keystore.properties —— Использование запрещено для предварительных сборок debug подпись"
}

android {
    namespace = "io.github.shaonialife.cardeck"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.shaonialife.cardeck"
        minSdk = 31
        targetSdk = 34
        versionCode = effectiveVersionCode
        versionName = effectiveVersionName
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // размерность моста: стандарт=daily/release (имя пакета остается неизменным, авторизация Shizuku предоставляется в соответствии с именем пакета и остается неизменной);
    // удаленная = версия удаленной отладки (независимое имя пакета .rd, может быть установлено одновременно с ежедневной версией, код моста отладки входит только в этот вариант)
    flavorDimensions += "bridge"
    productFlavors {
        create("standard") {
            dimension = "bridge"
        }
        create("remote") {
            dimension = "bridge"
            applicationIdSuffix = ".rd"
            versionNameSuffix = "-rd"
        }
    }

    buildTypes {
        release {
            // **Без обфускации кода**: этот проект основан на рефлексии (SystemProperties, Shizuku API) и
            // Настраиваемый AIDL, R8 требует дополнительных правил хранения; первая версия гарантированно будет работоспособной, и путаница останется на потом.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKeystore) "release" else "debug")
        }
        debug {
            // **Не добавляйте applicationIdSuffix**: пакет отладки этого проекта используется ежедневно——
            // Авторизация Shizuku предоставляется на основе имени пакета. Изменение имени пакета потребует повторной авторизации; AGENTS.md
            // Канал отладки `run-as io.github.shaonialife.cardeck` также выйдет из строя.
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        aidl = true
        // Удаленный вариант считывает BuildConfig.VERSION_NAME и отправляет отчет на сервер (AGP 8 по умолчанию отключен).
        buildConfig = true
    }

    testOptions {
        // Тест воспроизведения на уровне протокола управляет реальным AirPlayClient, SystemClock в request() и т. д.
        // Вызов Android может вернуть значение по умолчанию в JVM (в противном случае «не издевается» взорвется напрямую)
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // В новом коде возникают проблемы с ворсом (уровень ошибки), которые непосредственно приводят к сбою сборки; существующие проблемы исключены из базового файла.
        //  ./gradlew :app:lintDebug — проверка (автоматически выполняется перед отправкой перехватчика предварительной фиксации)
        //  ./gradlew :app:updateLintBaseline — перестроить базовую версию после устранения проблемы (при намеренном исключении)
        abortOnError = true
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Рабочий стол в стиле CarPlay: сетка с разбивкой на страницы
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Криптография сопряжения AirPlay: X25519/Ed25519/ChaCha20-Poly1305/HKDF-SHA512.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // Режим невидимого экрана: разрешения оболочки (активация беспроводной отладки, без рута)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    // Android org.json в локальном одиночном тесте представляет собой заглушку (не все методы имитируются):
    // Замените эталонной реализацией того же API (требуется как для тестов воспроизведения, так и для тестов синтаксического анализа навигации).
    testImplementation("org.json:json:20240303")
}
