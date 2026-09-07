package org.ort.eval

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The harness reports callsign precision/recall over a labelled corpus (technical design §17). */
class HarnessCallsignPrecisionRecallTest {

    private fun testConfig() = HarnessConfig(
        fold = "dev",
        machine = "test-machine",
        provider = "cpu",
        threadCount = 1,
        runtimeVersion = "test",
        precisionTarget = 0.7f,
    )

    @Test
    fun `the harness reports nonzero precision and recall on a mostly-resolvable synthetic set`() {
        val occurrences = (1..20).map { positive("p$it", "VK3MMM") } + (1..5).map { negative("n$it") }
        val harness = Harness(bundledGrammar(), bundledCombiner())
        val report = harness.run(occurrences, occurrences, testConfig())
        assertTrue(report.callsignMetrics.precision > 0f, "expected some correct CONFIRMED calls")
        assertTrue(report.callsignMetrics.recall > 0f, "expected some recall on an easy synthetic set")
    }

    @Test
    fun `negative examples that never resolve a candidate contribute no false positives`() {
        val occurrences = (1..10).map { negative("n$it") }
        val harness = Harness(bundledGrammar(), bundledCombiner())
        val report = harness.run(occurrences, occurrences, testConfig())
        assertTrue(report.callsignMetrics.falsePositives == 0)
    }

    @Test
    fun `the report carries a run fingerprint with fold, machine, provider and thread count`() {
        val harness = Harness(bundledGrammar(), bundledCombiner())
        val occurrences = listOf(positive("p1", "VK3MMM"))
        val report = harness.run(occurrences, occurrences, testConfig())
        val fp = report.fingerprint
        assertTrue(fp.fold == "dev" && fp.machine == "test-machine" && fp.provider == "cpu" && fp.threadCount == 1)
    }
}
