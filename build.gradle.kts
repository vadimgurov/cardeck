plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.diffplug.spotless") version "7.2.1"
}

// Форматирование кода: ktlint (официальный стиль кода Kotlin), правила см. в корневом каталоге .editorconfig.
//  ./gradlew SpotlessApply — автоматическое форматирование
//  ./gradlew SpotlessCheck — только проверка (автоматически выполняется перед фиксацией перехватчика перед фиксацией)
spotless {
    kotlin {
        target("app/src/**/*.kt")
        ktlint("1.3.1")
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktlint("1.3.1")
    }
}
