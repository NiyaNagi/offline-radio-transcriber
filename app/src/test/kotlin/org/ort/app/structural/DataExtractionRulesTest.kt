package org.ort.app.structural

import android.content.res.XmlResourceParser
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.R
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * R-1106 (register; technical-design §12.4, NFR-6a, FR-PLT-5, FR-STO-8) — [AllowBackupTest]
 * already proves `android:allowBackup="false"`; the technical design calls for both that flag
 * **and** an explicit `data_extraction_rules` exclusion, declared where API 31+'s own backup and
 * device-transfer framework reads it, rather than left to that framework's own defaults. This
 * class proves the second half exists and says what it must: `R.xml.data_extraction_rules`
 * compiles as a real resource, and its content excludes the app's entire private storage tree
 * from *both* cloud backup and device-to-device transfer — never one without the other, since
 * either alone would leave a route the constitution does not permit (audio, the database, station
 * knowledge and any token are all under the app's private storage tree, per
 * FR-PLT-5/FR-STO-8/AC-81).
 *
 * JVM/Robolectric only, reading the real compiled resource — not a hand-copy of the XML. **What
 * this does not prove:** that `AndroidManifest.xml`'s own `android:dataExtractionRules` attribute
 * actually points at this resource rather than some other one, or none. The field that would let
 * a JVM test check that — `ApplicationInfo.dataExtractionRulesRes` — is not part of the public
 * SDK 34 stub jar this module compiles against (confirmed directly: referencing it is a compile
 * error, not merely an unpopulated Robolectric shadow), so a unit test cannot read it at all. The
 * manifest attribute itself is a one-line, hand-reviewable wire-up (`AndroidManifest.xml`'s own
 * `<application>` tag) rather than logic, so this is reviewed by reading the manifest rather than
 * asserted by a test; it does not need a device to confirm, unlike the accessibility fixes this
 * same change carries.
 */
@RunWith(RobolectricTestRunner::class)
class DataExtractionRulesTest {

    @Test
    @Requirement("R-1106")
    fun `R_1106 the app declares its own data extraction rules resource`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()

        // See this class's own doc comment for why the manifest's own `android:dataExtractionRules`
        // wire-up is reviewed rather than asserted here. This test and the one below cover the
        // resource itself: that `app/src/main/res/xml/data_extraction_rules.xml` exists, compiles
        // to a real resource id, and excludes what technical-design §12.4 requires.
        val resId = context.resources.getIdentifier("data_extraction_rules", "xml", context.packageName)
        assertTrue("expected an R.xml.data_extraction_rules resource to exist", resId != 0)
        assertTrue(
            "expected R.xml.data_extraction_rules to resolve to the real generated resource id",
            resId == R.xml.data_extraction_rules,
        )
    }

    @Test
    @Requirement("R-1106")
    fun `R_1106 the rules exclude the whole app sandbox from cloud backup and device transfer`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val excludedDomainsBySection = excludedDomainsBySection(context.resources.getXml(R.xml.data_extraction_rules))

        assertTrue(
            "expected a <cloud-backup> section excluding domain=\"root\", got " +
                excludedDomainsBySection["cloud-backup"],
            excludedDomainsBySection["cloud-backup"]?.contains("root") == true,
        )
        assertTrue(
            "expected a <device-transfer> section excluding domain=\"root\", got " +
                excludedDomainsBySection["device-transfer"],
            excludedDomainsBySection["device-transfer"]?.contains("root") == true,
        )
    }

    /**
     * Walks [parser] once, mapping each `<cloud-backup>`/`<device-transfer>` section to the
     * `domain` values its own `<exclude>` children carry — split out of the test above purely to
     * keep that function under detekt's nesting-depth limit, no behaviour of its own beyond that.
     *
     * R-1106: unlike almost every other manifest-adjacent resource, the data-extraction-rules
     * schema's own `domain`/`path` attributes are plain, unnamespaced XML attributes (confirmed
     * empirically here: the `android:` namespace read back null even though the compiled resource
     * carries the value) — this is not a mistake in this file's own XML, it is how the platform
     * defines this particular schema.
     */
    private fun excludedDomainsBySection(parser: XmlResourceParser): Map<String, List<String?>> {
        val bySection = mutableMapOf<String, MutableList<String?>>()
        var currentSection: String? = null
        var event = parser.eventType
        while (event != XmlResourceParser.END_DOCUMENT) {
            if (event == XmlResourceParser.START_TAG) currentSection = recordTag(parser, bySection, currentSection)
            event = parser.next()
        }
        parser.close()
        return bySection
    }

    /** One `START_TAG` step of [excludedDomainsBySection]'s walk — returns the section [parser] is
     * now inside (unchanged unless this tag itself opened a new one). */
    private fun recordTag(
        parser: XmlResourceParser,
        bySection: MutableMap<String, MutableList<String?>>,
        currentSection: String?,
    ): String? {
        when (parser.name) {
            "cloud-backup", "device-transfer" -> return parser.name
            "exclude" -> currentSection?.let { section ->
                bySection.getOrPut(section) { mutableListOf() }.add(parser.getAttributeValue(null, "domain"))
            }
        }
        return currentSection
    }
}
