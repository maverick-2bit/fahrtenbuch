package at.zweibit.fahrtenbuch

import at.zweibit.fahrtenbuch.tracking.Geo
import at.zweibit.fahrtenbuch.tracking.GeoPunkt
import at.zweibit.fahrtenbuch.tracking.StillstandErkennung
import at.zweibit.fahrtenbuch.tracking.StreckenRechner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreckeTest {
    // 0,001° Breite ≈ 111,2 m
    private fun p(latOffset: Double, sek: Long, gen: Float = 5f) =
        GeoPunkt(48.2 + latOffset, 16.37, sek * 1000, gen)

    @Test
    fun haversineWienGraz() {
        // Stephansdom – Grazer Hauptplatz, Luftlinie ca. 145 km
        val d = Geo.distanzMeter(48.2085, 16.3731, 47.0707, 15.4382)
        assertTrue("war $d", d in 144_000.0..147_000.0)
    }

    @Test
    fun summiertGeradeStrecke() {
        val r = StreckenRechner()
        for (i in 0..10) r.hinzufuegen(p(i * 0.001, i * 10L))
        assertEquals(1112.0, r.meter, 5.0)
    }

    @Test
    fun ungenauePunkteWerdenIgnoriert() {
        val r = StreckenRechner()
        assertTrue(r.hinzufuegen(p(0.0, 0)))
        assertFalse(r.hinzufuegen(p(0.01, 10, gen = 200f)))
        assertEquals(0.0, r.meter, 0.001)
    }

    @Test
    fun gpsSprungWirdVerworfen() {
        val r = StreckenRechner()
        r.hinzufuegen(p(0.0, 0))
        // 11 km in 10 s = 4000 km/h
        assertFalse(r.hinzufuegen(p(0.1, 10)))
        assertTrue(r.hinzufuegen(p(0.001, 20)))
        assertEquals(111.2, r.meter, 1.0)
    }

    @Test
    fun zitternImStandZaehltNicht() {
        val r = StreckenRechner()
        r.hinzufuegen(p(0.0, 0))
        for (i in 1..50) r.hinzufuegen(p(if (i % 2 == 0) 0.00003 else -0.00003, i * 5L, gen = 8f))
        assertEquals(0.0, r.meter, 0.001)
    }

    @Test
    fun langsameFahrtGehtNichtVerloren() {
        val r = StreckenRechner()
        // 5-m-Schritte liegen unter der Mindestschrittweite, die Strecke wird trotzdem vollständig gezählt
        for (i in 0..100) r.hinzufuegen(p(i * 0.000045, i.toLong()))
        assertEquals(500.0, r.meter, 15.0)
    }

    @Test
    fun fortsetzenMitStartwert() {
        val r = StreckenRechner(startMeter = 1000.0, letzterPunkt = p(0.0, 0))
        r.hinzufuegen(p(0.001, 10))
        assertEquals(1111.2, r.meter, 1.0)
    }

    @Test
    fun stillstandWirdErkannt() {
        val s = StillstandErkennung(startZeit = 0)
        s.punkt(p(0.0, 0))
        s.punkt(p(0.001, 30)) // Bewegung > 75 m
        assertEquals(30_000L, s.letzteBewegung)
        s.punkt(p(0.0012, 60)) // nur ~22 m weiter
        s.punkt(p(0.0011, 400))
        assertEquals(30_000L, s.letzteBewegung)
        assertEquals(370_000L, s.stillstandMillis(400_000))
    }
}
