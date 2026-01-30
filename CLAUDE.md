<!-- SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me> -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# CLAUDE.md - Android App

Native Android app using Kotlin and Jetpack Compose.

## Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose
- **Build**: Gradle (Kotlin DSL)
- **Native**: UniFFI bindings via `vauchi-mobile-android` Maven package

## Commands

```bash
./gradlew build                  # Build
./gradlew test                   # Run tests
./gradlew assembleDebug          # Debug APK
./gradlew assembleRelease        # Release APK
```

## Rules

- Follow Android/Kotlin conventions
- Use Compose for all new UI
- Native bindings via `vauchi-mobile-android` Maven package (no local build needed)

## Structure

- `app/` - Main application module
- `gradle/` - Gradle wrapper
