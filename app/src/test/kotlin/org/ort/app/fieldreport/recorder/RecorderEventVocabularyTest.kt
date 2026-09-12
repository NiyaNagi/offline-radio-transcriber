package org.ort.app.fieldreport.recorder

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement
import java.lang.reflect.Field
import java.lang.reflect.ParameterizedType

/**
 * AC-141: "The debug-build session recorder writes only its closed event vocabulary — no
 * free-text field appears in its output under any recorded event". [RecorderEvent]'s own doc
 * comment already argues this in prose; this test proves the strongest form of it a test can: it
 * inspects [RecorderEvent]'s six sealed variants (and the one nested [AudioDeviceSnapshot]) **by
 * plain Java reflection over their compiled fields** and asserts a structural property of the
 * *type*, not of any particular instance's rendered output. (Plain `java.lang.reflect`, not
 * `kotlin-reflect`'s `KClass` API — this module has no `kotlin-reflect` dependency to add, and
 * adding one is outside this package's ownership; every property this vocabulary declares is a
 * `val` in a `data class`/`data object`, which the Kotlin compiler already turns into an ordinary
 * private backing field plus a getter, so plain [Class.getDeclaredFields] sees exactly the same
 * shape `kotlin-reflect` would report.)
 *
 * **What this test proves.** For every field on every one of [RecorderEvent]'s six variants, plus
 * [AudioDeviceSnapshot]: the field's type is an enum, a primitive/boxed `Boolean`/`Int`/`Long`/
 * `Double`, a `List<Int>`, or — the one deliberate, named exception, asserted by a second test to
 * be *exactly* two fields and no more — `productName`/`address` on [AudioDeviceSnapshot]. No field
 * anywhere is named `message`, `detail`, `note`, `text`, `description`, `reason` or `cause` (the
 * exact shape a future, well-intentioned addition would take to smuggle prose back in). Because
 * this walks the compiled class shape itself rather than a constructed sample, it holds for
 * *every* possible instance of *every* event type this vocabulary can ever construct — a claim
 * about the vocabulary itself, not about today's call sites' sample data.
 *
 * **What this test does not prove.** It does not prove that a *string a caller already had* (a
 * live transcript, a callsign) can never end up as the *value* of
 * [AudioDeviceSnapshot.productName]/`.address` — those two fields exist precisely to carry a
 * platform-reported device name/address, and nothing here stops a caller from constructing
 * `AudioDeviceSnapshot(productName = "N9ABC just said this", ...)` by hand. That misuse is a
 * code-review question at the real call site (outside this package's ownership this round —
 * `AndroidAudioIo.kt`), not something a closed-type signature can rule out for a field whose whole
 * job is to carry platform-reported text. Every *other* field in this vocabulary has no such gap:
 * there is no other `String`-typed field anywhere in the hierarchy for a caller to misuse in the
 * first place.
 */
class RecorderEventVocabularyTest {

    private val eventAndSupportClasses: List<Class<*>> = listOf(
        RecorderEvent.DestinationChanged::class.java,
        RecorderEvent.ControlTapped::class.java,
        RecorderEvent.PermissionResultRecorded::class.java,
        RecorderEvent.CaptureStateChanged::class.java,
        RecorderEvent.SetupStepChanged::class.java,
        RecorderEvent.AudioDevicesEnumerated::class.java,
        AudioDeviceSnapshot::class.java,
    )

    private val allowedFreeStringFields = setOf(
        AudioDeviceSnapshot::class.java to "productName",
        AudioDeviceSnapshot::class.java to "address",
    )

    private val disallowedNames = setOf("message", "detail", "note", "text", "description", "reason", "cause")

    private val allowedPrimitiveTypes = setOf(
        Boolean::class.java,
        java.lang.Boolean::class.java,
        Int::class.java,
        Integer::class.java,
        Long::class.java,
        java.lang.Long::class.java,
        Double::class.java,
        java.lang.Double::class.java,
    )

    /** Kotlin's compiler-generated fields (a data class's own `Companion` reference, coroutine or
     * serialization synthetics) — never a real property this vocabulary declares. */
    private fun isSynthetic(field: Field): Boolean =
        field.isSynthetic || field.name == "Companion" || field.name.startsWith("$")

    /** The one offense [field] (declared on [klass]) commits against the closed-type discipline,
     * or `null` if it is one of the allowed shapes. Extracted out of the test method itself so the
     * per-field decision is a single flat `when`, not a loop nested inside a loop with its own
     * early exits. */
    private fun offenseFor(klass: Class<*>, field: Field): String? {
        val qualifiedName = "${klass.simpleName}.${field.name}"
        val elementType = listElementTypeOf(field)

        return when {
            field.name.lowercase() in disallowedNames -> "$qualifiedName is named like a free-text field"
            field.type == String::class.java && (klass to field.name) !in allowedFreeStringFields ->
                "$qualifiedName is an un-allow-listed String field"
            field.type == String::class.java -> null
            field.type.isEnum -> null
            field.type in allowedPrimitiveTypes -> null
            field.type != List::class.java && field.type != java.util.List::class.java ->
                "$qualifiedName has un-recognised, potentially open-ended type ${field.type}"
            elementType == Int::class.java || elementType == Integer::class.java -> null
            elementType != null && elementType in eventAndSupportClasses -> null
            else -> "$qualifiedName has an un-allow-listed List element type $elementType"
        }
    }

    private fun listElementTypeOf(field: Field): Class<*>? =
        ((field.genericType as? ParameterizedType)?.actualTypeArguments?.firstOrNull()) as? Class<*>

    @Test
    @Requirement("AC-141")
    fun `AC_141_every declared field in the RecorderEvent vocabulary is a closed type`() {
        val offenders = eventAndSupportClasses.flatMap { klass ->
            klass.declaredFields.filterNot(::isSynthetic).mapNotNull { field -> offenseFor(klass, field) }
        }

        assertTrue(
            offenders.isEmpty(),
            "found free-text-shaped fields in the recorder event vocabulary: $offenders",
        )
    }

    @Test
    @Requirement("AC-141")
    fun `AC_141_the audio device snapshot exception is exactly productName and address, nothing more`() {
        val stringFields = AudioDeviceSnapshot::class.java.declaredFields
            .filter { !isSynthetic(it) && it.type == String::class.java }
            .map { it.name }
            .toSet()

        assertTrue(
            stringFields == setOf("productName", "address"),
            "AudioDeviceSnapshot's String fields changed from the two FR-OBS-6 explicitly names " +
                "('productName', 'address') to $stringFields — if this is deliberate, update " +
                "RecorderEventVocabularyTest's own allow-list in the same change",
        )
    }

    @Test
    @Requirement("AC-141")
    fun `AC_141_no event or support class has a field named like a free-text parameter`() {
        val offending = eventAndSupportClasses
            .flatMap { it.declaredFields.toList() }
            .filterNot(::isSynthetic)
            .map { it.name.lowercase() }
            .filter { it in disallowedNames }

        assertTrue(offending.isEmpty(), "found free-text-named fields: $offending")
    }
}
