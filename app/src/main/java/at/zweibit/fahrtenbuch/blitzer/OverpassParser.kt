package at.zweibit.fahrtenbuch.blitzer

import at.zweibit.fahrtenbuch.tracking.Geo
import org.json.JSONObject

/**
 * Wandelt die Overpass-Antwort (OpenStreetMap) in Blitzer um.
 *
 * - Relationen `type=enforcement` liefern Gerät, Start- und Endpunkt der Messung. Aus `from` → `to`
 *   ergibt sich die gemessene Fahrtrichtung, damit Blitzer der Gegenfahrbahn nicht warnen.
 * - `enforcement=average_speed` (Section Control) wird zu Start- und Endpunkt; die Kameras
 *   innerhalb der Strecke warnen nicht einzeln.
 * - Knoten `highway=speed_camera` ohne Relation werden ohne Richtung übernommen.
 */
object OverpassParser {
    const val QUERY = """[out:json][timeout:180];
area(id:3600016239)->.at;
rel["type"="enforcement"]["enforcement"~"^(maxspeed|average_speed|traffic_signals)$"](area.at)->.r;
(
  node["highway"="speed_camera"](area.at);
  node(r.r);
  .r;
);
out body qt;"""

    /** Unter dieser Entfernung zwischen zwei Punkten ist eine Richtung nicht sinnvoll bestimmbar. */
    private const val MIN_RICHTUNG_M = 15.0

    private class Knoten(val lat: Double, val lon: Double, val tags: JSONObject?)

    private class Sammler(val id: Long, var art: BlitzerArt, val lat: Double, val lon: Double, var maxspeed: Int?) {
        val richtungen = mutableListOf<Double>()
        var richtungUnbekannt = false
    }

    fun maxspeedLesen(wert: String?): Int? =
        wert?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()?.takeIf { it in 5..200 }

    private fun peilung(a: Knoten, b: Knoten): Double? =
        if (Geo.distanzMeter(a.lat, a.lon, b.lat, b.lon) < MIN_RICHTUNG_M) null
        else Geo.peilung(a.lat, a.lon, b.lat, b.lon)

    fun parsen(json: String): List<Blitzer> {
        val elemente = JSONObject(json).getJSONArray("elements")
        val knoten = HashMap<Long, Knoten>()
        val relationen = mutableListOf<JSONObject>()
        for (i in 0 until elemente.length()) {
            val e = elemente.getJSONObject(i)
            when (e.optString("type")) {
                "node" -> knoten[e.getLong("id")] = Knoten(e.getDouble("lat"), e.getDouble("lon"), e.optJSONObject("tags"))
                "relation" -> relationen += e
            }
        }

        val geraete = LinkedHashMap<Long, Sammler>()
        val sections = mutableListOf<Blitzer>()
        val sectionGeraete = HashSet<Long>()

        for (r in relationen) {
            val tags = r.optJSONObject("tags") ?: continue
            val enforcement = tags.optString("enforcement")
            val mitglieder = r.optJSONArray("members") ?: continue
            val rollen = HashMap<String, MutableList<Pair<Long, Knoten>>>()
            for (j in 0 until mitglieder.length()) {
                val m = mitglieder.getJSONObject(j)
                if (m.optString("type") != "node") continue
                val id = m.getLong("ref")
                val k = knoten[id] ?: continue
                rollen.getOrPut(m.optString("role")) { mutableListOf() } += id to k
            }
            val von = rollen["from"].orEmpty()
            val bis = rollen["to"].orEmpty()
            val devices = rollen["device"].orEmpty()
            val tempo = maxspeedLesen(tags.optString("maxspeed", ""))

            when (enforcement) {
                "average_speed" -> {
                    devices.forEach { sectionGeraete += it.first }
                    val ersterBis = bis.firstOrNull()?.second
                    val ersterVon = von.firstOrNull()?.second
                    von.forEach { (id, k) ->
                        val r1 = ersterBis?.let { peilung(k, it) }
                        sections += Blitzer(id, BlitzerArt.SECTION_START, k.lat, k.lon, tempo, listOfNotNull(r1))
                    }
                    bis.forEach { (id, k) ->
                        val r1 = ersterVon?.let { peilung(it, k) }
                        sections += Blitzer(id, BlitzerArt.SECTION_ENDE, k.lat, k.lon, tempo, listOfNotNull(r1))
                    }
                }
                "maxspeed", "traffic_signals" -> {
                    val art = if (enforcement == "maxspeed") BlitzerArt.FIX else BlitzerArt.ROTLICHT
                    for ((id, k) in devices) {
                        // Richtung: Start → Ende der Messstrecke, sonst Start → Gerät bzw. Gerät → Ende
                        val richtung = when {
                            von.size == 1 && bis.size == 1 -> peilung(von[0].second, bis[0].second)
                            else -> null
                        } ?: when {
                            von.size == 1 -> peilung(von[0].second, k)
                            bis.size == 1 -> peilung(k, bis[0].second)
                            else -> null
                        }
                        val s = geraete.getOrPut(id) {
                            Sammler(id, art, k.lat, k.lon, tempo ?: maxspeedLesen(k.tags?.optString("maxspeed", "")))
                        }
                        if (art == BlitzerArt.FIX) s.art = BlitzerArt.FIX // Kombigerät: Tempo hat Vorrang
                        if (s.maxspeed == null) s.maxspeed = tempo
                        if (richtung == null) s.richtungUnbekannt = true else s.richtungen += richtung
                    }
                }
            }
        }

        // Kameras ohne Relation: Richtung unbekannt
        for ((id, k) in knoten) {
            if (k.tags?.optString("highway") != "speed_camera") continue
            if (id in geraete || id in sectionGeraete) continue
            geraete[id] = Sammler(id, BlitzerArt.FIX, k.lat, k.lon, maxspeedLesen(k.tags.optString("maxspeed", "")))
                .apply { richtungUnbekannt = true }
        }

        val einzeln = geraete.values
            .filter { it.id !in sectionGeraete }
            .map { s ->
                Blitzer(
                    id = s.id, art = s.art, lat = s.lat, lon = s.lon, maxspeed = s.maxspeed,
                    richtungen = if (s.richtungUnbekannt) emptyList() else s.richtungen.distinct(),
                )
            }
        return einzeln + sections.distinctBy { it.schluessel }
    }
}
