import de.undercouch.gradle.tasks.download.Download
import de.undercouch.gradle.tasks.download.Verify

plugins {
    alias(libs.plugins.android.application)
    id("de.undercouch.download") version "5.6.0"
}

// settings.gradle.kts includes :unityLibrary when the Unity export exists. The "unity" flavor
// (real avatar) is only defined then; the "stub" flavor (landmark view, no Unity) always is.
val hasUnity = rootProject.findProject(":unityLibrary") != null

// MediaPipe face landmarker model (Apache 2.0). Pinned to a version and checksum so builds are
// reproducible; downloaded into the build directory, not the source tree.
val faceModelUrl = "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"
val faceModelSha256 = "64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff"
// CC0 VRoid sample model (license in the file's VRM meta), shown until the user picks their own.
val sampleAvatarUrl = "https://raw.githubusercontent.com/madjin/vrm-samples/e16eb187100149a315ad92c3c9968f1d5baa6c7d/vroid/beta/Sendagaya_Shibu.vrm"
val sampleAvatarSha256 = "b7bcad5e5890abc4d7c65f9afc31da2445db03197d44f1ec10447f9db5abeaff"
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
        versionCode = 3
        versionName = "0.3.0"

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
    flavorDimensions += "avatar"
    productFlavors {
        create("stub") {
            dimension = "avatar"
            // Installs next to the full app.
            applicationIdSuffix = ".stub"
            versionNameSuffix = "-stub"
        }
        if (hasUnity) {
            create("unity") {
                dimension = "avatar"
                isDefault = true
            }
        }
    }
    androidResources {
        noCompress += "task"
    }
    sourceSets {
        getByName("main") {
            // Flavor code lives in src/unity/java and src/stub/java (the UnityHost variants).
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
val sampleAvatarFile = generatedAssets.map { it.file("sample_avatar.vrm") }
val downloadSampleAvatar by tasks.registering(Download::class) {
    src(sampleAvatarUrl)
    dest(sampleAvatarFile)
    overwrite(false)
}
val verifySampleAvatar by tasks.registering(Verify::class) {
    dependsOn(downloadSampleAvatar)
    src(sampleAvatarFile)
    algorithm("SHA-256")
    checksum(sampleAvatarSha256)
}
tasks.named("preBuild") { dependsOn(verifyFaceModel, verifySampleAvatar) }

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
        "unityImplementation"(project(":unityLibrary"))
        // unityLibrary keeps its player classes as an `implementation` jar; compile against it here.
        "unityCompileOnly"(files(rootProject.project(":unityLibrary").projectDir.resolve("libs/unity-classes.jar")))
    }
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
