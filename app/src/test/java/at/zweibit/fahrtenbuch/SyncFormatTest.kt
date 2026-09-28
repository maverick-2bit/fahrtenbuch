package at.zweibit.fahrtenbuch

import at.zweibit.fahrtenbuch.data.EinstellungenWerte
import at.zweibit.fahrtenbuch.data.Fahrt
import at.zweibit.fahrtenbuch.data.FahrtStatus
import at.zweibit.fahrtenbuch.data.Kategorie
import at.zweibit.fahrtenbuch.data.ProtokollEintrag
import at.zweibit.fahrtenbuch.sync.SyncFormat
import at.zweibit.fahrtenbuch.sync.Verbindung
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncFormatTest {
    private val code = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcd"

    @Test
    fun linkAusQrCode() {
        assertEquals(
            Verbindung("https://fahrtenbuch.smarte.events", code),
            SyncFormat.verbindungLesen("https://fahrtenbuch.smarte.events/verbinden#code=$code"),
        )
    }

    @Test
    fun rueckfallLinkUndNackterCode() {
        assertEquals(
            Verbindung("https://fahrtenbuch.smarte.events", code),
            SyncFormat.verbindungLesen("fahrtenbuch://verbinden?server=https%3A%2F%2Ffahrtenbuch.smarte.events&code=$code"),
        )
        assertEquals(Verbindung("https://fahrtenbuch.smarte.events", code), SyncFormat.verbindungLesen("  $code "))
    }

    @Test
    fun fremdeOderUnsichereServerWerdenAbgelehnt() {
        assertNull(SyncFormat.verbindungLesen("https://boese.example.com/verbinden#code=$code"))
        assertNull(SyncFormat.verbindungLesen("http://fahrtenbuch.smarte.events/verbinden#code=$code"))
        assertNull(SyncFormat.verbindungLesen("fahrtenbuch://verbinden?server=https%3A%2F%2Fboese.example.com&code=$code"))
        assertNull(SyncFormat.verbindungLesen("https://fahrtenbuch.smarte.events/anderes#code=$code"))
        assertNull(SyncFormat.verbindungLesen("https://fahrtenbuch.smarte.events/verbinden#code=kurz"))
        assertNull(SyncFormat.verbindungLesen("irgendwas"))
    }

    @Test
    fun anfrageEnthaeltAllesFuerDenServer() {
        val f = Fahrt(id = 7, uuid = "0f0e0d0c-0000-4000-8000-000000000001", startZeit = 1_000, endeZeit = null,
            startAdresse = "Zuhause", distanzMeter = 1234.5, kategorieId = 2, status = FahrtStatus.OFFEN)
        val p = ProtokollEintrag(eintragId = "e-1234567890", fahrtUuid = f.uuid, aktion = "neu", zeit = 5_000,
            daten = SyncFormat.fahrtDaten(f).toString())
        val json = JSONObject(
            SyncFormat.anfrage(
                "0.4.0", "Android 16 · Samsung SM-G556B",
                EinstellungenWerte(kennzeichen = "MT 317 AS", kmStandStart = 45_200, kmStandAb = 99),
                listOf(Kategorie(id = 2, name = "Privat", farbe = 0xFF43A047, sortierung = 2, aktiv = false)),
                listOf(p),
            )
        )
        assertEquals("0.4.0", json.getJSONObject("app").getString("version"))
        assertEquals("MT 317 AS", json.getJSONObject("einstellungen").getString("kennzeichen"))
        assertEquals(45_200, json.getJSONObject("einstellungen").getInt("kmStandStart"))
        val k = json.getJSONArray("kategorien").getJSONObject(0)
        assertEquals(false, k.getBoolean("aktiv"))
        val e = json.getJSONArray("eintraege").getJSONObject(0)
        assertEquals(f.uuid, e.getString("uuid"))
        val d = e.getJSONObject("daten")
        assertTrue(d.isNull("endeZeit"))
        assertEquals(1234.5, d.getDouble("distanzMeter"), 0.0)
        assertEquals("offen", d.getString("status"))
    }

    private val schluessel = ByteArray(32) { it.toByte() }

    private fun eintrag(id: String, uuid: String, kategorie: Long?, status: String = "fertig", aktion: String = "neu") =
        ProtokollEintrag(
            eintragId = id, fahrtUuid = uuid, aktion = aktion, zeit = 1,
            daten = SyncFormat.fahrtDaten(
                Fahrt(uuid = uuid, startZeit = 1, startAdresse = "Zuhause", endeAdresse = "Therme", notiz = "Wellness",
                    startLat = 47.2, startLon = 14.8, distanzMeter = 50_000.0, kategorieId = kategorie, status = status)
            ).toString(),
        )

    @Test
    fun privatfahrtenGehenNurVerschluesseltHinaus() {
        val v = SyncFormat.vorbereiten(listOf(eintrag("e-1", "f-dienst", 1), eintrag("e-2", "f-privat", 2)), setOf(2L), schluessel)
        assertEquals(2, v.senden.size)
        assertEquals(0, v.wartenAufPin)
        val dienst = JSONObject(v.senden[0].daten)
        assertEquals("Zuhause", dienst.getString("startAdresse"))
        assertTrue(dienst.isNull("geheim"))

        val privat = JSONObject(v.senden[1].daten)
        assertEquals("", privat.getString("startAdresse"))
        assertEquals("", privat.getString("notiz"))
        assertTrue(privat.isNull("startLat"))
        assertEquals(50_000.0, privat.getDouble("distanzMeter"), 0.0) // Kilometer bleiben sichtbar
        val klar = JSONObject(at.zweibit.fahrtenbuch.sync.Krypto.geheimOeffnen(schluessel, privat.getJSONObject("geheim")))
        assertEquals("Therme", klar.getString("endeAdresse"))
        assertEquals("Wellness", klar.getString("notiz"))
        assertEquals(14.8, klar.getDouble("startLon"), 0.0)
        assertTrue(!v.senden[1].daten.contains("Therme"))
    }

    @Test
    fun ohnePinBleibenPrivatfahrtenSamtFolgeeintraegenAmHandy() {
        val v = SyncFormat.vorbereiten(
            listOf(
                eintrag("e-1", "f-privat", 2),
                eintrag("e-2", "f-dienst", 1),
                // später auf „Dienstlich“ umgestellt: darf nicht vor dem ersten Eintrag ankommen
                eintrag("e-3", "f-privat", 1, aktion = "geaendert"),
            ),
            setOf(2L), null,
        )
        assertEquals(listOf("e-2"), v.senden.map { it.eintragId })
        assertEquals(2, v.wartenAufPin)
    }

    @Test
    fun nichtZugeordneteAlteintraegeEntfallen() {
        val v = SyncFormat.vorbereiten(listOf(eintrag("e-1", "f-1", null, status = "offen"), eintrag("e-2", "f-1", 1)), setOf(2L), null)
        assertEquals(listOf("e-1"), v.entfallen)
        assertEquals(listOf("e-2"), v.senden.map { it.eintragId })
    }

    @Test
    fun ichMitUndOhneSchluesselhuelle() {
        assertEquals("Thomas" to null, SyncFormat.ichLesen("""{"fahrer":{"name":"Thomas"},"schluessel":null}"""))
        assertEquals("Thomas" to null, SyncFormat.ichLesen("""{"fahrer":{"name":"Thomas"}}""")) // Server vor 0.5
        val (name, huelle) = SyncFormat.ichLesen(
            """{"fahrer":{"name":"Bernd"},"schluessel":{"v":1,"iv":"ZGVmZ2hpamtsbW5v","ct":"WEM8","salt":"AQIDBAUGBwgJCgsMDQ4PEA==","iter":310000}}"""
        )
        assertEquals("Bernd", name)
        assertEquals("AQIDBAUGBwgJCgsMDQ4PEA==", JSONObject(huelle!!).getString("salt"))
    }

    @Test
    fun huelleVomServerIstDieselbeTrotzAndererFeldreihenfolge() {
        val eigene = at.zweibit.fahrtenbuch.sync.Krypto.Huelle("AQIDBAUGBwgJCgsMDQ4PEA==", 310_000, "ZGVmZ2hpamtsbW5v", "WEM8").json().toString()
        // So speichert und liefert der Server die Hülle: {v, iv, ct, salt, iter}
        val vomServer = """{"v":1,"iv":"ZGVmZ2hpamtsbW5v","ct":"WEM8","salt":"AQIDBAUGBwgJCgsMDQ4PEA==","iter":310000}"""
        assertTrue(at.zweibit.fahrtenbuch.sync.Krypto.huelleGleich(eigene, vomServer))
        assertTrue(!at.zweibit.fahrtenbuch.sync.Krypto.huelleGleich(eigene, vomServer.replace("WEM8", "WEM9")))
        assertTrue(!at.zweibit.fahrtenbuch.sync.Krypto.huelleGleich(eigene, "kaputt"))
    }

    @Test
    fun antwortLesen() {
        val a = SyncFormat.antwort(
            """{"fahrer":{"name":"Thomas"},"angenommen":["e-1","e-2"],"abgelehnt":[{"eintragId":"e-3","grund":"ungültig"}],"serverZeit":1}"""
        )
        assertEquals("Thomas", a.fahrerName)
        assertEquals(listOf("e-1", "e-2"), a.angenommen)
        assertEquals(listOf("e-3" to "ungültig"), a.abgelehnt)
    }
}
