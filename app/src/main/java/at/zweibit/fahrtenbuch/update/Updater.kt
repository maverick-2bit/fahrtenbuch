package at.zweibit.fahrtenbuch.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import at.zweibit.fahrtenbuch.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val version: String,
    val apkUrl: String,
    val groesse: Long,
    val notizen: String,
)

sealed interface UpdateStatus {
    data object Unbekannt : UpdateStatus
    data object Pruefe : UpdateStatus
    data object Aktuell : UpdateStatus
    data class Verfuegbar(val info: UpdateInfo) : UpdateStatus
    data class Laedt(val info: UpdateInfo, val prozent: Int) : UpdateStatus
    /** @param datei heruntergeladene APK – für den zweiten Weg über den normalen Android-Installer */
    data class Fehler(val text: String, val datei: File? = null) : UpdateStatus
}

/**
 * In-App-Update über GitHub-Releases (wie bei der Kassa): die App fragt das neueste Release ab,
 * lädt die APK und übergibt sie dem Android-Paketinstaller. Weil jede Version mit demselben
 * Schlüssel signiert ist, bleiben alle Fahrten erhalten.
 */
object Updater {
    const val REPO = "smarte-events/fahrtenbuch"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PREFS = "update"
    private const val LETZTE_PRUEFUNG = "letzte_pruefung"
    private const val AUTO_ABSTAND_MS = 6L * 3600 * 1000

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Unbekannt)

    /** Zuletzt heruntergeladene APK (für den Rückfallweg, falls die Installation scheitert). */
    @Volatile
    internal var letzteDatei: File? = null
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    /** Vergleicht Versionen wie „0.10.1“ > „0.9.3“; ein führendes „v“ wird ignoriert. */
    fun istNeuer(neu: String, alt: String): Boolean {
        fun teile(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = teile(neu)
        val b = teile(alt)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Liest die Antwort von `releases/latest`; null, wenn keine APK angehängt ist. */
    fun releaseLesen(json: String): UpdateInfo? {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val version = o.optString("tag_name").removePrefix("v").ifBlank { return null }
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                return UpdateInfo(
                    version = version,
                    apkUrl = a.getString("browser_download_url"),
                    groesse = a.optLong("size"),
                    notizen = o.optString("body").trim(),
                )
            }
        }
        return null
    }

    /**
     * Prüft auf eine neue Version. [automatisch] = höchstens alle 6 Stunden und ohne Fehlermeldung.
     */
    suspend fun pruefen(context: Context, automatisch: Boolean = false) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (automatisch) {
            if (System.currentTimeMillis() - prefs.getLong(LETZTE_PRUEFUNG, 0) < AUTO_ABSTAND_MS) return
            if (_status.value !is UpdateStatus.Unbekannt && _status.value !is UpdateStatus.Aktuell) return
        }
        _status.value = UpdateStatus.Pruefe
        val ergebnis = runCatching {
            withContext(Dispatchers.IO) {
                val con = (URL(API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Fahrtenbuch-App/${BuildConfig.VERSION_NAME}")
                }
                when (val code = con.responseCode) {
                    200 -> con.inputStream.bufferedReader().use { it.readText() }
                    404 -> null // noch kein Release veröffentlicht
                    else -> error("GitHub meldet Fehler $code")
                }
            }
        }
        prefs.edit().putLong(LETZTE_PRUEFUNG, System.currentTimeMillis()).apply()
        _status.value = ergebnis.fold(
            onSuccess = { json ->
                val info = json?.let { releaseLesen(it) }
                if (info != null && istNeuer(info.version, BuildConfig.VERSION_NAME)) UpdateStatus.Verfuegbar(info)
                else UpdateStatus.Aktuell
            },
            onFailure = {
                if (automatisch) UpdateStatus.Unbekannt
                else UpdateStatus.Fehler(if (it is java.net.UnknownHostException) "Keine Internetverbindung." else it.message ?: "Unbekannter Fehler")
            },
        )
    }

    /** Lädt die APK und startet die Installation. */
    suspend fun installieren(context: Context, info: UpdateInfo) {
        val ctx = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            // Einmalig erlauben: „Unbekannte Apps installieren“ für das Fahrtenbuch
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            _status.value = UpdateStatus.Verfuegbar(info)
            return
        }
        _status.value = UpdateStatus.Laedt(info, 0)
        // Sonst würde nach einem abgebrochenen Download eine halbe Datei zum Installieren angeboten
        letzteDatei = null
        val ergebnis = runCatching {
            withContext(Dispatchers.IO) {
                val ordner = File(ctx.cacheDir, "update").apply { mkdirs() }
                ordner.listFiles()?.forEach { it.delete() }
                val datei = File(ordner, "Fahrtenbuch-${info.version}.apk")
                val con = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "Fahrtenbuch-App/${BuildConfig.VERSION_NAME}")
                }
                check(con.responseCode == 200) { "Download fehlgeschlagen (${con.responseCode})" }
                val gesamt = con.contentLengthLong.takeIf { it > 0 } ?: info.groesse
                con.inputStream.use { ein ->
                    datei.outputStream().use { aus ->
                        val puffer = ByteArray(64 * 1024)
                        var gelesen = 0L
                        while (true) {
                            val n = ein.read(puffer)
                            if (n < 0) break
                            aus.write(puffer, 0, n)
                            gelesen += n
                            if (gesamt > 0) _status.value = UpdateStatus.Laedt(info, (gelesen * 100 / gesamt).toInt())
                        }
                    }
                }
                pruefeApk(ctx, datei)
                letzteDatei = datei
                sitzungStarten(ctx, datei)
            }
        }
        ergebnis.onFailure { _status.value = UpdateStatus.Fehler(it.message ?: "Installation fehlgeschlagen", letzteDatei) }
    }

    /**
     * Rückfallweg: übergibt die heruntergeladene APK dem normalen Android-Installer
     * (wie beim Antippen der Datei im Dateimanager).
     */
    fun mitAndroidInstallerOeffnen(context: Context, datei: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", datei)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun pruefeApk(ctx: Context, datei: File) {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val neu = pm.getPackageArchiveInfo(datei.path, 0) ?: error("Heruntergeladene Datei ist keine gültige App")
        check(neu.packageName == ctx.packageName) { "Falsche App im Update (${neu.packageName})" }
        @Suppress("DEPRECATION")
        val alt = pm.getPackageInfo(ctx.packageName, 0)
        val neuCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) neu.longVersionCode else neu.versionCode.toLong()
        val altCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) alt.longVersionCode else alt.versionCode.toLong()
        check(neuCode > altCode) { "Update ist nicht neuer als die installierte Version" }
    }

    private fun sitzungStarten(ctx: Context, datei: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { sitzung ->
            sitzung.openWrite("fahrtenbuch.apk", 0, datei.length()).use { aus ->
                datei.inputStream().use { it.copyTo(aus) }
                sitzung.fsync(aus)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
            sitzung.commit(pi.intentSender)
        }
    }

    internal fun ergebnis(status: UpdateStatus) {
        _status.value = status
    }
}

/** Empfängt das Ergebnis der Installationssitzung und zeigt bei Bedarf die Bestätigung an. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val s = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val bestaetigen: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                bestaetigen?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // Die App wird dabei neu gestartet
            else -> {
                // Begründung von Android immer mit anzeigen – sonst lässt sich ein Fehler nicht eingrenzen
                val grund = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.takeIf { it.isNotBlank() }
                val art = when (s) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation abgebrochen"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation vom System blockiert"
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "Installation fehlgeschlagen (Konflikt mit der installierten App)"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "App passt nicht zu diesem Gerät"
                    PackageInstaller.STATUS_FAILURE_INVALID -> "Ungültige Installationsdatei"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Zu wenig Speicherplatz"
                    else -> "Installation fehlgeschlagen"
                }
                Log.w("Fahrtenbuch-Update", "Status $s: $grund")
                Updater.ergebnis(UpdateStatus.Fehler(art + (grund?.let { " ($it)" } ?: "") + ".", Updater.letzteDatei))
            }
        }
    }
}
