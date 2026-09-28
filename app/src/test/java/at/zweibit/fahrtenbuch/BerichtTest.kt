package at.zweibit.fahrtenbuch

import at.zweibit.fahrtenbuch.bericht.BerichtErstellen
import at.zweibit.fahrtenbuch.bericht.BerichtExport
import at.zweibit.fahrtenbuch.bericht.Zeitraum
import at.zweibit.fahrtenbuch.data.EinstellungenWerte
import at.zweibit.fahrtenbuch.data.Fahrt
import at.zweibit.fahrtenbuch.data.FahrtMitKategorie
import at.zweibit.fahrtenbuch.data.FahrtStatus
import at.zweibit.fahrtenbuch.data.Kategorie
import at.zweibit.fahrtenbuch.util.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class BerichtTest {
    private val zone = ZoneId.of("Europe/Vienna")
    private val dienst = Kategorie(id = 1, name = "Dienstlich", farbe = 0xFF1E88E5, sortierung = 1)
    private val privat = Kategorie(id = 2, name = "Privat", farbe = 0xFF43A047, sortierung = 2)

    private fun ms(tag: Int, stunde: Int) =
        LocalDate.of(2026, 9, tag).atTime(stunde, 0).atZone(zone).toInstant().toEpochMilli()

    private fun fahrt(id: Long, tag: Int, meter: Double, k: Kategorie?, notiz: String = "") = FahrtMitKategorie(
        Fahrt(
            id = id, startZeit = ms(tag, 8), endeZeit = ms(tag, 9),
            startAdresse = "Hauptstraße 1, 1010 Wien", endeAdresse = "Ziel $id",
            distanzMeter = meter, kategorieId = k?.id, notiz = notiz, status = FahrtStatus.FERTIG,
        ),
        k,
    )

    private val fahrten = listOf(
        fahrt(3, 20, 30_040.0, privat),
        fahrt(1, 2, 12_340.0, dienst, notiz = "Kunde A; Termin"),
        fahrt(2, 10, 7_660.0, dienst),
        fahrt(4, 21, 1_000.0, null),
    )

    @Test
    fun zeitraumGrenzen() {
        val (von, bis) = Zeitraum.Monat(YearMonth.of(2026, 2)).grenzen(zone)
        assertEquals(LocalDate.of(2026, 2, 1).atStartOfDay(zone).toInstant().toEpochMilli(), von)
        assertEquals(LocalDate.of(2026, 3, 1).atStartOfDay(zone).toInstant().toEpochMilli(), bis)
        val (jv, jb) = Zeitraum.Jahr(2026).grenzen(zone)
        assertEquals(LocalDate.of(2026, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli(), jv)
        assertEquals(LocalDate.of(2027, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli(), jb)
        val (iv, ib) = Zeitraum.Individuell(LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5)).grenzen(zone)
        assertEquals(24 * 3600 * 1000L, ib - iv)
    }

    @Test
    fun gruppiertJeKategorieUndSummiert() {
        val b = BerichtErstellen.erstellen(Zeitraum.Monat(YearMonth.of(2026, 9)), fahrten, null, null, reihenfolge = listOf(1, 2))
        assertEquals(listOf("Dienstlich", "Privat", "Ohne Kategorie"), b.bloecke.map { it.name })
        assertEquals(20.0, b.bloecke[0].summeKm, 0.001) // 12,3 + 7,7
        assertEquals(listOf(1L, 2L), b.bloecke[0].zeilen.map { it.fahrt.id }) // chronologisch
        assertEquals(30.0, b.bloecke[1].summeKm, 0.001)
        assertEquals(51.0, b.gesamtKm, 0.001)
        assertEquals(4, b.gesamtAnzahl)
        assertEquals(20.0 / 51.0 * 100, b.anteilProzent(b.bloecke[0]), 0.01)
        assertTrue(b.bloecke.all { z -> z.zeilen.all { it.kmStandBeginn == null } })
    }

    @Test
    fun filterNachKategorie() {
        val b = BerichtErstellen.erstellen(Zeitraum.Monat(YearMonth.of(2026, 9)), fahrten, setOf(2L, 0L), null)
        assertEquals(listOf("Privat", "Ohne Kategorie"), b.bloecke.map { it.name })
    }

    @Test
    fun kilometerstandLaeuftUeberAlleKategorien() {
        val b = BerichtErstellen.erstellen(
            Zeitraum.Monat(YearMonth.of(2026, 9)), fahrten, setOf(2L), kmStandVorher = 10_000.0,
        )
        // Privatfahrt am 20.: davor 12,3 + 7,7 km Dienstfahrten
        val z = b.bloecke.single().zeilen.single()
        assertEquals(10_020.0, z.kmStandBeginn!!, 0.001)
        assertEquals(10_050.0, z.kmStandEnde!!, 0.001)
    }

    @Test
    fun kilometerstandErstAbStichtag() {
        val b = BerichtErstellen.erstellen(
            Zeitraum.Monat(YearMonth.of(2026, 9)), fahrten, null,
            kmStandVorher = 5_000.0, kmStandAb = ms(10, 0),
        )
        val dienstZeilen = b.bloecke.first { it.kategorieId == 1L }.zeilen
        assertNull(dienstZeilen[0].kmStandBeginn) // 2.9. liegt vor dem Stichtag
        assertEquals(5_000.0, dienstZeilen[1].kmStandBeginn!!, 0.001)
        assertEquals(5_007.7, dienstZeilen[1].kmStandEnde!!, 0.001)
    }

    @Test
    fun csvExport() {
        val b = BerichtErstellen.erstellen(Zeitraum.Monat(YearMonth.of(2026, 9)), fahrten, null, 10_000.0, reihenfolge = listOf(1, 2))
        val csv = BerichtExport.csv(b, EinstellungenWerte(kennzeichen = "W-12345A", fahrer = "Max Muster"))
        assertTrue(csv.startsWith("﻿Fahrtenbuch"))
        assertTrue(csv.contains("Kennzeichen;W-12345A"))
        assertTrue(csv.contains("Kategorie;Datum;Abfahrt;Ankunft;Von;Über;Nach;km;Km-Stand Beginn;Km-Stand Ende;Zweck / Notiz"))
        // Feld mit Semikolon wird gequotet, Zahlen mit Dezimalkomma, ohne Zwischenziel bleibt „Über“ leer
        assertTrue(csv.contains("Dienstlich;02.09.2026;08:00;09:00;Hauptstraße 1, 1010 Wien;;Ziel 1;12,3;10.000,0;10.012,3;\"Kunde A; Termin\""))
        assertTrue(csv.contains("Gesamt;4;51,0;100,0"))
    }

    @Test
    fun kmEingabe() {
        assertEquals(12.5, Format.kmParsen("12,5")!!, 0.0)
        assertEquals(1234.5, Format.kmParsen("1.234,5")!!, 0.0)
        assertEquals(12.5, Format.kmParsen("12.5")!!, 0.0)
        assertNull(Format.kmParsen("abc"))
        assertNull(Format.kmParsen("-3"))
        assertEquals(12.3, Format.kmWert(12_340.0), 0.0)
        assertEquals(12.4, Format.kmWert(12_350.0), 0.0)
    }
}
