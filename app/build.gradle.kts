import de.undercouch.gradle.tasks.download.Download

plugins {
    alias(libs.plugins.android.application)
    id("de.undercouch.download") version "5.6.0"
}

val hasUnity = rootProject.findProject(":unityLibrary") != null

android {
    namespace = "com.ouor.vrmdroid"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.ouor.vrmdroid"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Unity exports arm64 only by default; keep MediaPipe ABIs in sync.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    androidResources {
        noCompress += "task"
    }
    sourceSets {
        getByName("main") {
            // Real Unity embedding when the exported unityLibrary is present, a stub otherwise.
            kotlin.srcDir(if (hasUnity) "src/unity/java" else "src/nounity/java")
        }
    }
}

// MediaPipe face landmarker model (Apache 2.0), fetched from Google's model bucket.
val faceModel = layout.projectDirectory.file("src/main/assets/face_landmarker.task")
val downloadFaceModel by tasks.registering(Download::class) {
    src("https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task")
    dest(faceModel)
    overwrite(false)
}
tasks.named("preBuild") { dependsOn(downloadFaceModel) }

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    if (hasUnity) {
        implementation(project(":unityLibrary"))
        // unityLibrary keeps its player classes as an `implementation` jar; compile against it here.
        compileOnly(files(rootProject.project(":unityLibrary").projectDir.resolve("libs/unity-classes.jar")))
    }
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
