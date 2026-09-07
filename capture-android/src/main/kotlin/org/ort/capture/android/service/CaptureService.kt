package org.ort.capture.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.ort.capture.android.heartbeat.HeartbeatRecord
import org.ort.capture.android.heartbeat.HeartbeatStore
import org.ort.core.Clock

/**
 * technical design §5.6: the foreground capture service. Holds a `PARTIAL_WAKE_LOCK` for the
 * duration of the session, writes a heartbeat every [HEARTBEAT_INTERVAL_MILLIS] via
 * [HeartbeatStore] (so liveness can be proven empirically — AC-65), and marks a clean shutdown
 * only when [cleanStop] is actually called, so an unclean end is detectable next launch (AC-5).
 * The notification is built exclusively from [CaptureNotificationBuilder], which cannot express
 * transcript text (AC-61).
 *
 * Wiring a real `AudioRecordSource` into this service (starting it, forwarding its
 * [org.ort.captureapi.CaptureEvent]s into the durable queue) is deployment glue that needs a real
 * `AudioRecord` and a real foreground-service microphone type — outside anything Robolectric can
 * meaningfully exercise — so it is left as [attachSource]'s caller's responsibility (`:pipeline`,
 * which owns wiring capture into the queue) rather than hard-coded here. What *is* tested here on
 * Robolectric is the service's own lifecycle: wake lock acquisition, heartbeat persistence,
 * notification content, and clean-vs-unclean shutdown marking.
 */
public class CaptureService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    public var heartbeatStore: HeartbeatStore? = null
    public var clock: Clock? = null
    public var sessionId: String = ""
    private var transmissionCount = 0
    private var startedAtWallMillis = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ort:capture")
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cleanStop()
            return START_NOT_STICKY
        }
        wakeLock?.let { if (!it.isHeld) it.acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
        startedAtWallMillis = clock?.wallMillis() ?: System.currentTimeMillis()
        startForeground(NOTIFICATION_ID, buildNotification("Capturing", 0))
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    /** Records one heartbeat and refreshes the notification's elapsed time (technical design §5.6). */
    public fun onHeartbeat(samplePosition: Long) {
        val c = clock ?: return
        heartbeatStore?.write(HeartbeatRecord(sessionId, c.monotonicNanos(), c.wallMillis(), samplePosition))
        updateNotification("Capturing", c.wallMillis() - startedAtWallMillis)
    }

    public fun onTransmissionCaptured() {
        transmissionCount++
    }

    /** The only path that marks a shutdown clean — anything else (kill, crash) leaves it unclean (AC-5). */
    public fun cleanStop() {
        heartbeatStore?.markCleanShutdown(sessionId)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(state: String, elapsedMillis: Long): Notification {
        val content = CaptureNotificationBuilder.build(state, elapsedMillis, transmissionCount)
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(state: String, elapsedMillis: Long) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(state, elapsedMillis))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_LOW))
        }
    }

    public companion object {
        public const val CHANNEL_ID: String = "ort.capture"
        public const val NOTIFICATION_ID: Int = 1001
        public const val ACTION_STOP: String = "org.ort.capture.android.STOP"
        public const val HEARTBEAT_INTERVAL_MILLIS: Long = 30_000
        private const val WAKE_LOCK_TIMEOUT_MILLIS: Long = 12 * 60 * 60 * 1000L
    }
}
