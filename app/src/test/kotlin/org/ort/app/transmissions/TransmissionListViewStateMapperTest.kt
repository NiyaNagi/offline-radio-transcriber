package org.ort.app.transmissions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.Attribution
import org.ort.core.AttributionState
import org.ort.testing.Requirement

/**
 * FR-A11Y-1 / constitution I & VII (build-plan P11): this is the first point in the project
 * where an [Attribution] reaches a screen, so its four states must render distinguishably
 * **without colour** — verified here as plain-text assertions, which is what makes the guarantee
 * checkable on a JVM with no display at all, greyscale or otherwise.
 */
class TransmissionListViewStateMapperTest {

    private fun row(id: String, attribution: Attribution, transcript: String = "") =
        TransmissionRow(id, transcript, attribution)

    @Test
    @Requirement("FR-A11Y-1", "FR-SPK-10")
    fun `every one of the four attribution states renders a distinct label`() {
        val rows = listOf(
            row("1", Attribution.confirmed("K7ABC", 0.9)),
            row("2", Attribution.inferred("K7ABD", 0.6)),
            row("3", Attribution.ambiguous()),
            row("4", Attribution.unknown()),
        )
        val labels = rows.map { TransmissionListViewStateMapper.from(it).attributionLabel }
        assertEquals(labels.toSet().size, labels.size, "every state must render distinguishably")
    }

    @Test
    @Requirement("FR-A11Y-1")
    fun `each label carries a state name in plain text, not only a colour or icon`() {
        val labels = AttributionState.entries.map {
            val attribution = when (it) {
                AttributionState.CONFIRMED -> Attribution.confirmed("K7ABC", 0.9)
                AttributionState.INFERRED -> Attribution.inferred("K7ABC", 0.6)
                AttributionState.AMBIGUOUS -> Attribution.ambiguous()
                AttributionState.UNKNOWN -> Attribution.unknown()
            }
            it to TransmissionListViewStateMapper.from(row("x", attribution)).attributionLabel
        }
        labels.forEach { (state, label) -> assertTrue(label.contains(state.name), "label '$label' must name $state") }
    }

    @Test
    @Requirement("FR-SPK-10")
    fun `a CONFIRMED row shows its station id, an UNKNOWN row shows none`() {
        val confirmed = TransmissionListViewStateMapper.from(row("1", Attribution.confirmed("K7ABC", 0.9)))
        val unknown = TransmissionListViewStateMapper.from(row("2", Attribution.unknown()))
        assertTrue(confirmed.stationLabel!!.contains("K7ABC"))
        assertEquals(null, unknown.stationLabel)
    }
}
