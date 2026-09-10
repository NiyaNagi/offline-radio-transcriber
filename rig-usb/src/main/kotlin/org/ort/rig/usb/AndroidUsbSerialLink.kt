package org.ort.rig.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import com.hoho.android.usbserial.driver.UsbSerialPort as VendorUsbSerialPort

private const val ACTION_USB_PERMISSION = "org.ort.rig.usb.action.USB_PERMISSION"

/**
 * The real [UsbSerialLink]: usb-serial-for-android's CDC-ACM driver claimed through
 * [UsbManager], permission requested through a [PendingIntent] broadcast (FR-RIG-3, FR-PLT-2).
 * Exercised only by hardware rows H1/H4 (`results/e2e-audit/hardware-checklist.md`) — every
 * behavioural test in this module runs [UsbSerialTransport] against
 * [org.ort.rig.usb.fakes.FakeUsbSerialPort] instead, so nothing here is unit-tested by this
 * package; it exists to satisfy the contract, not to carry coverage.
 *
 * One instance owns exactly one [Context.registerReceiver] pair for its lifetime — call
 * [dispose] when the module that created it is torn down, or the receivers leak.
 */
public class AndroidUsbSerialLink(private val context: Context) : UsbSerialLink {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val eventsChannel = Channel<UsbSerialLinkEvent>(capacity = Channel.UNLIMITED)
    private val incoming = Channel<ByteArray>(capacity = Channel.UNLIMITED)

    private var port: VendorUsbSerialPort? = null
    private var openDevice: UsbDevice? = null
    private var ioManager: SerialInputOutputManager? = null

    override val events: Flow<UsbSerialLinkEvent> = eventsChannel.receiveAsFlow()

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            eventsChannel.trySend(UsbSerialLinkEvent.PermissionResult(device.toHandle(), granted))
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            if (device == openDevice) {
                eventsChannel.trySend(UsbSerialLinkEvent.Detached(device.toHandle()))
            }
        }
    }

    init {
        registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
        registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
    }

    private fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    /** Unregisters both broadcast receivers. Call once, when the owning module is torn down. */
    public fun dispose() {
        runCatching { context.unregisterReceiver(permissionReceiver) }
        runCatching { context.unregisterReceiver(detachReceiver) }
    }

    private fun UsbDevice.toHandle() = UsbDeviceHandle(vendorId, productId, deviceName)

    private fun findUsbDevice(vendorId: Int, productId: Int): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { it.vendorId == vendorId && it.productId == productId }

    override fun attachedDevices(vendorId: Int, productId: Int): List<UsbDeviceHandle> =
        listOfNotNull(findUsbDevice(vendorId, productId)?.toHandle())

    override fun hasPermission(device: UsbDeviceHandle): Boolean {
        val usbDevice = findUsbDevice(device.vendorId, device.productId) ?: return false
        return usbManager.hasPermission(usbDevice)
    }

    override fun requestPermission(device: UsbDeviceHandle) {
        val usbDevice = findUsbDevice(device.vendorId, device.productId)
        if (usbDevice == null) {
            // The device vanished between attachedDevices() and requestPermission(): report the
            // only honest outcome (F16 — never throw into capture over a race like this).
            eventsChannel.trySend(UsbSerialLinkEvent.PermissionResult(device, granted = false))
            return
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
        usbManager.requestPermission(usbDevice, pendingIntent)
    }

    override fun open(device: UsbDeviceHandle, config: UsbSerialLineConfig) {
        val usbDevice = findUsbDevice(device.vendorId, device.productId)
            ?: throw UsbSerialIoException("device no longer attached: ${device.deviceName}")
        val driver = UsbSerialProber.getDefaultProber().probeDevice(usbDevice)
            ?: throw UsbSerialIoException("no CDC-ACM driver claims ${device.deviceName}")
        val connection = usbManager.openDevice(usbDevice)
            ?: throw UsbSerialIoException("UsbManager.openDevice returned null for ${device.deviceName}")
        val serialPort = driver.ports.firstOrNull()
            ?: throw UsbSerialIoException("driver exposes no ports for ${device.deviceName}")
        try {
            serialPort.open(connection)
            serialPort.setParameters(
                config.baudRate,
                config.dataBits,
                config.stopBits.toVendorStopBits(),
                config.parity.toVendorParity(),
            )
        } catch (e: IOException) {
            throw UsbSerialIoException("failed to open ${device.deviceName}", e)
        }
        port = serialPort
        openDevice = usbDevice
        val manager = SerialInputOutputManager(
            serialPort,
            object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    incoming.trySend(data)
                }

                override fun onRunError(e: Exception) {
                    eventsChannel.trySend(UsbSerialLinkEvent.Detached(device))
                }
            },
        )
        ioManager = manager
        manager.start()
    }

    override fun close() {
        ioManager?.stop()
        ioManager = null
        runCatching { port?.close() }
        port = null
        openDevice = null
    }

    override fun write(bytes: ByteArray) {
        val current = port ?: throw UsbSerialIoException("port is not open")
        try {
            current.write(bytes, WRITE_TIMEOUT_MILLIS)
        } catch (e: IOException) {
            throw UsbSerialIoException("write failed", e)
        }
    }

    override suspend fun read(timeoutMs: Long): ByteArray =
        withTimeoutOrNull(timeoutMs) { incoming.receive() } ?: ByteArray(0)

    private fun Int.toVendorStopBits(): Int = when (this) {
        1 -> VendorUsbSerialPort.STOPBITS_1
        2 -> VendorUsbSerialPort.STOPBITS_2
        else -> throw UsbSerialIoException("unsupported stop bits: $this")
    }

    private fun UsbSerialParity.toVendorParity(): Int = when (this) {
        UsbSerialParity.NONE -> VendorUsbSerialPort.PARITY_NONE
        UsbSerialParity.ODD -> VendorUsbSerialPort.PARITY_ODD
        UsbSerialParity.EVEN -> VendorUsbSerialPort.PARITY_EVEN
    }

    private companion object {
        const val WRITE_TIMEOUT_MILLIS = 1_000
    }
}
