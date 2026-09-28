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
