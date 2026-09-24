package org.ort.app.ui

import org.junit.Test
import org.ort.testing.Requirement

/**
 * R-1164 and R-1165 — the two privacy claims that were shipping falsely.
 *
 * This is the one place where asserting on user-facing prose is correct rather than the mistake
 * the constitution warns about (II, "assertions MUST NOT depend on prose"): **FR-ANL-14 makes the
 * wording itself the requirement**, naming one permitted sentence verbatim and forbidding a whole
 * class of stronger claim. A designer may not legitimately change this copy tomorrow — changing it
 * is exactly what these tests exist to catch.
 *
 * The companion render tests (`WelcomeScreenTest`, `SettingsAboutScreenTest`) prove each screen
 * shows these constants under its own test tags; this file proves the constants say the truth.
 * Neither half is sufficient alone, which is the shape R-1160 and R-1070 argued for.
 */
class OfflinePromiseCopyTest {

    @Test
    @Requirement("FR-ANL-14", "R-1164")
    fun `FR_ANL_14 the Welcome promise carries the one permitted sentence, verbatim`() {
        assert(OfflinePromiseCopy.WELCOME_PROMISE.contains(PERMITTED_SENTENCE)) {
            "FR-ANL-14 names one permitted single-sentence claim and it must appear character for " +
                "character. Expected to find:\n  $PERMITTED_SENTENCE\nin:\n  " +
                OfflinePromiseCopy.WELCOME_PROMISE
        }
    }

    @Test
    @Requirement("FR-ANL-14", "R-1164")
    fun `FR_ANL_14 no offline-promise copy makes a bare never-uploaded claim`() {
        val all = allCopy().joinToString("\n")

        FORBIDDEN_ABSOLUTES.forEach { forbidden ->
            assert(!all.contains(forbidden, ignoreCase = true)) {
                "FR-ANL-14 forbids a bare claim that no audio ever leaves the device: contribution, " +
                    "a field report and analytics tier 3 can each carry audio by the operator's own " +
                    "choice, and setup offers tier 3 a few screens after Welcome. Found \"$forbidden\" " +
                    "in:\n$all"
            }
        }

        // The permitted sentence's own "never uploaded" is only permitted because of what follows
        // it; an unqualified one anywhere else is the same defect wearing the right words.
        var from = 0
        while (true) {
            val at = all.indexOf(UPLOAD_CLAIM, from)
            if (at < 0) break
            assert(all.startsWith(QUALIFIED_UPLOAD_CLAIM, at)) {
                "\"$UPLOAD_CLAIM\" at index $at is not followed by FR-ANL-14's qualification. " +
                    "Every such claim must read \"$QUALIFIED_UPLOAD_CLAIM\". In:\n$all"
            }
            from = at + UPLOAD_CLAIM.length
        }
    }

    @Test
    @Requirement("FR-SPK-25", "FR-DIG-13", "FR-LEX-24", "R-1165")
    fun `FR_SPK_25 names, station knowledge and location keep their absolute guarantee`() {
        val absolute = OfflinePromiseCopy.POINTS.single { it.contains("names you give stations") }

        listOf("what this phone has learned about who is around when", "your location", "never leave")
            .forEach { required ->
                assert(absolute.contains(required)) {
                    "The three categories in no channel and no tier (FR-SPK-25, FR-DIG-13, FR-LEX-24) " +
                        "keep an absolute guarantee; \"$required\" is missing from:\n  $absolute"
                }
            }
        assert(!absolute.contains("oiceprint")) {
            "D38 removed voiceprints from this absolute; they belong in their own point with the " +
                "field-report exception stated. Found them still in:\n  $absolute"
        }
    }

    @Test
    @Requirement("FR-SPK-20", "FR-OBS-9", "FR-OBS-10", "R-1165")
    fun `FR_SPK_20 the voiceprint point states the exception and every condition on it`() {
        val voiceprints = OfflinePromiseCopy.POINTS.single { it.contains("Voiceprints") }

        assert(!voiceprints.contains("never leave")) {
            "D38 made \"voiceprints never leave\" false; this point must not restate it:\n  $voiceprints"
        }
        mapOf(
            "field report" to "FR-SPK-20's one channel must be named",
            "real size" to "FR-OBS-9: every file and its real size is named before each upload",
            "public" to "FR-OBS-10: a public destination is refused",
            "Settings" to "FR-OBS-10: unless the visible Settings switch was explicitly turned off",
            "your own device" to "FR-SPK-20: an export for the operator's own device-to-device transfer",
        ).forEach { (required, why) ->
            assert(voiceprints.contains(required)) {
                "$why — \"$required\" is missing from:\n  $voiceprints"
            }
        }
    }

    /**
     * R-1173 — the finding this test exists for is that a *second* surface restated the voiceprint
     * absolute after R-1165 had corrected the first two. Naming surfaces one at a time is how that
     * happened, so the sweep enumerates the object by reflection: a constant added to
     * [OfflinePromiseCopy] tomorrow is checked whether or not anyone remembers to list it here.
     */
    @Test
    @Requirement("FR-SPK-20", "D38", "R-1173")
    fun `FR_SPK_20 no copy anywhere in the object restates the voiceprint absolute`() {
        allCopy().forEach { copy ->
            if (!copy.contains("oiceprint")) return@forEach
            VOICEPRINT_ABSOLUTES.forEach { forbidden ->
                assert(!copy.contains(forbidden, ignoreCase = true)) {
                    "D38 gave voiceprints an outbound path (FR-SPK-20, FR-OBS-9, FR-OBS-10), so no " +
                        "surface may restate the absolute. Found \"$forbidden\" in:\n  $copy"
                }
            }
        }
    }

