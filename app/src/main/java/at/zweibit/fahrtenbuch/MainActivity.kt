package at.zweibit.fahrtenbuch

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableLongStateOf
import at.zweibit.fahrtenbuch.tracking.Benachrichtigungen
import at.zweibit.fahrtenbuch.ui.AppOberflaeche
import at.zweibit.fahrtenbuch.ui.FahrtenbuchTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_FAHRT_ID = "fahrt_id"
    }

    /** Fahrt, deren Kategorie-Dialog aus einer Benachrichtigung heraus geöffnet werden soll. */
    private val angeforderteFahrt = mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Benachrichtigungen.kanaeleAnlegen(this)
        uebernehmen(intent)
        setContent {
            FahrtenbuchTheme {
                AppOberflaeche(
                    angeforderteFahrt = angeforderteFahrt.longValue,
                    anforderungErledigt = { angeforderteFahrt.longValue = 0L },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        uebernehmen(intent)
    }

    private fun uebernehmen(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_FAHRT_ID, 0L) ?: 0L
        if (id != 0L) angeforderteFahrt.longValue = id
    }
}
