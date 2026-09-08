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
    // R-204: the platform/OEM SQLite Room would otherwise open (and Robolectric's shadow SQLite)
    // cannot be trusted to ship fts5 — the API 34 reference emulator's does not. This bundles a
    // known-good SQLite build (fts5 included) and is wired in via RoomDatabase.Builder.setDriver
    // in OrtDatabase.create, so both the shipped app and every JVM/Robolectric test use it.
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.room.compiler)

    testImplementation(project(":testing"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    // R-204: `implementation(libs.androidx.sqlite.bundled)` above resolves, for a Robolectric
    // unit test's runtime classpath, to that library's Android-variant artifact — even though the
    // test executes on the host JVM, not a device — because AGP does not switch the unit-test
    // classpath's `org.gradle.jvm.environment` attribute away from `android`. That artifact's
    // native library is packaged for APK installation (an .so per Android ABI under `lib/<abi>/`),
    // which the host JVM cannot `System.loadLibrary` from, so the JVM-target artifact is added
    // explicitly here to give Robolectric a native library it actually can load.
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    // ort.android-library brings JUnit4 for Robolectric's @RunWith; the vintage engine lets
    // those classes run on the JUnit Platform that ort.common's Test tasks are configured for.
    testRuntimeOnly(libs.junit.vintage.engine)
}
