package at.zweibit.fahrtenbuch.blitzer

import at.zweibit.fahrtenbuch.tracking.Geo
import kotlin.math.abs
import kotlin.math.cos

data class Warnung(
    val blitzer: Blitzer,
    val distanzM: Double,
    val zuSchnell: Boolean,
)

/**
 * Prüft bei jedem GPS-Punkt, ob ein Blitzer voraus in Fahrtrichtung liegt.
 *
 * - Gewarnt wird nur einmal je Vorbeifahrt; erst wenn der Blitzer [RESET_M] hinter einem liegt,
 *   darf er wieder warnen.
 * - Die Warndistanz wächst mit der Geschwindigkeit: rund 15 Sekunden Vorlauf, mindestens 250 m.
 * - Bei bekannter Messrichtung warnen Blitzer der Gegenrichtung nicht.
 */
class BlitzerPruefer(private val blitzer: List<Blitzer>) {

    companion object {
        const val MIN_KMH = 15.0
        const val VORLAUF_S = 15.0
        const val MIN_WARN_M = 250.0
        const val MAX_WARN_M = 800.0
        const val RESET_M = 1_000.0
        const val VORAUS_GRAD = 35.0
        const val RICHTUNG_GRAD = 50.0
        const val TOLERANZ_KMH = 3

        fun warnDistanz(kmh: Double): Double = (kmh / 3.6 * VORLAUF_S).coerceIn(MIN_WARN_M, MAX_WARN_M)
    }

    data class Ergebnis(
        /** Neue Warnung, die jetzt angesagt werden soll. */
        val neu: Warnung?,
        /** Nächster Blitzer voraus (auch wenn schon gewarnt) – für die Anzeige. */
        val voraus: Warnung?,
    )

    private val index = blitzer.associateBy { it.schluessel }
    private val gewarnt = HashMap<String, Long>()

    val anzahl: Int get() = blitzer.size

    fun pruefen(lat: Double, lon: Double, kursGrad: Double?, kmh: Double, zeit: Long): Ergebnis {
        // Hinter uns liegende oder lange zurückliegende Warnungen wieder freigeben
        gewarnt.entries.removeAll { (key, t) ->
            val b = index[key]
            b == null || zeit - t > 30 * 60_000L || Geo.distanzMeter(lat, lon, b.lat, b.lon) > RESET_M
        }
        if (kursGrad == null || kmh < MIN_KMH) return Ergebnis(null, null)

        val warnM = warnDistanz(kmh)
        val dLat = warnM / 111_000.0
        val dLon = warnM / (111_000.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        var bester: Warnung? = null
        for (b in blitzer) {
            if (abs(b.lat - lat) > dLat || abs(b.lon - lon) > dLon) continue
            val d = Geo.distanzMeter(lat, lon, b.lat, b.lon)
            if (d > warnM || d < 15.0) continue
            if (Geo.winkelDiff(kursGrad, Geo.peilung(lat, lon, b.lat, b.lon)) > VORAUS_GRAD) continue
            if (b.richtungen.isNotEmpty() && b.richtungen.none { Geo.winkelDiff(kursGrad, it) <= RICHTUNG_GRAD }) continue
            if (bester == null || d < bester.distanzM) {
                bester = Warnung(b, d, b.maxspeed != null && kmh > b.maxspeed + TOLERANZ_KMH)
            }
        }
        val neu = bester?.takeIf { it.blitzer.schluessel !in gewarnt }
        if (neu != null) gewarnt[neu.blitzer.schluessel] = zeit
        return Ergebnis(neu, bester)
    }
}

object BlitzerText {
    /** Entfernung für die Ansage: auf 50 m gerundet. */
    fun meter(d: Double): Int = (Math.round(d / 50.0) * 50).toInt().coerceAtLeast(50)

    fun ansage(w: Warnung): String {
        val b = w.blitzer
        val wo = "in ${meter(w.distanzM)} Metern"
        val tempo = b.maxspeed?.let { ", Tempo $it" } ?: ""
        val text = when (b.art) {
            BlitzerArt.FIX -> "Blitzer $wo$tempo"
            BlitzerArt.ROTLICHT -> "Rotlichtkamera $wo"
            BlitzerArt.SECTION_START -> "Section Control $wo$tempo"
            BlitzerArt.SECTION_ENDE -> "Section Control endet $wo"
        }
        return if (w.zuSchnell && b.art != BlitzerArt.SECTION_ENDE) "$text. Zu schnell!" else text
    }

    fun kurz(w: Warnung): String {
        val b = w.blitzer
        val name = when (b.art) {
            BlitzerArt.FIX -> "Blitzer"
            BlitzerArt.ROTLICHT -> "Rotlichtkamera"
            BlitzerArt.SECTION_START -> "Section Control"
            BlitzerArt.SECTION_ENDE -> "Ende Section Control"
        }
        val tempo = b.maxspeed?.let { " · $it km/h" } ?: ""
        return "$name in ${(Math.round(w.distanzM / 10.0) * 10)} m$tempo"
    }
}
