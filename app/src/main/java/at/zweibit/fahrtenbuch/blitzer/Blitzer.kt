package at.zweibit.fahrtenbuch.blitzer

import java.util.Locale

enum class BlitzerArt(val code: Char) {
    FIX('F'),
    ROTLICHT('R'),
    SECTION_START('S'),
    SECTION_ENDE('E');

    companion object {
        fun aus(code: Char): BlitzerArt? = entries.firstOrNull { it.code == code }
    }
}

data class Blitzer(
    val id: Long,
    val art: BlitzerArt,
    val lat: Double,
    val lon: Double,
    val maxspeed: Int? = null,
    /** Fahrtrichtungen in Grad, in denen gemessen wird. Leer = unbekannt, gilt für alle Richtungen. */
    val richtungen: List<Double> = emptyList(),
) {
    val schluessel: String get() = "${art.code}$id"
}

data class BlitzerInfo(
    val stand: Long,
    val fix: Int,
    val rotlicht: Int,
    val section: Int,
) {
    companion object {
        fun aus(stand: Long, liste: List<Blitzer>) = BlitzerInfo(
            stand = stand,
            fix = liste.count { it.art == BlitzerArt.FIX },
            rotlicht = liste.count { it.art == BlitzerArt.ROTLICHT },
            section = liste.count { it.art == BlitzerArt.SECTION_START },
        )
    }
}

/**
 * Kompaktes Textformat für die lokale Blitzerdatei.
 * Kopf:  `# stand=<millis>;fix=<n>;rotlicht=<n>;section=<n>`
 * Zeile: `<art>;<id>;<lat>;<lon>;<maxspeed>;<richtung>|<richtung>`
 */
object BlitzerFormat {
    fun schreiben(stand: Long, liste: List<Blitzer>): String {
        val info = BlitzerInfo.aus(stand, liste)
        val sb = StringBuilder()
        sb.append("# stand=").append(info.stand).append(";fix=").append(info.fix)
            .append(";rotlicht=").append(info.rotlicht).append(";section=").append(info.section).append('\n')
        for (b in liste) {
            sb.append(b.art.code).append(';').append(b.id).append(';')
                .append(String.format(Locale.ROOT, "%.7f", b.lat)).append(';')
                .append(String.format(Locale.ROOT, "%.7f", b.lon)).append(';')
                .append(b.maxspeed?.toString() ?: "").append(';')
                .append(b.richtungen.joinToString("|") { String.format(Locale.ROOT, "%.1f", it) })
                .append('\n')
        }
        return sb.toString()
    }

    fun infoLesen(kopfzeile: String?): BlitzerInfo? {
        if (kopfzeile == null || !kopfzeile.startsWith("#")) return null
        val werte = kopfzeile.removePrefix("#").trim().split(';')
            .mapNotNull { teil -> teil.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
            .toMap()
        val stand = werte["stand"]?.toLongOrNull() ?: return null
        return BlitzerInfo(
            stand = stand,
            fix = werte["fix"]?.toIntOrNull() ?: 0,
            rotlicht = werte["rotlicht"]?.toIntOrNull() ?: 0,
            section = werte["section"]?.toIntOrNull() ?: 0,
        )
    }

    fun lesen(text: String): Pair<BlitzerInfo?, List<Blitzer>> {
        val zeilen = text.lineSequence().filter { it.isNotBlank() }.toList()
        val info = infoLesen(zeilen.firstOrNull())
        val liste = zeilen.asSequence().filterNot { it.startsWith("#") }.mapNotNull { z ->
            val t = z.split(';')
            if (t.size < 6) return@mapNotNull null
            val art = t[0].singleOrNull()?.let { BlitzerArt.aus(it) } ?: return@mapNotNull null
            Blitzer(
                id = t[1].toLongOrNull() ?: return@mapNotNull null,
                art = art,
                lat = t[2].toDoubleOrNull() ?: return@mapNotNull null,
                lon = t[3].toDoubleOrNull() ?: return@mapNotNull null,
                maxspeed = t[4].toIntOrNull(),
                richtungen = t[5].split('|').mapNotNull { it.toDoubleOrNull() },
            )
        }.toList()
        return info to liste
    }
}
