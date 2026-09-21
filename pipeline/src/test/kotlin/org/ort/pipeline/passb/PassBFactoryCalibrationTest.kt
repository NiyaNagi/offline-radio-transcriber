package org.ort.pipeline.passb

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.asrapi.fake.FakeAsrEngine
import org.ort.core.AssetRef
import org.ort.core.AttributionState
import org.ort.core.PassId
import org.ort.data.OrtDatabase
import org.ort.data.WorkQueue
import org.ort.lexicon.CallsignCandidate
import org.ort.lexicon.ConversationInfo
import org.ort.lexicon.ItuAllocation
import org.ort.lexicon.ParsedCallsign
import org.ort.lexicon.PropagationInputs
import org.ort.lexicon.RankingContext
import org.ort.lexicon.RecencyInfo
import org.ort.lexicon.RepeaterMatch
import org.ort.lexicon.Season
import org.ort.pipeline.CaptureProcessingLoop
import org.ort.pipeline.PassDrainRunner
import org.ort.pipeline.PipelineTestFixtures
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Build-plan P33, register R-1109/R-1110 (constitution I, VI): [PassBFactory] used to build
 * `PriorCombiner(emptyList())` — every one of the seven evidence priors was dead on every real
 * device capture, only `:eval`'s harness ever exercised them — and set `confirmThreshold = -1f`,
 * below every possible score, so [CallsignResolver] reported `CONFIRMED` for the top candidate
 * unconditionally, with `calibrationVersion` always `null`. These pin both fixes.
 *
 * Robolectric only (JVM) -- [PassBFactory.create] needs a real [OrtDatabase]/`filesDir`; nothing
 * here is device-verified (see this unit's own "Done when": the tour re-capture is left to the
 * lead's batch run, not attempted here).
 */
@RunWith(RobolectricTestRunner::class)
public class PassBFactoryCalibrationTest {

    private lateinit var db: OrtDatabase
    private lateinit var filesDir: File
    private val modelRef = AssetRef("fake-asr-model", "1")

    @Before
    public fun openDatabase() {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        filesDir = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
    }

    /** A resolvable transcript ("K7ABC") -- the same fixture [PassBFactoryTest] uses so every
     * caller of a real Pass B run has one real station id to resolve against. */
    private fun resolvableEngine(): FakeAsrEngine {
        val text = "kilo seven alpha bravo charlie"
        return FakeAsrEngine(FakeAsrEngine.Behaviour.Returns(FakeAsrEngine.defaultResult(text = text)))
    }

    private fun stageAudio(transmissionId: String, sessionId: String = "SESSION01") {
        val codec = org.ort.capture.android.codec.DeflatePredictiveCodec()
        val pcm = ByteArray(16_000 * 2)
        val encoded = codec.encode(pcm)
        val audioFile = File(filesDir, "audio/$sessionId/$transmissionId.flac")
        audioFile.parentFile?.mkdirs()
        audioFile.writeBytes(encoded)
    }

    /** A structurally valid "K7ABC" candidate, the same shape [CallsignResolverTest.fakeRanked]
     * builds -- but here handed straight to the factory's own wired [PriorCombiner], not a
     * hand-built one, so the assertion is about what [PassBFactory.create] actually assembled. */
    private fun fixtureCandidate(): CallsignCandidate {
        val parsed = ParsedCallsign(prefix = "K", areaDigit = '7', suffix = "ABC")
        return CallsignCandidate(
            parsed = parsed,
            allocation = ItuAllocation(parsed.prefix, "Test Entity", "TT"),
            acousticLogProb = 5.0f,
            editPenalty = 0f,
            slotSpan = 0..0,
        )
    }

    /** A context with real data for every one of the seven priors' inputs, tuned so each prior's
     * `rawLogOdds` is non-zero for [fixtureCandidate] (never merely "not cold start" -- the
     * assertion below checks the actual clamped [org.ort.lexicon.PriorContribution.logOdds]). */
    private fun fixtureContextWithDataForEveryPrior(): RankingContext = RankingContext(
        repeater = RepeaterMatch(frequencyHz = 146_520_000, expectedCallsigns = setOf("K7ABC")),
        databaseHits = setOf("K7ABC"),
        recency = mapOf("K7ABC" to RecencyInfo(secondsSinceLastHeard = 0)),
        geographicDistanceKm = { _ -> 10.0 },
        conversation = ConversationInfo(otherStationIdentified = true),
        myStations = setOf("K7ABC"),
        propagation = PropagationInputs(band = "20M", localHour = 12, season = Season.SUMMER, distanceKm = 500.0),
    )

    // --- R-1109: the seven evidence priors must be wired, not an empty combiner.

    @Test
    @Requirement("FR-LEX-9", "FR-LEX-25", "FR-LEX-26", "FR-LEX-27", "FR-LEX-31")
    public fun `R_1109 the factory wires all seven evidence priors, each contributing on a fixture over`() {
        val pass = PassBFactory.create(filesDir, db, resolvableEngine(), modelRef, provider = "cpu")

        val ranked = pass.resolution.combiner.rank(listOf(fixtureCandidate()), fixtureContextWithDataForEveryPrior())
            .single()

        val expectedPriors = setOf(
            "frequency",
            "band-plausibility",
            "database",
            "recency",
            "geographic",
            "conversation",
            "my-stations",
        )
        assertEquals(
            "PassBFactory.create must wire exactly the seven priors technical design §9.3 lists",
            expectedPriors,
            ranked.contributions.map { it.name }.toSet(),
        )
        expectedPriors.forEach { name ->
            val contribution = ranked.contribution(name)
            assertNotNull("expected a contribution from prior '$name'", contribution)
            assertTrue(
                "prior '$name' should not be a cold start given fixtureContextWithDataForEveryPrior",
                contribution!!.coldStart.not(),
            )
            assertNotEquals(
                "prior '$name' must contribute a non-zero term given real context data " +
                    "(an empty PriorCombiner would report no contribution for it at all)",
                0f,
                contribution.logOdds,
            )
        }
    }

    // --- R-1110: CONFIRMED requires a calibration; with none, AMBIGUOUS -- never CONFIRMED.

    @Test
    @Requirement("FR-LEX-17", "FR-LEX-18", "FR-LEX-19", "FR-LEX-20", "FR-LEX-21", "FR-SPK-10")
    public fun `R_1110 with no calibrator the persisted attribution is AMBIGUOUS, never CONFIRMED`(): Unit =
        runBlocking {
            db.sessionDao().insert(PipelineTestFixtures.session())
            db.transmissionDao().insert(PipelineTestFixtures.transmission("TX-NOCAL"))
            stageAudio("TX-NOCAL")

            val pass = PassBFactory.create(filesDir, db, resolvableEngine(), modelRef, provider = "cpu", calibrator = null)
            val queue = WorkQueue(db, TestClock())
            queue.enqueue("TX-NOCAL", PassId.B_OFFLINE)
            val loop = CaptureProcessingLoop(PassDrainRunner(queue, runId = "run-1"), pass)

            loop.drainOnce()

            val transmission = db.transmissionDao().getById("TX-NOCAL")!!
            assertEquals(AttributionState.AMBIGUOUS, transmission.attributionState)
            assertNotEquals(
                "R-1110: an uncalibrated score must never be reported as CONFIRMED",
                AttributionState.CONFIRMED,
                transmission.attributionState,
            )
            assertNull(pass.fingerprint.calibrationVersion)
        }

    @Test
    @Requirement("FR-LEX-17", "FR-LEX-18", "FR-SPK-10")
    public fun `R_1110 the default PassBFactory call site (no calibrator argument at all) is also AMBIGUOUS`(): Unit =
        runBlocking {
            // Exercises the real default production callers (RealCaptureService, ReprocessRunner)
            // are on today -- no calibrator argument supplied at all, not even explicit null.
            db.sessionDao().insert(PipelineTestFixtures.session())
            db.transmissionDao().insert(PipelineTestFixtures.transmission("TX-DEFAULT"))
            stageAudio("TX-DEFAULT")

            val pass = PassBFactory.create(filesDir, db, resolvableEngine(), modelRef, provider = "cpu")
            val queue = WorkQueue(db, TestClock())
            queue.enqueue("TX-DEFAULT", PassId.B_OFFLINE)
            val loop = CaptureProcessingLoop(PassDrainRunner(queue, runId = "run-1"), pass)

            loop.drainOnce()

            assertEquals(AttributionState.AMBIGUOUS, db.transmissionDao().getById("TX-DEFAULT")!!.attributionState)
        }

    // --- R-1110, other half: with a real calibrator, CONFIRMED is reachable and the fingerprint
    // carries its calibrationVersion.

    @Test
    @Requirement("FR-LEX-17", "FR-LEX-18", "FR-SPK-10")
    public fun `R_1110 with a calibrator present, CONFIRMED is reachable and the fingerprint carries calibrationVersion`():
        Unit = runBlocking {
        db.sessionDao().insert(PipelineTestFixtures.session())
        db.transmissionDao().insert(PipelineTestFixtures.transmission("TX-CAL"))
        stageAudio("TX-CAL")

        val calibrationVersion = AssetRef("dev-fold-calibration", "2026-09-20")
        val calibrator = FakeCalibrator(confirmThreshold = -1f, calibrationVersion = calibrationVersion)
        val pass = PassBFactory.create(filesDir, db, resolvableEngine(), modelRef, provider = "cpu", calibrator = calibrator)
        val queue = WorkQueue(db, TestClock())
        queue.enqueue("TX-CAL", PassId.B_OFFLINE)
        val loop = CaptureProcessingLoop(PassDrainRunner(queue, runId = "run-1"), pass)

        loop.drainOnce()

        val transmission = db.transmissionDao().getById("TX-CAL")!!
        assertEquals(AttributionState.CONFIRMED, transmission.attributionState)
        assertEquals("K7ABC", transmission.stationId)
        assertEquals(
            "the pass's own fingerprint must carry the calibrator's version, not null",
            calibrationVersion,
            pass.fingerprint.calibrationVersion,
        )
    }
}
