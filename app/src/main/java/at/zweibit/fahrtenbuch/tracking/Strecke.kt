package at.zweibit.fahrtenbuch.tracking

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPunkt(
    val lat: Double,
    val lon: Double,
    val zeit: Long,
    val genauigkeit: Float = 0f,
)

object Geo {
    private const val ERDRADIUS_M = 6_371_008.8

    /** Großkreisentfernung (Haversine) in Metern. */
    fun distanzMeter(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * ERDRADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun distanzMeter(a: GeoPunkt, b: GeoPunkt) = distanzMeter(a.lat, a.lon, b.lat, b.lon)

    /** Anfangskurs von Punkt 1 nach Punkt 2 in Grad (0 = Nord, 90 = Ost). */
    fun peilung(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Kleinster Winkel zwischen zwei Richtungen, 0 bis 180 Grad. */
    fun winkelDiff(a: Double, b: Double): Double {
        val d = ((a - b) % 360.0 + 360.0) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }
}

/**
 * Summiert die gefahrene Strecke aus GPS-Punkten.
 *
 * - Punkte mit schlechter Genauigkeit werden verworfen.
 * - Sprünge mit unrealistischer Geschwindigkeit (GPS-Ausreißer) werden verworfen.
 * - Kleine Bewegungen innerhalb der Messungenauigkeit (Zittern im Stand) zählen nicht;
 *   die Strecke wird erst ab dem nächsten weiter entfernten Punkt gezählt, geht also nicht verloren.
 */
class StreckenRechner(
    startMeter: Double = 0.0,
    letzterPunkt: GeoPunkt? = null,
    private val maxGenauigkeitM: Float = 35f,
    private val maxGeschwindigkeitMs: Double = 70.0, // ≈ 250 km/h
    private val minSchrittM: Double = 10.0,
) {
    var meter: Double = startMeter
        private set
    var letzter: GeoPunkt? = letzterPunkt
        private set

    /** @return true, wenn der Punkt übernommen wurde (und gespeichert werden soll). */
    fun hinzufuegen(p: GeoPunkt): Boolean {
        if (p.genauigkeit > maxGenauigkeitM) return false
        val vorher = letzter
        if (vorher == null) {
            letzter = p
            return true
        }
        val d = Geo.distanzMeter(vorher, p)
        if (d < max(minSchrittM, p.genauigkeit.toDouble())) return false
        val sekunden = (p.zeit - vorher.zeit) / 1000.0
        if (sekunden <= 0.0) return false
        if (d / sekunden > maxGeschwindigkeitMs) return false
        meter += d
        letzter = p
        return true
    }

    /** Nicht aufgezeichnete Strecke (z. B. vor einem verspäteten Start) dazurechnen oder wieder abziehen. */
    fun nachtragen(deltaMeter: Double) {
        meter = (meter + deltaMeter).coerceAtLeast(0.0)
    }
}

/**
 * Erkennt, seit wann sich das Fahrzeug nicht mehr nennenswert bewegt hat.
 * Eine Bewegung liegt vor, sobald ein Punkt mehr als [radiusM] vom Ankerpunkt entfernt ist.
 */
class StillstandErkennung(
    startZeit: Long,
    private val radiusM: Double = 75.0,
) {
    private var anker: GeoPunkt? = null

    /** Zeitpunkt der letzten erkannten Bewegung (bzw. Fahrtbeginn). */
    var letzteBewegung: Long = startZeit
        private set

    /** Letzter Punkt, an dem sich das Fahrzeug befand, als die Bewegung endete. */
    val standort: GeoPunkt? get() = anker

    fun punkt(p: GeoPunkt) {
        val a = anker
        if (a == null || Geo.distanzMeter(a, p) > radiusM) {
            anker = p
            letzteBewegung = p.zeit
        }
    }

    fun stillstandMillis(jetzt: Long): Long = (jetzt - letzteBewegung).coerceAtLeast(0)
}
