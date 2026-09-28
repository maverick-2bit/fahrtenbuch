package at.zweibit.fahrtenbuch.tracking

/**
 * Erkennt nach dem Verbinden mit dem Auto (Bluetooth), wann das Fahrzeug losfährt: sobald zwei genaue
 * Positionen hintereinander weiter als [radiusM] vom Standort beim Verbinden entfernt sind. Rangieren am
 * Parkplatz und einzelne GPS-Ausreißer starten so keine Fahrt.
 */
class LosfahrErkennung(
    private val radiusM: Double = 100.0,
    private val maxGenauigkeitM: Float = 35f,
    private val bestaetigungen: Int = 2,
) {
    /** Standort beim Verbinden (erste genaue Position). */
    var anker: GeoPunkt? = null
        private set

    /** Letzte Position noch im Umkreis des Ankers – dort und dann ist das Auto losgefahren. */
    var imStand: GeoPunkt? = null
        private set

    private val unterwegs = mutableListOf<GeoPunkt>()

    /** Positionen seit dem Verlassen des Umkreises (für die Strecke der neuen Fahrt). */
    val spur: List<GeoPunkt> get() = unterwegs.toList()

    /** Beginn der Fahrt: Ort und Zeit der letzten Position im Stand. */
    val startPunkt: GeoPunkt? get() = imStand ?: anker

    /** @return true, sobald das Losfahren feststeht */
    fun punkt(p: GeoPunkt): Boolean {
        if (p.genauigkeit > maxGenauigkeitM) return false
        val a = anker
        if (a == null) {
            anker = p
            imStand = p
            return false
        }
        if (Geo.distanzMeter(a, p) <= radiusM) {
            imStand = p
            unterwegs.clear()
            return false
        }
        unterwegs += p
        return unterwegs.size >= bestaetigungen
    }
}
