package at.zweibit.fahrtenbuch.ui.fahrten

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.zweibit.fahrtenbuch.FahrtenbuchApp
import at.zweibit.fahrtenbuch.data.FahrtMitKategorie
import at.zweibit.fahrtenbuch.data.FahrtStatus
import at.zweibit.fahrtenbuch.data.streckeText
import at.zweibit.fahrtenbuch.ui.kategorieFarbe
import at.zweibit.fahrtenbuch.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

class FahrtenViewModel(application: Application) : AndroidViewModel(application) {
    val fahrten: StateFlow<List<FahrtMitKategorie>?> = (application as FahrtenbuchApp).repository.alleFahrten
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FahrtenScreen(oeffnen: (Long) -> Unit, zuordnen: (Long) -> Unit, vm: FahrtenViewModel = viewModel()) {
    val fahrten by vm.fahrten.collectAsStateWithLifecycle()
    val monate = remember(fahrten) {
        val zone = ZoneId.systemDefault()
        (fahrten ?: emptyList()).groupBy { YearMonth.from(Instant.ofEpochMilli(it.fahrt.startZeit).atZone(zone)) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { oeffnen(0L) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Fahrt nachtragen") },
            )
        },
    ) { innen ->
        if (fahrten?.isEmpty() == true) {
            Box(Modifier.fillMaxSize().padding(innen).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Noch keine Fahrten aufgezeichnet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(innen),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            monate.forEach { (monat, liste) ->
                stickyHeader(key = "kopf-$monat") {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        val fertig = liste.filter { it.fahrt.status == FahrtStatus.FERTIG }
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(Format.monat(monat.atDay(1)), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text("${liste.size} Fahrten · ${Format.km(fertig.sumOf { it.fahrt.distanzMeter })}")
                        }
                    }
                }
                items(liste, key = { it.fahrt.id }) { fm ->
                    FahrtZeile(fm) {
                        if (fm.fahrt.status == FahrtStatus.OFFEN) zuordnen(fm.fahrt.id) else oeffnen(fm.fahrt.id)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun FahrtZeile(fm: FahrtMitKategorie, klick: () -> Unit) {
    val f = fm.fahrt
    val offen = f.status == FahrtStatus.OFFEN
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = klick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(5.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (offen) MaterialTheme.colorScheme.outline else kategorieFarbe(fm.kategorie?.farbe))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val bis = f.endeZeit?.let { "–" + Format.uhrzeit(it) } ?: ""
            Text("${Format.datumKurz(f.startZeit)} · ${Format.uhrzeit(f.startZeit)}$bis", fontWeight = FontWeight.Medium)
            Text(
                f.streckeText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (offen) "Nicht zugeordnet – antippen" else (fm.kategorie?.name ?: "Ohne Kategorie") +
                    (if (f.notiz.isNotBlank()) " · ${f.notiz}" else ""),
                style = MaterialTheme.typography.labelMedium,
                color = if (offen) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(Format.km(f.distanzMeter), fontWeight = FontWeight.SemiBold)
    }
}
