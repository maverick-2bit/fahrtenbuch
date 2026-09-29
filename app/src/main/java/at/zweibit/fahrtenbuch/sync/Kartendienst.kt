package at.zweibit.fahrtenbuch.sync

import at.zweibit.fahrtenbuch.FahrtenbuchApp
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Straßenkilometer über den Server der Online-Sicherung – dieselbe Route wie in der Web-App
 * (FOSSGIS-Routing auf OpenStreetMap-Daten, mit Zwischenspeicher am Server). Nur mit verbundenem Handy.
 */
object Kartendienst {
    /** Route zwischen zwei Punkten: Straßenkilometer und Fahrzeit (ohne Verkehr). */
    data class Route(val meter: Double, val sekunden: Long)

    private fun <T> fehler(text: String): Result<T> = Result.failure(IllegalStateException(text))

    /** @return die Route oder einen Fehler, dessen Meldung angezeigt werden kann */
    suspend fun route(app: FahrtenbuchApp, vonLat: Double, vonLon: Double, nachLat: Double, nachLon: Double): Result<Route> {
        val stand = app.einstellungen.syncAktuell()
        if (!stand.verbunden) return fehler("Dafür muss das Handy mit der Online-Sicherung verbunden sein (Einstellungen).")
        return try {
            val body = JSONObject()
                .put("von", JSONObject().put("lat", vonLat).put("lon", vonLon))
                .put("nach", JSONObject().put("lat", nachLat).put("lon", nachLon))
                .toString()
            val a = Sicherung.anfrage("${stand.server}/api/v1/route", stand.code, body, timeoutMs = 20_000)
            when (a.code) {
                200 -> JSONObject(a.text).let { Result.success(Route(it.getDouble("meter"), it.getLong("sekunden"))) }
                401 -> fehler("Code ungültig – bitte das Handy neu verbinden.")
                403 -> fehler("Fahrer ist gesperrt.")
                404 -> fehler("Keine Straßenverbindung gefunden.")
                else -> fehler(Sicherung.fehlertext(a))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fehler(
                when (e) {
                    is java.net.UnknownHostException -> "Keine Internetverbindung."
                    is java.net.SocketTimeoutException -> "Server antwortet nicht."
                    else -> e.message ?: e.javaClass.simpleName
                }
            )
        }
    }
}
