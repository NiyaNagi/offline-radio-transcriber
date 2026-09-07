import com.android.build.gradle.LibraryExtension

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("ort.common")
}

extensions.configure<LibraryExtension> {
    namespace = "org.ort." + project.name.replace('-', '.')
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
    jvmToolchain(17)
}

dependencies {
    "testImplementation"("junit:junit:4.13.2")
}
