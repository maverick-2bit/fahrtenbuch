package at.zweibit.fahrtenbuch

import android.app.Application
import at.zweibit.fahrtenbuch.blitzer.BlitzerDaten
import at.zweibit.fahrtenbuch.data.AppDatabase
import at.zweibit.fahrtenbuch.data.Einstellungen
import at.zweibit.fahrtenbuch.data.Repository
import at.zweibit.fahrtenbuch.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FahrtenbuchApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.erstellen(this) }
    val repository: Repository by lazy { Repository(database) }
    val einstellungen: Einstellungen by lazy { Einstellungen(this) }

    /** Für Arbeiten, die einen Bildschirmwechsel überdauern sollen (z. B. Blitzer-Download). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun blitzerAktualisieren() {
        appScope.launch { BlitzerDaten.aktualisieren(this@FahrtenbuchApp) }
    }

    override fun onCreate() {
        super.onCreate()
        // Blitzerdaten im Hintergrund auffrischen, wenn die Warnung aktiv und der Stand älter als 30 Tage ist
        // Höchstens alle 6 Stunden still nach einer neuen App-Version schauen
        appScope.launch { Updater.pruefen(this@FahrtenbuchApp, automatisch = true) }
        appScope.launch {
            val info = BlitzerDaten.infoLaden(this@FahrtenbuchApp)
            if (einstellungen.aktuell().blitzerWarnung && BlitzerDaten.veraltet(info)) {
                BlitzerDaten.aktualisieren(this@FahrtenbuchApp)
            }
        }
    }
}
