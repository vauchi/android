// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.library") version "9.2.0"
        id("org.jetbrains.kotlin.android") version "2.2.10"
        id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "PublicationUnderTest"
                    url = file(providers.gradleProperty("vauchiPublicationDir")
                        .orElse("../build/maven-repository").get()).toURI()
                    if (providers.gradleProperty("vauchiPomOnly").orNull == "true") {
                        metadataSources {
                            mavenPom()
                            ignoreGradleMetadataRedirection()
                        }
                    }
                }
            }
            filter { includeModule("app.vauchi", "android-display") }
        }
        google()
        mavenCentral()
        maven {
            url = uri("https://gitlab.com/api/v4/groups/122552022/-/packages/maven")
            credentials(HttpHeaderCredentials::class) {
                name = if (System.getenv("CI_JOB_TOKEN") != null) "Job-Token" else "Private-Token"
                value = System.getenv("CI_JOB_TOKEN") ?: System.getenv("GITLAB_TOKEN") ?: ""
            }
            authentication { create<HttpHeaderAuthentication>("header") }
        }
    }
}

rootProject.name = "display-publication-consumer"
