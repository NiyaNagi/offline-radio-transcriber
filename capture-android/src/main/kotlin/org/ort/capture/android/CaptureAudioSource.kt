package org.ort.capture.android

import android.media.MediaRecorder

/**
 * Which `MediaRecorder.AudioSource` capture opens the hardware on — the closed set technical
 * design §5.1 named, and register R-1169 found had never been implemented: the code hardcoded
 * [VOICE_RECOGNITION] unconditionally and nothing anywhere mentioned `UNPROCESSED` at all.
 *
 * The spec's rule, restated: **`UNPROCESSED` when the device reports supporting it**
 * (`AudioManager.getProperty(PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)`), **`VOICE_RECOGNITION`
 * otherwise, and explicitly never `MIC` with effects**, which applies AGC and noise suppression
 * that fight the ASR. This matters more than it reads: `VOICE_RECOGNITION` is an OEM-tuned path,
 * and on several skins — ColorOS, the reference device, in particular — it applies the vendor's
 * own hidden automatic gain and noise suppression, so a level that will not sit still may be a
 * vendor AGC rather than anything this app did.
 *
 * [operatorLabel] is deliberately plain: it is shown to the operator beside the negotiated native
 * rate, because before this there was no way to tell from a capture which processing the OEM had
 * applied to it.
 */
public enum class CaptureAudioSource(public val androidSource: Int, public val operatorLabel: String) {

    /** No OEM processing — what this project wants whenever a device offers it. */
    UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED, "unprocessed"),

    /** The fallback. Better than `MIC`, but the OEM may still be applying gain and suppression. */
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION, "voice recognition (OEM processed)"),
    ;

    public companion object {

        /** Technical design §5.1's decision, as one pure function with both branches testable. */
        public fun preferredFor(unprocessedSupported: Boolean): CaptureAudioSource =
            if (unprocessedSupported) UNPROCESSED else VOICE_RECOGNITION

        /**
         * Maps a real `AudioRecord.getAudioSource()` back onto this set, so what is recorded is the
         * source the open actually *obtained* rather than the one that was asked for. `null` for
         * anything outside the set — including `MIC`, which nothing in this project ever opens, so
         * a `null` here would be a genuine finding rather than a value to paper over.
         */
        public fun fromAndroidSource(source: Int): CaptureAudioSource? =
            entries.firstOrNull { it.androidSource == source }
    }
}
