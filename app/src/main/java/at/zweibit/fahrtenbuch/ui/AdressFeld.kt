package at.zweibit.fahrtenbuch.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import at.zweibit.fahrtenbuch.data.Ort

/**
 * Adress-Eingabe mit Auswahl aus den gespeicherten Orten (Knopf rechts im Feld).
 */
@Composable
fun AdressFeld(
    wert: String,
    aendern: (String) -> Unit,
    label: String,
    orte: List<Ort>,
    modifier: Modifier = Modifier,
) {
    var menue by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = wert,
        onValueChange = aendern,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        trailingIcon = if (orte.isEmpty()) null else {
            {
                Box {
                    IconButton(onClick = { menue = true }) {
                        Icon(Icons.Filled.Bookmarks, contentDescription = "Gespeicherten Ort wählen")
                    }
                    DropdownMenu(expanded = menue, onDismissRequest = { menue = false }) {
                        orte.forEach { o ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(o.name.ifBlank { o.adresse })
                                        if (o.name.isNotBlank() && o.adresse.isNotBlank()) {
                                            Text(
                                                o.adresse,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    aendern(o.alsAdresse)
                                    menue = false
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

/** Dialog zum Ändern einer einzelnen Adresse (Startadresse, Zwischenziel). */
@Composable
fun AdresseAendernDialog(
    titel: String,
    label: String,
    anfang: String,
    orte: List<Ort>,
    hinweis: String? = null,
    speichern: (String) -> Unit,
    abbrechen: () -> Unit,
) {
    var text by remember { mutableStateOf(anfang) }
    AlertDialog(
        onDismissRequest = abbrechen,
        title = { Text(titel) },
        text = {
            Column {
                if (hinweis != null) {
                    Text(hinweis, style = MaterialTheme.typography.bodySmall)
                }
                AdressFeld(text, { text = it }, label, orte)
            }
        },
        confirmButton = { TextButton(onClick = { speichern(text) }, enabled = text.isNotBlank()) { Text("Übernehmen") } },
        dismissButton = { TextButton(onClick = abbrechen) { Text("Abbrechen") } },
    )
}
