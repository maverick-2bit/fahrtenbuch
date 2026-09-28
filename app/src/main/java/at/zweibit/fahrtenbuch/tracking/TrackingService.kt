package at.zweibit.fahrtenbuch.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import at.zweibit.fahrtenbuch.FahrtenbuchApp
import at.zweibit.fahrtenbuch.blitzer.BlitzerDaten
import at.zweibit.fahrtenbuch.blitzer.BlitzerPruefer
import at.zweibit.fahrtenbuch.blitzer.BlitzerText
import at.zweibit.fahrtenbuch.blitzer.Warnausgabe
import at.zweibit.fahrtenbuch.data.EinstellungenWerte
import at.zweibit.fahrtenbuch.data.Fahrt
import at.zweibit.fahrtenbuch.data.FahrtStatus
import at.zweibit.fahrtenbuch.data.Trackpunkt
import at.zweibit.fahrtenbuch.data.Zwischenziel
import at.zweibit.fahrtenbuch.data.Zwischenziele
import at.zweibit.fahrtenbuch.data.pausiert
import at.zweibit.fahrtenbuch.data.zwischenzieleListe
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Vordergrund-Dienst, der eine Fahrt per GPS aufzeichnet.
 *
 * Ablauf: Start → aktuelle Position + Startadresse (vorgegeben, gespeicherter Ort oder GPS) →
 * laufende Positionsupdates (Strecke summieren, Punkte speichern) → optional Pausen mit
 * Zwischenziel → Ende per Knopf oder automatisch nach einstellbarer Stillstandszeit →
 * Zieladresse → Status „offen“ → Kategorie-Abfrage.
 */
class TrackingService : LifecycleService() {

