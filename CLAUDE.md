# CLAUDE.md - Android App

Native Android app: Kotlin, Jetpack Compose, Gradle (Kotlin DSL), UniFFI bindings via `vauchi-mobile-android`.

## Rules

- Use Compose for all new UI
- Pre-MR: `just check-android` (or `./gradlew lint assembleDebug test`)
