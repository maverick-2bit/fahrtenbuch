package at.zweibit.fahrtenbuch.ui

import android.content.Context
import android.content.IntentSender
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

/**
 * Apps dürfen den Standort nicht selbst einschalten. Ist er aus, zeigt Android über die
 * Google-Play-Dienste das Fenster „Standort aktivieren?“ – ein Tipp auf „OK“ genügt.
 */
object StandortEinschalten {
    fun pruefen(context: Context, anfragen: (IntentSender) -> Unit) {
        val anfrage = LocationSettingsRequest.Builder()
            .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4_000).build())
            .setAlwaysShow(true)
            .build()
        LocationServices.getSettingsClient(context).checkLocationSettings(anfrage)
            .addOnFailureListener { e ->
                if (e is ResolvableApiException) runCatching { anfragen(e.resolution.intentSender) }
            }
    }
}

/** Liefert eine Funktion, die bei ausgeschaltetem Standort das Einschalt-Fenster zeigt. */
@Composable
fun rememberStandortEinschalten(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
    return remember(context, launcher) {
        {
            StandortEinschalten.pruefen(context) { sender ->
                launcher.launch(IntentSenderRequest.Builder(sender).build())
            }
        }
    }
}
