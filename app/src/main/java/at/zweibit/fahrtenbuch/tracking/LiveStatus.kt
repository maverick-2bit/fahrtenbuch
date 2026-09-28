package at.zweibit.fahrtenbuch.tracking

import at.zweibit.fahrtenbuch.blitzer.Warnung
import kotlinx.coroutines.flow.MutableStateFlow

/** Live-Werte der laufenden Aufzeichnung für die Anzeige (gleicher Prozess wie der Dienst). */
data class LiveZustand(
    val kmh: Int? = null,
    val blitzerAktiv: Boolean = false,
    val blitzerAnzahl: Int = 0,
    val voraus: Warnung? = null,
)

object LiveStatus {
    val zustand = MutableStateFlow(LiveZustand())

    fun zuruecksetzen() {
        zustand.value = LiveZustand()
    }
}
