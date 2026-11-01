plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// AppRelease/ 里只有 release-info.json / release-notes.md 是 App 资产；其余（源码 zip 等）不打包。
val appReleaseAssetsDir = layout.buildDirectory.dir("generated/appReleaseAssets").get().asFile
val stageAppReleaseAssets = tasks.register<Copy>("stageAppReleaseAssets") {
    from(rootProject.file("AppRelease")) {
        include("release-info.json", "release-notes.md")
    }
    into(appReleaseAssetsDir)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(stageAppReleaseAssets) }

android {
    namespace = "com.clarklevis.dsh.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.clarklevis.dsh.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 40 // 本批（Bot 页结构对齐：共享页头/「Bot」区标题/搜索框/单一 Agent 分组/纯列表行/状态副行 + 对话页副行）；39 为上一批
        versionName = "1.7.13"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    // App 只从 AppRelease/ 读这两份（`ui/AppReleaseInfo.kt:12-14`）。**白名单暂存**，不能整目录当 assets：
    // 该目录还放过发版用的源码 zip，整目录打包会把源码塞进 APK —— 2026-11-01 实测 debug APK 156MB，
    // 内含 assets/Orcarness-source-20261027.zip (99MB) + Orcarness-source-code-20261027.zip (5.5MB)。
    // （AGP 的 assets 没有可用的 include/exclude DSL，故用 Copy 任务只搬这两份到 build/generated。）
    sourceSets.getByName("main").assets.srcDir(appReleaseAssetsDir)

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.window)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.backdrop)
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.tasklist)
    implementation(libs.markwon.html)
    implementation(libs.android.svg)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.mlkit.vision)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // 避免旧 Espresso 反射已移除的 InputManager.getInstance，兼容新模拟器。
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
