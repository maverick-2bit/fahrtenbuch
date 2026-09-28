package at.zweibit.fahrtenbuch.ui.fahrt

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.zweibit.fahrtenbuch.ui.Abschnitt
import at.zweibit.fahrtenbuch.ui.Farbpunkt
import at.zweibit.fahrtenbuch.ui.HauptViewModel
import at.zweibit.fahrtenbuch.ui.StartGruen
import at.zweibit.fahrtenbuch.ui.StoppRot
import at.zweibit.fahrtenbuch.ui.kategorieFarbe
import at.zweibit.fahrtenbuch.ui.rememberStandortEinschalten
import at.zweibit.fahrtenbuch.blitzer.BlitzerText
import at.zweibit.fahrtenbuch.tracking.LiveStatus
import at.zweibit.fahrtenbuch.update.UpdateStatus
import at.zweibit.fahrtenbuch.update.Updater
import at.zweibit.fahrtenbuch.util.Format
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import at.zweibit.fahrtenbuch.data.Ort
import at.zweibit.fahrtenbuch.data.pausiert
import at.zweibit.fahrtenbuch.data.streckeText
import at.zweibit.fahrtenbuch.data.zwischenzieleListe
import at.zweibit.fahrtenbuch.ui.AdresseAendernDialog
import kotlinx.coroutines.delay

/** Eine Adresszeile der laufenden Fahrt mit „Ändern“-Knopf. */
@Composable
private fun AdressZeile(titel: String, adresse: String, aendern: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titel, style = MaterialTheme.typography.labelMedium)
            Text(adresse, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = aendern) { Text("Ändern") }
    }
}

/** Vor der Fahrt: Start per GPS oder an einem gespeicherten Ort (z. B. wenn der Start vergessen wurde). */
@Composable
private fun StartAuswahl(orte: List<Ort>, gewaehlt: Ort?, waehlen: (Ort?) -> Unit) {
    if (orte.isEmpty()) {
        Text(
            "Tipp: Unter „Einstellungen“ kannst du Orte wie Zuhause oder Büro speichern. " +
                "Sie werden dann automatisch erkannt und sind als Start wählbar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    var offen by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { offen = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Start: " + (gewaehlt?.name?.ifBlank { gewaehlt.adresse } ?: "Aktueller Standort (GPS)"),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = offen, onDismissRequest = { offen = false }) {
            DropdownMenuItem(text = { Text("Aktueller Standort (GPS)") }, onClick = {
                waehlen(null)
                offen = false
            })
            orte.forEach { o ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(o.name.ifBlank { o.adresse })
                            if (o.name.isNotBlank()) Text(o.adresse, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    },
                    onClick = {
                        waehlen(o)
                        offen = false
                    },
                )
            }
        }
    }
}

private fun erlaubt(context: Context, recht: String) =
    ContextCompat.checkSelfPermission(context, recht) == PackageManager.PERMISSION_GRANTED

