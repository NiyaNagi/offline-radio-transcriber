package org.ort.telemetry

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Encodes an [AnalyticsEvent] to exactly one NDJSON line and back — the wire/on-disk shape both
 * the on-device queue ([AnalyticsEventQueue]) and `tools/analytics`' reference ingest server
 * agree on (D48: "reference ingest server writing append-only NDJSON").
 *
 * AC-176: calling [encode] twice on two [AnalyticsEvent]s built from the same plain values (the
 * same provenance, the same payload fields) always produces the byte-identical line — there is no
 * hidden timestamp, random id or entity reference this codec adds on its own. Recomputing a
 * payload from a closed field list ([AnalyticsEventFactory]) and then encoding it deterministically
 * is what "reproduced bit-for-bit" means here.
 */
public object AnalyticsEventCodec {

    private val json = Json {
        encodeDefaults = true
        classDiscriminator = "payloadType"
    }

    /** One line, no trailing newline — the caller decides how lines are joined (queue storage
     * joins with `\n`; an upload request joins the same way, see `AnalyticsUploadRequest`). */
    public fun encode(event: AnalyticsEvent): String = json.encodeToString(event)

    public fun decode(line: String): AnalyticsEvent = json.decodeFromString(AnalyticsEvent.serializer(), line)

    /** UTF-8 byte length of [encode]'s output — what [AnalyticsEventQueue.byteSize] bounds
     * against (FR-ANL-13). */
    public fun byteSize(event: AnalyticsEvent): Long = encode(event).toByteArray(Charsets.UTF_8).size.toLong()
}
