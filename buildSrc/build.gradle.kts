plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("com.android.tools.build:gradle:8.7.2")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    // P13: the Compose compiler is its own Gradle plugin from Kotlin 2.0 on, pinned to the exact
    // Kotlin version above — it must be on buildSrc's own classpath for the precompiled
    // `ort.android-app` convention plugin to apply it.
    implementation("org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:2.0.21")
    implementation("org.jlleitschuh.gradle:ktlint-gradle:12.1.1")
    implementation("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.23.7")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
