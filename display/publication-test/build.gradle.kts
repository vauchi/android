// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val pomOnly = providers.gradleProperty("vauchiPomOnly").orNull == "true"
dependencyLocking {
    lockAllConfigurations()
    lockMode.set(LockMode.STRICT)
    lockFile = file(if (pomOnly) "pom/gradle.lockfile" else "gradle.lockfile")
}

android {
    namespace = "app.vauchi.publicationtest"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("app.vauchi:android-display:0.1.0")
}

val publishedRuntime = configurations.matching { it.name == "debugRuntimeClasspath" }
tasks.register("verifyPublishedDependencies") {
    doLast {
        val runtime = publishedRuntime.single()
        val resolved = runtime.incoming.resolutionResult
        val modules = resolved.allComponents.mapNotNull {
            it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier
        }
        check(modules.filter { it.group == "app.vauchi" && it.module == "contract" }
            .map { it.version } == listOf("0.102.0")) { "Expected exactly contract 0.102.0" }
        check(modules.filter { it.group == "app.vauchi" && it.module == "android-display" }
            .map { it.version } == listOf("0.1.0")) { "Expected the published display 0.1.0" }
        check(modules.none { it.group == "app.vauchi" && it.module == "vauchi-platform" }) {
            "The display consumer must not pull Core"
        }
        check(modules.none { it.group == "app.vauchi" && it.module == "android-hw" }) {
            "The display consumer must not pull the hardware library"
        }
        check(resolved.allDependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
            .none { it.selected.id is org.gradle.api.artifacts.component.ProjectComponentIdentifier }) {
            "Publication verification must not substitute a project dependency"
        }
        runtime.incoming.artifacts.artifactFiles.files
    }
}