@Composable
fun FahrtScreen(vm: HauptViewModel, zuordnen: (Long) -> Unit) {
    val context = LocalContext.current
    val laufend by vm.laufend.collectAsStateWithLifecycle()
    val offene by vm.offene.collectAsStateWithLifecycle()
    val monat by vm.dieserMonat.collectAsStateWithLifecycle()
    val einstellungen by vm.einstellungen.collectAsStateWithLifecycle()
    val live by LiveStatus.zustand.collectAsStateWithLifecycle()
    val update by Updater.status.collectAsStateWithLifecycle()
    val orte by vm.orte.collectAsStateWithLifecycle()
    val startOrt by vm.startOrt.collectAsStateWithLifecycle()
    var hinweis by remember { mutableStateOf<String?>(null) }
    var startAendern by remember { mutableStateOf(false) }
    var zwischenzielIndex by remember { mutableStateOf<Int?>(null) }

    val rechte = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val standortEinschalten = rememberStandortEinschalten()

    fun losfahren() {
        hinweis = null
        vm.starten()
        // Ist der Standort aus, erscheint „Standort aktivieren?“ – die Aufzeichnung wartet auf das erste GPS-Signal.
        standortEinschalten()
    }

    val anfrage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        when {
            erlaubt(context, Manifest.permission.ACCESS_FINE_LOCATION) -> losfahren()
            erlaubt(context, Manifest.permission.ACCESS_COARSE_LOCATION) ->
                hinweis = "Für die Streckenmessung wird der genaue Standort benötigt. Bitte in den App-Einstellungen „Genauen Standort verwenden“ aktivieren."
            else ->
                hinweis = "Ohne Standortfreigabe kann keine Fahrt aufgezeichnet werden."
        }
    }

    fun starten() {
        val fehlend = rechte.any { !erlaubt(context, it) }
        if (fehlend) anfrage.launch(rechte) else losfahren()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Fahrtenbuch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)

        val f = laufend
        if (f != null) {
            var jetzt by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(f.id) {
                while (true) {
                    jetzt = System.currentTimeMillis()
                    delay(1_000)
                }
            }
            val pause = f.pausiert
            val zwischen = f.zwischenzieleListe

            if (startAendern) {
                AdresseAendernDialog(
                    titel = "Startadresse ändern",
                    label = "Start",
                    anfang = f.startAdresse,
                    orte = orte,
                    hinweis = "Zum Beispiel, wenn du den Start zu spät gedrückt hast. Kilometer und Abfahrtszeit " +
                        "kannst du nach der Fahrt unter „Fahrten“ korrigieren.",
                    speichern = {
                        vm.startAdresseAendern(f.id, it)
                        startAendern = false
                    },
                    abbrechen = { startAendern = false },
                )
            }
            zwischenzielIndex?.let { i ->
                AdresseAendernDialog(
                    titel = "Zwischenziel ändern",
                    label = "Zwischenziel",
                    anfang = zwischen.getOrNull(i)?.adresse.orEmpty(),
                    orte = orte,
                    speichern = {
                        vm.zwischenzielAendern(f.id, i, it)
                        zwischenzielIndex = null
                    },
                    abbrechen = { zwischenzielIndex = null },
                )
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (pause) "Fahrt pausiert" else "Fahrt läuft", style = MaterialTheme.typography.titleMedium)
                    Text(Format.km(f.distanzMeter), fontSize = 44.sp, fontWeight = FontWeight.Bold)
                    Text("Dauer: ${Format.dauer(jetzt - f.startZeit)} · seit ${Format.uhrzeit(f.startZeit)}")
                    if (!pause) live.kmh?.let { Text("Tempo: $it km/h", fontWeight = FontWeight.Medium) }
                    AdressZeile("Start", f.startAdresse.ifBlank { "Standort wird ermittelt …" }) { startAendern = true }
                    zwischen.forEachIndexed { i, z ->
                        val aktuell = pause && i == zwischen.lastIndex
                        val zeit = z.an?.let { " · " + Format.uhrzeit(it) + (z.ab?.let { ab -> "–" + Format.uhrzeit(ab) } ?: "") } ?: ""
                        AdressZeile(
                            titel = (if (aktuell) "Zwischenziel" else "Über") + zeit,
                            adresse = z.adresse.ifBlank { "Adresse wird ermittelt …" },
                        ) { zwischenzielIndex = i }
                    }
                    Text(
                        when {
                            pause && live.auto != null -> "Während der Pause wird nicht aufgezeichnet. Fährst du mit „${live.auto}“ los, " +
                                "geht die Fahrt automatisch weiter."
                            pause -> "Während der Pause wird nicht aufgezeichnet, und die Fahrt endet nicht automatisch."
                            live.auto != null -> "Mit „${live.auto}“ verbunden: Stau oder Ampel beenden die Fahrt nicht. " +
                                "Für Hin- und Rückfahrt am Zwischenziel „Pause“ tippen."
                            einstellungen.autoStoppMinuten > 0 -> "Endet automatisch nach ${einstellungen.autoStoppMinuten} min Stillstand. " +
                                "Für Hin- und Rückfahrt am Zwischenziel „Pause“ tippen."
                            else -> "Für Hin- und Rückfahrt am Zwischenziel „Pause“ tippen."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (einstellungen.blitzerWarnung && !pause) {
                val w = live.voraus
                when {
                    w != null -> Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (w.zuSchnell) StoppRot else Color(0xFFFFB300),
                            contentColor = if (w.zuSchnell) Color.White else Color.Black,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, null, Modifier.size(36.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(BlitzerText.kurz(w), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                if (w.zuSchnell) Text("Zu schnell!", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    live.blitzerAktiv -> Text(
                        "Blitzer-Warnung aktiv",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Text(
                        "Blitzer-Warnung: noch keine Blitzerdaten geladen (Einstellungen).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (pause) {
                    Button(
                        onClick = vm::weiterfahren,
                        colors = ButtonDefaults.buttonColors(containerColor = StartGruen),
                        modifier = Modifier.weight(1.3f).height(72.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(30.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Weiterfahren", fontSize = 18.sp)
                    }
                } else {
                    Button(
                        onClick = vm::pausieren,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color.Black),
                        modifier = Modifier.weight(1f).height(72.dp),
                    ) {
                        Icon(Icons.Filled.Pause, null, Modifier.size(30.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pause", fontSize = 18.sp)
                    }
                }
                Button(
                    onClick = vm::stoppen,
                    colors = ButtonDefaults.buttonColors(containerColor = StoppRot),
                    modifier = Modifier.weight(1f).height(72.dp),
                ) {
                    Icon(Icons.Filled.Stop, null, Modifier.size(30.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Beenden", fontSize = 18.sp)
                }
            }
        } else {
            live.wartetAuf?.let { auto ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Mit „$auto“ verbunden", style = MaterialTheme.typography.titleMedium)
                        Text("Die Fahrt startet automatisch, sobald du losfährst.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = ::starten, modifier = Modifier.weight(1f)) { Text("Jetzt starten") }
                            OutlinedButton(onClick = vm::nichtAufzeichnen, modifier = Modifier.weight(1f)) { Text("Nicht aufzeichnen") }
                        }
                    }
                }
            }
            StartAuswahl(orte, startOrt) { vm.startOrt.value = it }
            Button(
                onClick = ::starten,
                colors = ButtonDefaults.buttonColors(containerColor = StartGruen),
                modifier = Modifier.fillMaxWidth().height(96.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(40.dp))
                Spacer(Modifier.width(8.dp))
                Text("Fahrt starten", fontSize = 24.sp)
            }
            Text(
                if (startOrt == null) "Die Startadresse wird per GPS erfasst, gespeicherte Orte werden erkannt. " +
                    "Am Ziel fragt die App, wie die Fahrt gespeichert werden soll."
                else "Start: ${startOrt!!.alsAdresse}. Am Ziel fragt die App, wie die Fahrt gespeichert werden soll.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        (update as? UpdateStatus.Verfuegbar)?.let { u ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(
                    "Update auf Version ${u.info.version} verfügbar – installieren unter Einstellungen.",
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        hinweis?.let { text ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null)
                        Spacer(Modifier.width(8.dp))
                        Text(text)
                    }
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        )
                    }) { Text("App-Einstellungen öffnen") }
                }
            }
        }

        if (offene.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Abschnitt("Noch nicht zugeordnet (${offene.size})")
                    offene.forEach { o ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { zuordnen(o.id) }
                                .padding(vertical = 6.dp)
                        ) {
                            Text(
                                "${Format.datumKurz(o.startZeit)} · ${Format.uhrzeit(o.startZeit)} · ${Format.km(o.distanzMeter)}",
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                o.streckeText,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Abschnitt("Dieser Monat")
                if (monat.isEmpty()) {
                    Text("Noch keine Fahrten.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                monat.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Farbpunkt(kategorieFarbe(m.kategorie?.farbe))
                        Spacer(Modifier.width(10.dp))
                        Text(m.kategorie?.name ?: "Ohne Kategorie", Modifier.weight(1f))
                        Text("${m.anzahl}×", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        Text(Format.km(m.meter), fontWeight = FontWeight.Medium)
                    }
                }
                if (monat.size > 1) {
                    HorizontalDivider()
                    Row {
                        Text("Gesamt", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text(Format.km(monat.sumOf { it.meter }), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
