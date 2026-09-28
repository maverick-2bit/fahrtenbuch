package at.zweibit.fahrtenbuch.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Ein Zwischenhalt einer Fahrt (Pause unterwegs), z. B. beim Kunden auf einer Hin- und Rückfahrt.
 * [ab] == null bei der letzten Station bedeutet: Die Fahrt ist gerade pausiert.
 */
data class Zwischenziel(
    val adresse: String,
    val lat: Double? = null,
    val lon: Double? = null,
    /** Ankunft (Beginn der Pause); null bei nachträglich eingetragenen Zwischenzielen. */
    val an: Long? = null,
    /** Weiterfahrt; null solange pausiert bzw. unbekannt. */
    val ab: Long? = null,
)

object Zwischenziele {
    fun lesen(json: String): List<Zwischenziel> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Zwischenziel(
                    adresse = o.optString("adresse"),
                    lat = if (o.has("lat")) o.getDouble("lat") else null,
                    lon = if (o.has("lon")) o.getDouble("lon") else null,
                    an = if (o.has("an")) o.getLong("an") else null,
                    ab = if (o.has("ab")) o.getLong("ab") else null,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun schreiben(liste: List<Zwischenziel>): String {
        if (liste.isEmpty()) return ""
        val a = JSONArray()
        liste.forEach { z ->
            a.put(JSONObject().apply {
                put("adresse", z.adresse)
                z.lat?.let { put("lat", it) }
                z.lon?.let { put("lon", it) }
                z.an?.let { put("an", it) }
                z.ab?.let { put("ab", it) }
            })
        }
        return a.toString()
    }

    /** Anzeige einer Strecke: Start, Zwischenziele, Ziel. */
    fun strecke(von: String, zwischen: List<Zwischenziel>, nach: String, trenner: String = " → "): String =
        (listOf(von.ifBlank { "?" }) + zwischen.map { it.adresse.ifBlank { "?" } } + listOf(nach.ifBlank { "?" }))
            .joinToString(trenner)
}

val Fahrt.zwischenzieleListe: List<Zwischenziel> get() = Zwischenziele.lesen(zwischenziele)

/** Pausiert = laufende Fahrt, deren letztes Zwischenziel noch keine Weiterfahrt hat. */
val Fahrt.pausiert: Boolean
    get() = status == FahrtStatus.LAUFEND && zwischenzieleListe.lastOrNull()?.let { it.ab == null && it.an != null } == true

val Fahrt.streckeText: String get() = Zwischenziele.strecke(startAdresse, zwischenzieleListe, endeAdresse)
