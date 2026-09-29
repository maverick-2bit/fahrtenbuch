package at.zweibit.fahrtenbuch.tracking

import at.zweibit.fahrtenbuch.FahrtenbuchApp
import at.zweibit.fahrtenbuch.data.Fahrt
import at.zweibit.fahrtenbuch.sync.Kartendienst
import at.zweibit.fahrtenbuch.util.Format
import kotlin.math.max

/** Strecke vor dem Beginn der Aufzeichnung: Straßenkilometer und geschätzte Fahrzeit. */
data class Nachtrag(val meter: Double, val ms: Long)

sealed interface NachtragErgebnis {
    data class Ok(val nachtrag: Nachtrag) : NachtragErgebnis
    data class Fehler(val text: String) : NachtragErgebnis
}

/**
 * Startadresse korrigiert, weil der Start zu spät gedrückt wurde: Die Strecke vom echten Start bis zum
 * Beginn der Aufzeichnung fehlt. Sie wird über die Straße nachgerechnet (Route über die Online-Sicherung),
 * und die Abfahrt rückt um die geschätzte Fahrzeit nach vorne. Eine erneute Korrektur ersetzt den Nachtrag.
 */
object StartKorrektur {
    /** Näher am Beginn der Aufzeichnung: nur eine genauere Adresse, es fehlt keine Strecke. */
    const val MIN_ABSTAND_M = 250.0

    /** Weiter entfernt: vermutlich falsch gefunden (z. B. gleichnamige Straße in einem anderen Ort). */
    const val MAX_ABSTAND_M = 100_000.0

    val KEINER = Nachtrag(0.0, 0)

    /** Wendet einen Nachtrag an; ein früherer wird dabei ersetzt, nicht addiert. */
    fun anwenden(f: Fahrt, n: Nachtrag): Fahrt = f.copy(
        distanzMeter = (f.distanzMeter - f.nachtragMeter + n.meter).coerceAtLeast(0.0),
        startZeit = f.startZeit + f.nachtragMs - n.ms,
        nachtragMeter = n.meter,
        nachtragMs = n.ms,
    )

    /** Entscheidet anhand der Luftlinie zum Beginn der Aufzeichnung; null = Route berechnen. */
    fun vorpruefen(abstandM: Double): NachtragErgebnis? = when {
        abstandM < MIN_ABSTAND_M -> NachtragErgebnis.Ok(KEINER)
        abstandM > MAX_ABSTAND_M -> NachtragErgebnis.Fehler(
            "Die Adresse liegt ${Format.km(abstandM)} vom Beginn der Aufzeichnung entfernt – vermutlich ein anderer Ort. " +
                "Bitte mit Postleitzahl und Ort eingeben."
        )
        else -> null
    }

    /** Nachtrag aus der Route; über die Straße ist es nie kürzer als die Luftlinie. */
    fun ausRoute(meter: Double, sekunden: Long, abstandM: Double) = Nachtrag(max(meter, abstandM), sekunden * 1000)

    /**
     * Ermittelt den Nachtrag vom korrigierten Start [adresse] bis zum Beginn der Aufzeichnung von [f].
     * Gespeicherte Orte liefern ihre Koordinaten, sonst sucht der Geocoder des Handys in der Umgebung.
     */
    suspend fun berechnen(app: FahrtenbuchApp, f: Fahrt, adresse: String): NachtragErgebnis {
        val lat = f.startLat
        val lon = f.startLon
        if (lat == null || lon == null) {
            return NachtragErgebnis.Fehler("Der Beginn der Aufzeichnung ist noch nicht bekannt (noch kein GPS-Signal).")
        }
        val text = adresse.trim()
        val ort = app.einstellungen.orteAktuell().firstOrNull { it.hatKoordinaten && it.alsAdresse.equals(text, ignoreCase = true) }
        val (sLat, sLon) = ort?.let { it.lat!! to it.lon!! }
            ?: Adressen.koordinaten(app, text, nahe = lat to lon)
            ?: return NachtragErgebnis.Fehler("Adresse nicht gefunden – bitte mit Postleitzahl und Ort eingeben.")
        val abstand = Geo.distanzMeter(sLat, sLon, lat, lon)
        vorpruefen(abstand)?.let { return it }
        return Kartendienst.route(app, sLat, sLon, lat, lon).fold(
            onSuccess = { NachtragErgebnis.Ok(ausRoute(it.meter, it.sekunden, abstand)) },
            onFailure = { NachtragErgebnis.Fehler(it.message ?: "Keine Route verfügbar.") },
        )
    }
}
