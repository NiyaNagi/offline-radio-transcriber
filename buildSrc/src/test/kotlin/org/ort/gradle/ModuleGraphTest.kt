package org.ort.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The meta-guard for `./gradlew dependencyRules`. It must reject a forbidden edge before the
 * task is trusted to protect anything (constitution VII; build-plan P1 "write first").
 */
class ModuleGraphTest {

    @Test
    fun `the real design graph has no violations against itself`() {
        val actual = ModuleGraph.allowed.mapValues { it.value }
        assertEquals(emptyList<ModuleGraph.Violation>(), ModuleGraph.violations(actual))
    }

    @Test
    fun `a deliberate capture-api to asr-api edge is reported`() {
        val poisoned = ModuleGraph.allowed.toMutableMap()
        poisoned[":capture-api"] = poisoned.getValue(":capture-api") + ":asr-api"

        val violations = ModuleGraph.violations(poisoned)

        assertTrue(
            violations.any { it.from == ":capture-api" && it.to == ":asr-api" },
            "expected the :capture-api -> :asr-api edge to be flagged, got $violations",
        )
        assertTrue(
            violations.single().reason.contains("explicitly forbidden"),
            "capture must never block on inference — this is a named rule, not a soft one",
        )
    }

    @Test
    fun `only app may depend on net`() {
        val poisoned = ModuleGraph.allowed.toMutableMap()
        poisoned[":pipeline"] = poisoned.getValue(":pipeline") + ":net"
        assertTrue(ModuleGraph.violations(poisoned).any { it.from == ":pipeline" && it.to == ":net" })
    }

    @Test
    fun `the pure modules may not touch an Android module`() {
        val poisoned = ModuleGraph.allowed.toMutableMap()
        poisoned[":lexicon"] = poisoned.getValue(":lexicon") + ":data"
        assertTrue(ModuleGraph.violations(poisoned).any { it.from == ":lexicon" && it.to == ":data" })
    }

    @Test
    fun `a module missing from the design graph is itself a violation`() {
        val violations = ModuleGraph.violations(mapOf(":ghost" to setOf(":core")))
        assertTrue(violations.any { it.from == ":ghost" })
    }

    // P28/D42/FR-ANL: :telemetry is a fourth declared outbound channel, and analytics is exactly
    // the kind of thing capture must never block on or feed directly (constitution IV, VII) — the
    // forbidden edge is named explicitly, in both directions, the same way rule 2 already forbids
    // :capture-* -> :asr-*.

    @Test
    fun `capture-* to telemetry is forbidden`() {
        val poisoned = ModuleGraph.allowed.toMutableMap()
        poisoned[":capture-api"] = poisoned.getValue(":capture-api") + ":telemetry"
        poisoned[":capture-android"] = poisoned.getValue(":capture-android") + ":telemetry"

        val violations = ModuleGraph.violations(poisoned)

        assertTrue(
            violations.any { it.from == ":capture-api" && it.to == ":telemetry" && it.reason.contains("explicitly forbidden") },
            "expected :capture-api -> :telemetry to be named forbidden, got $violations",
        )
        assertTrue(
            violations.any { it.from == ":capture-android" && it.to == ":telemetry" && it.reason.contains("explicitly forbidden") },
            "expected :capture-android -> :telemetry to be named forbidden, got $violations",
        )
    }

    @Test
    fun `telemetry to capture-* is forbidden`() {
        val poisoned = ModuleGraph.allowed.toMutableMap()
        poisoned[":telemetry"] = poisoned.getValue(":telemetry") + ":capture-api"

        val violations = ModuleGraph.violations(poisoned)

        assertTrue(
            violations.any { it.from == ":telemetry" && it.to == ":capture-api" && it.reason.contains("explicitly forbidden") },
            "expected :telemetry -> :capture-api to be named forbidden, got $violations",
        )
    }

    @Test
    fun `telemetry itself depends on core only`() {
        assertEquals(setOf(":core"), ModuleGraph.allowed[":telemetry"])
    }
}
