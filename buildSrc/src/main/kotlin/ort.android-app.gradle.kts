import com.android.build.gradle.internal.dsl.BaseAppModuleExtension

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("ort.common")
}

extensions.configure<BaseAppModuleExtension> {
    namespace = "org.ort.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.ort.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
    jvmToolchain(17)
}

dependencies {
    "androidTestImplementation"("androidx.test.ext:junit:1.2.1")
    "androidTestImplementation"("androidx.test:runner:1.6.2")
    "androidTestImplementation"("androidx.test:core:1.6.1")
}
