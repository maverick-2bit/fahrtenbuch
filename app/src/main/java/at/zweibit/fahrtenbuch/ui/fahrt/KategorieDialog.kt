package at.zweibit.fahrtenbuch.ui.fahrt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import at.zweibit.fahrtenbuch.data.Fahrt
import at.zweibit.fahrtenbuch.data.Kategorie
import at.zweibit.fahrtenbuch.ui.kategorieFarbe
import at.zweibit.fahrtenbuch.ui.schriftAuf
import androidx.compose.runtime.toMutableStateList
import at.zweibit.fahrtenbuch.data.Ort
import at.zweibit.fahrtenbuch.data.Zwischenziel
import at.zweibit.fahrtenbuch.data.zwischenzieleListe
import at.zweibit.fahrtenbuch.ui.AdressFeld
import at.zweibit.fahrtenbuch.util.Format

/**
 * „Wie soll die Fahrt gespeichert werden?“ – erscheint am Ziel bzw. beim nächsten Öffnen der App.
 * Ein Tipp auf eine Kategorie speichert die Fahrt sofort.
 */
@Composable
fun KategorieDialog(
    fahrt: Fahrt,
    kategorien: List<Kategorie>,
    orte: List<Ort>,
    adressenNachladen: suspend (Fahrt) -> Pair<String?, String?>,
    speichern: (kategorieId: Long, notiz: String, start: String, ziel: String, zwischen: List<Zwischenziel>) -> Unit,
    verwerfen: () -> Unit,
    spaeter: () -> Unit,
) {
    var start by remember(fahrt.id) { mutableStateOf(fahrt.startAdresse) }
    var ziel by remember(fahrt.id) { mutableStateOf(fahrt.endeAdresse) }
    var notiz by remember(fahrt.id) { mutableStateOf(fahrt.notiz) }
    val zwischen = remember(fahrt.id) { fahrt.zwischenzieleListe.toMutableStateList() }
    var verwerfenFragen by remember(fahrt.id) { mutableStateOf(false) }

    // Fehlende Adressen (z. B. kein Netz am Ziel) nachträglich ermitteln, sofern nicht schon geändert.
    LaunchedEffect(fahrt.id) {
        val (s, z) = adressenNachladen(fahrt)
        if (s != null && start == fahrt.startAdresse) start = s
        if (z != null && ziel == fahrt.endeAdresse) ziel = z
    }

    if (verwerfenFragen) {
        AlertDialog(
            onDismissRequest = { verwerfenFragen = false },
            title = { Text("Fahrt verwerfen?") },
            text = { Text("Die Fahrt wird gelöscht und erscheint in keinem Bericht.") },
            confirmButton = { TextButton(onClick = verwerfen) { Text("Verwerfen") } },
            dismissButton = { TextButton(onClick = { verwerfenFragen = false }) { Text("Abbrechen") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = spaeter,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Wie soll die Fahrt gespeichert werden?") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val bis = fahrt.endeZeit?.let { "–" + Format.uhrzeit(it) } ?: ""
                Text(
                    "${Format.datumKurz(fahrt.startZeit)} · ${Format.uhrzeit(fahrt.startZeit)}$bis · ${Format.km(fahrt.distanzMeter)}",
                    fontWeight = FontWeight.SemiBold,
                )
                AdressFeld(start, { start = it }, "Von", orte)
                zwischen.forEachIndexed { i, z ->
                    AdressFeld(z.adresse, { zwischen[i] = z.copy(adresse = it) }, "Über (Zwischenziel ${i + 1})", orte)
                }
                AdressFeld(ziel, { ziel = it }, "Nach", orte)
                OutlinedTextField(notiz, { notiz = it }, label = { Text("Zweck / Notiz (optional)") }, modifier = Modifier.fillMaxWidth())
                if (kategorien.isEmpty()) {
                    Text(
                        "Keine Kategorien vorhanden – bitte in den Einstellungen anlegen.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                kategorien.forEach { k ->
                    val farbe = kategorieFarbe(k.farbe)
                    Button(
                        onClick = { speichern(k.id, notiz, start, ziel, zwischen.toList()) },
                        colors = ButtonDefaults.buttonColors(containerColor = farbe, contentColor = schriftAuf(farbe)),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text(k.name, fontSize = 18.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = spaeter) { Text("Später") } },
        dismissButton = { TextButton(onClick = { verwerfenFragen = true }) { Text("Verwerfen") } },
    )
}
