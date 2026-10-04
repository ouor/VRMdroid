pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Unity as a Library ships its runtime as local .aar/.jar files.
        flatDir { dirs("${rootDir}/unity/Builds/AndroidExport/unityLibrary/libs") }
    }
}

rootProject.name = "vrmdroid"
include(":app")

// The Unity player module is produced by exporting the project in unity/ (VRMDroid > Export
// Android Library, see README.md). The host app builds and runs without it; the avatar preview
// button is disabled in that case.
val unityLibraryDir = file("unity/Builds/AndroidExport/unityLibrary")
// -Pvrmdroid.unity=false builds with the stub even when an export exists (e.g. to check that
// the stub still compiles).
val unityEnabled = providers.gradleProperty("vrmdroid.unity").orNull != "false"
val unityExported = unityLibraryDir.resolve("build.gradle").exists() || unityLibraryDir.resolve("build.gradle.kts").exists()
if (unityEnabled && unityExported) {
    check(unityLibraryDir.resolve("../gradle.properties").exists()) {
        "Incomplete Unity export at $unityLibraryDir (gradle.properties missing); re-run VRMDroid > Export Android Library."
    }
    include(":unityLibrary")
    project(":unityLibrary").projectDir = unityLibraryDir

    // unityLibrary's build script reads unity.* properties (NDK/SDK paths, streaming assets)
    // from the exported project's gradle.properties; expose them to that module only.
    val unityProps = java.util.Properties().apply {
        unityLibraryDir.resolve("../gradle.properties").inputStream().use { load(it) }
    }
    gradle.beforeProject {
        if (path == ":unityLibrary") {
            unityProps.stringPropertyNames()
                .filter { it.startsWith("unity") }
                .forEach { extensions.extraProperties[it] = unityProps.getProperty(it) }
        }
    }
}
