package at.zweibit.fahrtenbuch.ui.berichte

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import at.zweibit.fahrtenbuch.FahrtenbuchApp
import at.zweibit.fahrtenbuch.bericht.Bericht
import at.zweibit.fahrtenbuch.bericht.BerichtErstellen
import at.zweibit.fahrtenbuch.bericht.BerichtExport
import at.zweibit.fahrtenbuch.bericht.Zeitraum
import at.zweibit.fahrtenbuch.data.EinstellungenWerte
import at.zweibit.fahrtenbuch.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

enum class ZeitraumArt(val titel: String) { MONAT("Monat"), JAHR("Jahr"), INDIVIDUELL("Zeitraum") }

enum class ExportFormat { PDF, CSV }

class BerichtViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FahrtenbuchApp
    private val repo = app.repository

    val art = MutableStateFlow(ZeitraumArt.MONAT)
    val monat = MutableStateFlow(YearMonth.now())
    val jahr = MutableStateFlow(LocalDate.now().year)
    val von = MutableStateFlow(LocalDate.now().withDayOfMonth(1))
    val bis = MutableStateFlow(LocalDate.now())

    /** Ausgewählte Kategorie-IDs; null = alle. Die ID 0 steht für „ohne Kategorie“. */
    val filter = MutableStateFlow<Set<Long>?>(null)

    private val zeitraum: StateFlow<Zeitraum> = combine(art, monat, jahr, von, bis) { a, m, j, v, b ->
        when (a) {
            ZeitraumArt.MONAT -> Zeitraum.Monat(m)
            ZeitraumArt.JAHR -> Zeitraum.Jahr(j)
            ZeitraumArt.INDIVIDUELL -> if (b.isBefore(v)) Zeitraum.Individuell(b, v) else Zeitraum.Individuell(v, b)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Zeitraum.Monat(YearMonth.now()))

    /** Bericht über alle Kategorien – Grundlage für Filter-Chips und Kilometerstand. */
    val gesamt: StateFlow<Bericht?> = combine(zeitraum, repo.alleFahrten, app.einstellungen.werte) { z, _, e ->
        berechnen(z, e)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val bericht: StateFlow<Bericht?> = combine(gesamt, filter) { b, f ->
        if (b == null || f == null) b else b.copy(bloecke = b.bloecke.filter { (it.kategorieId ?: 0L) in f })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private suspend fun berechnen(z: Zeitraum, e: EinstellungenWerte): Bericht {
        val (vonMs, bisMs) = z.grenzen()
        val fahrten = repo.fahrtenImZeitraum(vonMs, bisMs)
        val kmVorher = if (e.kmStandStart > 0) {
            val gefahren = if (vonMs > e.kmStandAb) {
                repo.fahrtenImZeitraum(e.kmStandAb, vonMs).sumOf { Format.kmWert(it.fahrt.distanzMeter) }
            } else 0.0
            e.kmStandStart + gefahren
        } else null
        val reihenfolge = repo.alleKategorien().map { it.id }
        return BerichtErstellen.erstellen(z, fahrten, null, kmVorher, e.kmStandAb, reihenfolge)
    }

    fun blaettern(richtung: Int) {
        when (art.value) {
            ZeitraumArt.MONAT -> monat.value = monat.value.plusMonths(richtung.toLong())
            ZeitraumArt.JAHR -> jahr.value = jahr.value + richtung
            ZeitraumArt.INDIVIDUELL -> Unit
        }
    }

    fun filterUmschalten(id: Long, alleIds: Set<Long>) {
        val aktuell = filter.value ?: alleIds
        val neu = if (id in aktuell) aktuell - id else aktuell + id
        filter.value = if (neu.containsAll(alleIds)) null else neu
    }

    /** Erstellt die Exportdatei und liefert einen Teilen-Intent dafür. */
    suspend fun exportieren(format: ExportFormat): Intent? {
        val b = bericht.value ?: return null
        val e = app.einstellungen.aktuell()
        val ordner = File(app.cacheDir, "berichte").apply { mkdirs() }
        ordner.listFiles()?.forEach { it.delete() }
        val iso = DateTimeFormatter.ISO_LOCAL_DATE
        val name = when (val z = b.zeitraum) {
            is Zeitraum.Monat -> "Fahrtenbuch_${z.monat}"
            is Zeitraum.Jahr -> "Fahrtenbuch_${z.jahr}"
            is Zeitraum.Individuell -> "Fahrtenbuch_${iso.format(z.von)}_${iso.format(z.bis)}"
        }
        val datei = File(ordner, name + if (format == ExportFormat.PDF) ".pdf" else ".csv")
        withContext(Dispatchers.IO) {
            when (format) {
                ExportFormat.PDF -> BerichtExport.pdf(b, e, datei)
                ExportFormat.CSV -> datei.writeText(BerichtExport.csv(b, e), Charsets.UTF_8)
            }
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", datei)
        val senden = Intent(Intent.ACTION_SEND)
            .setType(if (format == ExportFormat.PDF) "application/pdf" else "text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Fahrtenbuch ${b.zeitraum.titel}")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(senden, "Bericht teilen")
    }
}
