package at.zweibit.fahrtenbuch.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import at.zweibit.fahrtenbuch.FahrtenbuchApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Ordnet eine beendete Fahrt direkt aus der Benachrichtigung einer Kategorie zu. */
class KategorieReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_FAHRT = "fahrt_id"
        const val EXTRA_KATEGORIE = "kategorie_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val fahrtId = intent.getLongExtra(EXTRA_FAHRT, 0L)
        val kategorieId = intent.getLongExtra(EXTRA_KATEGORIE, 0L)
        if (fahrtId == 0L || kategorieId == 0L) return
        val app = context.applicationContext as FahrtenbuchApp
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val f = app.repository.fahrt(fahrtId) ?: return@launch
                var start = f.startAdresse
                var ziel = f.endeAdresse
                val orte = app.einstellungen.orteAktuell()
                if (Adressen.fehlt(start) && f.startLat != null && f.startLon != null) {
                    start = Adressen.bestimmen(context, f.startLat, f.startLon, orte, 3_000)
                }
                if (Adressen.fehlt(ziel) && f.endeLat != null && f.endeLon != null) {
                    ziel = Adressen.bestimmen(context, f.endeLat, f.endeLon, orte, 3_000)
                }
                app.repository.kategorisieren(fahrtId, kategorieId, f.notiz, start, ziel)
                Benachrichtigungen.beendetEntfernen(context, fahrtId)
            } finally {
                pending.finish()
            }
        }
    }
}
