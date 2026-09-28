package at.zweibit.fahrtenbuch.util

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {
    private val LOCALE = Locale.GERMANY
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy", LOCALE)
    private val DATUM_KURZ = DateTimeFormatter.ofPattern("EE, dd.MM.", LOCALE)
    private val UHRZEIT = DateTimeFormatter.ofPattern("HH:mm", LOCALE)
    private val MONAT = DateTimeFormatter.ofPattern("LLLL yyyy", LOCALE)

    private fun zahl(nachkomma: Int): NumberFormat = NumberFormat.getNumberInstance(LOCALE).apply {
        minimumFractionDigits = nachkomma
        maximumFractionDigits = nachkomma
    }

    /** Kilometer mit einer Nachkommastelle, gerundet wie im Bericht. */
    fun kmWert(meter: Double): Double = Math.round(meter / 100.0) / 10.0

    fun km(meter: Double): String = zahl(1).format(kmWert(meter)) + " km"
    fun kmZahl(km: Double): String = zahl(1).format(km)

    /** Kilometer für ein Eingabefeld: ohne Tausenderpunkt, mit Dezimalkomma. */
    fun kmEingabe(meter: Double): String = String.format(LOCALE, "%.1f", kmWert(meter))

    /** Akzeptiert „12,5“, „1.234,5“ und „12.5“. */
    fun kmParsen(text: String): Double? {
        val t = text.trim().replace(" ", "")
        if (t.isEmpty()) return null
        val norm = if (t.contains(',')) t.replace(".", "").replace(',', '.') else t
        return norm.toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }
    }

    fun dauer(millis: Long): String {
        val min = (millis / 60_000).coerceAtLeast(0)
        return if (min < 60) "$min min" else "${min / 60} h ${"%02d".format(min % 60)} min"
    }

    fun datum(epoch: Long): String = DATUM.format(Instant.ofEpochMilli(epoch).atZone(zone))
    fun datum(d: LocalDate): String = DATUM.format(d)
    fun datumKurz(epoch: Long): String = DATUM_KURZ.format(Instant.ofEpochMilli(epoch).atZone(zone))
    fun uhrzeit(epoch: Long): String = UHRZEIT.format(Instant.ofEpochMilli(epoch).atZone(zone))
    fun monat(d: LocalDate): String = MONAT.format(d).replaceFirstChar { it.uppercase(LOCALE) }
    fun monat(epoch: Long): String = monat(Instant.ofEpochMilli(epoch).atZone(zone).toLocalDate())
}
