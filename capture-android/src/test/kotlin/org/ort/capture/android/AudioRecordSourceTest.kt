package org.ort.capture.android

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.capture.android.fake.FakeAudioIo
import org.ort.captureapi.CaptureEvent
import org.ort.testing.Requirement
import org.ort.testing.TestClock

class AudioRecordSourceTest {

    private val usb = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Adapter")
    private val builtIn = AudioDeviceDescriptor("mic-0", AudioDeviceKind.BUILT_IN_MIC, "Built-in Microphone")

    private fun tone(n: Int): ShortArray = ShortArray(n) { (it % 100).toShort() }

    /** Collects until [stopWhen] is true for some event, then requests a clean stop and drains. */
    private suspend fun collectUntilStopped(
        source: AudioRecordSource,
        stopWhen: (CaptureEvent) -> Boolean,
    ): List<CaptureEvent> {
        val out = mutableListOf<CaptureEvent>()
        source.start().collect { ev ->
            out.add(ev)
            if (stopWhen(ev)) source.stop()
        }
        return out
    }

    @Test
    @Requirement("AC-2", "FR-CAP-3")
    fun `AC_2 a route mismatch after the first read halts and no frames from the mic are emitted`() = runTest {
        val io = FakeAudioIo(deviceSampleRate = 16_000)
        io.enqueueFrames(tone(160))
        // The OS silently routes the built-in mic even though USB was selected.
        io.forceRoutedDevice(builtIn)
        val source = AudioRecordSource(io, selection = usb)

        val events = collectUntilStopped(source) { false } // a mismatch halts the flow on its own

        assertTrue(events.none { it is CaptureEvent.Frames }, "must not have recorded from the built-in mic")
        assertTrue(events.any { it is CaptureEvent.Failed }, "a mismatch must halt with a visible error")
    }

    @Test
    @Requirement("AC-98", "FR-CAP-3a")
    fun `AC_98 a deliberate selection of the built-in mic captures successfully`() = runTest {
        val io = FakeAudioIo(deviceSampleRate = 16_000)
        io.enqueueFrames(tone(160))
        io.forceRoutedDevice(builtIn)
        val source = AudioRecordSource(io, selection = builtIn)

        val events = collectUntilStopped(source) { it is CaptureEvent.Frames }

        assertTrue(events.any { it is CaptureEvent.Frames }, "the deliberately-selected built-in mic must capture")
        assertFalse(events.any { it is CaptureEvent.Failed })
    }

    @Test
    @Requirement("AC-97", "FR-CAP-2a", "FR-CAP-1")
    fun `AC_97 a 48kHz device records the resampler identity used to reach 16kHz`() {
        val io = FakeAudioIo(deviceSampleRate = 48_000)
        val source = AudioRecordSource(io, selection = usb)

        assertEquals(48_000, source.deviceFormat.sampleRate)
        assertEquals(16_000, source.outputFormat.sampleRate)
        assertEquals(1, source.outputFormat.channels, "capture output is mono PCM (FR-CAP-1)")
        assertTrue(source.resamplerIdentity != null, "a rate-changing device must record which resampler produced it")
    }

    @Test
    @Requirement("AC-97", "FR-CAP-1")
    fun `AC_97 a native 16kHz device records no resampler identity`() {
        val io = FakeAudioIo(deviceSampleRate = 16_000)
        val source = AudioRecordSource(io, selection = usb)

        assertEquals(null, source.resamplerIdentity)
        assertEquals(16_000, source.outputFormat.sampleRate)
        assertEquals(1, source.outputFormat.channels, "capture output is mono PCM (FR-CAP-1)")
    }

    @Test
    @Requirement("AC-48", "AC-49", "FR-RUN-11", "FR-RUN-12")
    fun `AC_48 an interruption produces a gap with correct bounds and capture resumes automatically`() = runTest {
        val io = FakeAudioIo(deviceSampleRate = 16_000)
        io.enqueueFrames(tone(160))
        io.forceRoutedDevice(usb)
        val source = AudioRecordSource(io, selection = usb)
        val clock = TestClock()
        val tracker = GapTracker(clock)

        val collected = mutableListOf<CaptureEvent>()
        val job = this.launch {
            source.start().collect { ev ->
                collected.add(ev)
                tracker.onEvent(ev)
                if (ev is CaptureEvent.Frames && collected.count { it is CaptureEvent.Frames } == 1) {
                    io.raiseInterruption("focus loss")
                }
                if (ev is CaptureEvent.Interrupted) {
                    clock.advance(5_000) // real time passing while the device is down
                }
                if (ev is CaptureEvent.Resumed) {
                    io.enqueueFrames(tone(160))
                }
                if (ev is CaptureEvent.Frames && collected.count { it is CaptureEvent.Frames } == 2) {
                    source.stop()
                }
            }
        }
        job.join()

        assertTrue(collected.any { it is CaptureEvent.Interrupted }, "an interruption must be reported")
        assertTrue(collected.any { it is CaptureEvent.Resumed }, "capture must resume automatically (AC-48)")
        assertEquals(1, tracker.gaps.size, "exactly one gap must be recorded, with correct bounds")
        val gap = tracker.gaps.single()
        assertEquals("focus loss", gap.cause)
        assertEquals(5_000L, gap.endWallMillis - gap.startWallMillis, "the gap must span exactly the outage duration")
        assertTrue(gap.endMonotonicNanos > gap.startMonotonicNanos)
    }

