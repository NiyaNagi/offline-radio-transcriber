pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "offline-radio-transcriber"

// Every module in technical design §2. JVM modules first, then the Android-only six.
include(
    ":core",
    ":onnx",
    ":capture-api",
    ":segment",
    ":asr-api",
    ":asr-sherpa",
    ":lexicon",
    ":identity",
    ":rig",
    ":eval",
    ":testing",
    // Android-only
    ":capture-android",
    ":rig-usb",
    ":data",
    ":net",
    ":pipeline",
    ":app",
)
