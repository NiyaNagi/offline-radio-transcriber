package org.ort.telemetry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement
import java.io.File

/**
 * Register R-1193/R-1196 — the positive half of AC-173 and AC-174, and the only kind of assertion a
 * dead implementation cannot satisfy by doing nothing.
 *
 * **Why this test exists.** AC-174 was scored covered by
 * `AnalyticsControllerTest.AC_174_tier 3 is never queued while disabled` and by
 * `AnalyticsFieldVocabularyTest.AC_174_tier3's closed field list …`. Both are true of a tier-3
 * implementation that is entirely absent: the first is a negative ("nothing was queued"), the
 * second inspects a declared shape. Every line of tier 3 could be deleted and both would stay
 * green. Tier 2 had a positive counterpart (`AC_173_tier 2 queues once enabled`, which constructs a
 * payload in a test) and tier 3 had none — R-1196's "tell".
 *
 * **What it asserts instead.** The *fact the shipped copy claims*: that no production source set in
 * this repository constructs an [AnalyticsTier3Payload] or an [AnalyticsTier2Payload.Transcript].
 * `SettingsAnalyticsScreen`'s opt-in disclosure ("No audio is ever recorded for analytics here …
 * Tier 2 collects only the callsign you chose and the one it replaced") is a claim about production
 * code, so it is tested against production code. The moment either producer is built, this test
 * goes red and the change that built it must delete or amend that copy in the same commit —
 * which is the whole point: the claim and the code can no longer drift apart silently.
 *
 * **Anti-false-green.** A source scan that finds nothing because it scanned nothing is the classic
 * way this style of test lies (constitution II). `the scan finds the one tier-2 producer this build
 * really has` is the positive control: it asserts the scanner *does* locate
 * `AnalyticsTier2Payload.Correction(` in `:app`'s own `src/main`, so a zero result from the other
 * two queries means "absent", never "not looked for".
 *
 * **Stated limitation.** This is a plain-text scan of committed `src/main` Kotlin, the same
 * technique and the same caveat as `org.ort.net.NetManifestPermissionTest`: it proves what is
 * *written* in source, not what a build reaches at runtime, and a construction hidden behind
 * `import … as SomethingElse` would slip past it. R-1197 tracks the general reachability guard.
 */
class AnalyticsProducerReachabilityTest {

    /** Walk up from the test's working directory to the repo root (identified by settings.gradle.kts). */
    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("Could not locate repo root (settings.gradle.kts) above ${File(".").absolutePath}")
        }
        return dir
    }

    /** Every committed `src/main` Kotlin file in every module — never `src/test`, `src/debug` or `build/`. */
    private fun productionKotlinSources(): List<File> =
        repoRoot().listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            .orEmpty()
            .map { File(it, "src/main") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }

    private fun filesMatching(pattern: Regex): List<String> = productionKotlinSources()
        .filter { pattern.containsMatchIn(it.readText()) }
        .map { it.relativeTo(repoRoot()).path.replace('\\', '/') }

    // `(?<!class )` skips the payload's own `data class` declaration; every other occurrence of the
    // name followed by `(` is a construction.
    private val tier3Construction = Regex("""(?<!class )AnalyticsTier3Payload\(""")
    private val tier2TranscriptConstruction = Regex("""AnalyticsTier2Payload\.Transcript\(""")
    private val tier2CorrectionConstruction = Regex("""AnalyticsTier2Payload\.Correction\(""")

    @Test
    @Requirement("AC-174", "FR-ANL-4", "R-1193", "R-1196")
    fun `AC_174_no production source constructs a tier 3 payload, which is what the screen says`() {
        val offenders = filesMatching(tier3Construction)

        assertEquals(
            emptyList<String>(),
            offenders,
            "A tier-3 (audio) analytics producer now exists in production source. " +
                "SettingsAnalyticsScreen and WelcomeScreen both tell the operator that no audio is " +
                "ever recorded for analytics in this build (register R-1193); that copy, its " +
                "artboards and docs/privacy-policy.md must be corrected in the same change that " +
                "makes this red. Found in: $offenders",
        )
    }

    @Test
    @Requirement("AC-173", "FR-ANL-3", "R-1193")
    fun `AC_173_no production source constructs a tier 2 transcript payload, which is what the screen says`() {
        val offenders = filesMatching(tier2TranscriptConstruction)

        assertEquals(
            emptyList<String>(),
            offenders,
            "A tier-2 transcript-text producer now exists in production source. The Settings " +
                "analytics screen tells the operator tier 2 collects only the callsign pair and no " +
                "transcript text (register R-1193); correct that copy, the artboard and the privacy " +
                "policy in the same change. Found in: $offenders",
        )
    }

    @Test
    @Requirement("AC-173", "FR-ANL-3")
    fun `AC_173_the scan finds the one tier 2 producer this build really has`() {
        val sources = productionKotlinSources()
        assertTrue(
            sources.size > MINIMUM_PLAUSIBLE_SOURCE_COUNT,
            "Only ${sources.size} production Kotlin files were scanned — the walk is broken, so " +
                "the two absence assertions above would pass without looking at anything.",
        )

        assertEquals(
            listOf("app/src/main/kotlin/org/ort/app/analytics/CorrectionAnalytics.kt"),
            filesMatching(tier2CorrectionConstruction),
            "CorrectionAnalytics is the one live analytics producer above tier 1. If it moved, this " +
                "test's positive control moved with it; if it vanished, tier 2 is now as dead as " +
                "tier 3 and the Settings copy must say so.",
        )
    }

    private companion object {
        /** The repository had well over a thousand `src/main` Kotlin files when this was written. */
        const val MINIMUM_PLAUSIBLE_SOURCE_COUNT = 200
    }
}
