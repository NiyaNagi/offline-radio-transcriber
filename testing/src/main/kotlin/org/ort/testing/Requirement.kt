package org.ort.testing

/**
 * Carries one or more requirement / acceptance-criterion ids from a test into the generated
 * coverage matrix (test-plan §3 step 4, §9). Use it when the test name cannot cleanly encode
 * the id, or when one test establishes several.
 *
 * The `coverageMatrix` task also recognises the id directly in a backtick-quoted test name
 * (`AC_47_...`, `FR_RUN_10a_...`), so this annotation is the explicit alternative, not the
 * only mechanism.
 *
 * ```
 * @Requirement("AC-91", "FR-RUN-15")
 * @Test fun sample_clock_survives_a_wall_clock_jump() { ... }
 * ```
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Requirement(vararg val ids: String)
