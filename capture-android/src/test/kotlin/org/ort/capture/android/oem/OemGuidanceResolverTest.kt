package org.ort.capture.android.oem

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OemGuidanceResolverTest {

    @Test
    @Requirement("AC-66", "FR-SVC-5a")
    fun `AC_66 a deep link nothing on the device can handle is dropped, the step's text is kept`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val resolver = OemGuidanceResolver(context)
        val guidance = OemGuidanceTable.forDevice("OPPO", "oppo")

        val resolved = resolver.resolve(guidance)

        assertEquals(guidance.steps.size, resolved.steps.size)
        // Robolectric's default PackageManager resolves nothing for these OEM-specific actions.
        resolved.steps.forEach { assertNull(it.deepLinkAction) }
        assertEquals(guidance.steps.map { it.title }, resolved.steps.map { it.title })
    }
}
