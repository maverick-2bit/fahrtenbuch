package at.zweibit.fahrtenbuch.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import at.zweibit.fahrtenbuch.FahrtenbuchApp
import java.util.concurrent.TimeUnit

/** Führt die Online-Sicherung im Hintergrund aus; bei Netzproblemen später erneut. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when (Sicherung.synchronisieren(applicationContext as FahrtenbuchApp)) {
            is SyncErgebnis.Ok, SyncErgebnis.NichtVerbunden, is SyncErgebnis.Abgewiesen -> Result.success()
            is SyncErgebnis.Fehler -> if (runAttemptCount < 20) Result.retry() else Result.failure()
        }
}

object SyncPlaner {
    private val mitNetz = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Kurz nach einer Änderung sichern (mehrere Änderungen hintereinander werden zusammengefasst). */
    fun bald(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "sync-bald",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(mitNetz)
                .setInitialDelay(15, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )
    }

    /** Sicherheitsnetz: alle 6 Stunden, falls einmal etwas liegen geblieben ist. */
    fun regelmaessig(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "sync-regelmaessig",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(mitNetz).build(),
        )
    }

    fun beenden(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork("sync-bald")
            cancelUniqueWork("sync-regelmaessig")
        }
    }
}
