package at.zweibit.fahrtenbuch

import android.app.Application
import at.zweibit.fahrtenbuch.blitzer.BlitzerDaten
import at.zweibit.fahrtenbuch.data.AppDatabase
import at.zweibit.fahrtenbuch.data.Einstellungen
import at.zweibit.fahrtenbuch.data.Repository
import at.zweibit.fahrtenbuch.sync.SyncPlaner
import at.zweibit.fahrtenbuch.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FahrtenbuchApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.erstellen(this) }

    /** Nach jeder protokollierten Änderung die Online-Sicherung anstoßen (wirkungslos ohne Verbindung). */
    val repository: Repository by lazy { Repository(database) { SyncPlaner.bald(this) } }
    val einstellungen: Einstellungen by lazy { Einstellungen(this) }

    /** Für Arbeiten, die einen Bildschirmwechsel überdauern sollen (z. B. Blitzer-Download). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun blitzerAktualisieren() {
        appScope.launch { BlitzerDaten.aktualisieren(this@FahrtenbuchApp) }
    }

    override fun onCreate() {
        super.onCreate()
        // Höchstens alle 6 Stunden still nach einer neuen App-Version schauen
        appScope.launch { Updater.pruefen(this@FahrtenbuchApp, automatisch = true) }
        // Blitzerdaten im Hintergrund auffrischen, wenn die Warnung aktiv und der Stand älter als 30 Tage ist
        appScope.launch {
            val info = BlitzerDaten.infoLaden(this@FahrtenbuchApp)
            if (einstellungen.aktuell().blitzerWarnung && BlitzerDaten.veraltet(info)) {
                BlitzerDaten.aktualisieren(this@FahrtenbuchApp)
            }
        }
        // Online-Sicherung: regelmäßiger Lauf als Sicherheitsnetz
        appScope.launch {
            if (einstellungen.syncAktuell().verbunden) {
                SyncPlaner.regelmaessig(this@FahrtenbuchApp)
                SyncPlaner.bald(this@FahrtenbuchApp)
            }
        }
    }
}
