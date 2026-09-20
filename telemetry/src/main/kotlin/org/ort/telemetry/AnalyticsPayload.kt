package org.ort.telemetry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The closed, discriminated payload union every [AnalyticsEvent] carries. This interface has
 * exactly the members [AnalyticsTier1Payload], [AnalyticsTier2Payload] and [AnalyticsTier3Payload]
 * declare below — FR-ANL-1's "never an open schema a future change can extend without a spec
 * amendment" is enforced by this being `sealed`: a new payload shape cannot be added anywhere but
 * this file, and [org.ort.telemetry.vocabulary]'s field-list tests fail the moment one is.
 */
@Serializable
public sealed interface AnalyticsPayload

/**
 * FR-ANL-2's closed field list — tier 1, on by default. **No variant here may ever gain a
 * transcript, callsign, user-supplied name, station-knowledge or location field**; the vocabulary
 * test in `AnalyticsFieldVocabularyTest` fails the moment one does.
 */
@Serializable
public sealed interface AnalyticsTier1Payload : AnalyticsPayload {

    /** Crash traces and ANRs. [stackTrace] is code/line-number content, never operator or
     * third-party text — the same distinction [AnalyticsFieldVocabularyTest] draws explicitly.
     *
     * **[isAnr] is `Boolean?`, not `Boolean` (constitution I).** No ANR-detection mechanism exists
     * anywhere in this codebase — every event this build has ever produced reaches
     * [org.ort.app.analytics.CrashPayloads] through `Thread.UncaughtExceptionHandler`, which fires
     * for an uncaught exception, never for a hung main thread. A field that always reports `false`
     * is not "measured, and not an ANR" — it is "never measured" wearing a default, exactly the
     * silent wrongness constitution I forbids, and it would skew any crash analysis
     * `tools/analytics` later runs against this field. `null` states the honest fact: this build
     * cannot tell an ANR from an ordinary crash. The moment real detection lands, it sets this
     * field to a genuine `true`/`false`; until then every event is truthfully absent here, the
     * same discipline [AnalyticsProvenance.sessionId] already applies to "not yet known." */
    @Serializable
    @SerialName("tier1_crash")
    public data class Crash(
        val exceptionClass: String,
        val stackTrace: String,
        val isAnr: Boolean?,
        val threadName: String,
    ) : AnalyticsTier1Payload

    /** Usage and feature events — which screens and actions were used, never their content. */
    @Serializable
    @SerialName("tier1_usage")
    public data class Usage(val screen: String, val action: String) : AnalyticsTier1Payload

    /** Performance: per-pass latency and real-time factor. */
    @Serializable
    @SerialName("tier1_performance")
    public data class Performance(val passId: String, val latencyMs: Long, val realTimeFactor: Double) :
        AnalyticsTier1Payload

    /** Capture uptime and heartbeat gaps (NFR-8). */
    @Serializable
    @SerialName("tier1_capture_heartbeat")
    public data class CaptureHeartbeat(val uptimeMs: Long, val gapCount: Int, val gapDurationMs: Long) :
        AnalyticsTier1Payload

    /** The setup funnel, including model-download outcomes (FR-AST-11). */
    @Serializable
    @SerialName("tier1_setup_funnel")
    public data class SetupFunnel(val step: String, val outcome: String) : AnalyticsTier1Payload

    /** Aggregate transcript-quality statistics — never a transcript, never a callsign, only
     * rates and mixes keyed by field/state name (FR-SEG-10's VAD-fallback rate included). */
    @Serializable
    @SerialName("tier1_quality_stats")
    public data class QualityStats(
        val correctionRateByField: Map<String, Double>,
        val confidenceMix: Map<String, Double>,
        val attributionStateMix: Map<String, Double>,
        val unresolvedCallsignRate: Double,
        val vadFallbackRate: Double,
    ) : AnalyticsTier1Payload
}

/**
 * FR-ANL-3's closed field list — tier 2, opt-in: transcript text and callsigns, including
 * `(ASR hypothesis, user correction)` pairs.
 */
@Serializable
public sealed interface AnalyticsTier2Payload : AnalyticsPayload {

    @Serializable
    @SerialName("tier2_transcript")
    public data class Transcript(val text: String, val callsign: String?) : AnalyticsTier2Payload

    @Serializable
    @SerialName("tier2_correction")
    public data class Correction(val asrHypothesis: String, val userCorrection: String, val callsign: String?) :
        AnalyticsTier2Payload
}

/**
 * FR-ANL-4's closed field list — tier 3, opt-in: retained over audio together with its corrected
 * transcript. [audioBase64] rather than a raw `ByteArray` — this is the payload's own wire shape
 * (see [AnalyticsEventCodec]), and a `String` field carries `equals`/`hashCode` correctly, unlike
 * `ByteArray`'s reference identity.
 */
@Serializable
@SerialName("tier3_audio")
public data class AnalyticsTier3Payload(val audioBase64: String, val correctedTranscript: String) : AnalyticsPayload
