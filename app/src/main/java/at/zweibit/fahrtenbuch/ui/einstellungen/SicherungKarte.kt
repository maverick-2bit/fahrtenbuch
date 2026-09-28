package at.zweibit.fahrtenbuch.ui.einstellungen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.zweibit.fahrtenbuch.FahrtenbuchApp
import at.zweibit.fahrtenbuch.data.PrivatStand
import at.zweibit.fahrtenbuch.data.SyncStand
import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import at.zweibit.fahrtenbuch.sync.Krypto
import at.zweibit.fahrtenbuch.sync.PrivatSchutz
import at.zweibit.fahrtenbuch.sync.Sicherung
import at.zweibit.fahrtenbuch.sync.SyncErgebnis
import at.zweibit.fahrtenbuch.sync.SyncFormat
import at.zweibit.fahrtenbuch.sync.SyncPlaner
import at.zweibit.fahrtenbuch.sync.Verbindung
import at.zweibit.fahrtenbuch.ui.Abschnitt
import at.zweibit.fahrtenbuch.util.Format
import kotlinx.coroutines.launch
import java.net.URI

private fun hostVon(server: String) = runCatching { URI(server).host }.getOrNull() ?: server

/** Einstellungen: Verbindung zur Online-Sicherung (Fahrtenbuch-Verwaltung). */
@Composable
fun SicherungKarte() {
    val context = LocalContext.current
    val app = context.applicationContext as FahrtenbuchApp
    val stand by app.einstellungen.sync.collectAsStateWithLifecycle(initialValue = SyncStand())
    val offen by app.repository.protokollOffen.collectAsStateWithLifecycle(initialValue = 0)
    val abgelehnt by app.repository.protokollAbgelehnt.collectAsStateWithLifecycle(initialValue = 0)
    val scope = rememberCoroutineScope()
    var eingabe by remember { mutableStateOf(false) }
    var verbindung by remember { mutableStateOf<Verbindung?>(null) }
    var trennen by remember { mutableStateOf(false) }
    var laeuft by remember { mutableStateOf(false) }
    var meldung by remember { mutableStateOf<String?>(null) }
    val privat by app.einstellungen.privat.collectAsStateWithLifecycle(initialValue = PrivatStand())
    val kategorien by app.repository.kategorienFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var pinDialog by remember { mutableStateOf(false) }

    if (pinDialog) PinDialog(neu = !privat.hatPin) { pinDialog = false }

    if (eingabe) {
        CodeEingabeDialog(
            weiter = {
                eingabe = false
                verbindung = it
            },
            abbrechen = { eingabe = false },
        )
    }
    verbindung?.let { VerbindenDialog(it) { verbindung = null } }
    if (trennen) {
        AlertDialog(
            onDismissRequest = { trennen = false },
            title = { Text("Online-Sicherung trennen?") },
            text = { Text("Neue Fahrten werden dann nicht mehr gesichert. Die bereits gesicherten bleiben auf dem Server.") },
            confirmButton = {
                TextButton(onClick = {
                    trennen = false
                    app.appScope.launch {
                        app.einstellungen.syncTrennen()
                        SyncPlaner.beenden(app)
                    }
                }) { Text("Trennen") }
            },
            dismissButton = { TextButton(onClick = { trennen = false }) { Text("Abbrechen") } },
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Abschnitt("Online-Sicherung")
            if (!stand.verbunden) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text("Nicht verbunden")
                }
                Text(
                    "Sichert alle Fahrten samt Änderungsprotokoll auf dem Firmenserver – auch als Schutz bei Handyverlust. " +
                        "Zum Verbinden den QR-Code aus der Fahrtenbuch-Verwaltung mit der Handy-Kamera scannen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { eingabe = true }, modifier = Modifier.fillMaxWidth()) { Text("Link oder Code einfügen") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CloudDone, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Verbunden als ${stand.fahrer}", fontWeight = FontWeight.SemiBold)
                        Text(hostVon(stand.server), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    if (stand.zuletzt > 0) "Zuletzt gesichert: ${Format.datum(stand.zuletzt)}, ${Format.uhrzeit(stand.zuletzt)}"
                    else "Noch nicht gesichert",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (offen > 0) Text("Warten auf Übertragung: $offen Änderungen", style = MaterialTheme.typography.bodySmall)
                if (abgelehnt > 0) {
                    Text(
                        "$abgelehnt Änderungen hat der Server abgelehnt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (stand.meldung.isNotBlank()) {
                    Text(stand.meldung, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                meldung?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (kategorien.any { it.privat } || privat.hatPin) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(Icons.Filled.Lock, null, tint = if (privat.hatPin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (privat.hatPin) "PIN für Privatfahrten festgelegt" else "Kein PIN für Privatfahrten")
                            Text(
                                if (privat.hatPin) "Details deiner Privatfahrten sind nur mit deinem PIN lesbar."
                                else "Privatfahrten werden erst gesichert, wenn du einen PIN festlegst.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (privat.hatPin) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            )
                        }
                        TextButton(onClick = { pinDialog = true }) { Text(if (privat.hatPin) "Ändern" else "Festlegen") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            laeuft = true
                            meldung = null
                            scope.launch {
                                meldung = when (val e = Sicherung.synchronisieren(app)) {
                                    is SyncErgebnis.Ok -> when {
                                        e.wartenAufPin > 0 -> "${e.gesendet} Änderungen gesichert, ${e.wartenAufPin} warten auf deinen PIN."
                                        e.gesendet > 0 -> "${e.gesendet} Änderungen gesichert."
                                        else -> "Alles gesichert."
                                    }
                                    is SyncErgebnis.Fehler -> null
                                    is SyncErgebnis.Abgewiesen -> null
                                    SyncErgebnis.NichtVerbunden -> null
                                }
                                laeuft = false
                            }
                        },
                        enabled = !laeuft,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (laeuft) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Jetzt sichern")
                    }
                    OutlinedButton(onClick = { trennen = true }, modifier = Modifier.weight(1f)) { Text("Trennen") }
                }
            }
        }
    }
}

@Composable
private fun CodeEingabeDialog(weiter: (Verbindung) -> Unit, abbrechen: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val v = SyncFormat.verbindungLesen(text)
    AlertDialog(
        onDismissRequest = abbrechen,
        title = { Text("Online-Sicherung verbinden") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Den Verbindungs-Link aus der Fahrtenbuch-Verwaltung oder den Code einfügen.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    text, { text = it },
                    label = { Text("Link oder Code") },
                    isError = text.isNotBlank() && v == null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { v?.let(weiter) }, enabled = v != null) { Text("Weiter") } },
        dismissButton = { TextButton(onClick = abbrechen) { Text("Abbrechen") } },
    )
}

