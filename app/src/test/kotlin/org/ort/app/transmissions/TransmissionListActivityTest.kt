package org.ort.app.transmissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.Attribution
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * FR-A11Y-1 (build-plan P11): renders every row's [TransmissionListRowViewState.attributionLabel]
 * as plain text, so the four states are distinguishable on a screen reader or a greyscale
 * display exactly as they are in [TransmissionListViewStateMapperTest] — this test is what
 * proves the mapper's guarantee actually reaches the rendered view, not just the intermediate
 * data class.
 */
@RunWith(RobolectricTestRunner::class)
class TransmissionListActivityTest {

    @Test
    fun `each row's rendered text names its attribution state in plain text`() {
        val activity = Robolectric.buildActivity(TransmissionListActivity::class.java).create().get()
        activity.render(
            listOf(
                TransmissionRow("1", "kilo seven alpha bravo charlie", Attribution.confirmed("K7ABC", 0.9)),
                TransmissionRow("2", "unreadable noise", Attribution.unknown()),
            ),
        )

        assertEquals(2, activity.rowCount())
        val rendered = activity.renderedRowText()
        assertTrue(rendered[0].contains("CONFIRMED"))
        assertTrue(rendered[0].contains("K7ABC"))
        assertTrue(rendered[1].contains("UNKNOWN"))
    }

    @Test
    fun `an empty transmission list renders zero rows, not a crash`() {
        val activity = Robolectric.buildActivity(TransmissionListActivity::class.java).create().get()
        activity.render(emptyList())
        assertEquals(0, activity.rowCount())
    }
}
