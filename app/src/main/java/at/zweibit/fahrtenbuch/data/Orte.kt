package at.zweibit.fahrtenbuch.data

import at.zweibit.fahrtenbuch.tracking.Geo
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Gespeicherter Ort (z. B. „Zuhause“, „Büro“, Stammkunde). Mit Koordinaten wird er an
 * Start, Zwischenziel und Ziel automatisch erkannt; ohne Koordinaten nur zur Auswahl.
 */
data class Ort(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val adresse: String,
    val lat: Double? = null,
    val lon: Double? = null,
) {
    /** Text, der als Start-, Zwischen- oder Zieladresse in die Fahrt übernommen wird. */
    val alsAdresse: String
        get() = when {
            name.isBlank() -> adresse.trim()
            adresse.isBlank() -> name.trim()
            else -> "${name.trim()}, ${adresse.trim()}"
        }

    val hatKoordinaten: Boolean get() = lat != null && lon != null
}

object Orte {
    /** Umkreis, in dem ein gespeicherter Ort automatisch erkannt wird. */
    const val ERKENNUNG_M = 150.0

    fun lesen(json: String?): List<Ort> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Ort(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = o.optString("name"),
                    adresse = o.optString("adresse"),
                    lat = if (o.has("lat")) o.getDouble("lat") else null,
                    lon = if (o.has("lon")) o.getDouble("lon") else null,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun schreiben(liste: List<Ort>): String {
        val a = JSONArray()
        liste.forEach { o ->
            a.put(JSONObject().apply {
                put("id", o.id)
                put("name", o.name)
                put("adresse", o.adresse)
                o.lat?.let { put("lat", it) }
                o.lon?.let { put("lon", it) }
            })
        }
        return a.toString()
    }

    /** Nächster gespeicherter Ort im Umkreis von [radiusM], sonst null. */
    fun erkennen(orte: List<Ort>, lat: Double, lon: Double, radiusM: Double = ERKENNUNG_M): Ort? =
        orte.asSequence()
            .filter { it.hatKoordinaten }
            .map { it to Geo.distanzMeter(lat, lon, it.lat!!, it.lon!!) }
            .filter { it.second <= radiusM }
            .minByOrNull { it.second }
            ?.first
}
