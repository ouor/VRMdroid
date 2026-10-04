import de.undercouch.gradle.tasks.download.Download
import de.undercouch.gradle.tasks.download.Verify

plugins {
    alias(libs.plugins.android.application)
    id("de.undercouch.download") version "5.6.0"
}

// settings.gradle.kts includes :unityLibrary when the Unity export exists (and
// -Pvrmdroid.unity=false isn't set); otherwise the app builds with the stub avatar host.
val hasUnity = rootProject.findProject(":unityLibrary") != null

// MediaPipe face landmarker model (Apache 2.0). Pinned to a version and checksum so builds are
// reproducible; downloaded into the build directory, not the source tree.
val faceModelUrl = "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"
val faceModelSha256 = "64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff"
val generatedAssets = layout.buildDirectory.dir("generated/mlmodel")

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
            // Plain File: AGP rejects Providers in the source-set API.
            assets.srcDir(generatedAssets.get().asFile)
        }
    }
}

val faceModelFile = generatedAssets.map { it.file("face_landmarker.task") }
val downloadFaceModel by tasks.registering(Download::class) {
    src(faceModelUrl)
    dest(faceModelFile)
    overwrite(false)
}
val verifyFaceModel by tasks.registering(Verify::class) {
    dependsOn(downloadFaceModel)
    src(faceModelFile)
    algorithm("SHA-256")
    checksum(faceModelSha256)
}
tasks.named("preBuild") { dependsOn(verifyFaceModel) }

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
