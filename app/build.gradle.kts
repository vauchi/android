import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.android.compose.screenshot")
    id("com.github.triplet.play")
}

// Local bindings: build with ./gradlew assembleDebug -PlocalBindings
// to use jniLibs + Kotlin source from `just bindings` instead of Maven AAR.
// See: .claude/docs/local-bindings.md
val useLocalBindings = project.hasProperty("localBindings") || System.getenv("VAUCHI_LOCAL_BINDINGS") != null

android {
    namespace = "app.vauchi"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.vauchi"
        minSdk = 26  // Android 8.0 - 94.8% coverage
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Timestamp-based build ID for dev diagnostics
        val buildTimestamp = SimpleDateFormat("yyyyMMdd-HHmmss").apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        buildConfigField("String", "BUILD_ID", "\"$buildTimestamp\"")

        // Load native libraries for these ABIs
        // arm64-v8a: Modern 64-bit ARM devices
        // armeabi-v7a: Older 32-bit ARM devices (still ~10% of market)
        // x86_64: Emulator (included for local dev with local bindings)
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                    ?: error("ANDROID_KEYSTORE_PASSWORD must be set when ANDROID_KEYSTORE_PATH is provided")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                    ?: error("ANDROID_KEY_ALIAS must be set when ANDROID_KEYSTORE_PATH is provided")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                    ?: error("ANDROID_KEY_PASSWORD must be set when ANDROID_KEYSTORE_PATH is provided")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    lint {
        lintConfig = file("lint.xml")
    }

    // When using local bindings, include the locally-generated Kotlin source.
    // Files live in src/local-bindings/kotlin/ (not the default source set),
    // so they're only compiled when explicitly added.
    if (useLocalBindings) {
        sourceSets.getByName("main") {
            kotlin.srcDir("src/local-bindings/kotlin")
            // UI code that depends on UniFFI-generated types (e.g. MobileOnboardingWorkflow).
            // Only compiled when bindings are available locally. After core release publishes
            // bindings with these types, move files back to the main source set.
            kotlin.srcDir("src/binding-dependent/kotlin")
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/versions/**"
            )
        }
        jniLibs {
            useLegacyPackaging = false
            // Don't keep debug symbols in release for smaller APK
        }
    }
}

play {
    track.set("internal")
    val saPath = System.getenv("GOOGLE_PLAY_SERVICE_ACCOUNT")
    if (saPath != null) {
        serviceAccountCredentials.set(file(saPath))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.biometric:biometric:1.1.0")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Serialization (for ContentUpdateWorker)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // WorkManager for background sync
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // QR Code generation and scanning (ZXing)
    implementation("com.google.zxing:core:3.5.3")

    // CameraX for QR scanning
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // ML Kit for barcode scanning
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // VauchiPlatform native bindings
    if (useLocalBindings) {
        // Local: JNA needed explicitly (Maven AAR bundles it, local jniLibs don't)
        implementation("net.java.dev.jna:jna:5.14.0@aar")
    } else {
        // Remote: published AAR includes JNA + JNI libs + Kotlin bindings
        implementation("app.vauchi:vauchi-platform:0.7.0-dev.3")
    }

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.work:work-testing:2.9.1")
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Screenshot testing (Compose Preview Screenshot Testing)
    screenshotTestImplementation("com.android.tools.screenshot:screenshot-validation-api:0.0.1-alpha13")
    screenshotTestImplementation("androidx.compose.ui:ui-tooling")
}

apply(from = rootProject.file("test-coverage.gradle"))
