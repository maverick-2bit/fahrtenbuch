package at.zweibit.fahrtenbuch.bericht

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.text.TextUtils
import at.zweibit.fahrtenbuch.data.EinstellungenWerte
import at.zweibit.fahrtenbuch.data.Fahrt
import at.zweibit.fahrtenbuch.data.zwischenzieleListe
import at.zweibit.fahrtenbuch.util.Format
import java.io.File
import java.io.FileOutputStream

object BerichtExport {

    private fun kopfzeilen(bericht: Bericht, e: EinstellungenWerte): List<Pair<String, String>> = buildList {
        add("Zeitraum" to "${bericht.zeitraum.titel} (${Format.datum(bericht.zeitraum.von)} – ${Format.datum(bericht.zeitraum.bis)})")
        if (e.fahrzeug.isNotBlank()) add("Fahrzeug" to e.fahrzeug)
        if (e.kennzeichen.isNotBlank()) add("Kennzeichen" to e.kennzeichen)
        if (e.fahrer.isNotBlank()) add("Fahrer" to e.fahrer)
    }

    /** Zwischenziele einer Fahrt, z. B. „Kunde A / Kunde B“. */
    private fun ueber(f: Fahrt): String = f.zwischenzieleListe.joinToString(" / ") { it.adresse }

    private fun zeit(z: BerichtZeile): String {
        val f = z.fahrt
        return Format.uhrzeit(f.startZeit) + "–" + (f.endeZeit?.let { Format.uhrzeit(it) } ?: "")
    }

    // ---------------------------------------------------------------- CSV (Excel, deutsch)

