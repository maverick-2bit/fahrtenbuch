package at.zweibit.fahrtenbuch

import at.zweibit.fahrtenbuch.sync.Krypto
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Prüfvektor aus WebCrypto (Browser) – derselbe steht in server/test-node/geheim.test.mjs.
 * Stimmen die Ergebnisse überein, kann die Admin-Webseite entschlüsseln, was die App verschlüsselt.
 */
class KryptoTest {
    private val pin = "48151623"
    private val salt = ByteArray(16) { (it + 1).toByte() }
    private val ivHuelle = ByteArray(12) { (100 + it).toByte() }
    private val dek = ByteArray(32) { (200 - it).toByte() }
    private val ivDaten = ByteArray(12) { (50 + it).toByte() }
    private val klartext =
        "{\"startAdresse\":\"Zuhause, Hauptstraße 1, 8720 Knittelfeld\",\"endeAdresse\":\"Murpark, 8010 Graz\"," +
            "\"zwischenziele\":\"\",\"notiz\":\"Einkauf\",\"startLat\":47.2,\"startLon\":14.8,\"endeLat\":47.1,\"endeLon\":15.4}"

    @Test
    fun huelleWieImBrowser() {
        val h = Krypto.huelleErstellen(pin, dek, salt, ivHuelle, 310_000)
        assertEquals("AQIDBAUGBwgJCgsMDQ4PEA==", h.salt)
        assertEquals("ZGVmZ2hpamtsbW5v", h.iv)
        assertEquals("WEM8D4tb13Z/J45k3KQOfMNY00I86zSe7oyPy/WnyJ9eiQ7FhWccsUHJ9/odcuno", h.ct)
        assertArrayEquals(dek, Krypto.huelleOeffnen(h, pin))
    }

    @Test
    fun detailsWieImBrowser() {
        val g = Krypto.geheimErstellen(dek, klartext, ivDaten)
        assertEquals(
            "fa5eQmMNq0RuaIhlcL2e/JTKxJ6qjjyvLk4Hvt5eMuJHDejDSmlj67ClI07H8mUyB9VAwExI7IQazf/AjUF/0szCeku6+Ybx4DTejC+tXhE43P2x" +
                "pbKP77M/Px8LC1lJ5/oc7tYQn/qtNxRsX1FR12dEEqpMgOdghLscOblKf6l/0aIF3RL6WHw+PgnPEfCDPBGN4xz7z+7hIHnPu+JX/4eaoWeXTcgb3zvI" +
                "tTSGs5ByU5dGxOdR7LZRte23uxDRun9nwOCANisbMuX63MnWuiLP",
            g.getString("ct"),
        )
        assertEquals(klartext, Krypto.geheimOeffnen(dek, g))
    }

    @Test
    fun falscherPinWirdErkannt() {
        val h = Krypto.huelleErstellen(pin, dek, salt, ivHuelle, 310_000)
        assertThrows(Exception::class.java) { Krypto.huelleOeffnen(h, "48151624") }
    }

    @Test
    fun zufaelligeWerteSindJedesMalAnders() {
        val a = Krypto.geheimErstellen(dek, klartext)
        val b = Krypto.geheimErstellen(dek, klartext)
        assert(a.getString("iv") != b.getString("iv"))
        assertEquals(klartext, Krypto.geheimOeffnen(dek, JSONObject(b.toString())))
    }
}
