plugins {
    id("org.jetbrains.kotlin.jvm")
    id("ort.common")
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.3")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.11.3")
}
