package at.zweibit.fahrtenbuch.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import at.zweibit.fahrtenbuch.FahrtenbuchApp
import at.zweibit.fahrtenbuch.data.EinstellungenWerte
import at.zweibit.fahrtenbuch.data.Fahrt
import at.zweibit.fahrtenbuch.data.FahrtMitKategorie
import at.zweibit.fahrtenbuch.data.FahrtStatus
import at.zweibit.fahrtenbuch.data.Kategorie
import at.zweibit.fahrtenbuch.data.Ort
import at.zweibit.fahrtenbuch.data.Zwischenziel
import at.zweibit.fahrtenbuch.data.zwischenzieleListe
import at.zweibit.fahrtenbuch.tracking.Adressen
import at.zweibit.fahrtenbuch.tracking.Benachrichtigungen
import at.zweibit.fahrtenbuch.tracking.TrackingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class MonatsSumme(val kategorie: Kategorie?, val anzahl: Int, val meter: Double)

class HauptViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FahrtenbuchApp
    private val repo = app.repository

    private fun <T> kotlinx.coroutines.flow.Flow<T>.halten(start: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), start)

    val laufend: StateFlow<Fahrt?> = repo.laufendeFahrt.halten(null)
    val offene: StateFlow<List<Fahrt>> = repo.offeneFahrten.halten(emptyList())
    val kategorien: StateFlow<List<Kategorie>> = repo.kategorienFlow.halten(emptyList())
    val einstellungen: StateFlow<EinstellungenWerte> = app.einstellungen.werte.halten(EinstellungenWerte())
    val orte: StateFlow<List<Ort>> = app.einstellungen.orte.halten(emptyList())

    /** Vor dem Start gewählter Ort als Startadresse; null = aktueller Standort per GPS. */
    val startOrt = MutableStateFlow<Ort?>(null)

    /** Fahrten, deren Dialog der Nutzer mit „Später“ weggelegt hat (nur für diese Sitzung). */
    private val spaeter = MutableStateFlow<Set<Long>>(emptySet())

    /** Fahrt, die explizit (z. B. aus der Liste oder Benachrichtigung) zugeordnet werden soll. */
    private val angefordert = MutableStateFlow(0L)

    val dialogFahrt: StateFlow<Fahrt?> = combine(offene, spaeter, angefordert) { liste, weg, id ->
        liste.firstOrNull { it.id == id } ?: liste.firstOrNull { it.id !in weg }
    }.halten(null)

    val dieserMonat: StateFlow<List<MonatsSumme>> = repo.alleFahrten.map { alle ->
        val zone = ZoneId.systemDefault()
        val beginn = LocalDate.now().withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        alle.filter { it.fahrt.status == FahrtStatus.FERTIG && it.fahrt.startZeit >= beginn }
            .groupBy { it.kategorie?.id }
            .map { (_, l: List<FahrtMitKategorie>) -> MonatsSumme(l.first().kategorie, l.size, l.sumOf { it.fahrt.distanzMeter }) }
            .sortedByDescending { it.meter }
    }.halten(emptyList())

    fun starten() {
        TrackingService.starten(app, startOrt.value?.alsAdresse)
        startOrt.value = null
    }

    fun stoppen() = TrackingService.stoppen(app)
    fun pausieren() = TrackingService.pausieren(app)
    fun weiterfahren() = TrackingService.weiterfahren(app)

    /** Startadresse der laufenden Fahrt ändern, z. B. wenn der Start zu spät gedrückt wurde. */
    fun startAdresseAendern(fahrtId: Long, adresse: String) {
        viewModelScope.launch { repo.startAdresseSetzen(fahrtId, adresse.trim()) }
    }

    /** Adresse eines Zwischenziels der laufenden Fahrt ändern. */
    fun zwischenzielAendern(fahrtId: Long, index: Int, adresse: String) {
        viewModelScope.launch {
            val f = repo.fahrt(fahrtId) ?: return@launch
            val liste = f.zwischenzieleListe.toMutableList()
            if (index !in liste.indices) return@launch
            liste[index] = liste[index].copy(adresse = adresse.trim())
            repo.zwischenzieleSetzen(fahrtId, liste)
        }
    }

    fun anfordern(id: Long) {
        spaeter.value = spaeter.value - id
        angefordert.value = id
    }

    fun spaeter(id: Long) {
        spaeter.value = spaeter.value + id
        if (angefordert.value == id) angefordert.value = 0L
    }

    fun kategorisieren(
        fahrtId: Long,
        kategorieId: Long,
        notiz: String,
        start: String,
        ziel: String,
        zwischen: List<Zwischenziel>,
    ) {
        viewModelScope.launch {
            repo.kategorisieren(fahrtId, kategorieId, notiz, start, ziel, zwischen)
            Benachrichtigungen.beendetEntfernen(app, fahrtId)
            if (angefordert.value == fahrtId) angefordert.value = 0L
        }
    }

    fun verwerfen(fahrtId: Long) {
        viewModelScope.launch {
            repo.fahrtLoeschen(fahrtId)
            Benachrichtigungen.beendetEntfernen(app, fahrtId)
        }
    }

    /** Versucht fehlende Adressen (z. B. ohne Netz am Ziel) nachträglich zu ermitteln. */
    suspend fun adressenNachladen(f: Fahrt): Pair<String?, String?> {
        val orte = app.einstellungen.orteAktuell()
        val start = if (Adressen.fehlt(f.startAdresse) && f.startLat != null && f.startLon != null)
            Adressen.bestimmen(app, f.startLat, f.startLon, orte).takeUnless { Adressen.fehlt(it) } else null
        val ziel = if (Adressen.fehlt(f.endeAdresse) && f.endeLat != null && f.endeLon != null)
            Adressen.bestimmen(app, f.endeLat, f.endeLon, orte).takeUnless { Adressen.fehlt(it) } else null
        return start to ziel
    }
}
