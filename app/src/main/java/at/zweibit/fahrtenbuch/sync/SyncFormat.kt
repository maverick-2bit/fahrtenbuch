package at.zweibit.fahrtenbuch.sync

import at.zweibit.fahrtenbuch.data.EinstellungenWerte
import at.zweibit.fahrtenbuch.data.Fahrt
import at.zweibit.fahrtenbuch.data.Kategorie
import at.zweibit.fahrtenbuch.data.ProtokollEintrag
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** Verbindung zur Online-Sicherung. */
data class Verbindung(val server: String, val code: String)

data class SyncAntwort(
    val fahrerName: String,
    val angenommen: List<String>,
    val abgelehnt: List<Pair<String, String>>,
)

/** Datenformat zwischen App und Server (siehe server/src/sync.ts) – ohne Android-Abhängigkeiten. */
object SyncFormat {
    /** Nur dieser Server wird akzeptiert – ein fremder Link kann die Fahrten nicht umleiten. */
    val ERLAUBTE_SERVER = setOf("https://fahrtenbuch.smarte.events")
    const val STANDARD_SERVER = "https://fahrtenbuch.smarte.events"
    private val CODE = Regex("^[A-Za-z0-9_-]{20,100}$")

    private fun JSONObject.putOrNull(key: String, wert: Any?): JSONObject = put(key, wert ?: JSONObject.NULL)

    fun fahrtDaten(f: Fahrt): JSONObject = JSONObject()
        .put("startZeit", f.startZeit)
        .putOrNull("endeZeit", f.endeZeit)
        .put("startAdresse", f.startAdresse)
        .put("endeAdresse", f.endeAdresse)
        .put("zwischenziele", f.zwischenziele)
        .putOrNull("startLat", f.startLat)
        .putOrNull("startLon", f.startLon)
        .putOrNull("endeLat", f.endeLat)
        .putOrNull("endeLon", f.endeLon)
        .put("distanzMeter", f.distanzMeter)
        .putOrNull("kategorieId", f.kategorieId)
        .put("notiz", f.notiz)
        .put("status", f.status)

    fun anfrage(
        appVersion: String,
        geraet: String,
        e: EinstellungenWerte,
        kategorien: List<Kategorie>,
        eintraege: List<ProtokollEintrag>,
        schluesselHuelle: JSONObject? = null,
    ): String = JSONObject()
        .put("app", JSONObject().put("version", appVersion).put("geraet", geraet))
        .apply { if (schluesselHuelle != null) put("schluessel", schluesselHuelle) }
        .put(
            "einstellungen",
            JSONObject()
                .put("fahrer", e.fahrer)
                .put("fahrzeug", e.fahrzeug)
                .put("kennzeichen", e.kennzeichen)
                .put("kmStandStart", e.kmStandStart)
                .put("kmStandAb", e.kmStandAb),
        )
        .put(
            "kategorien",
            JSONArray().apply {
                kategorien.forEach { k ->
                    put(
                        JSONObject().put("id", k.id).put("name", k.name).put("farbe", k.farbe)
                            .put("sortierung", k.sortierung).put("aktiv", k.aktiv).put("privat", k.privat)
                    )
                }
            },
        )
        .put(
            "eintraege",
            JSONArray().apply {
                eintraege.forEach { p ->
                    put(
                        JSONObject().put("eintragId", p.eintragId).put("uuid", p.fahrtUuid).put("aktion", p.aktion)
                            .put("zeit", p.zeit).put("daten", JSONObject(p.daten))
                    )
                }
            },
        )
        .toString()

    /** Diese Felder einer Privatfahrt gehen nur verschlüsselt an den Server. */
    private val GEHEIME_FELDER = listOf("startAdresse", "endeAdresse", "zwischenziele", "notiz", "startLat", "startLon", "endeLat", "endeLon")

    /** Ersetzt die Details einer Privatfahrt durch deren verschlüsselte Form (`geheim`). */
    fun verschluesseln(daten: JSONObject, datenSchluessel: ByteArray): JSONObject {
        val klar = JSONObject()
        GEHEIME_FELDER.forEach { klar.put(it, daten.opt(it) ?: JSONObject.NULL) }
        val ergebnis = JSONObject(daten.toString())
        listOf("startAdresse", "endeAdresse", "zwischenziele", "notiz").forEach { ergebnis.put(it, "") }
        listOf("startLat", "startLon", "endeLat", "endeLon").forEach { ergebnis.put(it, JSONObject.NULL) }
        ergebnis.put("geheim", Krypto.geheimErstellen(datenSchluessel, klar.toString()))
        return ergebnis
    }

