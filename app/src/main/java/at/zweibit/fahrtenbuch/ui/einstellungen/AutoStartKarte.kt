package at.zweibit.fahrtenbuch.ui.einstellungen

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.zweibit.fahrtenbuch.FahrtenbuchApp
import at.zweibit.fahrtenbuch.data.AutoStartStand
import at.zweibit.fahrtenbuch.data.BtGeraet
import at.zweibit.fahrtenbuch.tracking.AutoStart
import at.zweibit.fahrtenbuch.ui.Abschnitt
import kotlinx.coroutines.launch

private fun appEinstellungenOeffnen(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }
}

@SuppressLint("BatteryLife")
private fun akkuOptimierungAus(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
    }.onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
}

/** Eine Freigabe mit Häkchen; fehlt sie, gibt es einen Knopf zum Erteilen. */
@Composable
private fun Freigabe(text: String, ok: Boolean, erteilen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = if (ok) "erteilt" else "fehlt",
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!ok) TextButton(onClick = erteilen) { Text("Erteilen") }
    }
}

/**
 * Einstellungen: Fahrt automatisch starten, wenn sich das Handy mit dem Auto verbindet (Bluetooth) und
 * das Auto losfährt. Zeigt, welche Freigaben Android dafür verlangt.
 */
@Composable
fun AutoStartKarte() {
    val context = LocalContext.current
    val app = context.applicationContext as FahrtenbuchApp
    val scope = rememberCoroutineScope()
    val stand by app.einstellungen.autoStart.collectAsStateWithLifecycle(initialValue = AutoStartStand())
    var rechte by remember { mutableStateOf(AutoStart.rechte(context)) }
    var gekoppelt by remember { mutableStateOf(AutoStart.gekoppelteGeraete(context)) }
    var bluetoothAn by remember { mutableStateOf(AutoStart.bluetoothAn(context)) }
    var hintergrundErklaeren by remember { mutableStateOf(false) }

    fun neuPruefen() {
        rechte = AutoStart.rechte(context)
        gekoppelt = AutoStart.gekoppelteGeraete(context)
        bluetoothAn = AutoStart.bluetoothAn(context)
    }

    // Nach Rückkehr aus den Systemeinstellungen neu prüfen
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) neuPruefen() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val einzelAnfrage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { neuPruefen() }
    val standortAnfrage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { neuPruefen() }

    fun speichern(neu: AutoStartStand) = scope.launch { app.einstellungen.autoStartSpeichern(neu) }

    fun bluetoothFreigeben() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) einzelAnfrage.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    val immer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.packageManager.backgroundPermissionOptionLabel.toString()
    } else "Immer zulassen"

    if (hintergrundErklaeren) {
        AlertDialog(
            onDismissRequest = { hintergrundErklaeren = false },
            title = { Text("Standort auch im Hintergrund") },
            text = {
                Text(
                    "Damit die Aufzeichnung startet, während das Handy in der Tasche oder Halterung liegt, braucht die App den Standort " +
                        "auch im Hintergrund. Wähle auf der folgenden Seite bei „Standort“ die Option „$immer“.\n\n" +
                        "Die App nutzt den Standort nur während einer Fahrt und kurz nach dem Verbinden mit dem Auto, um das Losfahren zu erkennen.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    hintergrundErklaeren = false
                    when {
                        !rechte.standort -> standortAnfrage.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        )
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> einzelAnfrage.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                }) { Text("Weiter") }
            },
            dismissButton = { TextButton(onClick = { hintergrundErklaeren = false }) { Text("Abbrechen") } },
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Abschnitt("Automatisch starten")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Fahrt beim Einsteigen starten")
                    Text(
                        "Sobald sich das Handy mit dem Auto verbindet (Bluetooth) und du losfährst.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = stand.aktiv,
                    onCheckedChange = { an ->
                        speichern(stand.copy(aktiv = an))
                        if (an && !rechte.bluetooth) bluetoothFreigeben()
                    },
                )
            }
            if (!stand.aktiv) return@Column

            Text("Auto", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            when {
                !rechte.bluetooth -> {
                    Text(
                        "Zum Anzeigen der gekoppelten Geräte braucht die App die Freigabe „Geräte in der Nähe“.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = ::bluetoothFreigeben) { Text("Freigabe erteilen") }
                }
                !bluetoothAn -> Text(
                    "Bluetooth ist ausgeschaltet – zum Auswählen bitte einschalten.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                gekoppelt.isEmpty() -> Text(
                    "Keine gekoppelten Geräte. Koppel das Handy zuerst in den Bluetooth-Einstellungen mit dem Auto.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Gewählte Geräte, die nicht (mehr) gekoppelt sind, bleiben sichtbar, damit man sie abwählen kann
            val alle: List<BtGeraet> = gekoppelt + stand.geraete.filter { g -> gekoppelt.none { it.adresse.equals(g.adresse, ignoreCase = true) } }
            alle.forEach { g ->
                val gewaehlt = stand.geraet(g.adresse) != null
                val umschalten = {
                    speichern(
                        stand.copy(
                            geraete = if (gewaehlt) stand.geraete.filterNot { it.adresse.equals(g.adresse, ignoreCase = true) }
                            else stand.geraete + g,
                        )
                    )
                }
                Row(Modifier.fillMaxWidth().clickable { umschalten() }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = gewaehlt, onCheckedChange = { umschalten() })
                    Column(Modifier.weight(1f)) {
                        Text(g.name)
                        if (gekoppelt.none { it.adresse.equals(g.adresse, ignoreCase = true) }) {
                            Text("nicht gekoppelt oder Bluetooth aus", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (stand.geraete.isEmpty() && gekoppelt.isNotEmpty()) {
                Text(
                    "Wähle dein Auto aus (meist die Freisprecheinrichtung, z. B. „Audi MMI“ oder „VW Radio“).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text("Freigaben", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            Freigabe("Geräte in der Nähe (Bluetooth)", rechte.bluetooth, ::bluetoothFreigeben)
            Freigabe("Standort: „$immer“", rechte.standort && rechte.hintergrund) { hintergrundErklaeren = true }
            Freigabe("Akku-Optimierung aus", rechte.akku) { akkuOptimierungAus(context) }
            Freigabe("Benachrichtigungen", rechte.benachrichtigung) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) einzelAnfrage.launch(Manifest.permission.POST_NOTIFICATIONS)
                else appEinstellungenOeffnen(context)
            }
            Text(
                if (rechte.vollstaendig) "Alles bereit: Beim Einsteigen startet die Aufzeichnung von selbst, sobald du losfährst."
                else "Solange eine Freigabe fehlt, fragt die App beim Verbinden mit dem Auto per Benachrichtigung, ob die Fahrt starten soll.",
                style = MaterialTheme.typography.bodySmall,
                color = if (rechte.vollstaendig) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!rechte.vollstaendig) {
                TextButton(onClick = { appEinstellungenOeffnen(context) }) { Text("App-Einstellungen von Android öffnen") }
            }
            Text(
                "Solange das Handy mit dem Auto verbunden ist, endet eine Fahrt nicht wegen Stillstand (Stau, Ampel). " +
                    "Nach dem Trennen gilt wieder das automatische Ende, als Ende zählt der Zeitpunkt des Anhaltens. " +
                    "Eine pausierte Fahrt geht beim Losfahren automatisch weiter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
