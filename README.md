<!-- SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me> -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

> [!WARNING]
> **Pre-Alpha Software** - This project is under heavy development and not ready for production use.
> APIs may change without notice. Use at your own risk.

# Vauchi Android

Native Android app for privacy-focused contact card exchange.

## Features

- **Contact Card Management**: Create and edit your personal contact card
- **QR Exchange**: Scan or display QR codes to exchange contacts in-person
- **Selective Visibility**: Control which contacts see which fields
- **Background Sync**: Automatic updates via WorkManager (15-min intervals)
- **Encrypted Backup**: Export/import with password-protected encryption

## Tech Stack

- Kotlin + Jetpack Compose (Material Design 3)
- UniFFI bindings to Rust core (`vauchi-mobile`)
- CameraX + ML Kit for QR scanning
- Android KeyStore for secure key storage

## Quick Start

```bash
# Build and install (requires Android SDK)
./gradlew installDebug

# Or open in Android Studio
```

## Requirements

- Android SDK: Compile 35, Min 24, Target 35
- Java 17
- Rust toolchain (for building native library from source)

## Project Structure

```
app/src/main/kotlin/com/vauchi/
├── MainActivity.kt      # Entry point
├── VauchiApp.kt        # Application class
├── ui/screens/          # Compose screens
├── data/                # Repository, KeyStore helper
└── viewmodels/          # ViewModel layer
```

## Related Repositories

| Repository | Description |
|------------|-------------|
| [vauchi/code](https://gitlab.com/vauchi/code) | Core Rust library (source of UniFFI bindings) |
| [vauchi/ios](https://gitlab.com/vauchi/ios) | iOS app (SwiftUI) |
| [vauchi/docs](https://gitlab.com/vauchi/docs) | Documentation |
| [vauchi/dev-tools](https://gitlab.com/vauchi/dev-tools) | Build scripts and workspace tools |

## Development Workflow

### Getting UniFFI Bindings

This app requires the `vauchi-mobile` UniFFI bindings from the core repo:

```bash
# Option 1: Download from CI (when available)
# See dev-tools repo for download script

# Option 2: Build from source
git clone git@gitlab.com:vauchi/code.git ../code
cd ../code
cargo build -p vauchi-mobile --release --target aarch64-linux-android
```

### Building the App

```bash
./gradlew assembleDebug    # Debug build
./gradlew assembleRelease  # Release build
./gradlew installDebug     # Install to connected device
```

### Testing

```bash
./gradlew test                    # Unit tests
./gradlew connectedAndroidTest    # Instrumented tests
```

## Contributing

1. Check [vauchi/docs](https://gitlab.com/vauchi/docs) for architecture decisions
2. Follow Kotlin coding conventions and Material Design 3 guidelines
3. Write tests for new features
4. Core library changes go to [vauchi/code](https://gitlab.com/vauchi/code)

## Support the Project

Vauchi is open source and community-funded — no VC money, no data harvesting.

- [GitHub Sponsors](https://github.com/sponsors/vauchi)
- [Liberapay](https://liberapay.com/Vauchi/donate)
- [Supporters](https://docs.vauchi.app/about/supporters/) for sponsorship tiers

## License

MIT
