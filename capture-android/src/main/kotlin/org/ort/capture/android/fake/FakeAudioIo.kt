package org.ort.capture.android.fake

import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioIo
import org.ort.capture.android.AudioIoEvent

/**
 * A scriptable [AudioIo] — the behavioural fake every [org.ort.capture.android.AudioRecordSource]
 * test in this module runs against (constitution II). It can be told to route somewhere other
 * than what was selected ([forceRoutedDevice]), to raise an interruption
 * ([raiseInterruption]), to fail to reopen ([openSucceeds]), or to have its routed device
 * disappear mid-read ([dropDeviceMidRead]) — the shape a real Bluetooth SCO disconnect takes,
 * per [org.ort.capture.android.AndroidAudioIo]'s own doc comment on how minimal its real
 * route-change detection still is: no explicit event, just the next read failing and the route
 * going away. This is what lets AC-2, AC-48, AC-49, AC-98 and FR-CAP-11/FR-RIG-15's Bluetooth
 * drop scenarios be demonstrated without a device.
 */
public class FakeAudioIo(
    override val deviceSampleRate: Int = 16_000,
    private val devices: List<AudioDeviceDescriptor> = emptyList(),
) : AudioIo {

    private var routed: AudioDeviceDescriptor? = null
    private var listener: ((AudioIoEvent) -> Unit)? = null
    private var opened = false
    private var droppedMidRead = false

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
        // A fresh open mirrors a real reconnect: whatever previously dropped is no longer assumed gone.
        droppedMidRead = false
        return true
    }

    override fun routedDevice(): AudioDeviceDescriptor? = if (opened && !droppedMidRead) routed else null

    override fun read(buffer: ShortArray): Int {
        if (!opened) return -1
        if (droppedMidRead) return -1
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

    /**
     * FR-CAP-11, FR-RIG-15: simulates the routed device disappearing mid-stream — a Bluetooth SCO
     * link dropping, or a USB adapter pulled — with **no** explicit [AudioIoEvent] raised, exactly
     * mirroring [org.ort.capture.android.AndroidAudioIo]'s current minimal real behaviour (its own
     * doc comment: no proactive route-change notification, just the next read failing). The next
     * [read] returns `-1` — the same "read error" [org.ort.capture.android.AudioRecordSource]
     * already treats as an interruption (`n < 0`) — and [routedDevice] reports `null` immediately,
     * as a real disconnected device would. Cleared by the next successful [open].
     */
    public fun dropDeviceMidRead() {
        droppedMidRead = true
    }
}