    companion object {
        const val ACTION_START = "at.zweibit.fahrtenbuch.START"
        const val ACTION_STOPP = "at.zweibit.fahrtenbuch.STOPP"
        const val ACTION_PAUSE = "at.zweibit.fahrtenbuch.PAUSE"
        const val ACTION_WEITER = "at.zweibit.fahrtenbuch.WEITER"
        private const val EXTRA_START_ADRESSE = "start_adresse"

        /** Fahrten unter dieser Strecke werden beim automatischen Ende verworfen. */
        private const val MIN_FAHRT_METER = 100.0

        /** @param startAdresse vorgegebene Startadresse (gespeicherter Ort); null = per GPS */
        fun starten(context: Context, startAdresse: String? = null) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrackingService::class.java).setAction(ACTION_START)
                    .apply { if (!startAdresse.isNullOrBlank()) putExtra(EXTRA_START_ADRESSE, startAdresse) },
            )
        }

        fun stoppen(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOPP))
        }

        fun pausieren(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_PAUSE))
        }

        fun weiterfahren(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_WEITER))
        }
    }

    private val app by lazy { application as FahrtenbuchApp }
    private val repo by lazy { app.repository }
    private lateinit var fused: FusedLocationProviderClient

    private val mutex = Mutex()
    private var fahrt: Fahrt? = null
    private var rechner: StreckenRechner? = null
    private var stillstand: StillstandErkennung? = null
    private var letzteLocation: Location? = null
    private var updatesAktiv = false
    private var tickerJob: Job? = null
    private var beendet = false
    private var pausiert = false
    private var letzteStartId = 0

    // Blitzer-Warnung
    private var pruefer: BlitzerPruefer? = null
    private var prueferStand: Long? = null
    private var warnausgabe: Warnausgabe? = null
    private var ansage = true
    private var kursBasis: Location? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val punkte = result.locations.toList()
            lifecycleScope.launch { punkte.forEach { verarbeiten(it) } }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        Benachrichtigungen.kanaeleAnlegen(this)
        lifecycleScope.launch {
            combine(app.einstellungen.werte, BlitzerDaten.info) { w, _ -> w }
                .collect { blitzerEinrichten(it) }
        }
    }

    /** Schaltet die Blitzer-Warnung je nach Einstellung und vorhandenen Daten ein oder aus. */
    private suspend fun blitzerEinrichten(w: EinstellungenWerte) {
        ansage = w.blitzerAnsage
        if (w.blitzerWarnung) {
            val liste = BlitzerDaten.liste(this)
            val stand = BlitzerDaten.info.value?.stand
            if (liste.isNotEmpty() && (pruefer == null || stand != prueferStand)) {
                pruefer = BlitzerPruefer(liste)
                prueferStand = stand
            }
            if (pruefer != null && warnausgabe == null) warnausgabe = Warnausgabe(this)
        } else {
            pruefer = null
            prueferStand = null
            warnausgabe?.beenden()
            warnausgabe = null
        }
        val p = pruefer
        LiveStatus.zustand.update {
            it.copy(blitzerAktiv = p != null, blitzerAnzahl = p?.anzahl ?: 0, voraus = if (p == null) null else it.voraus)
        }
    }

    private fun blitzerPruefen(loc: Location) {
        val basis = kursBasis
        val kmh = when {
            loc.hasSpeed() -> loc.speed * 3.6
            basis != null && loc.time > basis.time -> loc.distanceTo(basis) / ((loc.time - basis.time) / 1000.0) * 3.6
            else -> 0.0
        }
        val kurs: Double? = when {
            loc.hasBearing() && kmh >= 8.0 -> loc.bearing.toDouble()
            basis != null && loc.distanceTo(basis) >= 10f ->
                Geo.peilung(basis.latitude, basis.longitude, loc.latitude, loc.longitude)
            else -> null
        }
        if (basis == null || loc.distanceTo(basis) >= 10f) kursBasis = loc

        val p = pruefer
        val erg = if (p != null && loc.accuracy <= 50f) {
            p.pruefen(loc.latitude, loc.longitude, kurs, kmh, System.currentTimeMillis())
        } else null
        LiveStatus.zustand.update { it.copy(kmh = kmh.roundToInt(), voraus = erg?.voraus) }
        erg?.neu?.let { w ->
            warnausgabe?.warnen(BlitzerText.ansage(w), ansage, dringend = w.zuSchnell)
            Benachrichtigungen.blitzer(this, BlitzerText.kurz(w), w.zuSchnell)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        letzteStartId = startId
        when (intent?.action) {
            ACTION_STOPP -> {
                vordergrundAktuell()
                lifecycleScope.launch { beenden(automatisch = false, startId = startId) }
            }
            ACTION_PAUSE -> {
                vordergrundAktuell()
                lifecycleScope.launch { pausieren() }
            }
            ACTION_WEITER -> {
                vordergrundAktuell()
                lifecycleScope.launch { weiterfahren() }
            }
            // START oder Neustart durch das System (intent == null): laufende Fahrt fortsetzen
            else -> {
                vordergrund(System.currentTimeMillis(), 0.0, "")
                val vorgabe = intent?.getStringExtra(EXTRA_START_ADRESSE)
                lifecycleScope.launch { startenOderFortsetzen(vorgabe) }
            }
        }
        return START_STICKY
    }

    private fun vordergrundAktuell() {
        vordergrund(fahrt?.startZeit ?: System.currentTimeMillis(), rechner?.meter ?: 0.0, fahrt?.startAdresse.orEmpty())
    }

    private fun vordergrund(startZeit: Long, meter: Double, adresse: String) {
        val n = Benachrichtigungen.laufend(this, startZeit, meter, adresse)
        val typ = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, Benachrichtigungen.ID_LAUFEND, n, typ)
        } catch (e: Exception) {
            // z. B. Standortrecht entzogen – ohne Recht ist keine Aufzeichnung möglich
            stopSelf()
        }
    }

    private suspend fun startenOderFortsetzen(startAdresse: String? = null) {
        val vorhanden = mutex.withLock {
            if (fahrt != null && !beendet) return
            if (beendet) {
                // Neue Fahrt direkt nach dem Beenden der vorigen im selben Dienst
                beendet = false
                pausiert = false
                fahrt = null
                letzteLocation = null
                vordergrund(System.currentTimeMillis(), 0.0, "")
            }
            val laufend = repo.laufendeFahrtEinmal()
            if (laufend != null) {
                // Fortsetzen nach Prozessende: Strecke, letzten Punkt und Pause wiederherstellen
                val letzter = repo.letzterPunkt(laufend.id)
                    ?.let { GeoPunkt(it.lat, it.lon, it.zeit, it.genauigkeit) }
                fahrt = laufend
                pausiert = laufend.pausiert
                rechner = StreckenRechner(laufend.distanzMeter, letzter)
                stillstand = StillstandErkennung(letzter?.zeit ?: System.currentTimeMillis())
                    .also { s -> letzter?.let { s.punkt(it) } }
                true
            } else {
                val jetzt = System.currentTimeMillis()
                val neu = Fahrt(startZeit = jetzt, startAdresse = startAdresse.orEmpty().trim(), status = FahrtStatus.LAUFEND)
                fahrt = neu.copy(id = repo.fahrtAnlegen(neu))
                pausiert = false
                rechner = StreckenRechner()
                stillstand = StillstandErkennung(jetzt)
                false
            }
        }
        benachrichtigungAktualisieren()
        if (!pausiert) updatesStarten()
        tickerStarten()
        if (!vorhanden) startpositionErmitteln()
    }

    /** Stellt nach einem Neustart des Dienstes die laufende Fahrt wieder her. */
    private suspend fun wiederherstellen(): Boolean {
        if (fahrt != null && !beendet) return true
        if (repo.laufendeFahrtEinmal() == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return false
        }
        startenOderFortsetzen()
        return fahrt != null
    }

    /**
     * Pause unterwegs: Die aktuelle Position wird Zwischenziel, die Aufzeichnung ruht,
     * und die Fahrt endet nicht automatisch. Mit [weiterfahren] geht es als dieselbe Fahrt weiter.
     */
    private suspend fun pausieren() {
        if (!wiederherstellen()) return
        mutex.withLock {
            val f = fahrt?.let { repo.fahrt(it.id) } ?: return@withLock
            if (beendet || f.status != FahrtStatus.LAUFEND || f.pausiert) return@withLock
            updatesStoppen()
            val jetzt = System.currentTimeMillis()
            // Ankunft = Zeitpunkt des Anhaltens, wenn das Auto schon kurz steht
            val an = stillstand?.letzteBewegung?.takeIf { jetzt - it in 0..10 * 60_000L } ?: jetzt
            val lat = letzteLocation?.latitude ?: rechner?.letzter?.lat
            val lon = letzteLocation?.longitude ?: rechner?.letzter?.lon
            val liste = f.zwischenzieleListe + Zwischenziel(adresse = "", lat = lat, lon = lon, an = an)
            repo.zwischenzieleSetzen(f.id, liste)
            fahrt = f.copy(zwischenziele = Zwischenziele.schreiben(liste))
            pausiert = true
            LiveStatus.zustand.update { it.copy(kmh = null, voraus = null) }
        }
        benachrichtigungAktualisieren()
        zwischenzielAdresseErmitteln()
    }

    /** Adresse des gerade erreichten Zwischenziels nachtragen (gespeicherter Ort oder GPS-Adresse). */
    private suspend fun zwischenzielAdresseErmitteln() {
        val f = fahrt ?: return
        val z = f.zwischenzieleListe.lastOrNull() ?: return
        if (z.adresse.isNotBlank() || z.lat == null || z.lon == null) return
        val adresse = Adressen.bestimmen(this, z.lat, z.lon, app.einstellungen.orteAktuell())
        mutex.withLock {
            val db = repo.fahrt(f.id) ?: return@withLock
            val liste = db.zwischenzieleListe.toMutableList()
            val i = liste.indexOfLast { it.an == z.an && it.lat == z.lat && it.lon == z.lon }
            // Nicht überschreiben, falls inzwischen von Hand eingetragen
            if (i < 0 || liste[i].adresse.isNotBlank()) return@withLock
            liste[i] = liste[i].copy(adresse = adresse)
            repo.zwischenzieleSetzen(f.id, liste)
            val m = fahrt
            if (m != null && m.id == f.id) fahrt = m.copy(zwischenziele = Zwischenziele.schreiben(liste))
        }
        benachrichtigungAktualisieren()
    }

    private suspend fun weiterfahren() {
        if (!wiederherstellen()) return
        mutex.withLock {
            val f = fahrt?.let { repo.fahrt(it.id) } ?: return@withLock
            if (beendet || !f.pausiert) return@withLock
            val jetzt = System.currentTimeMillis()
            val liste = f.zwischenzieleListe.toMutableList()
            liste[liste.lastIndex] = liste.last().copy(ab = jetzt)
            repo.zwischenzieleSetzen(f.id, liste)
            fahrt = f.copy(zwischenziele = Zwischenziele.schreiben(liste))
            pausiert = false
            // Stillstand zählt ab der Weiterfahrt neu, sonst würde die Fahrt sofort automatisch enden
            stillstand = StillstandErkennung(jetzt)
            kursBasis = null
            updatesStarten()
        }
        benachrichtigungAktualisieren()
    }

    @SuppressLint("MissingPermission")
    private suspend fun startpositionErmitteln() {
        val loc = withTimeoutOrNull(20_000) {
            runCatching {
                fused.getCurrentLocation(
                    CurrentLocationRequest.Builder()
                        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                        .setMaxUpdateAgeMillis(30_000)
                        .build(),
                    null,
                ).await()
            }.getOrNull()
        } ?: letzteLocation ?: return
        val f = fahrt ?: return
        // Evtl. hat das erste Positionsupdate die Startposition schon gesetzt – dann diese verwenden.
        val lat = f.startLat ?: loc.latitude
        val lon = f.startLon ?: loc.longitude
        if (f.startLat == null) {
            repo.startPositionSetzen(f.id, lat, lon)
            fahrt = f.copy(startLat = lat, startLon = lon)
        }
        if (f.startAdresse.isNotBlank()) return
        val adresse = Adressen.bestimmen(this, lat, lon, app.einstellungen.orteAktuell())
        // Nicht überschreiben, falls die Startadresse inzwischen von Hand gesetzt wurde
        if (repo.fahrt(f.id)?.startAdresse?.isNotBlank() == true) return
        repo.startAdresseSetzen(f.id, adresse)
        // Während der Adresssuche kann die Fahrt beendet oder eine neue begonnen worden sein.
        val aktuell = fahrt
        if (aktuell != null && aktuell.id == f.id) fahrt = aktuell.copy(startAdresse = adresse)
        benachrichtigungAktualisieren()
    }

    @SuppressLint("MissingPermission")
    private fun updatesStarten() {
        if (updatesAktiv) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4_000)
            .setMinUpdateIntervalMillis(2_000)
            .setMinUpdateDistanceMeters(5f)
            .build()
        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
            updatesAktiv = true
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun updatesStoppen() {
        if (updatesAktiv) fused.removeLocationUpdates(callback)
        updatesAktiv = false
    }

    private suspend fun verarbeiten(loc: Location) = mutex.withLock {
        val f = fahrt ?: return@withLock
        if (beendet || pausiert) return@withLock
        letzteLocation = loc
        val p = GeoPunkt(loc.latitude, loc.longitude, loc.time, loc.accuracy)
        if (loc.accuracy <= 50f) stillstand?.punkt(p)
        val r = rechner ?: return@withLock
        if (f.startLat == null) {
            repo.startPositionSetzen(f.id, p.lat, p.lon)
            fahrt = f.copy(startLat = p.lat, startLon = p.lon)
        }
        if (r.hinzufuegen(p)) {
            repo.punktSpeichern(
                Trackpunkt(
                    fahrtId = f.id, zeit = p.zeit, lat = p.lat, lon = p.lon,
                    genauigkeit = loc.accuracy, geschwindigkeit = loc.speed,
                )
            )
            repo.distanzSetzen(f.id, r.meter)
        }
        blitzerPruefen(loc)
    }

    private fun tickerStarten() {
        if (tickerJob?.isActive == true) return
        tickerJob = lifecycleScope.launch {
            while (isActive) {
                delay(20_000)
                // Änderungen aus der App (Startadresse, Zwischenziele) für die Benachrichtigung übernehmen
                mutex.withLock {
                    val m = fahrt
                    val db = m?.let { repo.fahrt(it.id) }
                    if (m != null && db != null && fahrt?.id == m.id) {
                        fahrt = m.copy(startAdresse = db.startAdresse, startZeit = db.startZeit, zwischenziele = db.zwischenziele)
                    }
                }
                benachrichtigungAktualisieren()
                if (pausiert) continue
                val minuten = app.einstellungen.aktuell().autoStoppMinuten
                val s = stillstand ?: continue
                if (minuten > 0 && s.stillstandMillis(System.currentTimeMillis()) >= minuten * 60_000L) {
                    beenden(automatisch = true, startId = letzteStartId)
                    break
                }
            }
        }
    }

    private fun benachrichtigungAktualisieren() {
        val f = fahrt ?: return
        if (beendet) return
        val meter = rechner?.meter ?: f.distanzMeter
        val n = if (pausiert) {
            val z = f.zwischenzieleListe.lastOrNull()
            Benachrichtigungen.pausiert(this, meter, z?.adresse.orEmpty(), z?.an ?: System.currentTimeMillis())
        } else {
            Benachrichtigungen.laufend(this, f.startZeit, meter, f.startAdresse)
        }
        try {
            NotificationManagerCompat.from(this).notify(Benachrichtigungen.ID_LAUFEND, n)
        } catch (_: SecurityException) {
        }
    }

    /**
     * Beendet die Fahrt. [startId] ist die Start-ID zum Zeitpunkt des Beendens: kam inzwischen
     * ein neuer Start-Befehl, bleibt der Dienst für die neue Fahrt aktiv.
     */
    private suspend fun beenden(automatisch: Boolean, startId: Int) = mutex.withLock {
        if (beendet) return@withLock
        beendet = true
        updatesStoppen()
        if (automatisch) tickerJob = null else tickerJob?.cancel()
        // Nach einem Prozess-Neustart kennt der Dienst die Fahrt evtl. noch nicht → aus der DB holen
        val f = fahrt?.let { repo.fahrt(it.id) } ?: repo.laufendeFahrtEinmal()
        val ergebnis = if (f == null || f.status != FahrtStatus.LAUFEND) null else {
            val meter = rechner?.meter ?: f.distanzMeter
            if (automatisch && meter < MIN_FAHRT_METER && f.zwischenzieleListe.isEmpty()) {
                repo.fahrtLoeschen(f.id)
                null
            } else {
                // Beenden während einer Pause: Das Zwischenziel ist in Wahrheit das Ziel.
                val zwischen = f.zwischenzieleListe
                val pausenZiel = if (f.pausiert) zwischen.last() else null
                val restliche = if (pausenZiel != null) zwischen.dropLast(1) else zwischen
                // Bei automatischem Ende zählt der Zeitpunkt, an dem das Fahrzeug zum Stehen kam.
                val ende = when {
                    pausenZiel?.an != null -> pausenZiel.an
                    automatisch -> stillstand?.letzteBewegung ?: System.currentTimeMillis()
                    else -> System.currentTimeMillis()
                }
                val dbPunkt =
                    if (pausenZiel == null && letzteLocation == null && rechner?.letzter == null) repo.letzterPunkt(f.id) else null
                val lat = pausenZiel?.lat ?: letzteLocation?.latitude ?: rechner?.letzter?.lat ?: dbPunkt?.lat
                val lon = pausenZiel?.lon ?: letzteLocation?.longitude ?: rechner?.letzter?.lon ?: dbPunkt?.lon
                val adresse = pausenZiel?.adresse?.takeIf { it.isNotBlank() }
                    ?: if (lat != null && lon != null) Adressen.bestimmen(this, lat, lon, app.einstellungen.orteAktuell()) else ""
                val fertig = f.copy(
                    endeZeit = maxOf(ende, f.startZeit),
                    endeLat = lat,
                    endeLon = lon,
                    endeAdresse = adresse,
                    distanzMeter = meter,
                    zwischenziele = Zwischenziele.schreiben(restliche),
                    status = FahrtStatus.OFFEN,
                )
                repo.fahrtSpeichern(fertig)
                fertig
            }
        }
        fahrt = null
        pausiert = false
        rechner = null
        stillstand = null
        kursBasis = null
        LiveStatus.zustand.update { it.copy(kmh = null, voraus = null) }
        if (ergebnis != null) {
            Benachrichtigungen.beendet(
                this, ergebnis.id, ergebnis.distanzMeter, ergebnis.endeAdresse,
                repo.alleKategorien().filter { it.aktiv },
            )
        }
        if (startId == letzteStartId) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    override fun onDestroy() {
        updatesStoppen()
        warnausgabe?.beenden()
        warnausgabe = null
        LiveStatus.zuruecksetzen()
        super.onDestroy()
    }
}
