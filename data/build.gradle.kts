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
    //
    // CI cross-platform fix (this task): adding this artifact alongside the Android-variant one
    // above is *not* enough on its own — both then sit on the same test classpath, each declaring
    // its own `androidx.sqlite.driver.bundled.BundledSQLiteDriver` class, and which jar's copy the
    // JVM classloader actually resolves is classpath-order-dependent, not something Gradle
    // guarantees stable across machines. That is a documented upstream hazard (Google's own fix
    // for JVM projects wrongly resolving the Android variant of `androidx.sqlite:sqlite-bundled`,
    // b/396148592 and b/396184120) and the recommended fix is dependency *substitution*, not
    // addition — see the `configurations.matching(...)` block below, which removes the Android
    // variant from every `*UnitTest*` classpath entirely so exactly one driver class is ever
    // present, deterministically, on every platform.
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    // ort.android-library brings JUnit4 for Robolectric's @RunWith; the vintage engine lets
    // those classes run on the JUnit Platform that ort.common's Test tasks are configured for.
    testRuntimeOnly(libs.junit.vintage.engine)
}

/**
 * CI cross-platform fix (this task; see the `testImplementation(libs.androidx.sqlite.bundled.jvm)`
 * comment above for the full mechanism). `TranscriptVersioningTest` and `WorkQueueTest` each
 * assert that a duplicate row **violates** one of [org.ort.data.OrtDatabase]'s hand-written
 * partial unique indexes (`idx_transcript_one_current`, `idx_wq_active`) — both passed on Windows
 * and failed on Linux CI (the insert silently succeeded instead of throwing), with every other
 * `:data` DB test — migrations, FTS5, ordinary CRUD — passing on both. That pattern (schema DDL
 * clearly applies; only *constraint enforcement* on a duplicate write differs by platform) is
 * consistent with two different builds of SQLite servicing the same test suite depending on which
 * of the two `BundledSQLiteDriver`-declaring jars a given machine's classpath happens to resolve
 * first — never reproduced directly on Linux (no Linux machine was available to this change), but
 * the classpath duplication itself *is* directly confirmed here (`./gradlew :data:dependencies
 * --configuration debugUnitTestRuntimeClasspath` lists both `androidx.sqlite:sqlite-bundled:2.5.2`
 * → `sqlite-bundled-android` and `androidx.sqlite:sqlite-bundled-jvm:2.5.2` on the same classpath)
 * and is independently documented as a real upstream hazard for exactly this Robolectric-on-JVM
 * shape (b/396148592, b/396184120) — so this removes it structurally (constitution VII) rather
 * than leaving the two artifacts to coexist. `WorkQueue.enqueue` (register this task) also gained
 * an application-level active-row guard as defense in depth for the one invariant
 * (`idx_wq_active`) a production code path writes without going through a single-transaction
 * read-modify-write already, in case this is not the whole mechanism.
 */
configurations.matching { it.name.contains("UnitTest") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.sqlite:sqlite-bundled"))
            .using(module("androidx.sqlite:sqlite-bundled-jvm:${libs.versions.androidxSqlite.get()}"))
            .because(
                "keep exactly one BundledSQLiteDriver-providing artifact on the Robolectric " +
                    "host-JVM test classpath — see the testImplementation(...bundled.jvm) comment above",
            )
    }
}
