// 独立 spike 工程：不属于 dsh-mobile 产品构建（不在根 settings.gradle.kts 的 include 中）。
// 目的：用 commonMain 纯 Kotlin 验证 ds-bot-mobile-app 的 /mobile/v1 协议可被 KMP 解析。
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "mobile-bridge-protocol-spike"

include(":protocol")
