package at.zweibit.fahrtenbuch.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import at.zweibit.fahrtenbuch.FahrtenbuchApp
import at.zweibit.fahrtenbuch.data.BtGeraet
import kotlinx.coroutines.launch

/** Welche Freigaben der automatische Start hat – ohne alle startet er nur per Antippen der Benachrichtigung. */
data class AutoStartRechte(
    val bluetooth: Boolean,
    val standort: Boolean,
    val hintergrund: Boolean,
    val akku: Boolean,
    val benachrichtigung: Boolean,
) {
    /** Android lässt die Aufzeichnung ohne Antippen im Hintergrund starten. */
    val vollstaendig: Boolean get() = bluetooth && standort && hintergrund && akku && benachrichtigung
}

/**
 * Automatischer Start einer Fahrt, wenn sich das Handy mit dem Auto (Bluetooth) verbindet.
 *
 * Android erlaubt den Start der Aufzeichnung aus dem Hintergrund nur, wenn die App von der
 * Akku-Optimierung ausgenommen ist, und den Standort im Hintergrund nur mit „Immer zulassen“.
 * Fehlt das, fragt die App per Benachrichtigung – ein Tipp darauf ist immer erlaubt.
 */
object AutoStart {
    private fun erlaubt(context: Context, recht: String) =
        ContextCompat.checkSelfPermission(context, recht) == PackageManager.PERMISSION_GRANTED

    fun rechte(context: Context) = AutoStartRechte(
        bluetooth = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || erlaubt(context, Manifest.permission.BLUETOOTH_CONNECT),
        standort = erlaubt(context, Manifest.permission.ACCESS_FINE_LOCATION),
        hintergrund = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || erlaubt(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        akku = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
        benachrichtigung = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    )

    /** Mit dem Handy gekoppelte Bluetooth-Geräte, nach Namen sortiert (leer ohne Freigabe oder bei ausgeschaltetem Bluetooth). */
    @SuppressLint("MissingPermission")
    fun gekoppelteGeraete(context: Context): List<BtGeraet> {
        if (!rechte(context).bluetooth) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.orEmpty().map { BtGeraet(it.address, it.name?.takeIf(String::isNotBlank) ?: it.address) }
        }.getOrDefault(emptyList()).sortedBy { it.name.lowercase() }
    }

    fun bluetoothAn(context: Context): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    /** Das Handy hat sich mit einem der gewählten Geräte verbunden. */
    fun verbunden(context: Context, g: BtGeraet) {
        val r = rechte(context)
        if (r.standort && r.hintergrund && r.akku) {
            try {
                ContextCompat.startForegroundService(context, TrackingService.bereitIntent(context, g))
                return
            } catch (_: Exception) {
                // z. B. ForegroundServiceStartNotAllowedException – dann per Benachrichtigung fragen
            }
        }
        Benachrichtigungen.autoStartFrage(context, g, r)
    }

    /** Die Verbindung zum Auto ist getrennt (Motor aus, ausgestiegen). */
    fun getrennt(context: Context, g: BtGeraet) {
        Benachrichtigungen.autoStartFrageEntfernen(context)
        try {
            // Läuft der Dienst nicht, lehnt Android den Start aus dem Hintergrund ab – dann ist auch nichts zu tun
            context.startService(TrackingService.getrenntIntent(context, g))
        } catch (_: Exception) {
        }
    }
}

/** Empfängt Verbindungen und Trennungen von Bluetooth-Geräten (auch wenn die App nicht läuft). */
class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val aktion = intent.action
        if (aktion != BluetoothDevice.ACTION_ACL_CONNECTED && aktion != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        val geraet: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val adresse = geraet?.address ?: return
        val app = context.applicationContext as FahrtenbuchApp
        val pending = goAsync()
        app.appScope.launch {
            try {
                val stand = app.einstellungen.autoStartAktuell()
                val g = stand.geraet(adresse)
                if (stand.aktiv && g != null) {
                    if (aktion == BluetoothDevice.ACTION_ACL_CONNECTED) AutoStart.verbunden(app, g) else AutoStart.getrennt(app, g)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
