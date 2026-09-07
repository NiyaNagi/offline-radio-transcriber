import com.android.build.gradle.LibraryExtension

plugins {
    id("ort.android-library")
    alias(libs.plugins.ksp)
}

// :data — Room, FTS5, migrations, the durable work queue and the transmission lifecycle
// (technical design §7, §12; functional spec §7.14, §8; build-plan P5). Depends on :core only
// per the module graph. Robolectric backs the DB tests — real SQLite, no device.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "false")
}

// Exported schemas, committed per technical design §12.3, doubling as the fixture-per-released-
// version set the migration test (AC-53) runs Room's MigrationTestHelper against.
extensions.configure<LibraryExtension> {
    sourceSets.getByName("test") {
        assets.srcDirs("$projectDir/schemas")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(project(":testing"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    // ort.android-library brings JUnit4 for Robolectric's @RunWith; the vintage engine lets
    // those classes run on the JUnit Platform that ort.common's Test tasks are configured for.
    testRuntimeOnly(libs.junit.vintage.engine)
}
