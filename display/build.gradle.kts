// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    `maven-publish`
}

group = "app.vauchi"
version = "0.1.0"

android {
    namespace = "app.vauchi.display"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api("app.vauchi:contract:0.102.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("androidx.activity:activity:1.9.3")
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.core:core-ktx:1.15.0")
    api(platform("androidx.compose:compose-bom:2024.12.01"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.kotest:kotest-property:5.9.1")
    testImplementation("org.robolectric:robolectric:4.16.1")
    // Robolectric's bundled ASM cannot read the JDK 26 runner's classes.
    testImplementation("org.ow2.asm:asm:9.10.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

val releaseVersion = version.toString()
val releaseTag = providers.environmentVariable("CI_COMMIT_TAG").orElse("").get()
val releaseProject = providers.environmentVariable("CI_PROJECT_PATH").orElse("").get()
val releaseProjectId = providers.environmentVariable("CI_PROJECT_ID").orElse("").get()
val releaseTokenPresent = providers.environmentVariable("CI_JOB_TOKEN").orElse("").get().isNotBlank()

val verifyMavenRelease by tasks.registering {
    inputs.properties(mapOf(
        "version" to releaseVersion,
        "tag" to releaseTag,
        "project" to releaseProject,
        "projectId" to releaseProjectId,
        "tokenPresent" to releaseTokenPresent,
    ))
    doLast {
        require(releaseVersion.matches(Regex("""(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"""))) {
            "Maven releases require a stable semantic version"
        }
        require(releaseTag == "v$releaseVersion") { "Release tag must match v$releaseVersion" }
        require(releaseProject == "vauchi/android-display") { "Publish only from vauchi/android-display" }
        require(releaseProjectId.matches(Regex("[1-9][0-9]*"))) { "A GitLab project ID is required" }
        require(releaseTokenPresent) { "A CI job token is required" }
    }
}

publishing {
    repositories {
        maven {
            name = "Local"
            url = file(providers.gradleProperty("vauchiPublicationDir").orElse(
                layout.buildDirectory.dir("maven-repository").map { it.asFile.absolutePath },
            ).get()).toURI()
        }
        maven {
            name = "GitLab"
            url = uri("https://gitlab.com/api/v4/projects/${releaseProjectId.ifEmpty { "0" }}/packages/maven")
            credentials(HttpHeaderCredentials::class) {
                name = "Job-Token"
                value = providers.environmentVariable("CI_JOB_TOKEN").orNull
            }
            authentication { create<HttpHeaderAuthentication>("header") }
        }
    }
    publications {
        register<MavenPublication>("release") {
            artifactId = "android-display"
            pom {
                name.set("Vauchi Android display")
                description.set("Android display adapters for Vauchi's command/event contract")
                url.set("https://gitlab.com/vauchi/android-display")
                licenses {
                    license {
                        name.set("GPL-3.0-or-later")
                        url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                    }
                }
                scm {
                    connection.set("scm:git:https://gitlab.com/vauchi/android-display.git")
                    url.set("https://gitlab.com/vauchi/android-display")
                }
            }
            afterEvaluate { from(components["release"]) }
        }
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    if (name.endsWith("ToGitLabRepository")) dependsOn(verifyMavenRelease)
}
