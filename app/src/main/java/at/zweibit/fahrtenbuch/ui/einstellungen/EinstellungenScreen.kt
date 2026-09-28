package at.zweibit.fahrtenbuch.ui.einstellungen

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.zweibit.fahrtenbuch.BuildConfig
import at.zweibit.fahrtenbuch.FahrtenbuchApp
import at.zweibit.fahrtenbuch.data.EinstellungenWerte
import at.zweibit.fahrtenbuch.data.Kategorie
import at.zweibit.fahrtenbuch.data.KategorieFarben
import at.zweibit.fahrtenbuch.ui.Abschnitt
import at.zweibit.fahrtenbuch.ui.Farbpunkt
import at.zweibit.fahrtenbuch.ui.datumWaehlen
import at.zweibit.fahrtenbuch.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.runtime.rememberCoroutineScope
import at.zweibit.fahrtenbuch.update.UpdateStatus
import at.zweibit.fahrtenbuch.update.Updater
import at.zweibit.fahrtenbuch.blitzer.BlitzerDaten
import at.zweibit.fahrtenbuch.blitzer.LadeStatus
import at.zweibit.fahrtenbuch.blitzer.Warnausgabe
import java.text.NumberFormat
import java.util.Locale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class EinstellungenViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FahrtenbuchApp
    private val repo = app.repository

    val kategorien: StateFlow<List<Kategorie>> =
        repo.kategorienFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val werte: StateFlow<EinstellungenWerte?> =
        app.einstellungen.werte.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun kategorieSpeichern(k: Kategorie?, name: String, farbe: Long) = viewModelScope.launch {
        if (k == null) repo.kategorieAnlegen(name, farbe) else repo.kategorieAendern(k.copy(name = name, farbe = farbe))
    }

    fun kategorieEntfernen(k: Kategorie) = viewModelScope.launch { repo.kategorieEntfernen(k) }

    fun verschieben(k: Kategorie, richtung: Int) = viewModelScope.launch {
        val liste = kategorien.value.toMutableList()
        val i = liste.indexOfFirst { it.id == k.id }
        val j = i + richtung
        if (i < 0 || j !in liste.indices) return@launch
        liste[i] = liste[j].also { liste[j] = liste[i] }
        liste.forEachIndexed { index, kat -> if (kat.sortierung != index) repo.kategorieAendern(kat.copy(sortierung = index)) }
    }

    fun speichern(neu: EinstellungenWerte) = viewModelScope.launch { app.einstellungen.speichern(neu) }
}

