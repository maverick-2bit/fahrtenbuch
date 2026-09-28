package at.zweibit.fahrtenbuch.ui.einstellungen

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.zweibit.fahrtenbuch.FahrtenbuchApp
import at.zweibit.fahrtenbuch.data.Ort
import at.zweibit.fahrtenbuch.data.Orte
import at.zweibit.fahrtenbuch.tracking.Adressen
import at.zweibit.fahrtenbuch.ui.Abschnitt
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

@SuppressLint("MissingPermission")
private suspend fun aktuellePosition(context: Context): Location? {
    val erlaubt = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (!erlaubt) return null
    return withTimeoutOrNull(20_000) {
        runCatching {
            LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(
                CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(10_000)
                    .build(),
                null,
            ).await()
        }.getOrNull()
    }
}

/** Gespeicherte Orte: Zuhause, Büro, Stammkunden … */
@Composable
fun OrteKarte() {
    val context = LocalContext.current
    val app = context.applicationContext as FahrtenbuchApp
    val orte by app.einstellungen.orte.collectAsStateWithLifecycle(initialValue = emptyList())
    var bearbeiten by remember { mutableStateOf<Ort?>(null) }
    var neu by remember { mutableStateOf(false) }
    var loeschen by remember { mutableStateOf<Ort?>(null) }

    // Schreiben im App-Bereich, damit ein Bildschirmwechsel das Speichern nicht abbricht
    fun speichern(liste: List<Ort>) {
        app.appScope.launch { app.einstellungen.orteSpeichern(liste) }
    }

    if (neu || bearbeiten != null) {
        OrtDialog(
            ort = bearbeiten,
            fertig = { o ->
                speichern(if (orte.any { it.id == o.id }) orte.map { if (it.id == o.id) o else it } else orte + o)
                neu = false
                bearbeiten = null
            },
            abbrechen = {
                neu = false
                bearbeiten = null
            },
        )
    }
    loeschen?.let { o ->
        AlertDialog(
            onDismissRequest = { loeschen = null },
            title = { Text("Ort „${o.name.ifBlank { o.adresse }}“ löschen?") },
            text = { Text("Bereits gespeicherte Fahrten behalten ihre Adressen.") },
            confirmButton = {
                TextButton(onClick = {
                    speichern(orte.filterNot { it.id == o.id })
                    loeschen = null
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { loeschen = null }) { Text("Abbrechen") } },
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Abschnitt("Orte")
            Text(
                "Zuhause, Büro, Stammkunden … Orte mit Standort werden an Start, Zwischenziel und Ziel automatisch erkannt " +
                    "(Umkreis ${Orte.ERKENNUNG_M.toInt()} m). Alle Orte sind außerdem als Start und in jedem Adressfeld wählbar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            orte.forEach { o ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { bearbeiten = o }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Place, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(o.name.ifBlank { o.adresse }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (o.name.isNotBlank()) {
                            Text(o.adresse, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (!o.hatKoordinaten) {
                            Text(
                                "Ohne Standort – wird nicht automatisch erkannt",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    IconButton(onClick = { loeschen = o }) { Icon(Icons.Filled.Delete, "Löschen") }
                }
            }
            OutlinedButton(onClick = { neu = true }, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("Ort hinzufügen")
            }
        }
    }
}

@Composable
private fun OrtDialog(ort: Ort?, fertig: (Ort) -> Unit, abbrechen: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(ort?.name ?: "") }
    var adresse by remember { mutableStateOf(ort?.adresse ?: "") }
    var lat by remember { mutableStateOf(ort?.lat) }
    var lon by remember { mutableStateOf(ort?.lon) }
    /** Adresse, zu der die gespeicherten Koordinaten gehören. */
    var koordinatenFuer by remember { mutableStateOf(if (ort?.hatKoordinaten == true) ort.adresse else null) }
    var arbeitet by remember { mutableStateOf(false) }
    var meldung by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = abbrechen,
        title = { Text(if (ort == null) "Neuer Ort" else "Ort bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    name, { name = it },
                    label = { Text("Name, z. B. Zuhause") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    adresse, { adresse = it },
                    label = { Text("Adresse") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            arbeitet = true
                            meldung = null
                            val pos = aktuellePosition(context)
                            if (pos == null) {
                                meldung = "Kein Standort verfügbar. Ist GPS eingeschaltet?"
                            } else {
                                lat = pos.latitude
                                lon = pos.longitude
                                adresse = Adressen.ermitteln(context, pos.latitude, pos.longitude)
                                koordinatenFuer = adresse
                                meldung = "Standort übernommen."
                            }
                            arbeitet = false
                        }
                    },
                    enabled = !arbeitet,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.MyLocation, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Aktuellen Standort übernehmen")
                }
                if (arbeitet) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    if (lat != null && koordinatenFuer == adresse) "Wird automatisch erkannt."
                    else "Beim Speichern sucht die App den Standort zur Adresse.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                meldung?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        arbeitet = true
                        var la = lat
                        var lo = lon
                        // Adresse von Hand eingegeben oder geändert: Standort dazu suchen
                        if (la == null || koordinatenFuer != adresse) {
                            Adressen.koordinaten(context, adresse)?.let { (a, b) ->
                                la = a
                                lo = b
                            }
                        }
                        arbeitet = false
                        fertig(
                            (ort ?: Ort(name = "", adresse = "")).copy(
                                name = name.trim(), adresse = adresse.trim(), lat = la, lon = lo,
                            )
                        )
                    }
                },
                enabled = !arbeitet && (name.isNotBlank() || adresse.isNotBlank()),
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = abbrechen) { Text("Abbrechen") } },
    )
}
