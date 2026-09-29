package at.zweibit.fahrtenbuch.ui.fahrten

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.zweibit.fahrtenbuch.FahrtenbuchApp
import at.zweibit.fahrtenbuch.data.Fahrt
import at.zweibit.fahrtenbuch.data.FahrtStatus
import at.zweibit.fahrtenbuch.data.Kategorie
import at.zweibit.fahrtenbuch.tracking.Benachrichtigungen
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import at.zweibit.fahrtenbuch.data.Ort
import at.zweibit.fahrtenbuch.data.Zwischenziel
import at.zweibit.fahrtenbuch.data.Zwischenziele
import at.zweibit.fahrtenbuch.data.zwischenzieleListe
import at.zweibit.fahrtenbuch.ui.AdressFeld
import at.zweibit.fahrtenbuch.ui.Farbpunkt
import at.zweibit.fahrtenbuch.ui.datumWaehlen
import at.zweibit.fahrtenbuch.ui.kategorieFarbe
import at.zweibit.fahrtenbuch.ui.zeitWaehlen
import at.zweibit.fahrtenbuch.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class BearbeitenViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FahrtenbuchApp
    private val repo = app.repository
    val kategorien: StateFlow<List<Kategorie>> =
        repo.kategorienFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val orte: StateFlow<List<Ort>> =
        app.einstellungen.orte.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun laden(id: Long): Fahrt? = repo.fahrt(id)

    fun speichern(fahrt: Fahrt, fertig: () -> Unit) = viewModelScope.launch {
        if (fahrt.id == 0L) repo.fahrtAnlegen(fahrt) else repo.fahrtSpeichern(fahrt)
        if (fahrt.status == FahrtStatus.FERTIG && fahrt.id != 0L) Benachrichtigungen.beendetEntfernen(app, fahrt.id)
        fertig()
    }

    fun loeschen(id: Long, fertig: () -> Unit) = viewModelScope.launch {
        repo.fahrtLoeschen(id)
        Benachrichtigungen.beendetEntfernen(app, id)
        fertig()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FahrtBearbeitenScreen(fahrtId: Long, zurueck: () -> Unit, vm: BearbeitenViewModel = viewModel()) {
    val context = LocalContext.current
    val kategorien by vm.kategorien.collectAsStateWithLifecycle()
    val orte by vm.orte.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val zwischen = remember { mutableStateListOf<Zwischenziel>() }

    var original by remember { mutableStateOf<Fahrt?>(null) }
    var geladen by remember { mutableStateOf(fahrtId == 0L) }
    var datum by remember { mutableStateOf(LocalDate.now()) }
    var abfahrt by remember { mutableStateOf(LocalTime.now().minusMinutes(30).truncatedTo(ChronoUnit.MINUTES)) }
    var ankunft by remember { mutableStateOf(LocalTime.now().truncatedTo(ChronoUnit.MINUTES)) }
    var von by remember { mutableStateOf("") }
    var nach by remember { mutableStateOf("") }
    var kmText by remember { mutableStateOf("") }
    var kategorieId by remember { mutableStateOf<Long?>(null) }
    var notiz by remember { mutableStateOf("") }
    var loeschenFragen by remember { mutableStateOf(false) }

    LaunchedEffect(fahrtId) {
        if (fahrtId == 0L) return@LaunchedEffect
        val f = vm.laden(fahrtId) ?: return@LaunchedEffect zurueck()
        original = f
        val s = Instant.ofEpochMilli(f.startZeit).atZone(zone)
        datum = s.toLocalDate()
        abfahrt = s.toLocalTime().truncatedTo(ChronoUnit.MINUTES)
        ankunft = Instant.ofEpochMilli(f.endeZeit ?: f.startZeit).atZone(zone).toLocalTime().truncatedTo(ChronoUnit.MINUTES)
        von = f.startAdresse
        nach = f.endeAdresse
        zwischen.clear()
        zwischen.addAll(f.zwischenzieleListe)
        kmText = Format.kmEingabe(f.distanzMeter)
        kategorieId = f.kategorieId
        notiz = f.notiz
        geladen = true
    }

    val km = Format.kmParsen(kmText)
    val gueltig = geladen && km != null && km >= 0 && kategorieId != null

    fun speichern() {
        val startMillis = datum.atTime(abfahrt).atZone(zone).toInstant().toEpochMilli()
        // Ankunft vor Abfahrt = Fahrt über Mitternacht
        val endeDatum = if (ankunft.isBefore(abfahrt)) datum.plusDays(1) else datum
        val endeMillis = endeDatum.atTime(ankunft).atZone(zone).toInstant().toEpochMilli()
        val basis = original ?: Fahrt(startZeit = startMillis)
        // Unveränderte km-Anzeige soll die exakte GPS-Strecke nicht verfälschen
        val meter = if (original != null && Format.kmWert(basis.distanzMeter) == km) basis.distanzMeter else km!! * 1000.0
        // Von Hand geänderte Kilometer oder Abfahrt ersetzen einen berechneten Nachtrag (korrigierter Start)
        val kmGleich = meter == basis.distanzMeter
        val abfahrtGleich = startMillis / 60_000 == basis.startZeit / 60_000
        vm.speichern(
            basis.copy(
                startZeit = startMillis,
                endeZeit = endeMillis,
                startAdresse = von.trim(),
                endeAdresse = nach.trim(),
                zwischenziele = Zwischenziele.schreiben(
                    zwischen.filter { it.adresse.isNotBlank() }.map { it.copy(adresse = it.adresse.trim()) }
                ),
                distanzMeter = meter,
                nachtragMeter = if (kmGleich) basis.nachtragMeter else 0.0,
                nachtragMs = if (abfahrtGleich) basis.nachtragMs else 0,
                kategorieId = kategorieId,
                notiz = notiz.trim(),
                status = FahrtStatus.FERTIG,
            ),
            zurueck,
        )
    }

    if (loeschenFragen) {
        AlertDialog(
            onDismissRequest = { loeschenFragen = false },
            title = { Text("Fahrt löschen?") },
            text = { Text("Die Fahrt wird endgültig entfernt.") },
            confirmButton = { TextButton(onClick = { vm.loeschen(fahrtId, zurueck) }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { loeschenFragen = false }) { Text("Abbrechen") } },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(if (fahrtId == 0L) "Fahrt nachtragen" else "Fahrt bearbeiten") },
                navigationIcon = {
                    IconButton(onClick = zurueck) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                },
                actions = {
                    if (fahrtId != 0L) {
                        IconButton(onClick = { loeschenFragen = true }) { Icon(Icons.Filled.Delete, "Löschen") }
                    }
                    TextButton(onClick = ::speichern, enabled = gueltig) { Text("Speichern") }
                },
            )
        },
    ) { innen ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innen)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Kategorie zuerst: der häufigste Grund, eine Fahrt nachträglich zu öffnen
            Text("Kategorie", style = MaterialTheme.typography.titleSmall)
            kategorien.forEach { k ->
                FilterChip(
                    selected = kategorieId == k.id,
                    onClick = { kategorieId = k.id },
                    label = { Text(k.name) },
                    leadingIcon = { Farbpunkt(kategorieFarbe(k.farbe)) },
                    colors = FilterChipDefaults.filterChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // Ausgeblendete Kategorie einer alten Fahrt bleibt erhalten
            if (kategorieId != null && kategorien.none { it.id == kategorieId }) {
                Text(
                    "Bisherige Kategorie ist ausgeblendet – bleibt erhalten, solange keine andere gewählt wird.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("Details", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            OutlinedButton(onClick = { datumWaehlen(context, datum) { datum = it } }, modifier = Modifier.fillMaxWidth()) {
                Text("Datum: ${Format.datum(datum)}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { zeitWaehlen(context, abfahrt) { abfahrt = it } }, modifier = Modifier.weight(1f)) {
                    Text("Abfahrt: %02d:%02d".format(abfahrt.hour, abfahrt.minute))
                }
                OutlinedButton(onClick = { zeitWaehlen(context, ankunft) { ankunft = it } }, modifier = Modifier.weight(1f)) {
                    Text("Ankunft: %02d:%02d".format(ankunft.hour, ankunft.minute))
                }
            }
            AdressFeld(von, { von = it }, "Von", orte)
            zwischen.forEachIndexed { i, z ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AdressFeld(
                        z.adresse, { zwischen[i] = z.copy(adresse = it) }, "Über (Zwischenziel ${i + 1})", orte,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { zwischen.removeAt(i) }) { Icon(Icons.Filled.Close, "Zwischenziel entfernen") }
                }
            }
            TextButton(onClick = { zwischen.add(Zwischenziel(adresse = "")) }) {
                Icon(Icons.Filled.Add, null)
                Text("Zwischenziel hinzufügen")
            }
            AdressFeld(nach, { nach = it }, "Nach", orte)
            OutlinedTextField(
                kmText, { kmText = it },
                label = { Text("Kilometer") },
                isError = kmText.isNotEmpty() && km == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(notiz, { notiz = it }, label = { Text("Zweck / Notiz") }, modifier = Modifier.fillMaxWidth())

            Button(onClick = ::speichern, enabled = gueltig, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Speichern")
            }
        }
    }
}
