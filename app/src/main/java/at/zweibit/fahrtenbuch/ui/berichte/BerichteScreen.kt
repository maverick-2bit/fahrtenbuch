package at.zweibit.fahrtenbuch.ui.berichte

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.zweibit.fahrtenbuch.bericht.Bericht
import at.zweibit.fahrtenbuch.bericht.BerichtErstellen
import at.zweibit.fahrtenbuch.bericht.KategorieBlock
import at.zweibit.fahrtenbuch.data.streckeText
import at.zweibit.fahrtenbuch.ui.Farbpunkt
import at.zweibit.fahrtenbuch.ui.datumWaehlen
import at.zweibit.fahrtenbuch.ui.kategorieFarbe
import at.zweibit.fahrtenbuch.util.Format
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BerichteScreen(oeffnen: (Long) -> Unit, vm: BerichtViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val art by vm.art.collectAsStateWithLifecycle()
    val monat by vm.monat.collectAsStateWithLifecycle()
    val jahr by vm.jahr.collectAsStateWithLifecycle()
    val von by vm.von.collectAsStateWithLifecycle()
    val bis by vm.bis.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val gesamt by vm.gesamt.collectAsStateWithLifecycle()
    val bericht by vm.bericht.collectAsStateWithLifecycle()

    fun export(format: ExportFormat) {
        scope.launch {
            val b = bericht
            if (b == null || b.gesamtAnzahl == 0) {
                Toast.makeText(context, "Keine Fahrten im gewählten Zeitraum.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            vm.exportieren(format)?.let { context.startActivity(it) }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text("Berichte") },
                actions = {
                    TextButton(onClick = { export(ExportFormat.PDF) }) {
                        Icon(Icons.Filled.PictureAsPdf, null)
                        Spacer(Modifier.width(4.dp))
                        Text("PDF")
                    }
                    TextButton(onClick = { export(ExportFormat.CSV) }) {
                        Icon(Icons.Filled.TableChart, null)
                        Spacer(Modifier.width(4.dp))
                        Text("CSV")
                    }
                },
            )
        },
    ) { innen ->
        LazyColumn(
            Modifier.fillMaxSize().padding(innen),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ZeitraumArt.entries.forEachIndexed { i, a ->
                        SegmentedButton(
                            selected = art == a,
                            onClick = { vm.art.value = a },
                            shape = SegmentedButtonDefaults.itemShape(i, ZeitraumArt.entries.size),
                        ) { Text(a.titel) }
                    }
                }
            }
            item {
                if (art == ZeitraumArt.INDIVIDUELL) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { datumWaehlen(context, von) { vm.von.value = it } }, modifier = Modifier.weight(1f)) {
                            Text("Von ${Format.datum(von)}")
                        }
                        OutlinedButton(onClick = { datumWaehlen(context, bis) { vm.bis.value = it } }, modifier = Modifier.weight(1f)) {
                            Text("Bis ${Format.datum(bis)}")
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.blaettern(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Zurück") }
                        Text(
                            if (art == ZeitraumArt.MONAT) Format.monat(monat.atDay(1)) else "Jahr $jahr",
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { vm.blaettern(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Weiter") }
                    }
                }
            }

            val alle = gesamt?.bloecke.orEmpty()
            if (alle.size > 1) {
                item {
                    val alleIds = alle.map { it.kategorieId ?: 0L }.toSet()
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        alle.forEach { b ->
                            val id = b.kategorieId ?: 0L
                            FilterChip(
                                selected = filter?.contains(id) ?: true,
                                onClick = { vm.filterUmschalten(id, alleIds) },
                                label = { Text(b.name) },
                                leadingIcon = { Farbpunkt(kategorieFarbe(b.farbe)) },
                            )
                        }
                    }
                }
            }

            val b = bericht
            if (b == null) return@LazyColumn
            if (b.gesamtAnzahl == 0) {
                item {
                    Text(
                        "Keine Fahrten im gewählten Zeitraum.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                return@LazyColumn
            }
            item { Zusammenfassung(b) }
            b.bloecke.forEach { block ->
                item(key = "kopf-${block.kategorieId}") { BlockKopf(block) }
                items(block.zeilen, key = { "z-${block.kategorieId}-${it.fahrt.id}" }) { z ->
                    val f = z.fahrt
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { oeffnen(f.id) }
                            .padding(start = 4.dp),
                    ) {
                        Row {
                            val ende = f.endeZeit?.let { "–" + Format.uhrzeit(it) } ?: ""
                            Text("${Format.datum(f.startZeit)} ${Format.uhrzeit(f.startZeit)}$ende", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            Text(Format.kmZahl(z.km) + " km", fontWeight = FontWeight.Medium)
                        }
                        Text(
                            f.streckeText,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        val zusatz = listOfNotNull(
                            if (z.kmStandBeginn != null && z.kmStandEnde != null)
                                "Km-Stand ${Format.kmZahl(z.kmStandBeginn)} – ${Format.kmZahl(z.kmStandEnde)}" else null,
                            f.notiz.takeIf { it.isNotBlank() },
                        )
                        if (zusatz.isNotEmpty()) {
                            Text(
                                zusatz.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Zusammenfassung(b: Bericht) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Zusammenfassung · ${b.zeitraum.titel}", style = MaterialTheme.typography.titleMedium)
            b.bloecke.forEach { k ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Farbpunkt(kategorieFarbe(k.farbe))
                    Spacer(Modifier.width(10.dp))
                    Text(k.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${k.anzahl}×", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text("${Format.kmZahl(k.summeKm)} km", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${Format.kmZahl(b.anteilProzent(k))} %",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(56.dp),
                    )
                }
            }
            HorizontalDivider()
            Row {
                Text("Gesamt", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text("${b.gesamtAnzahl}×", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(12.dp))
                Text("${Format.kmZahl(b.gesamtKm)} km", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(68.dp))
            }
        }
    }
}

@Composable
private fun BlockKopf(k: KategorieBlock) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Farbpunkt(kategorieFarbe(k.farbe), 14.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            if (k.kategorieId == null) BerichtErstellen.OHNE_KATEGORIE else k.name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Text("${k.anzahl} Fahrten · ${Format.kmZahl(k.summeKm)} km", style = MaterialTheme.typography.bodyMedium)
    }
}
