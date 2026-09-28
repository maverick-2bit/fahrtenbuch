package at.zweibit.fahrtenbuch

import at.zweibit.fahrtenbuch.data.AutoStartStand
import at.zweibit.fahrtenbuch.data.BtGeraet
import at.zweibit.fahrtenbuch.data.BtGeraete
import at.zweibit.fahrtenbuch.tracking.GeoPunkt
import at.zweibit.fahrtenbuch.tracking.LosfahrErkennung
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoStartTest {
    // 0,0001° Breite ≈ 11 m
    private fun p(schritte: Int, sek: Long, gen: Float = 6f) = GeoPunkt(47.38 + schritte * 0.0001, 15.09, sek * 1000, gen)

    @Test
    fun stehenUndRangierenStartetKeineFahrt() {
        val l = LosfahrErkennung()
        // Parkplatz: Zittern und Rangieren innerhalb von ~70 m
        listOf(0, 1, -1, 3, 6, 4, 2, 5).forEachIndexed { i, s -> assertFalse(l.punkt(p(s, i * 5L))) }
        assertEquals(47.38, l.anker!!.lat, 1e-9)
    }

    @Test
    fun losfahrenWirdNachZweiPositionenErkannt() {
        val l = LosfahrErkennung()
        assertFalse(l.punkt(p(0, 0)))
        assertFalse(l.punkt(p(1, 30))) // noch am Parkplatz, 30 s nach dem Verbinden
        assertFalse(l.punkt(p(11, 40))) // ~122 m: erste Position außerhalb
        assertTrue(l.punkt(p(14, 43))) // zweite bestätigt
        // Start = letzte Position im Stand (Ort und Zeit des Losfahrens)
        assertEquals(30_000L, l.startPunkt!!.zeit)
        assertEquals(2, l.spur.size)
    }

    @Test
    fun einzelnerAusreisserZaehltNicht() {
        val l = LosfahrErkennung()
        l.punkt(p(0, 0))
        assertFalse(l.punkt(p(30, 5))) // Sprung 330 m
        assertFalse(l.punkt(p(1, 10))) // wieder am Parkplatz – setzt zurück
        assertFalse(l.punkt(p(30, 15)))
        assertEquals(1, l.spur.size)
    }

    @Test
    fun ungenauePositionenWerdenIgnoriert() {
        val l = LosfahrErkennung()
        assertFalse(l.punkt(p(0, 0, gen = 120f)))
        assertNull(l.anker)
        l.punkt(p(0, 5))
        assertFalse(l.punkt(p(20, 10, gen = 80f)))
        assertFalse(l.punkt(p(25, 15, gen = 80f)))
        assertTrue(l.spur.isEmpty())
    }

    @Test
    fun geraeteSpeichernUndFinden() {
        val liste = listOf(BtGeraet("AA:BB:CC:DD:EE:01", "Audi MMI 4711"), BtGeraet("11:22:33:44:55:66", "VW \"Tiguan\"; Bluetooth"))
        assertEquals(liste, BtGeraete.lesen(BtGeraete.schreiben(liste)))
        assertEquals(emptyList<BtGeraet>(), BtGeraete.lesen(null))
        assertEquals(emptyList<BtGeraet>(), BtGeraete.lesen("kaputt"))
        val stand = AutoStartStand(aktiv = true, geraete = liste)
        // Android liefert die Adresse je nach Stelle in Groß- oder Kleinbuchstaben
        assertEquals(liste[0], stand.geraet("aa:bb:cc:dd:ee:01"))
        assertNull(stand.geraet("AA:BB:CC:DD:EE:02"))
    }
}
