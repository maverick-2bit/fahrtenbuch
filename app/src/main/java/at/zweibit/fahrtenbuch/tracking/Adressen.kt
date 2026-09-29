package at.zweibit.fahrtenbuch.tracking

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import at.zweibit.fahrtenbuch.data.Ort
import at.zweibit.fahrtenbuch.data.Orte
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.cos

object Adressen {
    private val KOORDINATEN = Regex("""^-?\d{1,3}\.\d+,\s*-?\d{1,3}\.\d+$""")

    fun koordinatenText(lat: Double, lon: Double): String =
        String.format(Locale.ROOT, "%.5f, %.5f", lat, lon)

    /** true, wenn noch keine echte Adresse ermittelt wurde (leer oder nur Koordinaten). */
    fun fehlt(adresse: String): Boolean = adresse.isBlank() || KOORDINATEN.matches(adresse.trim())

    /**
     * Ermittelt die Adresse zu einer Position. Ohne Netz oder Geocoder
     * werden die Koordinaten als Text zurückgegeben.
     */
    suspend fun ermitteln(context: Context, lat: Double, lon: Double, timeoutMs: Long = 10_000): String {
        val adresse = if (Geocoder.isPresent()) {
            withTimeoutOrNull(timeoutMs) { abfragen(context, lat, lon) }
        } else null
        return adresse?.let { formatieren(it) }?.takeIf { it.isNotBlank() } ?: koordinatenText(lat, lon)
    }

    /**
     * Adresse für eine Position: zuerst ein gespeicherter Ort im Umkreis, sonst die GPS-Adresse.
     */
    suspend fun bestimmen(context: Context, lat: Double, lon: Double, orte: List<Ort>, timeoutMs: Long = 10_000): String =
        Orte.erkennen(orte, lat, lon)?.alsAdresse ?: ermitteln(context, lat, lon, timeoutMs)

    /**
     * Koordinaten zu einer eingegebenen Adresse (gespeicherte Orte, korrigierter Start). Mit [nahe] wird im
     * Umkreis von rund 100 km um diesen Punkt gesucht – so findet „Hauptplatz 1“ den im eigenen Ort.
     */
    @Suppress("DEPRECATION")
    suspend fun koordinaten(context: Context, adresse: String, nahe: Pair<Double, Double>? = null): Pair<Double, Double>? {
        if (adresse.isBlank() || !Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val box = nahe?.let { (lat, lon) ->
            val dLon = UMKREIS_GRAD / cos(Math.toRadians(lat)).coerceAtLeast(0.1)
            doubleArrayOf(
                (lat - UMKREIS_GRAD).coerceAtLeast(-90.0), (lon - dLon).coerceAtLeast(-180.0),
                (lat + UMKREIS_GRAD).coerceAtMost(90.0), (lon + dLon).coerceAtMost(180.0),
            )
        }
        val treffer: Address? = withTimeoutOrNull(10_000) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    val listener = object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    if (box == null) geocoder.getFromLocationName(adresse, 1, listener)
                    else geocoder.getFromLocationName(adresse, 1, box[0], box[1], box[2], box[3], listener)
                }
            } else {
                withContext(Dispatchers.IO) {
                    runCatching {
                        if (box == null) geocoder.getFromLocationName(adresse, 1)?.firstOrNull()
                        else geocoder.getFromLocationName(adresse, 1, box[0], box[1], box[2], box[3])?.firstOrNull()
                    }.getOrNull()
                }
            }
        }
        return treffer?.takeIf { it.hasLatitude() && it.hasLongitude() }?.let { it.latitude to it.longitude }
    }

    /** Halbe Kantenlänge des Suchgebiets in Breitengraden (0,9° ≈ 100 km). */
    private const val UMKREIS_GRAD = 0.9

    @Suppress("DEPRECATION")
    private suspend fun abfragen(context: Context, lat: Double, lon: Double): Address? {
        val geocoder = Geocoder(context, Locale.getDefault())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (cont.isActive) cont.resume(addresses.firstOrNull())
                    }

                    override fun onError(errorMessage: String?) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }
        } else {
            withContext(Dispatchers.IO) {
                runCatching { geocoder.getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull()
            }
        }
    }

    private fun formatieren(a: Address): String {
        val strasse = listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(" ").trim()
        val ort = listOfNotNull(a.postalCode, a.locality ?: a.subAdminArea).joinToString(" ").trim()
        val teile = listOf(strasse, ort).filter { it.isNotBlank() }
        return if (teile.isNotEmpty()) teile.joinToString(", ") else a.getAddressLine(0).orEmpty()
    }
}
