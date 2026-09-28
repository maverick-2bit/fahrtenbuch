package at.zweibit.fahrtenbuch.bericht

import at.zweibit.fahrtenbuch.data.Fahrt
import at.zweibit.fahrtenbuch.data.FahrtMitKategorie
import at.zweibit.fahrtenbuch.util.Format
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

sealed class Zeitraum {
    abstract val von: LocalDate

    /** Letzter enthaltener Tag. */
    abstract val bis: LocalDate
    abstract val titel: String

    data class Monat(val monat: YearMonth) : Zeitraum() {
        override val von: LocalDate get() = monat.atDay(1)
        override val bis: LocalDate get() = monat.atEndOfMonth()
        override val titel: String get() = Format.monat(von)
    }

    data class Jahr(val jahr: Int) : Zeitraum() {
        override val von: LocalDate get() = LocalDate.of(jahr, 1, 1)
        override val bis: LocalDate get() = LocalDate.of(jahr, 12, 31)
        override val titel: String get() = "Jahr $jahr"
    }

    data class Individuell(override val von: LocalDate, override val bis: LocalDate) : Zeitraum() {
        override val titel: String get() = "${Format.datum(von)} – ${Format.datum(bis)}"
    }

    /** Halb offenes Intervall [von, bis) in Epoch-Millis. */
    fun grenzen(zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> =
        von.atStartOfDay(zone).toInstant().toEpochMilli() to
            bis.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

data class BerichtZeile(
    val fahrt: Fahrt,
    val km: Double,
    val kmStandBeginn: Double?,
    val kmStandEnde: Double?,
)

data class KategorieBlock(
    val kategorieId: Long?,
    val name: String,
    val farbe: Long,
    val zeilen: List<BerichtZeile>,
) {
    val summeKm: Double get() = runde(zeilen.sumOf { it.km })
    val anzahl: Int get() = zeilen.size
    val dauerMillis: Long get() = zeilen.sumOf { ((it.fahrt.endeZeit ?: it.fahrt.startZeit) - it.fahrt.startZeit).coerceAtLeast(0) }
}

data class Bericht(
    val zeitraum: Zeitraum,
    val bloecke: List<KategorieBlock>,
    val mitKmStand: Boolean,
) {
    val gesamtKm: Double get() = runde(bloecke.sumOf { it.summeKm })
    val gesamtAnzahl: Int get() = bloecke.sumOf { it.anzahl }
    fun anteilProzent(block: KategorieBlock): Double =
        if (gesamtKm <= 0.0) 0.0 else block.summeKm / gesamtKm * 100.0
}

internal fun runde(km: Double): Double = Math.round(km * 10.0) / 10.0

object BerichtErstellen {
    const val OHNE_KATEGORIE = "Ohne Kategorie"
    private const val OHNE_FARBE = 0xFF9E9E9E

    /**
     * @param fahrten alle abgeschlossenen Fahrten im Zeitraum (beliebige Reihenfolge)
     * @param kategorien Filter; null = alle Kategorien. Die ID 0 steht für „ohne Kategorie“.
     * @param kmStandVorher Kilometerstand zu Beginn des Zeitraums, oder null ohne Kilometerstand
     * @param kmStandAb Fahrten vor diesem Zeitpunkt erhalten keinen Kilometerstand
     * @param reihenfolge Kategorie-IDs in gewünschter Anzeigereihenfolge
     */
    fun erstellen(
        zeitraum: Zeitraum,
        fahrten: List<FahrtMitKategorie>,
        kategorien: Set<Long>?,
        kmStandVorher: Double?,
        kmStandAb: Long = 0L,
        reihenfolge: List<Long> = emptyList(),
    ): Bericht {
        val sortiert = fahrten.sortedBy { it.fahrt.startZeit }

        // Kilometerstand läuft über ALLE Fahrten des Fahrzeugs, unabhängig vom Kategorie-Filter.
        var stand = kmStandVorher
        val zeilen = sortiert.map { fm ->
            val km = Format.kmWert(fm.fahrt.distanzMeter)
            val mitStand = stand != null && fm.fahrt.startZeit >= kmStandAb
            val beginn = if (mitStand) stand else null
            val ende = if (mitStand) runde(stand!! + km) else null
            if (mitStand) stand = ende
            fm to BerichtZeile(fm.fahrt, km, beginn, ende)
        }

        val bloecke = zeilen
            .filter { (fm, _) -> kategorien == null || (fm.kategorie?.id ?: 0L) in kategorien }
            .groupBy { (fm, _) -> fm.kategorie?.id }
            .map { (id, liste) ->
                val k = liste.first().first.kategorie
                KategorieBlock(
                    kategorieId = id,
                    name = k?.name ?: OHNE_KATEGORIE,
                    farbe = k?.farbe ?: OHNE_FARBE,
                    zeilen = liste.map { it.second },
                )
            }
            .sortedWith(compareBy({ it.kategorieId == null }, { reihenfolge.indexOf(it.kategorieId).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.name }))

        return Bericht(zeitraum, bloecke, mitKmStand = kmStandVorher != null)
    }
}
