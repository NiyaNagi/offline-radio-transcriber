package org.ort.app.ui.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.Attribution
import org.ort.core.TransmissionId
import org.ort.testing.Requirement

/**
 * ui-conformance WP6, R-050/R-051/R-053/R-057: the per-state "why this callsign" and body
 * explanation the design's four detail states need, built honestly from the real
 * [InspectionViewState] `:app` already reads — never a fabricated lattice slot, prior value or
 * "nearest voice match" figure the schema does not actually carry (constitution I).
 *
 * The rewritten FR-UI-4 rule this mapper establishes (design-guide.md §6.2, `States.dc.html`): a
 * confidence number is prose in the explanation sentence for every state that carries one — never
 * omitted — and the [org.ort.app.ui.components.ScoreChip] stays reserved for INFERRED alone (that
 * part is [org.ort.app.ui.components.AttributionRow]'s job, not this mapper's).
 *
 * **Register R-423 (design), narrower than the paragraph above.** `Detail.dc.html` — INFERRED's own
 * real board — carries no confidence figure in its explanation sentence at all (re-confirmed by
 * reading the board's own markup directly, twice: once implementing R-423, again reconciling this
 * file's own then-stale [R_050 INFERRED test][DetailViewStateMapperTest] against it): "Not heard in
 * this over. Matched by voice to `02:14:07`, where the callsign was heard clearly." — no number.
 * The confidence is not omitted overall (FR-UI-4's rule holds): it renders in the
 * [org.ort.app.ui.components.ScoreChip] beside the callsign, from `detail.attribution.confidence`
 * directly, never through this sentence. `Detail-Confirmed.dc.html`/`Fail-Wrong.dc.html` (CONFIRMED)
 * do carry the number in prose ("Resolved from the phonetics at 0.94.") — unchanged, and the
 * paragraph above still governs that state.
 */
class DetailViewStateMapperTest {

    private fun detail(attribution: Attribution, inspection: InspectionViewState = InspectionViewState.EMPTY) =
        TransmissionDetailViewState(
            id = "TX1",
            timeLabel = "02:14:22",
            frequencyLabel = "145.230",
            durationLabel = "4.2s",
            signalLabel = "S5",
            attribution = attribution,
            transcriptText = "roger that",
            revisionHistory = emptyList(),
            hasAudio = true,
            inspection = inspection,
        )

    // ---- R-050/FR-UI-4: the explanation sentence carries confidence in prose ----

    @Test
    fun `R_050 CONFIRMED explains itself and states the confidence in prose`() {
        val body = DetailViewStateMapper.from(detail(Attribution.confirmed("W7NPC", 0.94))).body
        check(body is DetailBodyViewState.Confirmed)
        assertTrue(body.explanation.contains("0.94"), body.explanation)
        assertTrue(body.explanation.contains("Heard in this over"), body.explanation)
    }

    @Test
    fun `R_050 R_423 INFERRED explains itself without a duplicated confidence clause, and carries the source over`() {
        val source = TransmissionId.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV")
        val body = DetailViewStateMapper.from(
            detail(Attribution.inferred("K7LWH", 0.82, source)),
        ).body
        check(body is DetailBodyViewState.Inferred)
        // R-423: `Detail.dc.html` carries no "Confidence 0.82." clause in this sentence — the chip
        // beside the callsign (built from `detail.attribution.confidence`, not this string) is
        // where FR-UI-4's confidence-never-omitted rule is satisfied for INFERRED.
        assertFalse(body.explanation.contains("0.82"), body.explanation)
        assertTrue(body.explanation.contains("Not heard in this over"), body.explanation)
        assertEquals(source.toString(), body.sourceTransmissionId)
    }

    @Test
    fun `R_053 an INFERRED attribution with no recorded source carries no link, honestly`() {
        val body = DetailViewStateMapper.from(detail(Attribution.inferred("K7LWH", 0.82))).body
        check(body is DetailBodyViewState.Inferred)
        assertNull(body.sourceTransmissionId)
    }

    @Test
    fun `R_057 AMBIGUOUS explains itself from the real top two candidates, never a fabricated letter`() {
        val inspection = InspectionViewState(
            lattice = null,
            candidates = listOf(
                CandidateInspectionViewState("KE7QRS", 0, 0.51, true, true, false, emptyList()),
                CandidateInspectionViewState("KE7QRF", 1, 0.46, true, false, false, emptyList()),
            ),
        )
        val body = DetailViewStateMapper.from(detail(Attribution.ambiguous(), inspection)).body
        check(body is DetailBodyViewState.Ambiguous)
        assertEquals("KE7QRF", body.alternateCallsign)
        assertEquals(2, body.candidates.size)
        assertEquals("KE7QRS", body.candidates[0].callsign)
        assertEquals("a known station", body.candidates[0].evidence)
        assertEquals("never heard before", body.candidates[1].evidence)
    }

