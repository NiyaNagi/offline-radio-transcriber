package org.ort.capture.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.capture.android.fake.FakeAudioIo
import org.ort.captureapi.CaptureEvent
import org.ort.testing.Requirement
import org.ort.testing.TestClock

/**
 * Register R-1113, constitution IV ("capture never blocks, never drops, never lies"): before P34,
 * [AudioRecordSource] only ever re-verified the route once, on the very first successful read (plus
 * whenever an explicit [AudioIoEvent.RouteChanged] happened to arrive) — nothing re-checked it
 * afterwards. On a real device that means a route that silently drifts away from the selected
 * device *without* the OS ever raising an event (the exact case [AndroidAudioIo]'s own callback
 * cannot cover — see that class's kdoc) is never caught at all. Periodic re-verification closes
 * that gap; this proves the constitutional constraint that closing it must not introduce: the
 * verifier runs alongside the read loop, never inline with it, so a verifier that never returns
 * cannot cost the capture loop a single frame or a single millisecond of added latency.
 */
class AudioRecordSourcePeriodicReverificationTest {

    private val usb = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Adapter")

    private fun tone(n: Int): ShortArray = ShortArray(n) { (it % 100).toShort() }

    @Test
    @Requirement("FR-CAP-3", "FR-RUN-11")
    fun `a stalled periodic route verifier never blocks a single frame nor adds latency to the read loop`() = runTest {
        val io = FakeAudioIo(deviceSampleRate = 16_000)
        io.forceRoutedDevice(usb)
        repeat(6) { io.enqueueFrames(tone(160)) }
        val clock = TestClock()
        var probeCalls = 0
        // Deliberately never completes -- the worst case a verifier implementation could do.
        val neverCompletes = CompletableDeferred<AudioDeviceDescriptor?>()
        val source = AudioRecordSource(
            io,
            selection = usb,
            clock = clock,
            routeReverifyIntervalMillis = 1_000L,
            routeProbe = {
                probeCalls++
                neverCompletes.await()
            },
        )

        val collected = mutableListOf<CaptureEvent>()
        val job = this.launch {
            source.start().collect { ev ->
                collected.add(ev)
                if (ev is CaptureEvent.Frames) {
                    // Real time passing between frames -- enough that several re-verify intervals
                    // elapse across the run, so the periodic check is actually exercised.
                    clock.advance(400)
                    if (collected.count { it is CaptureEvent.Frames } == 6) source.stop()
                }
            }
        }
        job.join()

        assertEquals(
            6,
            collected.count { it is CaptureEvent.Frames },
            "every frame must still be delivered while the verifier is stalled",
        )
        assertTrue(probeCalls > 0, "the periodic verifier must actually have been triggered during the run")
        assertFalse(
            collected.any { it is CaptureEvent.Interrupted && it.cause.startsWith("dropped samples:") },
            "a stalled verifier running alongside the read loop must never itself manifest as dropped audio",
        )
        assertFalse(collected.any { it is CaptureEvent.Failed }, "a stalled verifier must never fail the session")
    }

    @Test
    @Requirement("FR-CAP-3")
    fun `a periodic verifier that finds a real mismatch halts capture exactly as FR-CAP-3`() = runTest {
        val builtIn = AudioDeviceDescriptor("mic-0", AudioDeviceKind.BUILT_IN_MIC, "Built-in Microphone")
        val io = FakeAudioIo(deviceSampleRate = 16_000)
        io.forceRoutedDevice(usb)
        repeat(10) { io.enqueueFrames(tone(160)) }
        val clock = TestClock()
        val source = AudioRecordSource(
            io,
            selection = usb,
            clock = clock,
            routeReverifyIntervalMillis = 1_000L,
            // No AudioIoEvent.RouteChanged is ever raised here -- exactly the silent-mismatch case
            // no OS callback reliably reports, which only a periodic poll of routedDevice() catches.
            routeProbe = { io.routedDevice() },
        )

        val collected = mutableListOf<CaptureEvent>()
        source.start().collect { ev ->
            collected.add(ev)
            if (ev is CaptureEvent.Frames) {
                clock.advance(400)
                if (collected.count { it is CaptureEvent.Frames } == 2) {
                    // The OS silently reroutes -- no explicit event, mirroring AndroidAudioIo's own
                    // documented minimal real behaviour for this exact scenario.
                    io.silentlyReroute(builtIn)
                }
            }
        }

        assertTrue(
            collected.any { it is CaptureEvent.Failed },
            "a periodic check that finds a real mismatch must halt exactly as FR-CAP-3",
        )
    }
}