    fun csv(bericht: Bericht, e: EinstellungenWerte): String {
        fun feld(s: String): String =
            if (s.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

        val sb = StringBuilder("﻿") // BOM, damit Excel UTF-8 erkennt
        sb.append("Fahrtenbuch\r\n")
        kopfzeilen(bericht, e).forEach { (k, v) -> sb.append(feld(k)).append(';').append(feld(v)).append("\r\n") }
        sb.append("\r\n")

        val kopf = mutableListOf("Kategorie", "Datum", "Abfahrt", "Ankunft", "Von", "Über", "Nach", "km")
        if (bericht.mitKmStand) kopf += listOf("Km-Stand Beginn", "Km-Stand Ende")
        kopf += "Zweck / Notiz"
        sb.append(kopf.joinToString(";")).append("\r\n")

        for (b in bericht.bloecke) {
            for (z in b.zeilen) {
                val f = z.fahrt
                val werte = mutableListOf(
                    b.name,
                    Format.datum(f.startZeit),
                    Format.uhrzeit(f.startZeit),
                    f.endeZeit?.let { Format.uhrzeit(it) } ?: "",
                    f.startAdresse,
                    ueber(f),
                    f.endeAdresse,
                    Format.kmZahl(z.km),
                )
                if (bericht.mitKmStand) {
                    werte += z.kmStandBeginn?.let { Format.kmZahl(it) } ?: ""
                    werte += z.kmStandEnde?.let { Format.kmZahl(it) } ?: ""
                }
                werte += f.notiz
                sb.append(werte.joinToString(";") { feld(it) }).append("\r\n")
            }
        }

        sb.append("\r\nZusammenfassung\r\nKategorie;Fahrten;km;Anteil %\r\n")
        for (b in bericht.bloecke) {
            sb.append(feld(b.name)).append(';').append(b.anzahl).append(';')
                .append(Format.kmZahl(b.summeKm)).append(';')
                .append(Format.kmZahl(bericht.anteilProzent(b))).append("\r\n")
        }
        sb.append("Gesamt;").append(bericht.gesamtAnzahl).append(';').append(Format.kmZahl(bericht.gesamtKm)).append(";100,0\r\n")
        return sb.toString()
    }

    // ---------------------------------------------------------------- PDF (A4 quer)

    private const val BREITE = 842
    private const val HOEHE = 595
    private const val RAND = 36f

    private class Spalte(val titel: String, val breite: Float, val rechts: Boolean = false)

    fun pdf(bericht: Bericht, e: EinstellungenWerte, ziel: File) {
        val doc = PdfDocument()
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; color = Color.rgb(33, 33, 33) }
        val fett = TextPaint(text).apply { typeface = Typeface.DEFAULT_BOLD }
        val titel = TextPaint(fett).apply { textSize = 18f }
        val ueberschrift = TextPaint(fett).apply { textSize = 12f }
        val grau = TextPaint(text).apply { color = Color.rgb(110, 110, 110) }
        val linie = Paint().apply { color = Color.rgb(200, 200, 200); strokeWidth = 0.6f }
        val zeilenHoehe = 14f

        val nutzbar = BREITE - 2 * RAND
        val fest = mutableListOf(
            Spalte("Datum", 52f), Spalte("Zeit", 58f), Spalte("km", 42f, rechts = true),
        )
        if (bericht.mitKmStand) fest += listOf(Spalte("Km Beginn", 56f, true), Spalte("Km Ende", 56f, true))
        val rest = nutzbar - fest.sumOf { it.breite.toDouble() }.toFloat()
        // Spalte „Über“ nur, wenn im Zeitraum Fahrten mit Zwischenzielen vorkommen
        val mitUeber = bericht.bloecke.any { b -> b.zeilen.any { it.fahrt.zwischenzieleListe.isNotEmpty() } }
        val adressSpalten = if (mitUeber) {
            listOf(Spalte("Von", rest * 0.25f), Spalte("Über", rest * 0.25f), Spalte("Nach", rest * 0.25f))
        } else {
            listOf(Spalte("Von", rest * 0.34f), Spalte("Nach", rest * 0.34f))
        }
        val notizBreite = if (mitUeber) rest * 0.25f else rest * 0.32f
        val spalten = listOf(fest[0], fest[1]) + adressSpalten + fest[2] + fest.drop(3) + Spalte("Zweck / Notiz", notizBreite)

        var seitenNr = 0
        var seite: PdfDocument.Page? = null
        var y = 0f

        fun neueSeite() {
            seite?.let { doc.finishPage(it) }
            seitenNr++
            seite = doc.startPage(PdfDocument.PageInfo.Builder(BREITE, HOEHE, seitenNr).create())
            val c = seite!!.canvas
            c.drawText("Fahrtenbuch · ${bericht.zeitraum.titel}", RAND, HOEHE - 18f, grau)
            val nr = "Seite $seitenNr"
            c.drawText(nr, BREITE - RAND - grau.measureText(nr), HOEHE - 18f, grau)
            y = RAND
        }

        fun platz(bedarf: Float) {
            if (seite == null || y + bedarf > HOEHE - 40f) neueSeite()
        }

        fun zelle(s: String, x: Float, breite: Float, paint: TextPaint, rechts: Boolean = false) {
            val t = TextUtils.ellipsize(s, paint, breite - 4f, TextUtils.TruncateAt.END).toString()
            val tx = if (rechts) x + breite - 4f - paint.measureText(t) else x
            seite!!.canvas.drawText(t, tx, y, paint)
        }

        fun tabellenKopf() {
            platz(zeilenHoehe * 2)
            var x = RAND
            spalten.forEach { zelle(it.titel, x, it.breite, fett, it.rechts); x += it.breite }
            seite!!.canvas.drawLine(RAND, y + 4f, BREITE - RAND, y + 4f, linie)
            y += zeilenHoehe
        }

        // Kopf
        neueSeite()
        y += 10f
        seite!!.canvas.drawText("Fahrtenbuch", RAND, y, titel)
        y += 22f
        kopfzeilen(bericht, e).forEach { (k, v) ->
            seite!!.canvas.drawText("$k:", RAND, y, fett)
            seite!!.canvas.drawText(v, RAND + 80f, y, text)
            y += zeilenHoehe
        }

        // Zusammenfassung
        y += 10f
        seite!!.canvas.drawText("Zusammenfassung", RAND, y, ueberschrift)
        y += zeilenHoehe + 2f
        val sx = floatArrayOf(RAND, RAND + 180f, RAND + 250f, RAND + 330f)
        listOf("Kategorie", "Fahrten", "km", "Anteil").forEachIndexed { i, s ->
            val rechts = i > 0
            val w = if (i == 0) 170f else 70f
            zelle(s, sx[i], w, fett, rechts)
        }
        seite!!.canvas.drawLine(RAND, y + 4f, RAND + 400f, y + 4f, linie)
        y += zeilenHoehe
        val punkt = Paint(Paint.ANTI_ALIAS_FLAG)
        for (b in bericht.bloecke) {
            punkt.color = b.farbe.toInt()
            seite!!.canvas.drawCircle(RAND + 3f, y - 3f, 3f, punkt)
            zelle(b.name, sx[0] + 10f, 160f, text)
            zelle(b.anzahl.toString(), sx[1], 70f, text, true)
            zelle(Format.kmZahl(b.summeKm), sx[2], 70f, text, true)
            zelle(Format.kmZahl(bericht.anteilProzent(b)) + " %", sx[3], 70f, text, true)
            y += zeilenHoehe
        }
        seite!!.canvas.drawLine(RAND, y - 10f, RAND + 400f, y - 10f, linie)
        zelle("Gesamt", sx[0], 170f, fett)
        zelle(bericht.gesamtAnzahl.toString(), sx[1], 70f, fett, true)
        zelle(Format.kmZahl(bericht.gesamtKm), sx[2], 70f, fett, true)
        y += zeilenHoehe * 2

        // Fahrten je Kategorie
        for (b in bericht.bloecke) {
            platz(zeilenHoehe * 4)
            punkt.color = b.farbe.toInt()
            seite!!.canvas.drawCircle(RAND + 4f, y - 4f, 4f, punkt)
            seite!!.canvas.drawText("${b.name} · ${b.anzahl} Fahrten · ${Format.kmZahl(b.summeKm)} km", RAND + 14f, y, ueberschrift)
            y += zeilenHoehe + 4f
            tabellenKopf()
            for (z in b.zeilen) {
                if (y + zeilenHoehe > HOEHE - 40f) {
                    neueSeite()
                    tabellenKopf()
                }
                val f = z.fahrt
                val werte = mutableListOf(Format.datum(f.startZeit), zeit(z), f.startAdresse)
                if (mitUeber) werte += ueber(f)
                werte += listOf(f.endeAdresse, Format.kmZahl(z.km))
                if (bericht.mitKmStand) {
                    werte += z.kmStandBeginn?.let { Format.kmZahl(it) } ?: ""
                    werte += z.kmStandEnde?.let { Format.kmZahl(it) } ?: ""
                }
                werte += f.notiz
                var x = RAND
                spalten.forEachIndexed { i, s -> zelle(werte[i], x, s.breite, text, s.rechts); x += s.breite }
                y += zeilenHoehe
            }
            seite!!.canvas.drawLine(RAND, y - 10f, BREITE - RAND, y - 10f, linie)
            y += zeilenHoehe
        }

        seite?.let { doc.finishPage(it) }
        FileOutputStream(ziel).use { doc.writeTo(it) }
        doc.close()
    }
}