    @Test
    fun `R_057 UNKNOWN explains itself with the definitional sentence, never a fabricated voice match`() {
        val body = DetailViewStateMapper.from(detail(Attribution.unknown())).body
        check(body is DetailBodyViewState.Unknown)
        assertTrue(body.explanation.contains("Nothing is claimed"), body.explanation)
        assertTrue(body.tried.any { it.title.contains("grammar", ignoreCase = true) })
        assertTrue(body.tried.any { it.title.contains("unidentified voice", ignoreCase = true) })
    }

    @Test
    fun `R_057 UNKNOWN with a recorded but failed lattice names the best partial honestly`() {
        val inspection = InspectionViewState(
            lattice = null,
            candidates = listOf(CandidateInspectionViewState("?7??", 0, 0.31, false, false, false, emptyList())),
        )
        val body = DetailViewStateMapper.from(detail(Attribution.unknown(), inspection)).body
        check(body is DetailBodyViewState.Unknown)
        val grammarStep = body.tried.first { it.title.contains("grammar", ignoreCase = true) }
        assertTrue(grammarStep.detail.orEmpty().contains("0.31"), grammarStep.detail.orEmpty())
    }

    // ---- R-721 (register): `Detail-Unknown.dc.html` draws four "what was tried" steps — before
    // this fix, only "grammar" and "kept as an unidentified voice" ever rendered; "voice match
    // against N stations heard tonight" and "thread context" never did, because no caller passed
    // [DetailViewStateMapper.from] the real facts to build them with. With no
    // [UnknownTriedContextViewState] supplied (every call site built before this fix), the two-step
    // shape is unchanged — never four fabricated steps standing in for data this mapper does not
    // have. ----

    @Test
    fun `R_721 UNKNOWN with no context supplied still renders only the two steps this mapper always had`() {
        val body = DetailViewStateMapper.from(detail(Attribution.unknown())).body
        check(body is DetailBodyViewState.Unknown)
        assertEquals(2, body.tried.size)
    }

    @Test
    fun `R_721 UNKNOWN with a real context renders all four steps, each with a real not fabricated outcome`() {
        val context = UnknownTriedContextViewState(
            stationsHeardTonight = 19,
            voiceprintExtracted = true,
            hasThreadId = false,
        )
        val body = DetailViewStateMapper.from(detail(Attribution.unknown()), unknownContext = context).body
        check(body is DetailBodyViewState.Unknown)
        assertEquals(4, body.tried.size)
        assertTrue(body.tried.any { it.title.contains("grammar", ignoreCase = true) })
        val voiceStep = body.tried.first { it.title.contains("voice match", ignoreCase = true) }
        // The real count, never a hardcoded placeholder — `Detail-Unknown.dc.html`'s own worked
        // example reads "19", but this must be [context]'s own number, not that literal.
        assertTrue(voiceStep.title.contains("19"), voiceStep.title)
        assertTrue(voiceStep.title.contains("stations heard tonight"), voiceStep.title)
        // Honest, not a fabricated nearest-candidate distance (constitution I) — no `:data` query
        // this mapper can reach returns a per-candidate embedding distance for an unmatched voice.
        assertFalse(voiceStep.detail.orEmpty().contains("N7XYZ"))
        assertTrue(voiceStep.detail.orEmpty().contains("confidence floor"), voiceStep.detail.orEmpty())
        val threadStep = body.tried.first { it.title.equals("Thread context", ignoreCase = true) }
        assertTrue(threadStep.detail.orEmpty().contains("No conversation thread"), threadStep.detail.orEmpty())
        assertTrue(body.tried.any { it.title.contains("unidentified voice", ignoreCase = true) })
    }

    @Test
    fun `R_721 UNKNOWN with no voiceprint extracted at all says so, never a fabricated floor miss`() {
        val context = UnknownTriedContextViewState(
            stationsHeardTonight = 0,
            voiceprintExtracted = false,
            hasThreadId = false,
        )
        val body = DetailViewStateMapper.from(detail(Attribution.unknown()), unknownContext = context).body
        check(body is DetailBodyViewState.Unknown)
        val voiceStep = body.tried.first { it.title.contains("voice match", ignoreCase = true) }
        assertTrue(voiceStep.detail.orEmpty().contains("No voiceprint could be extracted"), voiceStep.detail.orEmpty())
    }

    @Test
    fun `R_721 UNKNOWN carrying a real threadId reads as grouped, not a fabricated no-thread line`() {
        val context = UnknownTriedContextViewState(
            stationsHeardTonight = 3,
            voiceprintExtracted = true,
            hasThreadId = true,
        )
        val body = DetailViewStateMapper.from(detail(Attribution.unknown()), unknownContext = context).body
        check(body is DetailBodyViewState.Unknown)
        val threadStep = body.tried.first { it.title.equals("Thread context", ignoreCase = true) }
        assertTrue(threadStep.detail.orEmpty().contains("ongoing conversation"), threadStep.detail.orEmpty())
    }