    /**
     * @param entfallen Einträge noch nicht zugeordneter Fahrten (aus Version 0.4) – werden nie übertragen,
     *   weil noch offen war, ob es eine Privatfahrt ist; der Eintrag beim Zuordnen ersetzt sie.
     */
    data class Vorbereitet(val senden: List<ProtokollEintrag>, val wartenAufPin: Int, val entfallen: List<String> = emptyList())

    /**
     * Bereitet Protokolleinträge für die Übertragung vor: Einträge von Privatfahrten werden
     * verschlüsselt. Ohne PIN bleiben sie auf dem Handy – samt allen späteren Einträgen derselben
     * Fahrt, damit die Reihenfolge der Fassungen am Server stimmt.
     */
    fun vorbereiten(eintraege: List<ProtokollEintrag>, privateKategorien: Set<Long>, datenSchluessel: ByteArray?): Vorbereitet {
        val senden = mutableListOf<ProtokollEintrag>()
        val entfallen = mutableListOf<String>()
        val wartendeFahrten = mutableSetOf<String>()
        var warten = 0
        for (e in eintraege) {
            val d = JSONObject(e.daten)
            if (d.optString("status") != "fertig" && e.aktion != "geloescht") {
                entfallen += e.eintragId
                continue
            }
            val kategorie = if (d.isNull("kategorieId")) null else d.optLong("kategorieId")
            val privat = kategorie != null && kategorie in privateKategorien
            when {
                e.fahrtUuid in wartendeFahrten -> warten++
                !privat -> senden += e
                datenSchluessel == null -> {
                    wartendeFahrten += e.fahrtUuid
                    warten++
                }
                else -> senden += e.copy(daten = verschluesseln(d, datenSchluessel).toString())
            }
        }
        return Vorbereitet(senden, warten, entfallen)
    }

    /** Antwort von /api/v1/ich: Name des Fahrers und die Schlüsselhülle am Server (JSON) oder null. */
    fun ichLesen(json: String): Pair<String, String?> {
        val o = JSONObject(json)
        return o.getJSONObject("fahrer").getString("name") to o.optJSONObject("schluessel")?.toString()
    }

    fun antwort(json: String): SyncAntwort {
        val o = JSONObject(json)
        val angenommen = o.optJSONArray("angenommen") ?: JSONArray()
        val abgelehnt = o.optJSONArray("abgelehnt") ?: JSONArray()
        return SyncAntwort(
            fahrerName = o.optJSONObject("fahrer")?.optString("name").orEmpty(),
            angenommen = (0 until angenommen.length()).map { angenommen.getString(it) },
            abgelehnt = (0 until abgelehnt.length()).map {
                val a = abgelehnt.getJSONObject(it)
                a.optString("eintragId") to a.optString("grund")
            },
        )
    }

    /**
     * Liest eine Verbindung aus dem QR-Link (`https://…/verbinden#code=…`), dem Rückfall-Link der
     * Verbindungsseite (`fahrtenbuch://verbinden?server=…&code=…`) oder einem nackten Code.
     */
    fun verbindungLesen(text: String): Verbindung? {
        val t = text.trim()
        if (CODE.matches(t)) return Verbindung(STANDARD_SERVER, t)
        val uri = runCatching { URI(t) }.getOrNull() ?: return null
        val (server, code) = when (uri.scheme?.lowercase()) {
            "https" -> {
                if (uri.path?.startsWith("/verbinden") != true) return null
                val parameter = parameterLesen(uri.rawFragment)
                "https://${uri.host?.lowercase()}" to parameter["code"]
            }
            "fahrtenbuch" -> {
                if (uri.host != "verbinden") return null
                val parameter = parameterLesen(uri.rawQuery)
                (parameter["server"]?.trimEnd('/')?.lowercase() ?: STANDARD_SERVER) to parameter["code"]
            }
            else -> return null
        }
        if (server !in ERLAUBTE_SERVER || code == null || !CODE.matches(code)) return null
        return Verbindung(server, code)
    }

    private fun parameterLesen(roh: String?): Map<String, String> =
        roh.orEmpty().split('&').mapNotNull { teil ->
            val i = teil.indexOf('=')
            if (i <= 0) null else URLDecoder.decode(teil.substring(0, i), "UTF-8") to URLDecoder.decode(teil.substring(i + 1), "UTF-8")
        }.toMap()
}
