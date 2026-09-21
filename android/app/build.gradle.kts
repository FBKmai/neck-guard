import java.net.URL
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.local.neckguard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.local.neckguard"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // .task 模型文件不要被 aapt 压缩，MediaPipe 需要直接 mmap
    androidResources {
        noCompress += "task"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// 构建前自动下载 MediaPipe Pose Landmarker 模型到 assets，避免把 5.8 MB 二进制提交进仓库
val poseModelUrl =
    "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task"
val poseModelFile = layout.projectDirectory.file("src/main/assets/pose_landmarker_lite.task").asFile

val downloadPoseModel by tasks.registering {
    outputs.file(poseModelFile)
    onlyIf { !poseModelFile.exists() || poseModelFile.length() < 1_000_000L }
    doLast {
        poseModelFile.parentFile.mkdirs()
        logger.lifecycle("Downloading pose model from $poseModelUrl")
        URL(poseModelUrl).openStream().use { input ->
            poseModelFile.outputStream().use { output -> input.copyTo(output) }
        }
        logger.lifecycle("Pose model saved to ${poseModelFile.absolutePath} (${poseModelFile.length()} bytes)")
    }
}

tasks.named("preBuild") {
    dependsOn(downloadPoseModel)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mediapipe.tasks.vision)

    testImplementation(libs.junit)
}
