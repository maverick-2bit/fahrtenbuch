package at.zweibit.fahrtenbuch.sync

import android.os.Build
import at.zweibit.fahrtenbuch.BuildConfig
import at.zweibit.fahrtenbuch.FahrtenbuchApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed interface SyncErgebnis {
    /** @param wartenAufPin Einträge von Privatfahrten, die erst mit festgelegtem PIN übertragen werden */
    data class Ok(val gesendet: Int, val wartenAufPin: Int = 0) : SyncErgebnis
    data object NichtVerbunden : SyncErgebnis
    /** Code ungültig oder Fahrer gesperrt – erneutes Versuchen hilft nicht. */
    data class Abgewiesen(val text: String) : SyncErgebnis
    /** Netz- oder Serverproblem – später erneut versuchen. */
    data class Fehler(val text: String) : SyncErgebnis
}

private class HttpAntwort(val code: Int, val text: String)

/** Überträgt das Änderungsprotokoll an die Online-Sicherung. */
object Sicherung {
    private const val JE_ANFRAGE = 100
    private const val HOECHSTENS = 5_000
    private val mutex = Mutex()

    val geraet: String get() = "Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}"

    private suspend fun anfrage(url: String, code: String, body: String?): HttpAntwort = withContext(Dispatchers.IO) {
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = if (body == null) "GET" else "POST"
            setRequestProperty("Authorization", "Bearer $code")
            setRequestProperty("User-Agent", "Fahrtenbuch-App/${BuildConfig.VERSION_NAME}")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) con.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = con.responseCode
            val strom = if (status in 200..299) con.inputStream else con.errorStream
            HttpAntwort(status, strom?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            con.disconnect()
        }
    }

    private fun fehlertext(a: HttpAntwort): String =
        runCatching { JSONObject(a.text).optString("fehler") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "Server meldet Fehler ${a.code}"

    /** Prüft einen Code und liefert den Namen des Fahrers. */
    suspend fun pruefen(v: Verbindung): Result<String> = runCatching {
        val a = anfrage("${v.server}/api/v1/ich", v.code, null)
        if (a.code != 200) error(fehlertext(a))
        JSONObject(a.text).getJSONObject("fahrer").getString("name")
    }

    suspend fun synchronisieren(app: FahrtenbuchApp): SyncErgebnis = mutex.withLock {
        val stand = app.einstellungen.syncAktuell()
        if (!stand.verbunden) return@withLock SyncErgebnis.NichtVerbunden
        val repo = app.repository
        repo.altbestandProtokollieren()
        try {
            val kategorien = repo.alleKategorien()
            val privat = app.einstellungen.privatAktuell()
            val datenSchluessel = if (privat.hatPin) Krypto.ausB64(privat.datenSchluessel) else null
            val vorbereitet = SyncFormat.vorbereiten(
                repo.protokollOffeneEintraege(HOECHSTENS),
                kategorien.filter { it.privat }.map { it.id }.toSet(),
                datenSchluessel,
            )
            if (vorbereitet.entfallen.isNotEmpty()) repo.protokollEntfallen(vorbereitet.entfallen)
            // Hülle des Datenschlüssels mitschicken, bis der Server sie bestätigt hat
            var huelle = if (privat.hatPin && !privat.huelleGesendet) JSONObject(privat.huelle) else null
            // Auch ohne Einträge einmal senden: Kategorien, Fahrzeugdaten, Schlüssel, „zuletzt gesichert“
            val stuecke = vorbereitet.senden.chunked(JE_ANFRAGE).ifEmpty { listOf(emptyList()) }
            var gesendet = 0
            for (stueck in stuecke) {
                val body = SyncFormat.anfrage(
                    BuildConfig.VERSION_NAME, geraet, app.einstellungen.aktuell(), kategorien, stueck, huelle,
                )
                val a = anfrage("${stand.server}/api/v1/sync", stand.code, body)
                when (a.code) {
                    200 -> Unit
                    401, 403 -> {
                        val text = if (a.code == 401) "Code ungültig – bitte das Handy neu verbinden." else "Fahrer ist gesperrt."
                        app.einstellungen.syncErgebnis(null, text)
                        return@withLock SyncErgebnis.Abgewiesen(text)
                    }
                    else -> error(fehlertext(a))
                }
                if (huelle != null) {
                    app.einstellungen.privatHuelleGesendet(privat.huelle)
                    huelle = null
                }
                val antwort = SyncFormat.antwort(a.text)
                if (antwort.angenommen.isNotEmpty()) repo.protokollAngenommen(antwort.angenommen)
                antwort.abgelehnt.forEach { (id, grund) -> if (id.isNotBlank()) repo.protokollAbgelehnt(id, grund) }
                gesendet += antwort.angenommen.size
            }
            app.einstellungen.syncErgebnis(
                System.currentTimeMillis(),
                if (vorbereitet.wartenAufPin > 0) "Privatfahrten warten auf deinen PIN." else "",
            )
            SyncErgebnis.Ok(gesendet, vorbereitet.wartenAufPin)
        } catch (e: Exception) {
            val text = when (e) {
                is java.net.UnknownHostException -> "Keine Internetverbindung."
                is java.net.SocketTimeoutException -> "Server antwortet nicht."
                else -> e.message ?: e.javaClass.simpleName
            }
            app.einstellungen.syncErgebnis(null, text)
            SyncErgebnis.Fehler(text)
        }
    }
}

/** PIN für Privatfahrten: legt den Datenschlüssel an (einmalig) und verpackt ihn mit dem PIN. */
object PrivatSchutz {
    suspend fun pinFestlegen(app: FahrtenbuchApp, pin: String) {
        require(pin.length >= Krypto.MIN_PIN && pin.all { it.isDigit() }) { "PIN muss mindestens ${Krypto.MIN_PIN} Ziffern haben" }
        val stand = app.einstellungen.privatAktuell()
        val (dek, huelle) = withContext(Dispatchers.Default) {
            val dek = if (stand.datenSchluessel.isNotBlank()) Krypto.ausB64(stand.datenSchluessel) else Krypto.zufall(32)
            dek to Krypto.huelleErstellen(pin, dek)
        }
        app.einstellungen.privatSpeichern(Krypto.b64(dek), huelle.json().toString())
        SyncPlaner.bald(app)
    }
}
