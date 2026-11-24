package de.dx8.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Vordergrund-Dienst, solange der DX8 verbunden ist:
 *  - haelt den App-Prozess am Leben und die CPU wach (Wakelock), damit Abfrage, Eingangsschutz (STOP)
 *    und CSV-Aufzeichnung auch bei ausgeschaltetem Display oder anderer App weiterlaufen
 *  - zeigt eine dauerhafte Benachrichtigung mit dem Zustand beider Kanaele
 *  - meldet Ereignisse (fertig, Fehler, Rueckfrage, STOP) mit Ton
 * Die Abfrage selbst laeuft weiter im Thread der MainActivity.
 */
class MonitorService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChargeDeck:monitor").apply { setReferenceCounted(false); acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: lastText
        val n = status(this, text)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(ID_STATUS, n)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { wake?.release() } catch (_: Exception) { }
        super.onDestroy()
    }

    companion object {
        private const val CH_STATUS = "status"
        /** Meldungen ohne eigenen Kanal-Ton; der Ton kommt ueber die Medienlautstaerke (siehe playSound). */
        private const val CH_ALERT = "alerts_media"
        private const val CH_ALERT_OLD = "alerts"
        private const val ID_STATUS = 1
        private const val EXTRA_TEXT = "text"
        @Volatile private var running = false
        @Volatile private var lastText = "Verbunden"
        private var lastUpdate = 0L
        private var alertId = 100

        private fun createChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Überwachung", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Laufender Zustand beider Kanäle" })
            // Kanal-Toene lassen sich nach dem Anlegen nicht mehr aendern: alter Kanal (Benachrichtigungston) weg,
            // neuer Kanal ohne Ton – die App spielt den Ton selbst mit der Tablet-(Medien-)Lautstaerke
            try { nm.deleteNotificationChannel(CH_ALERT_OLD) } catch (_: Exception) { }
            nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Meldungen", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Fertig, Fehler, Rückfrage, STOP (Ton folgt der Medienlautstärke)"
                    enableVibration(true)
                    setSound(null, null)
                })
        }

        /** Benachrichtigungston ueber den Medien-Strom: Lautstaerke = Tablet-Lautstaerke (Lautstaerketasten). */
        private fun playSound(ctx: Context) {
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
                val r = RingtoneManager.getRingtone(ctx.applicationContext, uri) ?: return
                r.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                r.play()
            } catch (_: Exception) { }
        }

        private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        @Suppress("DEPRECATION")
        private fun builder(ctx: Context, ch: String): Notification.Builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, ch) else Notification.Builder(ctx)

        private fun status(ctx: Context, text: String): Notification =
            builder(ctx, CH_STATUS)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("ChargeDeck überwacht")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(ctx))
                .build()

        /** Dienst starten (beim Verbinden, App im Vordergrund). */
        fun start(ctx: Context) {
            if (running) return
            running = true
            val i = Intent(ctx, MonitorService::class.java).putExtra(EXTRA_TEXT, lastText)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Exception) { running = false }
        }

        fun stop(ctx: Context) {
            if (!running) return
            running = false
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }

        /** Laufende Benachrichtigung aktualisieren (hoechstens alle 5 s). */
        fun update(ctx: Context, text: String) {
            lastText = text
            if (!running) return
            val now = System.currentTimeMillis()
            if (now - lastUpdate < 5000) return
            lastUpdate = now
            try { ctx.getSystemService(NotificationManager::class.java).notify(ID_STATUS, status(ctx, text)) } catch (_: Exception) { }
        }

        /** Ereignis mit Ton/Vibration melden. */
        fun alert(ctx: Context, title: String, text: String) {
            createChannels(ctx)
            val n = builder(ctx, CH_ALERT)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
                .apply { if (Build.VERSION.SDK_INT < 26) setDefaults(Notification.DEFAULT_VIBRATE or Notification.DEFAULT_LIGHTS).setPriority(Notification.PRIORITY_HIGH) }
                .build()
            try { ctx.getSystemService(NotificationManager::class.java).notify(alertId++, n) } catch (_: Exception) { }
            playSound(ctx)
        }
    }
}
