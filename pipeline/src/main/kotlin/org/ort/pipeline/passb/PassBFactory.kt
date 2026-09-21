package org.ort.pipeline.passb

import org.ort.asrapi.AsrEngine
import org.ort.asrapi.DecodeOptions
import org.ort.asrapi.RejectionPipeline
import org.ort.core.AssetRef
import org.ort.core.PassFingerprint
import org.ort.core.PassId
import org.ort.core.Tier
import org.ort.data.OrtDatabase
import org.ort.lexicon.CallsignGrammar
import org.ort.lexicon.ConfusionCostMatrix
import org.ort.lexicon.ItuPrefixTable
import org.ort.lexicon.PriorCombiner
import org.ort.lexicon.PropagationModel
import org.ort.lexicon.VariantTable
import org.ort.lexicon.defaultPriors
import org.ort.pipeline.alerts.AlertEvaluationTrigger
import org.ort.pipeline.alerts.NoOpAlertEvaluationTrigger
import java.io.File

/**
 * Wires the real Pass B (build-plan P12, defect 3): [FlacSegmentAudioProvider] reading retained
 * audio back from `:data`, [engine] doing the decode, the bundled lexicon grammar (`:lexicon`, P3)
 * resolving a callsign, and [DataPassBResultSink] persisting the result — the composition
 * `PassB`'s own doc comment describes, assembled with real collaborators instead of `PassBTest`'s
 * fixtures.
 *
 * **P33 / R-1109 (constitution I, FR-LEX-9, FR-LEX-25..27, FR-LEX-31):** [PriorCombiner] is built
 * from [defaultPriors] — frequency, band plausibility, database presence, recency, geography,
 * conversation context and my-stations, the same seven `:eval`'s `Main.kt` already builds for the
 * offline harness. Before this fix the combiner here was built from `emptyList()`, so every one
 * of those priors was dead on every real device capture; only `:eval` ever exercised them.
 *
 * **R-1124 (the other half of R-1109):** wiring the priors was not enough — [PassB]'s own
 * `contextFor` defaulted to an always-empty [org.ort.lexicon.RankingContext] and this factory
 * never supplied one, so every prior [defaultPriors] wired still evaluated against no data at
 * all. [DataRankingContextSource] is the real fix; see its own kdoc for exactly which priors it
 * makes read real `:data` and which stay honestly cold for lack of any on-device source.
 *
 * **P33 / R-1110 (constitution I, VI):** [calibrator] is `null` by default — there is no dev-fold
 * data yet to fit a real one against. See [CallsignResolver]'s own doc comment for what a `null`
 * calibrator does to the resolvable attribution states (never `CONFIRMED`), and [Calibrator]'s own
 * doc comment for why there is no "uncalibrated" implementation of it to reach for instead of
 * `null`. [separationThreshold] is unrelated to calibration (it only governs `AMBIGUOUS`) and
 * keeps its own provisional default.
 *
 * [provider] MUST be the execution provider [engine] actually runs on — supplied by the caller
 * (audit F-013), never guessed here. `:pipeline`'s own [AsrEngineAvailability.Available.provider]
 * is the one production source of this value; `"none"` is the honest answer when [engine] is an
 * [UnavailableAsrEngine] (constitution VI: "provider is part of provenance").
 *
 * **P31 follow-up (FR-ALR-3, FR-ALR-4, AC-194, AC-195):** [alertTrigger] defaults to
 * [NoOpAlertEvaluationTrigger] — the same honest-default discipline [DataPassBResultSink]'s own
 * constructor already applies — so every caller that has no real one to offer (`ReprocessRunner`,
 * every existing test) is unchanged. The one production caller with a real `Context` to build a
 * real [org.ort.pipeline.alerts.AlertEvaluationCoordinator] from —
 * `RealCaptureService.startProcessingLoop` — passes it explicitly; live capture is therefore the
 * only path that can ever fire a real alert, never reprocessing.
 */
public object PassBFactory {

    @Suppress("LongParameterList") // every parameter is an independent, real provenance/config fact
    // this factory needs (constitution VI/III) -- alertTrigger is the ninth, additive, defaulted
    // one (the same discipline RealCaptureService.kt's own RealSegmentSink suppression documents).
    public fun create(
        filesDir: File,
        db: OrtDatabase,
        engine: AsrEngine,
        modelRef: AssetRef,
        provider: String,
        tier: Tier = Tier.T0,
        calibrator: Calibrator? = null,
        separationThreshold: Float = 0.01f,
        alertTrigger: AlertEvaluationTrigger = NoOpAlertEvaluationTrigger,
    ): PassB {
        val variants = VariantTable.bundled()
        val ituTable = ItuPrefixTable.bundled()
        val confusionMatrix = ConfusionCostMatrix.bundled()
        val decodeOptions = DecodeOptions()
        val resolution = PassBResolutionChain(
            variants = variants,
            grammar = CallsignGrammar(ituTable, confusionMatrix),
            combiner = PriorCombiner(defaultPriors(PropagationModel())),
            resolver = CallsignResolver(separationThreshold = separationThreshold, calibrator = calibrator),
        )
        val fingerprint = PassFingerprint(
            passId = PassId.B_OFFLINE,
            codeVersion = 1,
            modelIds = listOf(modelRef),
            lexiconVersion = null,
            calibrationVersion = calibrator?.calibrationVersion,
            configHash = PassBFingerprintBuilder.configHash(
                confirmThreshold = calibrator?.confirmThreshold,
                separationThreshold = separationThreshold,
                decodeOptions = decodeOptions,
                variantsVersion = variants.version,
                ituVersion = ituTable.version,
                confusionVersion = confusionMatrix.version,
            ),
            provider = provider,
            tier = tier,
        )
        return PassB(
            audioProvider = FlacSegmentAudioProvider(filesDir, db),
            rejectionPipeline = RejectionPipeline(engine),
            resolution = resolution,
            fingerprint = fingerprint,
            sink = DataPassBResultSink(db, alertTrigger = alertTrigger),
            // R-1124: the real context source -- see its own kdoc for exactly which of the seven
            // priors this makes read real `:data`, and which stay honestly cold for lack of any
            // on-device data source at all.
            contextSource = DataRankingContextSource(db),
            decodeOptions = decodeOptions,
        )
    }
}
