package org.ort.telemetry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement
import java.lang.reflect.Field
import java.lang.reflect.ParameterizedType

/**
 * AC-172/AC-175: the same closed-vocabulary discipline `RecorderEventVocabularyTest` (AC-141)
 * already established, applied to the analytics schema (FR-ANL-1..6). This inspects the compiled
 * field shape of every payload class and [AnalyticsProvenance] by plain [java.lang.reflect] (no
 * `kotlin-reflect` dependency in this module, same reasoning as the field-report vocabulary test)
 * — a claim about what the *type* can ever carry, not about today's call sites' sample data.
 */
class AnalyticsFieldVocabularyTest {

    private val tier1Classes: List<Class<*>> = listOf(
        AnalyticsTier1Payload.Crash::class.java,
        AnalyticsTier1Payload.Usage::class.java,
        AnalyticsTier1Payload.Performance::class.java,
        AnalyticsTier1Payload.CaptureHeartbeat::class.java,
        AnalyticsTier1Payload.SetupFunnel::class.java,
        AnalyticsTier1Payload.QualityStats::class.java,
    )

    private val tier2Classes: List<Class<*>> = listOf(
        AnalyticsTier2Payload.Transcript::class.java,
        AnalyticsTier2Payload.Correction::class.java,
    )

    private val tier3Classes: List<Class<*>> = listOf(AnalyticsTier3Payload::class.java)

    private val allPayloadClasses = tier1Classes + tier2Classes + tier3Classes

    /** FR-ANL-5/AC-175: in no tier, and no combination of tiers, may any of these three
     * categories appear — checked as an EXACT (not substring) lowercase field-name match, so a
     * legitimate technical field like `threadName` (a JVM thread name, not a user-supplied one)
     * or `deviceModel` is never a false positive. */
    private val disallowedExactFieldNames = setOf(
        "name",
        "username",
        "operatorname",
        "stationname",
        "userscallsignname",
        "stationknowledge",
        "location",
        "latitude",
        "longitude",
        "gridsquare",
        "preciselocation",
    )

    /** FR-ANL-2: tier 1 additionally may never carry transcript text or a callsign — tier 2 is
     * the only tier allowed either, by name, so this check applies to tier 1's classes only. */
    private val tier1DisallowedFieldNames = disallowedExactFieldNames + setOf(
        "transcript",
        "transcripttext",
        "callsign",
        "text",
        "asrhypothesis",
        "usercorrection",
        "audio",
        "audiobase64",
        "correctedtranscript",
    )

    private fun isSynthetic(field: Field): Boolean =
        field.isSynthetic || field.name == "Companion" || field.name.startsWith("$")

    private fun declaredNonSyntheticFields(klass: Class<*>): List<Field> = klass.declaredFields.filterNot(::isSynthetic)

    @Test
    @Requirement("AC-172", "FR-ANL-2")
    fun `AC_172_tier1 payload classes never carry transcript, callsign, name, station knowledge or location fields`() {
        val offenders = tier1Classes.flatMap { klass ->
            declaredNonSyntheticFields(klass)
                .filter { it.name.lowercase() in tier1DisallowedFieldNames }
                .map { "${klass.simpleName}.${it.name}" }
        }
        assertTrue(offenders.isEmpty(), "tier 1 carries a disallowed field: $offenders")
    }

    @Test
    @Requirement("AC-175", "FR-ANL-5")
    fun `AC_175_no payload class of any tier ever carries a name, station-knowledge or location field`() {
        val offenders = (allPayloadClasses + AnalyticsProvenance::class.java).flatMap { klass ->
            declaredNonSyntheticFields(klass)
                .filter { it.name.lowercase() in disallowedExactFieldNames }
                .map { "${klass.simpleName}.${it.name}" }
        }
        assertTrue(offenders.isEmpty(), "found a name/station-knowledge/location field: $offenders")
    }

    @Test
    @Requirement("AC-173", "FR-ANL-3")
    fun `AC_173_tier2's closed field list is transcript text, callsigns and hypothesis-correction pairs`() {
        val transcriptFields = declaredNonSyntheticFields(AnalyticsTier2Payload.Transcript::class.java)
            .map { it.name }.toSet()
        val correctionFields = declaredNonSyntheticFields(AnalyticsTier2Payload.Correction::class.java)
            .map { it.name }.toSet()

        assertEquals(setOf("text", "callsign"), transcriptFields)
        assertEquals(setOf("asrHypothesis", "userCorrection", "callsign"), correctionFields)
    }

    @Test
    @Requirement("AC-174", "FR-ANL-4")
    fun `AC_174_tier3's closed field list is exactly retained audio with its corrected transcript`() {
        val fields = declaredNonSyntheticFields(AnalyticsTier3Payload::class.java).map { it.name }.toSet()
        assertEquals(setOf("audioBase64", "correctedTranscript"), fields)
    }

    /** [ParameterizedType] element extraction — only used to allow `List<String>`/`Map<String,
     * Double>` shapes, mirroring `RecorderEventVocabularyTest`'s own `listElementTypeOf`. */
    private fun typeArgsOf(field: Field): List<Class<*>> =
        ((field.genericType as? ParameterizedType)?.actualTypeArguments)?.mapNotNull { it as? Class<*> }.orEmpty()

    @Test
    @Requirement("AC-178", "FR-ANL-8")
    fun `AC_178_the provenance envelope carries exactly FR-ANL-8's fields`() {
        val expected = setOf(
            "installId", "sessionId", "overId", "appVersion", "buildHash", "modelIds", "modelShas",
            "executionProvider", "deviceModel", "soc", "detectedTier", "captureMode", "rigModule",
            "band", "schemaVersion",
        )
        val actual = declaredNonSyntheticFields(AnalyticsProvenance::class.java).map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    @Requirement("AC-178")
    fun `AC_178_modelIds and modelShas are closed list-of-string fields, not an open map`() {
        val listFields = listOf("modelIds", "modelShas")
        for (name in listFields) {
            val field = AnalyticsProvenance::class.java.getDeclaredField(name)
            assertEquals(String::class.java, typeArgsOf(field).singleOrNull(), "$name's element type")
        }
    }
}
