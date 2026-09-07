package org.ort.eval

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.CorpusManifest
import org.ort.testing.Fold

/** AC-100 / FR-TST-7: the harness never reads the eval fold except through the sanctioned,
 * explicit opt-in the manifest itself enforces (constitution VI). */
class EvalFoldGateTest {

    private val manifest = CorpusManifest.parse(
        """
        train-1	train	synthetic	/tmp/train-1.flac	true
        dev-1	dev	synthetic	/tmp/dev-1.flac	true
        eval-1	eval	real	/tmp/eval-1.flac	false
        """.trimIndent(),
    )

    @Test
    fun `AC_100 reading the eval fold through the harness without the explicit flag is refused`() {
        assertThrows(IllegalStateException::class.java) {
            ManifestHarness.occurrencesForFold(manifest, Fold.EVAL) { emptyList() }
        }
    }

    @Test
    fun `AC_100 the eval fold is readable only with the explicit opt-in`() {
        val entries = ManifestHarness.occurrencesForFold(manifest, Fold.EVAL, allowEval = true) { emptyList() }
        assertTrue(entries.isEmpty()) // no occurrences wired in this fixture, but the read was not refused
    }

    @Test
    fun `the dev fold needs no opt-in`() {
        val entries = ManifestHarness.occurrencesForFold(manifest, Fold.DEV) { entry ->
            listOf(positive(entry.id, "VK3MMM"))
        }
        assertTrue(entries.size == 1 && entries.single().id == "dev-1")
    }
}
