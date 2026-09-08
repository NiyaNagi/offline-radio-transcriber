package org.ort.app.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R-005 (ui-conformance-plan WP1), guide §4: "Nothing else. A size not in this table is a defect."
 * — every [OrtType] style's size must be one of the sixteen sizes the guide's typography table
 * actually uses, and every style must carry the family/weight the same table specifies.
 */
class OrtTypeTest {

    /** Every distinct size in guide §4's table, px == sp on the artboards (§4: "ship as sp"). */
    private val allowedSizesSp =
        setOf(27f, 19f, 17f, 16f, 15f, 14.5f, 14f, 13.5f, 13f, 12.5f, 12f, 11.5f, 11f, 10.5f, 10f, 9.5f)

    private val allStyles = listOf(
        OrtType.screenTitle, OrtType.callsignTitle, OrtType.cardTitle, OrtType.figure, OrtType.drawerTitle,
        OrtType.bodyProse, OrtType.rowTitle, OrtType.control, OrtType.subtitle, OrtType.transcript,
        OrtType.textAction, OrtType.cardBody, OrtType.chip, OrtType.subLine, OrtType.sectionLabel,
        OrtType.callsignRow, OrtType.callsignCard, OrtType.timeFreq, OrtType.signal, OrtType.scoreChip,
        OrtType.badge, OrtType.axis, OrtType.columnHeader,
    )

    @Test
    fun `guide_s4_every_OrtType_style_size_is_in_the_ramp`() {
        allStyles.forEach { style ->
            assertTrue(
                style.fontSize.value in allowedSizesSp,
                "size ${style.fontSize.value} is not one of guide §4's sixteen sizes",
            )
        }
    }

    @Test
    fun `guide_s4_mono_rows_use_OrtType_mono_and_sans_rows_use_OrtType_sans`() {
        val monoStyles = listOf(
            OrtType.callsignTitle, OrtType.figure, OrtType.callsignRow, OrtType.callsignCard,
            OrtType.timeFreq, OrtType.signal, OrtType.scoreChip, OrtType.axis, OrtType.columnHeader,
        )
        monoStyles.forEach { assertEquals(FontFamily.Monospace, it.fontFamily) }

        val sansStyles = listOf(
            OrtType.screenTitle, OrtType.cardTitle, OrtType.drawerTitle, OrtType.bodyProse,
            OrtType.rowTitle, OrtType.control, OrtType.subtitle, OrtType.transcript, OrtType.textAction,
            OrtType.cardBody, OrtType.chip, OrtType.subLine, OrtType.sectionLabel, OrtType.badge,
        )
        sansStyles.forEach { assertEquals(FontFamily.SansSerif, it.fontFamily) }
    }

    @Test
    fun `guide_s4_the_two_600-weight_display_rows_use_SemiBold`() {
        assertEquals(FontWeight.SemiBold, OrtType.screenTitle.fontWeight)
        assertEquals(FontWeight.SemiBold, OrtType.sectionLabel.fontWeight)
        assertEquals(FontWeight.SemiBold, OrtType.callsignRow.fontWeight)
        assertEquals(FontWeight.SemiBold, OrtType.badge.fontWeight)
    }

    @Test
    fun `R_005 typography maps bodyMedium to control 14sp, not the 12-5 caption it was before`() {
        assertEquals(14f, OrtType.typography.bodyMedium.fontSize.value)
        assertEquals(15f, OrtType.typography.bodyLarge.fontSize.value)
        assertEquals(12.5f, OrtType.typography.bodySmall.fontSize.value)
        assertEquals(11f, OrtType.typography.labelSmall.fontSize.value)
        assertEquals(27f, OrtType.typography.titleLarge.fontSize.value)
        assertEquals(19f, OrtType.typography.titleMedium.fontSize.value)
    }

    @Test
    fun `R_005 legacy aliases still resolve and match the row they document`() {
        assertEquals(OrtType.screenTitle, OrtType.titleLarge)
        assertEquals(OrtType.callsignRow, OrtType.callsign)
        assertEquals(OrtType.bodyProse, OrtType.body)
        assertEquals(OrtType.cardBody, OrtType.caption)
    }
}
