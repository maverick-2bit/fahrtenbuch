package at.zweibit.fahrtenbuch

import at.zweibit.fahrtenbuch.bericht.BerichtErstellen
import at.zweibit.fahrtenbuch.bericht.BerichtExport
import at.zweibit.fahrtenbuch.bericht.Zeitraum
import at.zweibit.fahrtenbuch.data.EinstellungenWerte
import at.zweibit.fahrtenbuch.data.Fahrt
import at.zweibit.fahrtenbuch.data.FahrtMitKategorie
import at.zweibit.fahrtenbuch.data.FahrtStatus
import at.zweibit.fahrtenbuch.data.Kategorie
import at.zweibit.fahrtenbuch.data.Ort
import at.zweibit.fahrtenbuch.data.Orte
import at.zweibit.fahrtenbuch.data.Zwischenziel
import at.zweibit.fahrtenbuch.data.Zwischenziele
import at.zweibit.fahrtenbuch.data.pausiert
import at.zweibit.fahrtenbuch.data.streckeText
import at.zweibit.fahrtenbuch.data.zwischenzieleListe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class OrteZwischenzieleTest {

    // ---------------------------------------------------------------- Zwischenziele

    @Test
    fun zwischenzieleSpeichernUndLesen() {
        val liste = listOf(
            Zwischenziel("Kunde A, Hauptplatz 1, 8010 Graz", 47.07, 15.44, an = 1_000, ab = 2_000),
            Zwischenziel("Tankstelle; Nord \"Shell\"", an = 3_000),
            Zwischenziel("Nachgetragen"),
        )
        assertEquals(liste, Zwischenziele.lesen(Zwischenziele.schreiben(liste)))
        assertEquals("", Zwischenziele.schreiben(emptyList()))
        assertEquals(emptyList<Zwischenziel>(), Zwischenziele.lesen(""))
        assertEquals(emptyList<Zwischenziel>(), Zwischenziele.lesen("kaputt"))
    }

    @Test
    fun pauseErkennung() {
        val basis = Fahrt(id = 1, startZeit = 0, startAdresse = "Zuhause", status = FahrtStatus.LAUFEND)
        assertFalse(basis.pausiert)
        val inPause = basis.copy(zwischenziele = Zwischenziele.schreiben(listOf(Zwischenziel("Kunde", an = 100))))
        assertTrue(inPause.pausiert)
        val weiter = basis.copy(zwischenziele = Zwischenziele.schreiben(listOf(Zwischenziel("Kunde", an = 100, ab = 200))))
        assertFalse(weiter.pausiert)
        // Eine abgeschlossene Fahrt ist nie pausiert
        assertFalse(inPause.copy(status = FahrtStatus.FERTIG).pausiert)
    }

    @Test
    fun streckeHinUndRetour() {
        val f = Fahrt(
            startZeit = 0, startAdresse = "Zuhause", endeAdresse = "Zuhause", status = FahrtStatus.FERTIG,
            zwischenziele = Zwischenziele.schreiben(listOf(Zwischenziel("Kunde A", an = 1, ab = 2))),
        )
        assertEquals("Zuhause → Kunde A → Zuhause", f.streckeText)
        assertEquals("? → ?", Fahrt(startZeit = 0).streckeText)
    }

    // ---------------------------------------------------------------- Orte

    private val zuhause = Ort(id = "z", name = "Zuhause", adresse = "Hauptstraße 1, 8700 Leoben", lat = 47.3800, lon = 15.0900)
    private val buero = Ort(id = "b", name = "Büro", adresse = "Industriestraße 5, 8700 Leoben", lat = 47.3900, lon = 15.1000)
    private val ohnePosition = Ort(id = "k", name = "Kunde C", adresse = "Irgendwo 1")

    @Test
    fun orteSpeichernUndLesen() {
        val liste = listOf(zuhause, buero, ohnePosition)
        assertEquals(liste, Orte.lesen(Orte.schreiben(liste)))
        assertEquals(emptyList<Ort>(), Orte.lesen(null))
    }

    @Test
    fun ortWirdImUmkreisErkannt() {
        // ~100 m neben „Zuhause“
        assertEquals(zuhause, Orte.erkennen(listOf(zuhause, buero, ohnePosition), 47.3809, 15.0900))
        // ~300 m entfernt: kein Treffer
        assertNull(Orte.erkennen(listOf(zuhause, buero), 47.3827, 15.0900))
        // Nächster Ort gewinnt
        val nah = zuhause.copy(id = "n", name = "Nachbar", lat = 47.3801)
        assertEquals(nah, Orte.erkennen(listOf(zuhause, nah), 47.3802, 15.0900))
    }

    @Test
    fun ortAlsAdresse() {
        assertEquals("Zuhause, Hauptstraße 1, 8700 Leoben", zuhause.alsAdresse)
        assertEquals("Hauptstraße 1", Ort(name = "", adresse = "Hauptstraße 1").alsAdresse)
        assertEquals("Zuhause", Ort(name = "Zuhause", adresse = " ").alsAdresse)
    }

    // ---------------------------------------------------------------- Bericht mit Zwischenziel

    @Test
    fun csvMitZwischenziel() {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 9, 28).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val k = Kategorie(id = 1, name = "Dienstlich", farbe = 0xFF1E88E5)
        val f = Fahrt(
            id = 1, startZeit = start, endeZeit = start + 4 * 3600_000,
            startAdresse = "Zuhause", endeAdresse = "Zuhause", distanzMeter = 84_000.0,
            kategorieId = 1, status = FahrtStatus.FERTIG,
            zwischenziele = Zwischenziele.schreiben(
                listOf(Zwischenziel("Kunde A", an = start + 3600_000, ab = start + 3 * 3600_000), Zwischenziel("Kunde B"))
            ),
        )
        val b = BerichtErstellen.erstellen(Zeitraum.Monat(YearMonth.of(2026, 9)), listOf(FahrtMitKategorie(f, k)), null, null)
        val csv = BerichtExport.csv(b, EinstellungenWerte())
        assertTrue(csv, csv.contains("Dienstlich;28.09.2026;08:00;12:00;Zuhause;Kunde A / Kunde B;Zuhause;84,0;"))
        assertEquals(2, f.zwischenzieleListe.size)
    }
}
