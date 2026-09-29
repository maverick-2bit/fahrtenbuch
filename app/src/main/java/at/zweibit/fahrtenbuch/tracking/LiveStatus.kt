package at.zweibit.fahrtenbuch.tracking

import at.zweibit.fahrtenbuch.blitzer.Warnung
import kotlinx.coroutines.flow.MutableStateFlow

/** Live-Werte der laufenden Aufzeichnung für die Anzeige (gleicher Prozess wie der Dienst). */
data class LiveZustand(
    val kmh: Int? = null,
    val blitzerAktiv: Boolean = false,
    val blitzerAnzahl: Int = 0,
    val voraus: Warnung? = null,
    /** Mit diesem Auto verbunden, Fahrt startet beim Losfahren (null = wartet nicht). */
    val wartetAuf: String? = null,
    /** Mit diesem Auto verbunden, während eine Fahrt läuft – Stillstand beendet sie dann nicht. */
    val auto: String? = null,
    /** Kilometer-Nachtrag nach korrigiertem Start: Berechnung läuft oder Grund, warum er fehlt. */
    val nachtrag: NachtragStand? = null,
)

data class NachtragStand(val text: String, val fehler: Boolean = false)

object LiveStatus {
    val zustand = MutableStateFlow(LiveZustand())

    fun zuruecksetzen() {
        zustand.value = LiveZustand()
    }
}
