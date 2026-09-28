package at.zweibit.fahrtenbuch.blitzer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed interface LadeStatus {
    data object Bereit : LadeStatus
    data object Laedt : LadeStatus
    data class Fehler(val text: String) : LadeStatus
}

/**
 * Lokale Blitzerdatei (Österreich, aus OpenStreetMap) samt Download über die Overpass-API.
 * Die Datei wird nur ersetzt, wenn der neue Datenstand plausibel ist.
 */
object BlitzerDaten {
    private const val DATEI = "blitzer_at.txt"
    private const val VERALTET_NACH_MS = 30L * 24 * 3600 * 1000
    private const val MIN_ANZAHL = 100

    private val SERVER = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    private val mutex = Mutex()
    private val _info = MutableStateFlow<BlitzerInfo?>(null)
    val info: StateFlow<BlitzerInfo?> = _info.asStateFlow()
    private val _status = MutableStateFlow<LadeStatus>(LadeStatus.Bereit)
    val status: StateFlow<LadeStatus> = _status.asStateFlow()

    @Volatile
    private var cache: List<Blitzer>? = null

    private fun datei(context: Context) = File(context.filesDir, DATEI)

    fun veraltet(i: BlitzerInfo?): Boolean = i == null || System.currentTimeMillis() - i.stand > VERALTET_NACH_MS

    /** Liest nur den Kopf der Datei (Stand und Anzahl) für die Anzeige. */
    suspend fun infoLaden(context: Context): BlitzerInfo? = withContext(Dispatchers.IO) {
        val f = datei(context)
        val i = if (f.exists()) f.bufferedReader().use { BlitzerFormat.infoLesen(it.readLine()) } else null
        _info.value = i
        i
    }

    suspend fun liste(context: Context): List<Blitzer> = mutex.withLock {
        cache ?: withContext(Dispatchers.IO) {
            val f = datei(context)
            if (!f.exists()) return@withContext emptyList()
            val (i, liste) = BlitzerFormat.lesen(f.readText())
            _info.value = i
            liste
        }.also { cache = it }
    }

    /** Lädt die aktuellen Blitzer für Österreich. @return true bei Erfolg */
    suspend fun aktualisieren(context: Context): Boolean {
        if (_status.value == LadeStatus.Laedt) return false
        _status.value = LadeStatus.Laedt
        val ergebnis = runCatching {
            withContext(Dispatchers.IO) {
                val json = herunterladen()
                val liste = OverpassParser.parsen(json)
                check(liste.count { it.art == BlitzerArt.FIX } >= MIN_ANZAHL) {
                    "Unvollständige Daten (${liste.size} Einträge)"
                }
                val stand = System.currentTimeMillis()
                val tmp = File(context.filesDir, "$DATEI.neu")
                tmp.writeText(BlitzerFormat.schreiben(stand, liste))
                check(tmp.renameTo(datei(context)) || run { tmp.copyTo(datei(context), overwrite = true); tmp.delete() }) {
                    "Datei konnte nicht gespeichert werden"
                }
                mutex.withLock { cache = liste }
                BlitzerInfo.aus(stand, liste)
            }
        }
        ergebnis.onSuccess {
            _info.value = it
            _status.value = LadeStatus.Bereit
        }.onFailure {
            _status.value = LadeStatus.Fehler(
                when (it) {
                    is java.net.UnknownHostException -> "Keine Internetverbindung."
                    is java.net.SocketTimeoutException -> "Server antwortet nicht – bitte später erneut versuchen."
                    else -> it.message ?: it.javaClass.simpleName
                }
            )
        }
        return ergebnis.isSuccess
    }

    private fun herunterladen(): String {
        val body = "data=" + URLEncoder.encode(OverpassParser.QUERY, "UTF-8")
        var letzterFehler: Exception? = null
        for (server in SERVER) {
            try {
                val con = (URL(server).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = 200_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    setRequestProperty("User-Agent", "Fahrtenbuch-App/0.2 (Android)")
                }
                con.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = con.responseCode
                if (code == 200) return con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                letzterFehler = IllegalStateException("Server meldet Fehler $code")
                con.disconnect()
            } catch (e: Exception) {
                letzterFehler = e
            }
        }
        throw letzterFehler ?: IllegalStateException("Kein Server erreichbar")
    }
}
