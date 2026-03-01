<!-- SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me> -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# CLAUDE.md - Android App

Native Android app: Kotlin, Jetpack Compose, Gradle (Kotlin DSL), UniFFI bindings via `vauchi-mobile-android`.

## Rules

- Use Compose for all new UI
- Pre-MR: `just check-android` (or `./gradlew lint assembleDebug test`)