    /**
     * R-1173's own warning, made mechanical: a replacement naming only the field report would
     * contradict `SettingsBackupScreen`, which already tells the operator voiceprints travel in a
     * backup they make themselves (FR-SPK-20's device-to-device clause).
     */
    @Test
    @Requirement("FR-SPK-20", "R-1173")
    fun `FR_SPK_20 every surface that names a voiceprint route names both of them`() {
        val surfaces = allCopy().filter { it.contains("oiceprint") && it.contains("can leave") }

        assert(surfaces.size >= EXPECTED_VOICEPRINT_SURFACES) {
            "R-1173 lists Welcome/About, Station identity and Export as the surfaces that state " +
                "where a voiceprint can go; found ${surfaces.size}:\n" + surfaces.joinToString("\n")
        }
        surfaces.forEach { surface ->
            assert(surface.contains(OfflinePromiseCopy.VOICEPRINT_ROUTES)) {
                "Both FR-SPK-20 routes must be stated from the one shared clause, never retyped " +
                    "or abridged. Expected:\n  ${OfflinePromiseCopy.VOICEPRINT_ROUTES}\nin:\n  $surface"
            }
        }
    }

    /**
     * The other half of R-1173, and the one a find-and-replace would have broken: FR-SPK-25,
     * FR-DIG-13 and FR-LEX-24 are untouched by D38, so every surface that corrects the voiceprint
     * claim must still say the other categories leave in no channel and no tier.
     */
    @Test
    @Requirement("FR-SPK-25", "FR-DIG-13", "FR-LEX-24", "R-1173")
    fun `FR_SPK_25 the station identity and export cards keep the absolute for names and location`() {
        assert(OfflinePromiseCopy.STATION_IDENTITY_CARD.contains("no channel and no tier")) {
            "The name and the note on ST04 are FR-SPK-25/FR-DIG-13 categories and keep an absolute " +
                "the voiceprint no longer has:\n  ${OfflinePromiseCopy.STATION_IDENTITY_CARD}"
        }
        assert(OfflinePromiseCopy.EXPORT_NEVER_INCLUDED.contains("no channel and no tier")) {
            "Names, notes and location keep their absolute on CF07 (FR-SPK-25, FR-DIG-13, " +
                "FR-LEX-24):\n  ${OfflinePromiseCopy.EXPORT_NEVER_INCLUDED}"
        }
    }

    /**
     * R-1173: the ST04 subtitle claimed all three facts stay on the phone. The callsign is public
     * by nature and is in every export, and a voiceprint has [OfflinePromiseCopy.VOICEPRINT_ROUTES],
     * so the only honest subtitle is one that does not make a blanket claim about all three.
     */
    @Test
    @Requirement("FR-SPK-20", "R-1173")
    fun `FR_SPK_20 the station identity subtitle makes no blanket claim about all three facts`() {
        assert(!OfflinePromiseCopy.STATION_IDENTITY_SUBTITLE.contains("none of which leave")) {
            "Two of the three do leave — the callsign in every export, a voiceprint by its two " +
                "routes. Found the blanket claim in:\n  ${OfflinePromiseCopy.STATION_IDENTITY_SUBTITLE}"
        }
    }

    /**
     * Every `String` and `List<String>` [OfflinePromiseCopy] holds, read off the object rather than
     * listed by hand: R-1173 is what happens when a sweep only covers the surfaces someone
     * remembered, so a constant added tomorrow is in this list without anyone adding it.
     */
    private fun allCopy(): List<String> {
        val values = OfflinePromiseCopy::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name == "INSTANCE" }
            .mapNotNull { field ->
                field.isAccessible = true
                field.get(OfflinePromiseCopy)
            }
        return values.flatMap { value ->
            when (value) {
                is String -> listOf(value)
                is List<*> -> value.filterIsInstance<String>()
                else -> emptyList()
            }
        }
    }

    private companion object {
        /**
         * Claims that were true of a voiceprint before D38 and are not now. These are deliberately
         * narrower than [FORBIDDEN_ABSOLUTES]: "never leave" is still correct wherever it is said
         * of a name, station knowledge or a location, so only copy that mentions a voiceprint is
         * held to this list.
         */
        val VOICEPRINT_ABSOLUTES: List<String> = listOf(
            "never leave",
            "never leaves",
            "none of which leave",
            "or a backup",
        )

        /** Welcome/About's [OfflinePromiseCopy.POINTS] entry, ST04's card, CF07's card. */
        const val EXPECTED_VOICEPRINT_SURFACES: Int = 3

        /** `spec/functional-spec.md` FR-ANL-14, and constitution V's "one permitted privacy claim". */
        const val PERMITTED_SENTENCE: String =
            "Your audio is processed only on your phone and is never uploaded unless you choose to share it."

        const val UPLOAD_CLAIM: String = "never uploaded"
        const val QUALIFIED_UPLOAD_CLAIM: String = "never uploaded unless you choose to share it."

        /**
         * Claims that are false the moment the operator turns on contribution, a field report or
         * analytics tier 3. The first entry is R-1164's own shipped wording.
         *
         * These are deliberately only the claims about **audio, or about everything** — a named
         * category may still carry an absolute where the spec gives it one (FR-SPK-25, FR-DIG-13,
         * FR-LEX-24), which is what the next test guards.
         */
        val FORBIDDEN_ABSOLUTES: List<String> = listOf(
            "no upload",
            "no audio leaves",
            "audio never leaves",
            "nothing leaves the device",
            "nothing leaves this phone",
            "nothing ever leaves",
        )
    }
}
