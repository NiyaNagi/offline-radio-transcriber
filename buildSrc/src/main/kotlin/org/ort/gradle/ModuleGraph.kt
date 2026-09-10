package org.ort.gradle

/**
 * The module dependency graph from technical design §2, "Allowed dependency edges".
 *
 * This is the single source of truth the [DependencyRulesTask] enforces. A forbidden edge
 * fails `./gradlew dependencyRules` — the rule is structural, per constitution Principle VII,
 * not a convention someone has to remember.
 *
 * Only *main* compile configurations are checked (`api`, `implementation`, `compileOnly`).
 * Test scope is deliberately excluded: `:testing` fakes are meant to be reachable from any
 * module's tests.
 */
object ModuleGraph {

    /** module path -> the set of module paths it is permitted to depend on at compile time. */
    val allowed: Map<String, Set<String>> = mapOf(
        ":core" to emptySet(),
        ":onnx" to setOf(":core"),
        ":capture-api" to setOf(":core"),
        ":lexicon" to setOf(":core"),
        ":rig" to setOf(":core"),
        ":llm-api" to setOf(":core"),
        ":segment" to setOf(":core", ":onnx"),
        ":asr-api" to setOf(":core", ":onnx"),
        ":identity" to setOf(":core", ":onnx"),
        ":asr-sherpa" to setOf(":core", ":onnx", ":asr-api"),
        ":capture-android" to setOf(":core", ":capture-api"),
        ":rig-usb" to setOf(":core", ":rig"),
        ":rig-bluetooth" to setOf(":core", ":rig"),
        ":llm-mediapipe" to setOf(":core", ":llm-api"),
        ":data" to setOf(":core"),
        ":net" to setOf(":core"),
        ":testing" to setOf(":core"),
        ":pipeline" to setOf(
            ":core", ":onnx", ":capture-api", ":capture-android", ":segment",
            ":asr-api", ":asr-sherpa", ":lexicon", ":identity", ":rig", ":rig-usb", ":data",
            ":llm-api", ":llm-mediapipe", ":rig-bluetooth",
        ),
        ":eval" to setOf(
            ":core", ":onnx", ":capture-api", ":segment", ":asr-api", ":asr-sherpa",
            ":lexicon", ":identity", ":rig", ":testing", ":llm-api",
        ),
        // ":lexicon" added for the Models screen's lexicon-import flow (R-154, FR-LEX-30, FR-AST-2):
        // ":app" needs LexiconImportValidator/LexiconImportInstaller directly, the same way F-008
        // added ":net" here for model downloads — no edge this adds is on the forbidden list below
        // (only ":capture-*" -> asr/lexicon/identity is named, and ":app" is not a capture module).
        // ":rig" and ":llm-api" added by Wave F scaffolding (WP0'): the setup UI reads the rig
        // catalogue directly (FR-RIG-16) and the Settings LLM toggle reads engine state through
        // the :llm-api contract (FR-DIG-3b) — neither is on the forbidden list below.
        ":app" to setOf(":pipeline", ":data", ":net", ":core", ":lexicon", ":rig", ":llm-api"),
    )

    /** The forbidden edges the design calls out by name (technical design §2, last paragraph). */
    val explicitlyForbidden: Set<Pair<String, String>> = buildSet {
        // rule 2 — capture must never block on inference. :llm-api/:llm-mediapipe join this list
        // at Wave F (WP0'): capture never blocks on inference, and an LLM is inference just as
        // much as ASR/lexicon/identity are (constitution IV).
        for (c in listOf(":capture-api", ":capture-android")) {
            for (i in listOf(":asr-api", ":asr-sherpa", ":lexicon", ":identity", ":llm-api", ":llm-mediapipe")) {
                add(c to i)
            }
        }
        // rule 5 — only :app may reach :net
        for (m in allowed.keys) if (m != ":app" && m != ":net") add(m to ":net")
        // rule 3 — the pure modules take nothing Android. :rig-bluetooth/:llm-mediapipe join the
        // Android list at Wave F (WP0').
        for (pure in listOf(":core", ":lexicon", ":eval")) {
            for (android in listOf(
                ":capture-android", ":rig-usb", ":rig-bluetooth", ":data", ":net",
                ":llm-mediapipe", ":pipeline", ":app",
            )) {
                add(pure to android)
            }
        }
    }

    data class Violation(val from: String, val to: String, val reason: String) {
        override fun toString(): String = "  $from  ->  $to   ($reason)"
    }

    /**
     * @param actual module path -> the module paths it actually declares a compile dependency on.
     * @return every declared edge that the design does not permit, most specific reason first.
     */
    fun violations(actual: Map<String, Set<String>>): List<Violation> {
        val out = mutableListOf<Violation>()
        for ((from, targets) in actual) {
            val permitted = allowed[from]
            if (permitted == null) {
                out += Violation(from, "?", "module is not in the design graph (ModuleGraph.allowed)")
                continue
            }
            for (to in targets) {
                if (to == from) continue
                if (to !in permitted) {
                    val named = (from to to) in explicitlyForbidden
                    out += Violation(
                        from,
                        to,
                        if (named) "explicitly forbidden by technical design §2" else "not in the allowed set for $from",
                    )
                }
            }
        }
        return out.sortedWith(compareBy({ it.from }, { it.to }))
    }
}