@Composable
fun EinstellungenScreen(vm: EinstellungenViewModel = viewModel()) {
    val context = LocalContext.current
    val kategorien by vm.kategorien.collectAsStateWithLifecycle()
    val werte by vm.werte.collectAsStateWithLifecycle()
    var bearbeiten by remember { mutableStateOf<Kategorie?>(null) }
    var neuAnlegen by remember { mutableStateOf(false) }
    var loeschen by remember { mutableStateOf<Kategorie?>(null) }

    if (neuAnlegen || bearbeiten != null) {
        KategorieBearbeitenDialog(
            kategorie = bearbeiten,
            vorschlagFarbe = KategorieFarben.PALETTE[kategorien.size % KategorieFarben.PALETTE.size],
            fertig = { name, farbe ->
                vm.kategorieSpeichern(bearbeiten, name, farbe)
                bearbeiten = null
                neuAnlegen = false
            },
            abbrechen = {
                bearbeiten = null
                neuAnlegen = false
            },
        )
    }
    loeschen?.let { k ->
        AlertDialog(
            onDismissRequest = { loeschen = null },
            title = { Text("Kategorie „${k.name}“ entfernen?") },
            text = { Text("Bereits zugeordnete Fahrten behalten die Kategorie in den Berichten. Für neue Fahrten steht sie nicht mehr zur Auswahl.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.kategorieEntfernen(k)
                    loeschen = null
                }) { Text("Entfernen") }
            },
            dismissButton = { TextButton(onClick = { loeschen = null }) { Text("Abbrechen") } },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Einstellungen", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)

        // ------------------------------------------------ Kategorien
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Abschnitt("Kategorien")
                Text(
                    "Stehen am Ende jeder Fahrt zur Auswahl. Die ersten drei erscheinen auch als Knöpfe in der Benachrichtigung. Antippen zum Bearbeiten.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                kategorien.forEachIndexed { i, k ->
                    Row(
                        Modifier.fillMaxWidth().clickable { bearbeiten = k },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Farbpunkt(Color(k.farbe), 16.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(k.name, Modifier.weight(1f))
                        IconButton(onClick = { vm.verschieben(k, -1) }, enabled = i > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, "Nach oben")
                        }
                        IconButton(onClick = { vm.verschieben(k, 1) }, enabled = i < kategorien.lastIndex) {
                            Icon(Icons.Filled.KeyboardArrowDown, "Nach unten")
                        }
                        IconButton(onClick = { loeschen = k }) { Icon(Icons.Filled.Delete, "Entfernen") }
                    }
                }
                OutlinedButton(onClick = { neuAnlegen = true }, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Kategorie hinzufügen")
                }
            }
        }

        // ------------------------------------------------ Orte
        OrteKarte()

        val w = werte ?: return@Column

        // ------------------------------------------------ Automatik
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Abschnitt("Fahrt automatisch beenden")
                Text(
                    "Nach dieser Zeit ohne Bewegung gilt das Ziel als erreicht. Als Ende zählt der Zeitpunkt des Anhaltens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 3, 5, 10, 15, 30).forEach { min ->
                        FilterChip(
                            selected = w.autoStoppMinuten == min,
                            onClick = { vm.speichern(w.copy(autoStoppMinuten = min)) },
                            label = { Text(if (min == 0) "Aus" else "$min min") },
                        )
                    }
                }
            }
        }

        // ------------------------------------------------ Blitzer
        BlitzerKarte(w, vm::speichern)

        // ------------------------------------------------ Fahrzeug & Kilometerstand
        FahrzeugKarte(w, vm::speichern)

        // ------------------------------------------------ Akku
        AkkuKarte()

        // ------------------------------------------------ Update
        UpdateKarte()

        Text(
            "Fahrtenbuch ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FahrzeugKarte(w: EinstellungenWerte, speichern: (EinstellungenWerte) -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var fahrzeug by remember(w) { mutableStateOf(w.fahrzeug) }
    var kennzeichen by remember(w) { mutableStateOf(w.kennzeichen) }
    var fahrer by remember(w) { mutableStateOf(w.fahrer) }
    var kmStand by remember(w) { mutableStateOf(if (w.kmStandStart > 0) w.kmStandStart.toString() else "") }
    var kmAb by remember(w) {
        mutableStateOf(
            if (w.kmStandAb > 0) Instant.ofEpochMilli(w.kmStandAb).atZone(zone).toLocalDate() else LocalDate.now()
        )
    }
    val kmZahl = kmStand.trim().replace(".", "").toIntOrNull()
    val kmGueltig = kmStand.isBlank() || (kmZahl != null && kmZahl >= 0)
    val neu = w.copy(
        fahrzeug = fahrzeug.trim(),
        kennzeichen = kennzeichen.trim(),
        fahrer = fahrer.trim(),
        kmStandStart = kmZahl ?: 0,
        kmStandAb = if ((kmZahl ?: 0) > 0) kmAb.atStartOfDay(zone).toInstant().toEpochMilli() else 0L,
    )

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Abschnitt("Fahrzeug & Fahrer (für Berichte)")
            OutlinedTextField(fahrzeug, { fahrzeug = it }, label = { Text("Fahrzeug") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(kennzeichen, { kennzeichen = it }, label = { Text("Kennzeichen") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(fahrer, { fahrer = it }, label = { Text("Fahrer") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Abschnitt("Kilometerstand", Modifier.padding(top = 8.dp))
            Text(
                "Optional: Tachostand zu einem Stichtag. Die Berichte schreiben den Kilometerstand dann für jede Fahrt fort.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                kmStand, { kmStand = it },
                label = { Text("Kilometerstand (km)") },
                isError = !kmGueltig,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { datumWaehlen(context, kmAb) { kmAb = it } }, modifier = Modifier.fillMaxWidth()) {
                Text("Stand am Beginn von ${Format.datum(kmAb)}")
            }
            Button(
                onClick = {
                    speichern(neu)
                    Toast.makeText(context, "Gespeichert", Toast.LENGTH_SHORT).show()
                },
                enabled = kmGueltig && neu != w,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Speichern") }
        }
    }
}

@Composable
private fun BlitzerKarte(w: EinstellungenWerte, speichern: (EinstellungenWerte) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as FahrtenbuchApp
    val info by BlitzerDaten.info.collectAsStateWithLifecycle()
    val status by BlitzerDaten.status.collectAsStateWithLifecycle()
    var test by remember { mutableStateOf<Warnausgabe?>(null) }
    LaunchedEffect(Unit) { BlitzerDaten.infoLaden(context) }
    DisposableEffect(Unit) { onDispose { test?.beenden() } }
    val zahl = remember { NumberFormat.getIntegerInstance(Locale.GERMANY) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Abschnitt("Blitzer-Warnung")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Vor fixen Blitzern warnen")
                    Text(
                        "Während einer aufgezeichneten Fahrt",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = w.blitzerWarnung,
                    onCheckedChange = { an ->
                        speichern(w.copy(blitzerWarnung = an))
                        if (an && BlitzerDaten.veraltet(info)) app.blitzerAktualisieren()
                    },
                )
            }
            if (w.blitzerWarnung) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sprachansage")
                        Text(
                            if (w.blitzerAnsage) "z. B. „Blitzer in 300 Metern, Tempo 50“" else "Nur Warnton",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = w.blitzerAnsage, onCheckedChange = { speichern(w.copy(blitzerAnsage = it)) })
                }
            }

            when (val s = status) {
                LadeStatus.Laedt -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Lade Blitzerdaten …")
                }
                is LadeStatus.Fehler -> Text("Laden fehlgeschlagen: ${s.text}", color = MaterialTheme.colorScheme.error)
                LadeStatus.Bereit -> Unit
            }
            Text(
                info?.let {
                    "${zahl.format(it.fix)} Blitzer, ${it.rotlicht} Rotlichtkameras, ${it.section} Section-Control-Abschnitte · Stand ${Format.datum(it.stand)}"
                } ?: "Noch keine Blitzerdaten geladen.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = { app.blitzerAktualisieren() },
                enabled = status != LadeStatus.Laedt,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Blitzerdaten aktualisieren") }
            if (w.blitzerWarnung) {
                OutlinedButton(
                    onClick = {
                        val t = test ?: Warnausgabe(context).also { test = it }
                        t.warnen("Blitzer in 300 Metern, Tempo 50", w.blitzerAnsage, dringend = false)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Ansage testen") }
            }
            Text(
                "Warnt nur vor fest installierten Blitzern in Österreich. Mobile Kontrollen sind nicht enthalten. " +
                    "In Deutschland und der Schweiz ist die Nutzung solcher Warnungen beim Fahren verboten.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Blitzerdaten © OpenStreetMap-Mitwirkende, Lizenz ODbL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UpdateKarte() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by Updater.status.collectAsStateWithLifecycle()
    val app = context.applicationContext as FahrtenbuchApp
    val laufend by app.repository.laufendeFahrt.collectAsStateWithLifecycle(initialValue = null)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Abschnitt("App-Update")
            Text("Installiert: Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            when (val s = status) {
                UpdateStatus.Pruefe -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Suche nach Updates …")
                }
                UpdateStatus.Aktuell -> Text("Die App ist auf dem neuesten Stand.", style = MaterialTheme.typography.bodySmall)
                is UpdateStatus.Fehler -> Text(s.text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                is UpdateStatus.Laedt -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Lade Version ${s.info.version} … ${s.prozent} %")
                    LinearProgressIndicator(progress = { s.prozent / 100f }, modifier = Modifier.fillMaxWidth())
                }
                is UpdateStatus.Verfuegbar -> {
                    Text("Version ${s.info.version} ist verfügbar.", fontWeight = FontWeight.SemiBold)
                    if (s.info.notizen.isNotBlank()) Text(s.info.notizen, style = MaterialTheme.typography.bodySmall)
                    if (laufend != null) {
                        Text(
                            "Bitte zuerst die laufende Fahrt beenden – das Update startet die App neu.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(
                        onClick = { scope.launch { Updater.installieren(context, s.info) } },
                        enabled = laufend == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Jetzt aktualisieren") }
                }
                UpdateStatus.Unbekannt -> Unit
            }
            if (status !is UpdateStatus.Verfuegbar && status !is UpdateStatus.Laedt) {
                OutlinedButton(
                    onClick = { scope.launch { Updater.pruefen(context) } },
                    enabled = status != UpdateStatus.Pruefe,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Nach Updates suchen") }
            }
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun AkkuKarte() {
    val context = LocalContext.current
    val pm = remember { context.getSystemService(PowerManager::class.java) }
    var befreit by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }

    // Nach Rückkehr aus den Systemeinstellungen neu prüfen
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) befreit = pm.isIgnoringBatteryOptimizations(context.packageName)
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Abschnitt("Akku-Optimierung")
            Text(
                if (befreit) "Die App ist von der Akku-Optimierung ausgenommen – die Aufzeichnung läuft zuverlässig im Hintergrund."
                else "Manche Handys (v. a. Samsung, Xiaomi, Huawei) beenden Hintergrund-Apps aggressiv. Für eine lückenlose Aufzeichnung die App von der Akku-Optimierung ausnehmen.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!befreit) {
                OutlinedButton(onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                    runCatching { context.startActivity(intent) }
                        .onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }) { Text("Akku-Optimierung ausschalten") }
            }
        }
    }
}

@Composable
private fun KategorieBearbeitenDialog(
    kategorie: Kategorie?,
    vorschlagFarbe: Long,
    fertig: (String, Long) -> Unit,
    abbrechen: () -> Unit,
) {
    var name by remember { mutableStateOf(kategorie?.name ?: "") }
    var farbe by remember { mutableStateOf(kategorie?.farbe ?: vorschlagFarbe) }
    AlertDialog(
        onDismissRequest = abbrechen,
        title = { Text(if (kategorie == null) "Neue Kategorie" else "Kategorie bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Farbe", style = MaterialTheme.typography.labelLarge)
                KategorieFarben.PALETTE.chunked(5).forEach { reihe ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        reihe.forEach { f ->
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .border(
                                        width = if (f == farbe) 3.dp else 0.dp,
                                        color = if (f == farbe) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                        shape = CircleShape,
                                    )
                                    .padding(5.dp)
                                    .clickable { farbe = f },
                                contentAlignment = Alignment.Center,
                            ) { Farbpunkt(Color(f), 30.dp) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { fertig(name.trim(), farbe) }, enabled = name.isNotBlank()) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = abbrechen) { Text("Abbrechen") } },
    )
}