    @Test
    @Requirement("AC-49", "FR-RUN-12")
    fun `AC_49 a gap is distinguishable from genuine captured silence`() = runTest {
        // Genuine silence: real Frames events full of zeros, no interruption at all.
        val io = FakeAudioIo(deviceSampleRate = 16_000)
        io.enqueueFrames(ShortArray(160)) // all-zero == silence, but it is still audio
        io.forceRoutedDevice(usb)
        val source = AudioRecordSource(io, selection = usb)
        val tracker = GapTracker(TestClock())

        collectUntilStopped(source) { ev ->
            tracker.onEvent(ev)
            ev is CaptureEvent.Frames
        }

        assertTrue(tracker.gaps.isEmpty(), "genuine silence that was actually captured must not register as a gap")
    }

    @Test
    @Requirement("AC-1", "FR-CAP-5")
    fun `AC_1 a mid-run disconnection is surfaced immediately and retried with backoff, not ended`() = runTest {
        val io = FakeAudioIo(deviceSampleRate = 16_000)
        io.enqueueFrames(tone(160))
        io.forceRoutedDevice(usb)
        val source = AudioRecordSource(io, selection = usb)

        val collected = mutableListOf<CaptureEvent>()
        val job = this.launch {
            source.start().collect { ev ->
                collected.add(ev)
                if (ev is CaptureEvent.Frames && collected.count { it is CaptureEvent.Frames } == 1) {
                    // The adapter is unplugged: the OS reports it as an interruption, and every
                    // reconnection attempt fails until it is physically plugged back in.
                    io.openSucceeds = false
                    io.raiseInterruption("disconnected")
                }
                if (ev is CaptureEvent.Frames && collected.count { it is CaptureEvent.Frames } == 2) {
                    source.stop()
                }
            }
        }

        // Drive the retry loop's virtual clock explicitly: it must attempt more than once
        // before giving up, which is what "retry with backoff" means as opposed to a single
        // failed attempt. 30_001ms comfortably exceeds every step of the backoff ladder
        // (BackoffLadderTest), so each iteration advances past exactly one scheduled retry.
        var guard = 0
        while (io.openCount < 3 && guard < 20) {
            advanceTimeBy(30_001)
            runCurrent()
            guard++
        }
        assertTrue(
            io.openCount >= 3,
            "must attempt reconnection more than once while the adapter is out (FR-CAP-5) — got ${io.openCount}",
        )
        // The adapter is plugged back in; let the next retry succeed and the session finish.
        io.openSucceeds = true
        io.enqueueFrames(tone(160))
        advanceUntilIdle()
        job.join()

        val interruption = collected.filterIsInstance<CaptureEvent.Interrupted>().single()
        assertEquals(
            "disconnected",
            interruption.cause,
            "the disconnection must be surfaced with its real cause, not swallowed or generic",
        )
        assertTrue(
            collected.any { it is CaptureEvent.Resumed },
            "capture must resume once the adapter is reconnected",
        )
        assertFalse(
            collected.any { it is CaptureEvent.Failed },
            "a disconnection must never end the session outright — it keeps retrying and the session stays open",
        )
    }

    @Test
    @Requirement("FR-RUN-13")
    fun `FR_RUN_13 a route change mid-session that lands on a mismatch halts exactly as FR-CAP-3`() = runTest {
        val io = FakeAudioIo(deviceSampleRate = 16_000)
        io.enqueueFrames(tone(160))
        io.forceRoutedDevice(usb) // starts out matching the selection
        val source = AudioRecordSource(io, selection = usb)

        val collected = mutableListOf<CaptureEvent>()
        source.start().collect { ev ->
            collected.add(ev)
            if (ev is CaptureEvent.Frames && collected.count { it is CaptureEvent.Frames } == 1) {
                // Mid-session: the OS silently re-routes — e.g. a headset is attached — after
                // capture was already under way and verified once.
                io.forceRoutedDevice(builtIn)
            }
        }

        assertTrue(
            collected.any { it is CaptureEvent.RouteChanged },
            "a mid-session route change must be detected and re-verified (FR-RUN-13)",
        )
        assertTrue(
            collected.any { it is CaptureEvent.Failed },
            "a route change landing on a mismatch must halt exactly as FR-CAP-3",
        )
        assertEquals(
            1,
            collected.count { it is CaptureEvent.Frames },
            "no frames must be recorded from the mismatched route after the change",
        )
    }
}
