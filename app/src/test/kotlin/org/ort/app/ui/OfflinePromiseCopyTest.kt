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
        val all = (OfflinePromiseCopy.POINTS + OfflinePromiseCopy.WELCOME_PROMISE).joinToString("\n")

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

    private companion object {
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
