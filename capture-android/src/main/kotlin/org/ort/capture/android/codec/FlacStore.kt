package org.ort.capture.android.codec

import java.io.File

/** What [FlacStore.encodeAndVerify] produced. */
public sealed interface FlacEncodeResult {
    public data class Success(val encodedFile: File, val stagedDeleted: Boolean) : FlacEncodeResult

    /** The codec failed the decode-and-compare check. Staged PCM is retained — never deleted quietly. */
    public data class VerificationFailed(val stagedFile: File, val reason: String) : FlacEncodeResult
}

/**
 * technical design §6.1: PCM is staged to disk, encoded, and the staged file is deleted **only**
 * once the encoded form has been decoded and compared byte-for-byte against it (constitution
 * III — retained audio must remain sufficient to re-run every pass; a silently-lossy codec
 * would violate that invisibly). A verification failure keeps the staged PCM and deletes the
 * broken encoded output instead — nothing is deleted quietly (constitution III, P9).
 */
public class FlacStore(private val codec: LosslessCodec) {

    public fun stage(pcmBytes: ByteArray, stagedFile: File) {
        stagedFile.parentFile?.mkdirs()
        stagedFile.writeBytes(pcmBytes)
    }

    public fun encodeAndVerify(stagedFile: File, encodedFile: File): FlacEncodeResult {
        val pcm = stagedFile.readBytes()
        val encoded = codec.encode(pcm)
        encodedFile.parentFile?.mkdirs()
        encodedFile.writeBytes(encoded)

        val roundTrip = codec.decode(encodedFile.readBytes())
        return if (roundTrip.contentEquals(pcm)) {
            stagedFile.delete()
            FlacEncodeResult.Success(encodedFile, stagedDeleted = true)
        } else {
            encodedFile.delete()
            FlacEncodeResult.VerificationFailed(
                stagedFile,
                "decode-and-compare mismatch: '${codec.name}' was not lossless for this input",
            )
        }
    }
}
