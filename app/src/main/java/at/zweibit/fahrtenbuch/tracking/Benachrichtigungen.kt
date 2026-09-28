package at.zweibit.fahrtenbuch.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import at.zweibit.fahrtenbuch.MainActivity
import at.zweibit.fahrtenbuch.R
import at.zweibit.fahrtenbuch.data.Kategorie
import at.zweibit.fahrtenbuch.util.Format

object Benachrichtigungen {
    const val KANAL_LAUFEND = "fahrt_laufend"
    const val KANAL_BEENDET = "fahrt_beendet"
    const val KANAL_BLITZER = "blitzer"
    const val ID_LAUFEND = 1
    private const val ID_BLITZER = 2
    private const val ID_BEENDET_BASIS = 1000

    fun kanaeleAnlegen(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(KANAL_LAUFEND, context.getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.notification_channel_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(KANAL_BEENDET, "Fahrt beendet", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Fragt nach der Kategorie einer beendeten Fahrt." }
        )
        nm.createNotificationChannel(
            NotificationChannel(KANAL_BLITZER, "Blitzer-Warnung", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Hinweis auf fixe Blitzer während der Fahrt. Ton und Ansage kommen von der App selbst."
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    /** Kurzer Hinweis oben am Bildschirm, z. B. wenn gerade das Navi im Vordergrund ist. */
    fun blitzer(context: Context, text: String, dringend: Boolean) {
        val n = NotificationCompat.Builder(context, KANAL_BLITZER)
            .setSmallIcon(R.drawable.ic_stat_fahrt)
            .setContentTitle(if (dringend) "$text · zu schnell!" else text)
            .setContentText("Fahrtenbuch · Blitzer-Warnung")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setAutoCancel(true)
            .setTimeoutAfter(25_000)
            .setContentIntent(appOeffnen(context))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_BLITZER, n)
        } catch (_: SecurityException) {
        }
    }

    private fun appOeffnen(context: Context, fahrtId: Long = 0L): PendingIntent =
        PendingIntent.getActivity(
            context,
            fahrtId.toInt(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_FAHRT_ID, fahrtId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun dienstAktion(context: Context, code: Int, aktion: String): PendingIntent =
        PendingIntent.getService(
            context, code,
            Intent(context, TrackingService::class.java).setAction(aktion),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun laufend(context: Context, startZeit: Long, meter: Double, adresse: String): Notification {
        val text = buildString {
            append(Format.km(meter)).append(" · seit ").append(Format.uhrzeit(startZeit))
            if (adresse.isNotBlank()) append(" · ab ").append(adresse)
        }
        return NotificationCompat.Builder(context, KANAL_LAUFEND)
            .setSmallIcon(R.drawable.ic_stat_fahrt)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(appOeffnen(context))
            .addAction(0, "Pause (Zwischenziel)", dienstAktion(context, 2, TrackingService.ACTION_PAUSE))
            .addAction(0, context.getString(R.string.notification_stop), dienstAktion(context, 1, TrackingService.ACTION_STOPP))
            .build()
    }

    /** Während einer Pause unterwegs: Zwischenziel anzeigen, Weiterfahren oder Beenden anbieten. */
    fun pausiert(context: Context, meter: Double, zwischenziel: String, seit: Long): Notification {
        val text = buildString {
            append(zwischenziel.ifBlank { "Zwischenziel wird ermittelt …" })
            append(" · seit ").append(Format.uhrzeit(seit)).append(" · ").append(Format.km(meter))
        }
        return NotificationCompat.Builder(context, KANAL_LAUFEND)
            .setSmallIcon(R.drawable.ic_stat_fahrt)
            .setContentTitle("Fahrt pausiert")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(appOeffnen(context))
            .addAction(0, "Weiterfahren", dienstAktion(context, 3, TrackingService.ACTION_WEITER))
            .addAction(0, context.getString(R.string.notification_stop), dienstAktion(context, 1, TrackingService.ACTION_STOPP))
            .build()
    }

    /**
     * „Fahrt beendet"-Hinweis. Die ersten drei Kategorien sind direkt als Knöpfe wählbar,
     * ein Tipp auf die Benachrichtigung öffnet den Dialog in der App.
     */
    fun beendet(context: Context, fahrtId: Long, meter: Double, ziel: String, kategorien: List<Kategorie>) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val builder = NotificationCompat.Builder(context, KANAL_BEENDET)
            .setSmallIcon(R.drawable.ic_stat_fahrt)
            .setContentTitle("${context.getString(R.string.notification_done_title)} · ${Format.km(meter)}")
            .setContentText(if (ziel.isNotBlank()) "Ziel: $ziel. ${context.getString(R.string.notification_done_text)}" else context.getString(R.string.notification_done_text))
            .setStyle(NotificationCompat.BigTextStyle())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(appOeffnen(context, fahrtId))
        kategorien.take(3).forEach { k ->
            val pi = PendingIntent.getBroadcast(
                context,
                (fahrtId * 10 + k.id).toInt(),
                Intent(context, KategorieReceiver::class.java)
                    .putExtra(KategorieReceiver.EXTRA_FAHRT, fahrtId)
                    .putExtra(KategorieReceiver.EXTRA_KATEGORIE, k.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, k.name, pi)
        }
        try {
            NotificationManagerCompat.from(context).notify(beendetId(fahrtId), builder.build())
        } catch (_: SecurityException) {
            // Benachrichtigungsrecht fehlt – die App zeigt den Dialog beim nächsten Öffnen.
        }
    }

    fun beendetEntfernen(context: Context, fahrtId: Long) {
        NotificationManagerCompat.from(context).cancel(beendetId(fahrtId))
    }

    private fun beendetId(fahrtId: Long) = ID_BEENDET_BASIS + (fahrtId % 100_000).toInt()
}
