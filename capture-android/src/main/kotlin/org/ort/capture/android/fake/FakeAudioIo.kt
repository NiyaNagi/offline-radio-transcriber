package org.ort.capture.android.fake

import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioIo
import org.ort.capture.android.AudioIoEvent

/**
 * A scriptable [AudioIo] — the behavioural fake every [org.ort.capture.android.AudioRecordSource]
 * test in this module runs against (constitution II). It can be told to route somewhere other
 * than what was selected ([forceRoutedDevice]), to raise an interruption
 * ([raiseInterruption]), or to fail to reopen ([openSucceeds]), which is what lets AC-2, AC-48,
 * AC-49 and AC-98 be demonstrated without a device.
 */
public class FakeAudioIo(
    override val deviceSampleRate: Int = 16_000,
    private val devices: List<AudioDeviceDescriptor> = emptyList(),
) : AudioIo {

    private var routed: AudioDeviceDescriptor? = null
    private var listener: ((AudioIoEvent) -> Unit)? = null
    private var opened = false

    /** Set to false to simulate the device refusing to (re)open. */
    public var openSucceeds: Boolean = true

    public var openCount: Int = 0
        private set
    public var closeCount: Int = 0
        private set

    private val frameQueue = ArrayDeque<ShortArray>()

    override fun availableDevices(): List<AudioDeviceDescriptor> = devices

    override fun select(device: AudioDeviceDescriptor) {
        // Deliberately does NOT set [routed] — selecting a device is a *request*; the OS decides
        // routing independently (that gap is exactly what AC-2/AC-98 test). A test sets what the
        // OS actually routed to via [forceRoutedDevice], normally *before* calling `start()`, to
        // simulate either agreement or the silent-mismatch failure mode.
    }

    override fun open(): Boolean {
        openCount++
        if (!openSucceeds) return false
        opened = true
        return true
    }

    override fun routedDevice(): AudioDeviceDescriptor? = if (opened) routed else null

    override fun read(buffer: ShortArray): Int {
        if (!opened) return -1
        val next = frameQueue.removeFirstOrNull() ?: return 0
        val n = minOf(buffer.size, next.size)
        System.arraycopy(next, 0, buffer, 0, n)
        return n
    }

    override fun close() {
        opened = false
        closeCount++
    }

    override fun setEventListener(listener: (AudioIoEvent) -> Unit) {
        this.listener = listener
    }

    /** Queues one block to be returned by the next [read]. */
    public fun enqueueFrames(frames: ShortArray) {
        frameQueue.addLast(frames)
    }

    /** Simulates the OS silently routing elsewhere — e.g. a USB adapter unplugged mid-run. */
    public fun forceRoutedDevice(device: AudioDeviceDescriptor?) {
        routed = device
        listener?.invoke(AudioIoEvent.RouteChanged)
    }

    public fun raiseInterruption(cause: String) {
        listener?.invoke(AudioIoEvent.Interrupted(cause))
    }
}
