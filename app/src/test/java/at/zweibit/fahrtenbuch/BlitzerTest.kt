package at.zweibit.fahrtenbuch

import at.zweibit.fahrtenbuch.blitzer.Blitzer
import at.zweibit.fahrtenbuch.blitzer.BlitzerArt
import at.zweibit.fahrtenbuch.blitzer.BlitzerFormat
import at.zweibit.fahrtenbuch.blitzer.BlitzerPruefer
import at.zweibit.fahrtenbuch.blitzer.BlitzerText
import at.zweibit.fahrtenbuch.blitzer.OverpassParser
import at.zweibit.fahrtenbuch.blitzer.Warnung
import at.zweibit.fahrtenbuch.tracking.Geo
import at.zweibit.fahrtenbuch.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlitzerTest {

    // ---------------------------------------------------------------- Geometrie

    @Test
    fun peilungUndWinkel() {
        assertEquals(0.0, Geo.peilung(48.0, 16.0, 48.01, 16.0), 0.5)
        assertEquals(90.0, Geo.peilung(48.0, 16.0, 48.0, 16.01), 0.5)
        assertEquals(180.0, Geo.peilung(48.01, 16.0, 48.0, 16.0), 0.5)
        assertEquals(270.0, Geo.peilung(48.0, 16.01, 48.0, 16.0), 0.5)
        assertEquals(20.0, Geo.winkelDiff(350.0, 10.0), 1e-9)
        assertEquals(180.0, Geo.winkelDiff(0.0, 180.0), 1e-9)
    }

    // ---------------------------------------------------------------- Overpass

    private val beispiel = """
        {"elements":[
          {"type":"node","id":1,"lat":48.0,"lon":16.0,"tags":{"highway":"speed_camera","maxspeed":"50"}},
          {"type":"node","id":2,"lat":48.1,"lon":16.1,"tags":{"highway":"speed_camera"}},
          {"type":"node","id":20,"lat":48.1,"lon":16.095},
          {"type":"node","id":21,"lat":48.1,"lon":16.105},
          {"type":"node","id":3,"lat":48.2,"lon":16.2,"tags":{"highway":"speed_camera"}},
          {"type":"node","id":30,"lat":48.195,"lon":16.2},
          {"type":"node","id":31,"lat":48.205,"lon":16.2},
          {"type":"node","id":4,"lat":48.3,"lon":16.3},
          {"type":"node","id":40,"lat":48.30,"lon":16.29},
          {"type":"node","id":5,"lat":48.4,"lon":16.4,"tags":{"highway":"speed_camera"}},
          {"type":"node","id":50,"lat":48.39,"lon":16.4},
          {"type":"node","id":51,"lat":48.45,"lon":16.4},
          {"type":"relation","id":100,"members":[
             {"type":"node","ref":20,"role":"from"},{"type":"node","ref":2,"role":"device"},{"type":"node","ref":21,"role":"to"}],
           "tags":{"type":"enforcement","enforcement":"maxspeed","maxspeed":"70"}},
          {"type":"relation","id":101,"members":[
             {"type":"node","ref":30,"role":"from"},{"type":"node","ref":3,"role":"device"},{"type":"node","ref":31,"role":"to"}],
           "tags":{"type":"enforcement","enforcement":"maxspeed","maxspeed":"100"}},
          {"type":"relation","id":102,"members":[
             {"type":"node","ref":31,"role":"from"},{"type":"node","ref":3,"role":"device"},{"type":"node","ref":30,"role":"to"}],
           "tags":{"type":"enforcement","enforcement":"maxspeed","maxspeed":"100"}},
          {"type":"relation","id":103,"members":[
             {"type":"node","ref":40,"role":"from"},{"type":"node","ref":4,"role":"device"}],
           "tags":{"type":"enforcement","enforcement":"traffic_signals"}},
          {"type":"relation","id":104,"members":[
             {"type":"node","ref":50,"role":"from"},{"type":"node","ref":5,"role":"device"},{"type":"node","ref":51,"role":"to"},
             {"type":"way","ref":999,"role":"section"}],
           "tags":{"type":"enforcement","enforcement":"average_speed","maxspeed":"80"}}
        ]}
    """.trimIndent()

    @Test
    fun overpassBeispiel() {
        val liste = OverpassParser.parsen(beispiel).associateBy { it.schluessel }

        val ohneRelation = liste.getValue("F1")
        assertEquals(50, ohneRelation.maxspeed)
        assertTrue(ohneRelation.richtungen.isEmpty())

        val ostwaerts = liste.getValue("F2")
        assertEquals(70, ostwaerts.maxspeed)
        assertEquals(90.0, ostwaerts.richtungen.single(), 1.0)

        // Ein Gerät, zwei Relationen: misst in beide Richtungen
        val beide = liste.getValue("F3")
        assertEquals(2, beide.richtungen.size)

        val rotlicht = liste.getValue("R4")
        assertEquals(BlitzerArt.ROTLICHT, rotlicht.art)
        assertEquals(90.0, rotlicht.richtungen.single(), 1.0)

        // Section Control: Start und Ende, die Kamera selbst warnt nicht einzeln
        assertNull(liste["F5"])
        assertEquals(80, liste.getValue("S50").maxspeed)
        assertEquals(0.0, liste.getValue("S50").richtungen.single(), 1.0)
        assertNotNull(liste["E51"])
    }

    @Test
    fun overpassEchteDatenOesterreich() {
        val json = javaClass.classLoader!!.getResource("overpass_at.json")!!.readText()
        val liste = OverpassParser.parsen(json)
        val fix = liste.filter { it.art == BlitzerArt.FIX }
        assertTrue("fix: ${fix.size}", fix.size > 1_000)
        assertTrue(liste.count { it.art == BlitzerArt.ROTLICHT } > 20)
        assertTrue(liste.count { it.art == BlitzerArt.SECTION_START } >= 8)
        // Mehr als die Hälfte der fixen Blitzer hat eine bekannte Messrichtung
        assertTrue(fix.count { it.richtungen.isNotEmpty() } > fix.size / 2)
        // Alle Punkte liegen in Österreich (grobe Box)
        assertTrue(liste.all { it.lat in 46.3..49.1 && it.lon in 9.4..17.2 })
        assertEquals(liste.size, liste.map { it.schluessel }.toSet().size)

        // Speicherformat verlustfrei
        val text = BlitzerFormat.schreiben(1_700_000_000_000, liste)
        val (info, gelesen) = BlitzerFormat.lesen(text)
        assertEquals(fix.size, info!!.fix)
        assertEquals(liste.size, gelesen.size)
        assertEquals(liste.first().richtungen.size, gelesen.first().richtungen.size)
    }

    // ---------------------------------------------------------------- Warnlogik

    // Blitzer 1 km nördlich des Starts, misst Richtung Norden, 50 km/h
    private val blitzer = Blitzer(1, BlitzerArt.FIX, 48.009, 16.0, 50, listOf(0.0))

    /** Position [meterSued] Meter südlich des Blitzers. */
    private fun lat(meterSued: Double) = 48.009 - meterSued / 111_195.0

    @Test
    fun warntEinmalBeiAnnaeherung() {
        val p = BlitzerPruefer(listOf(blitzer))
        // 80 km/h → Warndistanz 333 m
        assertNull(p.pruefen(lat(500.0), 16.0, 0.0, 80.0, 0).neu)
        val w = p.pruefen(lat(320.0), 16.0, 0.0, 80.0, 10_000).neu
        assertNotNull(w)
        assertTrue(w!!.zuSchnell)
        assertEquals("Blitzer in 300 Metern, Tempo 50. Zu schnell!", BlitzerText.ansage(w))
        // Keine Wiederholung, aber weiter als „voraus“ sichtbar
        val danach = p.pruefen(lat(150.0), 16.0, 0.0, 45.0, 20_000)
        assertNull(danach.neu)
        assertNotNull(danach.voraus)
        assertFalse(danach.voraus!!.zuSchnell)
    }

    @Test
    fun keineWarnungFuerGegenrichtungOderHinterUns() {
        val p = BlitzerPruefer(listOf(blitzer))
        // Fahrt Richtung Süden auf den Blitzer zu (von Norden): Messrichtung passt nicht
        val nordLat = 48.009 + 300 / 111_195.0
        assertNull(p.pruefen(nordLat, 16.0, 180.0, 80.0, 0).voraus)
        // Blitzer liegt hinter uns
        assertNull(p.pruefen(lat(-200.0), 16.0, 0.0, 80.0, 1_000).voraus)
        // Stillstand oder kein Kurs
        assertNull(p.pruefen(lat(200.0), 16.0, 0.0, 5.0, 2_000).voraus)
        assertNull(p.pruefen(lat(200.0), 16.0, null, 80.0, 3_000).voraus)
    }

    @Test
    fun warntBeiNaechsterVorbeifahrtWieder() {
        val p = BlitzerPruefer(listOf(blitzer))
        assertNotNull(p.pruefen(lat(240.0), 16.0, 0.0, 60.0, 0).neu)
        // weit weg (1,2 km) → freigegeben
        p.pruefen(lat(-1_200.0), 16.0, 0.0, 60.0, 60_000)
        assertNotNull(p.pruefen(lat(240.0), 16.0, 0.0, 60.0, 300_000).neu)
    }

    @Test
    fun blitzerOhneRichtungWarntInBeideRichtungen() {
        val b = blitzer.copy(richtungen = emptyList())
        val nordLat = 48.009 + 300 / 111_195.0
        assertNotNull(BlitzerPruefer(listOf(b)).pruefen(nordLat, 16.0, 180.0, 80.0, 0).neu)
    }

    @Test
    fun ansageTexte() {
        val s = Blitzer(2, BlitzerArt.SECTION_START, 0.0, 0.0, 100)
        assertEquals("Section Control in 500 Metern, Tempo 100", BlitzerText.ansage(Warnung(s, 480.0, false)))
        val r = Blitzer(3, BlitzerArt.ROTLICHT, 0.0, 0.0)
        assertEquals("Rotlichtkamera in 250 Metern", BlitzerText.ansage(Warnung(r, 260.0, false)))
        assertEquals("Blitzer in 260 m · 50 km/h", BlitzerText.kurz(Warnung(blitzer, 257.0, false)))
    }

    // ---------------------------------------------------------------- Update

    @Test
    fun versionsvergleich() {
        assertTrue(Updater.istNeuer("0.2.1", "0.2.0"))
        assertTrue(Updater.istNeuer("v0.10.0", "0.9.9"))
        assertTrue(Updater.istNeuer("1.0", "0.9.9"))
        assertFalse(Updater.istNeuer("0.2.0", "0.2.0"))
        assertFalse(Updater.istNeuer("0.1.9", "0.2.0"))
    }

    @Test
    fun githubRelease() {
        val json = """
            {"tag_name":"v0.3.0","draft":false,"prerelease":false,"body":"Neu: Karte",
             "assets":[{"name":"notes.txt","size":10,"browser_download_url":"https://x/notes.txt"},
                       {"name":"Fahrtenbuch-0.3.0.apk","size":12345,"browser_download_url":"https://x/Fahrtenbuch-0.3.0.apk"}]}
        """.trimIndent()
        val info = Updater.releaseLesen(json)!!
        assertEquals("0.3.0", info.version)
        assertEquals("https://x/Fahrtenbuch-0.3.0.apk", info.apkUrl)
        assertEquals(12345L, info.groesse)
        assertNull(Updater.releaseLesen("""{"tag_name":"v0.3.0","assets":[]}"""))
    }
}
