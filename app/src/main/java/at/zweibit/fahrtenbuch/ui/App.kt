package at.zweibit.fahrtenbuch.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import at.zweibit.fahrtenbuch.ui.berichte.BerichteScreen
import at.zweibit.fahrtenbuch.ui.einstellungen.EinstellungenScreen
import at.zweibit.fahrtenbuch.ui.fahrt.FahrtScreen
import at.zweibit.fahrtenbuch.ui.fahrt.KategorieDialog
import at.zweibit.fahrtenbuch.ui.fahrten.FahrtBearbeitenScreen
import at.zweibit.fahrtenbuch.ui.fahrten.FahrtenScreen

private data class Ziel(val route: String, val titel: String, val icon: ImageVector)

private val ZIELE = listOf(
    Ziel("fahrt", "Fahrt", Icons.Filled.DirectionsCar),
    Ziel("fahrten", "Fahrten", Icons.AutoMirrored.Filled.List),
    Ziel("berichte", "Berichte", Icons.Filled.Assessment),
    Ziel("einstellungen", "Einstellungen", Icons.Filled.Settings),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppOberflaeche(angeforderteFahrt: Long, anforderungErledigt: () -> Unit) {
    val nav = rememberNavController()
    val haupt: HauptViewModel = viewModel()
    val eintrag by nav.currentBackStackEntryAsState()
    val route = eintrag?.destination?.route

    // Bei jedem Öffnen der App: Standort aus? Dann das System-Fenster „Standort aktivieren?“ zeigen.
    val standortEinschalten = rememberStandortEinschalten()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { standortEinschalten() }

    LaunchedEffect(angeforderteFahrt) {
        if (angeforderteFahrt != 0L) {
            haupt.anfordern(angeforderteFahrt)
            anforderungErledigt()
        }
    }

    // Solange die Tastatur offen ist, stört die untere Leiste nur (sie schwebt sonst über der Tastatur)
    val tastaturOffen = WindowInsets.isImeVisible

    Scaffold(
        bottomBar = {
            if (ZIELE.any { it.route == route } && !tastaturOffen) {
                NavigationBar {
                    ZIELE.forEach { z ->
                        NavigationBarItem(
                            selected = route == z.route,
                            onClick = {
                                nav.navigate(z.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(z.icon, contentDescription = null) },
                            label = { Text(z.titel) },
                        )
                    }
                }
            }
        },
    ) { innen ->
        NavHost(
            nav,
            startDestination = "fahrt",
            modifier = Modifier
                .padding(innen)
                .consumeWindowInsets(innen)
                .imePadding(),
        ) {
            composable("fahrt") { FahrtScreen(haupt, zuordnen = haupt::anfordern) }
            composable("fahrten") {
                FahrtenScreen(
                    oeffnen = { id -> nav.navigate("bearbeiten/$id") },
                    zuordnen = haupt::anfordern,
                )
            }
            composable("berichte") { BerichteScreen(oeffnen = { id -> nav.navigate("bearbeiten/$id") }) }
            composable("einstellungen") { EinstellungenScreen() }
            composable(
                "bearbeiten/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { e ->
                FahrtBearbeitenScreen(
                    fahrtId = e.arguments?.getLong("id") ?: 0L,
                    zurueck = { nav.popBackStack() },
                )
            }
        }
    }

    val dialogFahrt by haupt.dialogFahrt.collectAsStateWithLifecycle()
    val kategorien by haupt.kategorien.collectAsStateWithLifecycle()
    val orte by haupt.orte.collectAsStateWithLifecycle()
    dialogFahrt?.let { f ->
        KategorieDialog(
            fahrt = f,
            kategorien = kategorien,
            orte = orte,
            adressenNachladen = haupt::adressenNachladen,
            speichern = { katId, notiz, start, ziel, zwischen ->
                haupt.kategorisieren(f.id, katId, notiz, start, ziel, zwischen)
            },
            verwerfen = { haupt.verwerfen(f.id) },
            spaeter = { haupt.spaeter(f.id) },
        )
    }
}
