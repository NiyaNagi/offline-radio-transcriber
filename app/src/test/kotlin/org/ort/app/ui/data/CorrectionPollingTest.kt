package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.Attribution
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.data.entity.VoiceprintBindingSource
import org.ort.data.entity.VoiceprintEntity
import org.robolectric.RobolectricTestRunner

/**
 * ui-conformance WP6 (R-052, R-058), `Detail-Correct-A/B/C.dc.html`, `Detail-Propagated.dc.html`,
 * `Flow-Correct.dc.html`: propagation (every over sharing the corrected over's `voiceprintId`, or
 * `stationId` when there is none, gets a [org.ort.data.entity.CorrectionEntity]) and `Confirm`
 * (an audit-only agreement, never a re-attribution). Its own reads/writes through [OrtDatabase]
 * directly — never through [ReaderPolling], per this package's file-ownership boundary.
 */
@RunWith(RobolectricTestRunner::class)
class CorrectionPollingTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    /**
     * Register R-110's `ScenariosTest` flake (`SQLiteBusyException: [database is locked]`) turned
     * out to be, in part, every file-backed-`OrtDatabase` test class in this module opening a fresh
     * `RoomDatabase` (its own connection pool, its own `InvalidationTracker`) against the *same*
     * on-disk `ort.db` and never closing the previous one — a leaked-writer effect that compounds
     * across an entire Gradle test-worker JVM, not just within one test class (Gradle can run many
     * test classes in one forked worker; `OrtDatabase.create(context)`'s default name is the same
     * every time). This class had exactly that gap — no `@After` at all — closed here the same way
     * `ScenariosTest` (`app/src/test/kotlin/org/ort/app/debug/ScenariosTest.kt`) now closes its own,
     * so this class stops being one of the never-closed instances a *different* test class's run
     * could contend against.
     */
    @After
    fun closeDatabase() {
        db.close()
    }

    private fun session() = SessionEntity(
        id = "S1",
        startedAt = 0L,
        endedAt = null,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
    )

    private fun transmission(
        id: String,
        stationId: String? = "K7LWH",
        voiceprintId: String? = "V1",
        samplePosition: Long = 0L,
    ) = TransmissionEntity(
        id = id,
        sessionId = "S1",
        threadId = null,
        startedAtUtc = samplePosition,
        endedAtUtc = samplePosition + 1_000L,
        durationMs = 1_000L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = 145_230_000L,
        frequencyProvenance = "measured",
        mode = null,
        signalStrength = null,
        channelName = null,
        voiceprintId = voiceprintId,
        attributionState = if (stationId != null) AttributionState.INFERRED else AttributionState.UNKNOWN,
        stationId = stationId,
        attributionConfidence = if (stationId != null) 0.7 else null,
        attributionSourceTransmissionId = null,
        processingState = TransmissionState.COMPLETE,
        rejectionReason = null,
        samplePosition = samplePosition,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    private fun request(
        transmissionId: String,
        previous: String?,
        new: String,
        tier: CorrectionTier = CorrectionTier.PICK_CANDIDATE,
    ) = CorrectionRequest(
        transmissionId = transmissionId,
        previousStationId = previous,
        newStationId = new,
        tier = tier,
        correctedAtMillis = 500L,
    )

    private fun station(id: String) = StationEntity(
        id = id,
        callsign = id,
        firstHeardAt = null,
        lastHeardAt = null,
        transmissionCount = 0,
        isUserPinned = false,
        notes = null,
        userName = null,
        frequenciesHeard = null,
        activityByHourDow = null,
        potaRefs = null,
        spokenGrids = null,
        ituRegionFromPrefix = null,
        overCountsByAttributionState = null,
    )

    private fun voiceprint(
        id: String,
        boundStationId: String? = null,
        bindingConfidence: Double? = null,
        bindingSource: VoiceprintBindingSource? = null,
    ) = VoiceprintEntity(
        id = id,
        embedding = ByteArray(0),
        memberCount = 1,
        centroidUpdatedAt = null,
        boundStationId = boundStationId,
        bindingConfidence = bindingConfidence,
        lastConfirmedAt = null,
        isEnrolled = false,
        enrolmentObservationCount = 0,
        enrolmentSessionIds = null,
        enrolledAt = null,
        lastMatchedAt = null,
        bindingSource = bindingSource,
        embeddingModelId = null,
        embeddingModelVersion = null,
    )

    @Test
    fun `R_052 THIS_OVER_ONLY corrects exactly the one transmission`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        db.transmissionDao().insert(transmission("TX2"))

        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH"),
            CorrectionScope.THIS_OVER_ONLY,
        )

        assertEquals(1, outcome.overCount)
        assertEquals("KA7LWH", db.transmissionDao().getById("TX1")!!.stationId)
        assertEquals("K7LWH", db.transmissionDao().getById("TX2")!!.stationId)
    }

    @Test
    fun `R_052 EVERY_OVER_SAME_VOICE propagates to every transmission sharing the voiceprint`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX2", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX3", voiceprintId = "V2"))

        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH"),
            CorrectionScope.EVERY_OVER_SAME_VOICE,
        )

        assertEquals(2, outcome.overCount)
        assertEquals("KA7LWH", db.transmissionDao().getById("TX1")!!.stationId)
        assertEquals("KA7LWH", db.transmissionDao().getById("TX2")!!.stationId)
        assertEquals("K7LWH", db.transmissionDao().getById("TX3")!!.stationId)
        assertEquals(setOf("TX1", "TX2"), outcome.affected.map { it.transmissionId }.toSet())
    }

    @Test
    fun `R_052 with no voiceprint, propagation falls back to the shared stationId`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH", voiceprintId = null))
        db.transmissionDao().insert(transmission("TX2", stationId = "K7LWH", voiceprintId = null))

        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH"),
            CorrectionScope.EVERY_OVER_SAME_VOICE,
        )

        assertEquals(2, outcome.overCount)
    }

    @Test
    fun `R_052 nothing is ever deleted by a correction`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))

        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH"),
            CorrectionScope.THIS_OVER_ONLY,
        )

        assertEquals(0, outcome.deletedCount)
        assertEquals(1, db.correctionDao().correctionsFor("TX1").size)
    }

    // ---- R-052 complete: voiceprint rebinding and prior versioning through StationIdentityDao ----

    @Test
    fun R_052_propagation_rebinds_the_voiceprint_and_keeps_the_old_binding_reachable(): Unit = runTest {
        db.sessionDao().insert(session())
        db.catalogDao().insert(station("K7LWH"))
        db.catalogDao().insert(station("KA7LWH"))
        db.catalogDao().insert(
            voiceprint(
                "V1",
                boundStationId = "K7LWH",
                bindingConfidence = 0.6,
                bindingSource = VoiceprintBindingSource.AUTO,
            ),
        )
        db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX2", stationId = "K7LWH", voiceprintId = "V1"))

        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH", tier = CorrectionTier.PICK_CANDIDATE),
            CorrectionScope.EVERY_OVER_SAME_VOICE,
        )

        // The voiceprint now belongs to the corrected station...
        assertTrue(outcome.voiceprintReassigned)
        val voiceprints = db.catalogDao().voiceprintsForStation("KA7LWH")
        assertEquals(1, voiceprints.size)
        assertEquals("V1", voiceprints.single().id)
        assertTrue(db.catalogDao().voiceprintsForStation("K7LWH").isEmpty())

        // ...but the earlier binding to K7LWH stays reachable, not overwritten out of existence.
        val history = db.stationIdentityDao().voiceprintBindingHistoryFor("V1")
        assertEquals(1, history.size)
        assertEquals("K7LWH", history.single().previousStationId)
        assertEquals("KA7LWH", history.single().newStationId)
        assertEquals(0.6, history.single().previousBindingConfidence)

        // A PICK_CANDIDATE correction is verified — it also reinforces the two named priors.
        assertEquals(2, outcome.priorsUpdatedCount)
        val onThisRepeater = db.stationIdentityDao().currentPriorWeight("KA7LWH", "on_this_repeater")!!
        assertEquals(0.15, onThisRepeater.weight, 0.0001)
        assertTrue(onThisRepeater.isCurrent)
    }

    @Test
    fun R_052_a_free_text_correction_never_rebinds_the_voiceprint_or_feeds_the_priors(): Unit = runTest {
        db.sessionDao().insert(session())
        db.catalogDao().insert(voiceprint("V1", boundStationId = "K7LWH"))
        db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH", voiceprintId = "V1"))

        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "N0CALL", tier = CorrectionTier.FREE_TEXT),
            CorrectionScope.EVERY_OVER_SAME_VOICE,
        )

        assertFalse(outcome.voiceprintReassigned)
        assertEquals(0, outcome.priorsUpdatedCount)
        assertTrue(db.stationIdentityDao().voiceprintBindingHistoryFor("V1").isEmpty())
    }

    @Test
    fun R_052_this_over_only_never_rebinds_a_voiceprint_shared_with_overs_left_alone(): Unit = runTest {
        db.sessionDao().insert(session())
        db.catalogDao().insert(voiceprint("V1", boundStationId = "K7LWH"))
        db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX2", stationId = "K7LWH", voiceprintId = "V1"))

        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH"),
            CorrectionScope.THIS_OVER_ONLY,
        )

        assertFalse(outcome.voiceprintReassigned)
        assertEquals(0, outcome.priorsUpdatedCount)
    }

    @Test
    fun R_052_undo_all_restores_the_previous_binding_without_deleting(): Unit = runTest {
        db.sessionDao().insert(session())
        db.catalogDao().insert(station("K7LWH"))
        db.catalogDao().insert(station("KA7LWH"))
        db.catalogDao().insert(
            voiceprint(
                "V1",
                boundStationId = "K7LWH",
                bindingConfidence = 0.6,
                bindingSource = VoiceprintBindingSource.AUTO,
            ),
        )
        db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH", voiceprintId = "V1"))
        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH"),
            CorrectionScope.EVERY_OVER_SAME_VOICE,
        )

        CorrectionPolling.undoAll(context, outcome, atMillis = 700L)

        // The binding is back on the original station...
        assertEquals(1, db.catalogDao().voiceprintsForStation("K7LWH").size)
        assertTrue(db.catalogDao().voiceprintsForStation("KA7LWH").isEmpty())
        // ...but undo is itself a further, kept binding — both changes stay reachable.
        val bindingHistory = db.stationIdentityDao().voiceprintBindingHistoryFor("V1")
        assertEquals(2, bindingHistory.size)
        assertEquals("KA7LWH", bindingHistory[1].previousStationId)
        assertEquals("K7LWH", bindingHistory[1].newStationId)

        // The prior weight is back at its pre-correction value, and both rows are kept.
        val current = db.stationIdentityDao().currentPriorWeight("KA7LWH", "on_this_repeater")!!
        assertEquals(0.0, current.weight, 0.0001)
        val priorHistory = db.stationIdentityDao().priorWeightHistoryFor("KA7LWH", "on_this_repeater")
        assertEquals(2, priorHistory.size)
        assertEquals(listOf(false, true), priorHistory.map { it.isCurrent })
    }

    @Test
    fun R_052_propagated_counts_are_the_real_row_counts(): Unit = runTest {
        db.sessionDao().insert(session())
        db.catalogDao().insert(voiceprint("V1", boundStationId = "K7LWH"))
        db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX2", stationId = "K7LWH", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX3", stationId = "K7LWH", voiceprintId = "V1"))

        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH"),
            CorrectionScope.EVERY_OVER_SAME_VOICE,
        )

        // Real, not placeholder: exactly the propagation's own blast radius, exactly two named
        // priors, and zero deleted — literal and true (constitution III: nothing deleted quietly).
        assertEquals(3, outcome.overCount)
        assertTrue(outcome.voiceprintReassigned)
        assertEquals(2, outcome.priorsUpdatedCount)
        assertEquals(0, outcome.deletedCount)
        assertEquals(
            3,
            db.correctionDao().correctionsFor("TX1").size + db.correctionDao().correctionsFor("TX2").size +
                db.correctionDao().correctionsFor("TX3").size,
        )
    }

    @Test
    fun `R_052 undo reverts every affected transmission with a new, kept correction row`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX2", voiceprintId = "V1"))
        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH"),
            CorrectionScope.EVERY_OVER_SAME_VOICE,
        )

        CorrectionPolling.undoAll(context, outcome, atMillis = 600L)

        assertEquals("K7LWH", db.transmissionDao().getById("TX1")!!.stationId)
        assertEquals("K7LWH", db.transmissionDao().getById("TX2")!!.stationId)
        // Undo is itself a correction — the reverted-from row is kept, not deleted (constitution III).
        assertEquals(2, db.correctionDao().correctionsFor("TX1").size)
    }

    // ---- R-058: Confirm is an audit row, never a re-attribution ----

    @Test
    fun `R_058 confirming a CONFIRMED transmission leaves its attribution state untouched`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(
            transmission("TX1", stationId = "K7LWH").copy(attributionState = AttributionState.CONFIRMED),
        )

        CorrectionPolling.confirm(context, transmissionId = "TX1", stationId = "K7LWH", atMillis = 100L)

        val entity = db.transmissionDao().getById("TX1")!!
        assertEquals(AttributionState.CONFIRMED, entity.attributionState)
        assertFalse(entity.corrected)
    }

    @Test
    fun `R_058 confirming records station_confirmed with an unchanged value`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH"))

        CorrectionPolling.confirm(context, transmissionId = "TX1", stationId = "K7LWH", atMillis = 100L)

        val corrections = db.correctionDao().correctionsFor("TX1")
        assertEquals(1, corrections.size)
        assertEquals(FIELD_STATION_CONFIRMED, corrections.single().field)
        assertEquals("K7LWH", corrections.single().previousValue)
        assertEquals("K7LWH", corrections.single().newValue)
    }

    @Test
    fun `affectedOverCount reports the propagation blast radius before applying`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX2", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX3", voiceprintId = "V2"))

        val count = CorrectionPolling.affectedOverCount(context, "TX1", CorrectionScope.EVERY_OVER_SAME_VOICE)

        assertEquals(2, count)
    }

    @Test
    fun `affectedOverCount for THIS_OVER_ONLY is always exactly one`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX2", voiceprintId = "V1"))

        val count = CorrectionPolling.affectedOverCount(context, "TX1", CorrectionScope.THIS_OVER_ONLY)

        assertEquals(1, count)
    }

    // ---- R-185 (halt): Tier B searches stations this phone has heard, not the lexicon ----

    private fun voiceprintFor(id: String, stationId: String) = voiceprint(id, boundStationId = stationId)

    @Test
    fun R_185_searchHeardStations_counts_real_hears_and_flags_voice_on_file(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", stationId = "KA7LWH"))
        db.transmissionDao().insert(transmission("TX2", stationId = "KA7LWH"))
        db.transmissionDao().insert(transmission("TX3", stationId = "KA7LWH"))
        db.transmissionDao().insert(transmission("TX4", stationId = "KA7LWH"))
        db.transmissionDao().insert(transmission("TX5", stationId = "KA7BQD"))
        db.catalogDao().insert(voiceprintFor("V1", "KA7LWH"))

        val outcome = CorrectionPolling.searchHeardStations(context, "KA7")

        assertEquals(2, outcome.matchCount)
        assertEquals(2, outcome.totalCount)
        val lwh = outcome.rows.single { it.stationId == "KA7LWH" }
        assertEquals("heard 4 times · voice on file", lwh.evidence)
        assertTrue(lwh.hasVoiceOnFile)
        val bqd = outcome.rows.single { it.stationId == "KA7BQD" }
        assertEquals("heard once · no voice on file yet", bqd.evidence)
        assertFalse(bqd.hasVoiceOnFile)
    }

    @Test
    fun R_185_searchHeardStations_filters_by_prefix_and_reports_the_real_total(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", stationId = "KA7LWH"))
        db.transmissionDao().insert(transmission("TX2", stationId = "W7NPC"))

        val outcome = CorrectionPolling.searchHeardStations(context, "KA7")

        assertEquals(1, outcome.matchCount)
        assertEquals(2, outcome.totalCount)
        assertEquals("KA7LWH", outcome.rows.single().stationId)
    }

    @Test
    fun R_185_searchHeardStations_never_returns_a_station_that_was_never_heard(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", stationId = "KA7LWH"))

        val outcome = CorrectionPolling.searchHeardStations(context, "N0CALL")

        assertTrue(outcome.rows.isEmpty())
        assertEquals(0, outcome.matchCount)
        assertEquals(1, outcome.totalCount)
    }

    // ---- R-183: the INFERRED explanation names the source over's real time ----

    @Test
    fun R_183_sourceOverTimeLabel_reads_the_real_source_transmissions_own_time(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("SRC1", samplePosition = 0L))

        val label = CorrectionPolling.sourceOverTimeLabel(context, "SRC1")

        assertEquals(ReaderTransmissionViewStateMapper.timeLabel(0L), label)
    }

    @Test
    fun R_183_sourceOverTimeLabel_is_null_for_a_null_or_unresolved_source(): Unit = runTest {
        assertEquals(null, CorrectionPolling.sourceOverTimeLabel(context, null))
        assertEquals(null, CorrectionPolling.sourceOverTimeLabel(context, "does-not-exist"))
    }

    // ---- R-189 (halt): current attribution honours the latest correction, not just Undo ----

    @Test
    fun R_189_currentAttribution_honours_a_fresh_typed_correction_despite_the_null_confidence(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH"))
        // The exact shape CorrectionDao.applyCorrectedAttribution really writes: INFERRED,
        // confidence NULL — no Undo involved, a single fresh correction.
        db.correctionDao().recordCorrection(request("TX1", "K7LWH", "VE7ABC").toEntity())

        val attribution = CorrectionPolling.currentAttribution(context, "TX1", fallback = Attribution.unknown())

        assertEquals(AttributionState.INFERRED, attribution.state)
        assertEquals("VE7ABC", attribution.stationId)
        assertTrue("a correction's attribution must carry the corrected lock", attribution.corrected)
    }

    /**
     * Register R-321 (fixed): before this fix, `undoAll` re-applied the correction write path
     * (`INFERRED`/`corrected = true`), so `currentAttribution`'s own patch still had to fire after
     * an undo to avoid `ReaderPolling` reading it back as UNKNOWN — this test used to assert
     * exactly that patched shape. Now `undoAll` restores the transmission's real, pre-correction
     * columns directly (`restoreAttribution`), so the row is genuinely `corrected = false` again
     * and `currentAttribution` correctly does *not* fire its patch — it defers to whatever the real
     * resolver fallback is, unchanged, the same as any other uncorrected row.
     */
    @Test
    fun R_189_currentAttribution_honours_undo_alls_revert_the_same_way(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH"))
        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH"),
            CorrectionScope.THIS_OVER_ONLY,
        )

        CorrectionPolling.undoAll(context, outcome, atMillis = 700L)

        val realResolverFallback = Attribution.inferred("K7LWH", 0.7)
        val attribution = CorrectionPolling.currentAttribution(context, "TX1", fallback = realResolverFallback)
        assertEquals(realResolverFallback, attribution)
        assertFalse("a genuinely undone correction must not still read as corrected", attribution.corrected)
        val entity = db.transmissionDao().getById("TX1")!!
        assertEquals(AttributionState.INFERRED, entity.attributionState)
        assertEquals("K7LWH", entity.stationId)
        assertEquals(0.7, entity.attributionConfidence)
        assertFalse(entity.corrected)
    }

    @Test
    fun R_189_currentAttribution_leaves_an_uncorrected_row_to_the_resolvers_own_fallback(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH"))
        val resolverFallback = Attribution.confirmed("K7LWH", 0.94)

        val attribution = CorrectionPolling.currentAttribution(context, "TX1", resolverFallback)

        assertEquals(resolverFallback, attribution)
    }

    @Test
    fun R_189_currentAttribution_returns_the_fallback_for_a_transmission_that_no_longer_exists(): Unit = runTest {
        val fallback = Attribution.unknown()

        val attribution = CorrectionPolling.currentAttribution(context, "does-not-exist", fallback)

        assertEquals(fallback, attribution)
    }

    // ---- R-321: `Undo all` restores the exact recorded prior attribution, through the real DAO ----

    /**
     * The coordinator's own repro: a Tier-A pick on an AMBIGUOUS over, then `Undo all` — before
     * this fix, the row was left reading `INFERRED`/`corrected` forever (never returning to its
     * chooser). Real DAO round trip: [CorrectionPolling.applyCorrection] then [CorrectionPolling.undoAll],
     * asserted against the real [org.ort.data.entity.TransmissionEntity] columns, not the mapper.
     */
    @Test
    fun R_321_undo_restores_ambiguous(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(
            transmission("TX1", stationId = null).copy(attributionState = AttributionState.AMBIGUOUS),
        )

        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", null, "KA7LWH"),
            CorrectionScope.THIS_OVER_ONLY,
        )
        CorrectionPolling.undoAll(context, outcome, atMillis = 700L)

        val entity = db.transmissionDao().getById("TX1")!!
        assertEquals(AttributionState.AMBIGUOUS, entity.attributionState)
        assertEquals(null, entity.stationId)
        assertEquals(null, entity.attributionConfidence)
        assertFalse("the CORRECTED badge must clear so the over returns to its chooser", entity.corrected)
    }

    /** The coordinator's own second repro: a typed (`FREE_TEXT`) correction on a CONFIRMED over,
     * then `Undo all` — the real confidence and CONFIRMED state must come back exactly, not a
     * downgrade to INFERRED. */
    @Test
    fun R_321_undo_restores_confirmed(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(
            transmission("TX1", stationId = "K7LWH").copy(
                attributionState = AttributionState.CONFIRMED,
                attributionConfidence = 0.95,
            ),
        )

        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "VE7ABC", tier = CorrectionTier.FREE_TEXT),
            CorrectionScope.THIS_OVER_ONLY,
        )
        CorrectionPolling.undoAll(context, outcome, atMillis = 700L)

        val entity = db.transmissionDao().getById("TX1")!!
        assertEquals(AttributionState.CONFIRMED, entity.attributionState)
        assertEquals("K7LWH", entity.stationId)
        assertEquals(0.95, entity.attributionConfidence)
        assertFalse("the CORRECTED badge must clear", entity.corrected)
    }

    // R-153 (passFailure/retryFailedPass), R-055/R-194 (revisions/restore) and R-188
    // (currentTranscriptConfidence) moved to `CorrectionPollingPassAndRevisionsTest.kt` — detekt's
    // `LargeClass` finding, this file's own size after this round's R-188/R-194 tests; the same fix
    // `RowsTest.kt`'s own `NavRowTest.kt` split used (see that file's doc comment).
}
