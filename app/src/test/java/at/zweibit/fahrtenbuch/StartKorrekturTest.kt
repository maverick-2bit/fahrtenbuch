package at.zweibit.fahrtenbuch

import at.zweibit.fahrtenbuch.data.Fahrt
import at.zweibit.fahrtenbuch.tracking.Nachtrag
import at.zweibit.fahrtenbuch.tracking.NachtragErgebnis
import at.zweibit.fahrtenbuch.tracking.StartKorrektur
import at.zweibit.fahrtenbuch.tracking.StreckenRechner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartKorrekturTest {
    private val start = 1_759_046_400_000L
    private val fahrt = Fahrt(startZeit = start, distanzMeter = 12_000.0, startLat = 47.38, startLon = 15.09)

    @Test
    fun nachtragErgaenztKilometerUndVerlegtDieAbfahrtVor() {
        val f = StartKorrektur.anwenden(fahrt, Nachtrag(4_200.0, 420_000))
        assertEquals(16_200.0, f.distanzMeter, 0.0)
        assertEquals(start - 420_000, f.startZeit)
        assertEquals(4_200.0, f.nachtragMeter, 0.0)
        assertEquals(420_000L, f.nachtragMs)
    }

    @Test
    fun erneuteKorrekturErsetztDenNachtrag() {
        val erste = StartKorrektur.anwenden(fahrt, Nachtrag(4_200.0, 420_000))
        val zweite = StartKorrektur.anwenden(erste, Nachtrag(1_500.0, 180_000))
        assertEquals(13_500.0, zweite.distanzMeter, 0.0)
        assertEquals(start - 180_000, zweite.startZeit)

        // Zurück auf den ursprünglichen Start: alles wie aufgezeichnet
        val zurueck = StartKorrektur.anwenden(zweite, StartKorrektur.KEINER)
        assertEquals(12_000.0, zurueck.distanzMeter, 0.0)
        assertEquals(start, zurueck.startZeit)
        assertEquals(0.0, zurueck.nachtragMeter, 0.0)
    }

    @Test
    fun luftlinieEntscheidetObGerechnetWird() {
        // Anderes Haus in derselben Straße: nur die Adresse wird genauer, es fehlt keine Strecke
        assertEquals(NachtragErgebnis.Ok(StartKorrektur.KEINER), StartKorrektur.vorpruefen(120.0))
        assertNull(StartKorrektur.vorpruefen(3_000.0))
        // Gleichnamige Straße in einem anderen Ort: keine Kilometer, sondern ein Hinweis
        val weit = StartKorrektur.vorpruefen(180_000.0)
        assertTrue(weit is NachtragErgebnis.Fehler && weit.text.contains("180"))
    }

    @Test
    fun strasseIstNieKuerzerAlsDieLuftlinie() {
        assertEquals(Nachtrag(4_200.0, 420_000), StartKorrektur.ausRoute(4_200.0, 420, 3_100.0))
        assertEquals(Nachtrag(3_100.0, 60_000), StartKorrektur.ausRoute(2_900.0, 60, 3_100.0))
    }

    @Test
    fun streckenrechnerZaehltAbDemNachgetragenenStandWeiter() {
        val r = StreckenRechner(12_000.0)
        r.nachtragen(4_200.0)
        assertEquals(16_200.0, r.meter, 0.0)
        r.nachtragen(1_500.0 - 4_200.0) // ersetzt durch einen kleineren Nachtrag
        assertEquals(13_500.0, r.meter, 0.0)
        r.nachtragen(-50_000.0)
        assertEquals(0.0, r.meter, 0.0)
    }
}
