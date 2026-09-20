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
import org.ort.lexicon.VariantTable
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
 * [confirmThreshold]/[separationThreshold] are the same **uncalibrated, provisional** values
 * `PassBTest`'s real-grammar case uses — there is no dev-fold data yet to fit a real threshold
 * against (constitution VI; see [CallsignResolver]'s own doc comment on why confidence here is
 * clamped, not calibrated). A future session with real recordings replaces these.
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
        confirmThreshold: Float = -1f,
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
            combiner = PriorCombiner(emptyList()),
            resolver = CallsignResolver(separationThreshold = separationThreshold, confirmThreshold = confirmThreshold),
        )
        val fingerprint = PassFingerprint(
            passId = PassId.B_OFFLINE,
            codeVersion = 1,
            modelIds = listOf(modelRef),
            lexiconVersion = null,
            calibrationVersion = null,
            configHash = PassBFingerprintBuilder.configHash(
                confirmThreshold = confirmThreshold,
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
            decodeOptions = decodeOptions,
        )
    }
}