/**
 * Bestätigung vor dem Verbinden: prüft den Code beim Server, speichert die Verbindung und
 * startet die erste Sicherung (inklusive aller bisherigen Fahrten).
 */
@Composable
fun VerbindenDialog(v: Verbindung, fertig: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as FahrtenbuchApp
    val scope = rememberCoroutineScope()
    var zustand by remember(v) { mutableStateOf<String?>(null) }
    var laeuft by remember(v) { mutableStateOf(false) }
    var verbundenAls by remember(v) { mutableStateOf<String?>(null) }
    val bisher by app.einstellungen.sync.collectAsStateWithLifecycle(initialValue = SyncStand())
    val privat by app.einstellungen.privat.collectAsStateWithLifecycle(initialValue = PrivatStand())
    val kategorien by app.repository.kategorienFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val brauchtPin = kategorien.any { it.privat } && !privat.hatPin
    var pinDialog by remember(v) { mutableStateOf(false) }
    if (pinDialog) PinDialog(neu = true) { pinDialog = false }

    AlertDialog(
        onDismissRequest = { if (!laeuft) fertig() },
        title = { Text(if (verbundenAls != null) "Verbunden" else "Mit der Online-Sicherung verbinden?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (verbundenAls != null) {
                    Text("Verbunden als $verbundenAls. Alle Fahrten werden jetzt gesichert, neue ab sofort automatisch.")
                    if (brauchtPin) {
                        Text(
                            "Lege jetzt deinen PIN für Privatfahrten fest. Bis dahin bleiben Privatfahrten nur auf dem Handy.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    Text("Server: ${hostVon(v.server)}")
                    Text(
                        "Danach werden alle Fahrten samt Änderungsprotokoll dort gesichert – auch die bisherigen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (bisher.verbunden) {
                        Text(
                            "Die bestehende Verbindung als ${bisher.fahrer} wird dabei ersetzt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (laeuft) CircularProgressIndicator(Modifier.size(24.dp))
                    zustand?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            if (verbundenAls != null) {
                if (brauchtPin) TextButton(onClick = { pinDialog = true }) { Text("PIN festlegen") }
                else TextButton(onClick = fertig) { Text("Fertig") }
            } else {
                TextButton(
                    enabled = !laeuft,
                    onClick = {
                        laeuft = true
                        zustand = null
                        scope.launch {
                            Sicherung.pruefen(v)
                                .onSuccess { name ->
                                    app.einstellungen.syncVerbinden(v.server, v.code, name)
                                    SyncPlaner.regelmaessig(app)
                                    SyncPlaner.bald(app)
                                    verbundenAls = name
                                }
                                .onFailure { zustand = it.message ?: "Verbindung fehlgeschlagen" }
                            laeuft = false
                        }
                    },
                ) { Text("Verbinden") }
            }
        },
        dismissButton = {
            if (verbundenAls == null) TextButton(onClick = fertig, enabled = !laeuft) { Text("Abbrechen") }
            else if (brauchtPin) TextButton(onClick = fertig) { Text("Später") }
        },
    )
    LaunchedEffect(verbundenAls) {
        // Erste Sicherung gleich im Vordergrund anstoßen, damit „Zuletzt gesichert“ sofort stimmt
        if (verbundenAls != null) app.appScope.launch { Sicherung.synchronisieren(app) }
    }
}

/**
 * PIN für Privatfahrten festlegen oder ändern. Der PIN wird nicht gespeichert; aus ihm entsteht nur
 * die Hülle, mit der die Verwaltungsseite nach Eingabe desselben PINs die Privatfahrten entschlüsselt.
 */
@Composable
fun PinDialog(neu: Boolean, fertig: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as FahrtenbuchApp
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var wiederholung by remember { mutableStateOf("") }
    var laeuft by remember { mutableStateOf(false) }
    var fehler by remember { mutableStateOf<String?>(null) }
    val gueltig = pin.length >= Krypto.MIN_PIN && pin.all { it.isDigit() } && pin == wiederholung

    AlertDialog(
        onDismissRequest = { if (!laeuft) fertig() },
        title = { Text(if (neu) "PIN für Privatfahrten festlegen" else "PIN für Privatfahrten ändern") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Mit diesem PIN kannst nur du auf der Verwaltungsseite die Adressen deiner Privatfahrten sehen. " +
                        "Er wird nirgends gespeichert. Vergisst du ihn, sind die Details nur noch hier in der App lesbar.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Mindestens ${Krypto.MIN_PIN} Ziffern, 8 oder mehr sind deutlich sicherer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    pin, { if (it.all(Char::isDigit)) pin = it },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    wiederholung, { if (it.all(Char::isDigit)) wiederholung = it },
                    label = { Text("PIN wiederholen") },
                    singleLine = true,
                    isError = wiederholung.isNotEmpty() && wiederholung != pin,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (laeuft) CircularProgressIndicator(Modifier.size(24.dp))
                fehler?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = gueltig && !laeuft,
                onClick = {
                    laeuft = true
                    scope.launch {
                        runCatching { PrivatSchutz.pinFestlegen(app, pin) }
                            .onSuccess { fertig() }
                            .onFailure { fehler = it.message ?: "PIN konnte nicht gespeichert werden" }
                        laeuft = false
                    }
                },
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = fertig, enabled = !laeuft) { Text("Abbrechen") } },
    )
}