    // ---- R-051/FR-UI-8: the "why this callsign" surface, built from real data only ----

    @Test
    fun `R_051 with no resolver output recorded, why-this-callsign says so honestly`() {
        val why = DetailViewStateMapper.from(detail(Attribution.confirmed("W7NPC", 0.94))).why
        assertFalse(why.hasData)
        assertTrue(why.candidates.isEmpty())
        assertTrue(why.priors.isEmpty())
    }

    @Test
    fun `R_051 priors for the chosen candidate render as real bars, a cold start prior gets no fabricated fill`() {
        val inspection = InspectionViewState(
            lattice = LatticeInspectionViewState(source = "ACOUSTIC", modelId = "whisper-small", createdAt = 1L),
            candidates = listOf(
                CandidateInspectionViewState(
                    callsign = "K7LWH",
                    rank = 0,
                    score = 8.6,
                    grammarValid = true,
                    databaseHit = true,
                    selected = true,
                    priorContributions = listOf(
                        PriorContributionViewState("heard acoustically", 3.1, isColdStart = false),
                        PriorContributionViewState("recent corrections", 0.0, isColdStart = true),
                        PriorContributionViewState("time of day", -0.4, isColdStart = false),
                    ),
                ),
                CandidateInspectionViewState("KA7LWH", 1, 4.4, true, false, false, emptyList()),
            ),
        )
        val why = DetailViewStateMapper.from(detail(Attribution.confirmed("K7LWH", 0.94), inspection)).why

        assertTrue(why.hasData)
        assertEquals(2, why.candidates.size)
        assertTrue(why.candidates.single { it.callsign == "K7LWH" }.chosen)
        assertEquals("KA7LWH", why.runnerUp?.callsign)

        // R-180: `DetailViewStateMapper.priorLabel` now translates a stored prior key into guide
        // §9 sentence-case prose ("Recent corrections", not "recent corrections") — the map's own
        // keys reflect that, not this fixture's original (pre-R-180) verbatim-passthrough casing.
        val priors = why.priors.associateBy { it.name }
        assertEquals(null, priors.getValue("Recent corrections").fillFraction)
        assertEquals(null, priors.getValue("Recent corrections").valueLabel)
        assertTrue(priors.getValue("Time of day").arguedAgainst)
        assertFalse(priors.getValue("Heard acoustically").arguedAgainst)
    }

    // -----------------------------------------------------------------------------------------
    // The `bt audio` header mark (checklist row E2-G04, D01-D04), threaded through for all four
    // attribution states the detail header shares one `DetailViewState`/`HeaderSection` for.
    // -----------------------------------------------------------------------------------------

    @Test
    @Requirement("FR-CAP-13")
    fun `FR_CAP_13 btAudioMark defaults to false so every existing call site is unaffected`() {
        val state = DetailViewStateMapper.from(detail(Attribution.confirmed("W7NPC", 0.94)))
        assertFalse(state.btAudioMark)
    }

    @Test
    @Requirement("FR-CAP-13")
    fun `FR_CAP_13 CONFIRMED (D01) carries the mark when the caller passes it`() {
        val state = DetailViewStateMapper.from(detail(Attribution.confirmed("W7NPC", 0.94)), btAudioMark = true)
        assertTrue(state.btAudioMark)
    }

    @Test
    @Requirement("FR-CAP-13")
    fun `FR_CAP_13 INFERRED (D02) carries the mark when the caller passes it`() {
        val state = DetailViewStateMapper.from(detail(Attribution.inferred("K7LWH", 0.82)), btAudioMark = true)
        assertTrue(state.btAudioMark)
    }

    @Test
    @Requirement("FR-CAP-13")
    fun `FR_CAP_13 AMBIGUOUS (D03) carries the mark when the caller passes it`() {
        val inspection = InspectionViewState(
            lattice = null,
            candidates = listOf(
                CandidateInspectionViewState("KE7QRS", 0, 0.51, true, true, false, emptyList()),
                CandidateInspectionViewState("KE7QRF", 1, 0.46, true, false, false, emptyList()),
            ),
        )
        val state = DetailViewStateMapper.from(detail(Attribution.ambiguous(), inspection), btAudioMark = true)
        assertTrue(state.btAudioMark)
    }

    @Test
    @Requirement("FR-CAP-13")
    fun `FR_CAP_13 UNKNOWN (D04) carries the mark when the caller passes it`() {
        val state = DetailViewStateMapper.from(detail(Attribution.unknown()), btAudioMark = true)
        assertTrue(state.btAudioMark)
    }

    @Test
    @Requirement("FR-CAP-13")
    fun `FR_CAP_13 a non-Bluetooth session never carries the mark`() {
        val state = DetailViewStateMapper.from(detail(Attribution.confirmed("W7NPC", 0.94)), btAudioMark = false)
        assertFalse(state.btAudioMark)
    }
}
