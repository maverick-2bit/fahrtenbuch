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
import at.zweibit.fahrtenbuch.data.BtGeraet
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
 *
 * Automatischer Start: Verbindet sich das Handy mit dem Auto (Bluetooth), wartet der Dienst, bis das
 * Auto losfährt ([LosfahrErkennung]), und beginnt dann die Fahrt am Parkplatz. Eine pausierte Fahrt
 * geht so automatisch weiter. Solange das Auto verbunden ist, beendet Stillstand die Fahrt nicht.
 */
class TrackingService : LifecycleService() {

    companion object {
        const val ACTION_START = "at.zweibit.fahrtenbuch.START"
        const val ACTION_STOPP = "at.zweibit.fahrtenbuch.STOPP"
        const val ACTION_PAUSE = "at.zweibit.fahrtenbuch.PAUSE"
        const val ACTION_WEITER = "at.zweibit.fahrtenbuch.WEITER"
        const val ACTION_BEREIT = "at.zweibit.fahrtenbuch.BEREIT"
        const val ACTION_BT_GETRENNT = "at.zweibit.fahrtenbuch.BT_GETRENNT"
        const val ACTION_NICHT_AUFZEICHNEN = "at.zweibit.fahrtenbuch.NICHT_AUFZEICHNEN"
        const val ACTION_START_KORRIGIEREN = "at.zweibit.fahrtenbuch.START_KORRIGIEREN"
        private const val EXTRA_START_ADRESSE = "start_adresse"
        private const val EXTRA_AUTO_ADRESSE = "auto_adresse"
        private const val EXTRA_AUTO_NAME = "auto_name"

        /** So lange wartet der Dienst nach dem Verbinden mit dem Auto auf das Losfahren. */
        private const val WARTEN_MAX_MS = 30 * 60_000L

        /** Längstens so lange hält eine Verbindung zum Auto das automatische Fahrtende auf. */
        private const val AUSSETZEN_MAX_MS = 2 * 3600_000L

        private fun Intent.mitAuto(g: BtGeraet): Intent = putExtra(EXTRA_AUTO_ADRESSE, g.adresse).putExtra(EXTRA_AUTO_NAME, g.name)

        private fun autoAus(intent: Intent?): BtGeraet? =
            intent?.getStringExtra(EXTRA_AUTO_ADRESSE)?.let { BtGeraet(it, intent.getStringExtra(EXTRA_AUTO_NAME) ?: it) }

        /** Mit dem Auto verbunden: auf das Losfahren warten (Start als Vordergrunddienst). */
        fun bereitIntent(context: Context, g: BtGeraet): Intent =
            Intent(context, TrackingService::class.java).setAction(ACTION_BEREIT).mitAuto(g)

        fun getrenntIntent(context: Context, g: BtGeraet): Intent =
            Intent(context, TrackingService::class.java).setAction(ACTION_BT_GETRENNT).mitAuto(g)

        /** Fahrt sofort starten; [g] = Auto, mit dem das Handy verbunden ist (aus der Nachfrage-Benachrichtigung). */
        fun startIntent(context: Context, g: BtGeraet? = null): Intent =
            Intent(context, TrackingService::class.java).setAction(ACTION_START).apply { if (g != null) mitAuto(g) }

        /** Warten auf das Losfahren abbrechen („Nicht aufzeichnen“). */
        fun nichtAufzeichnen(context: Context) {
            runCatching { context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_NICHT_AUFZEICHNEN)) }
        }

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

        /** Startadresse der laufenden Fahrt wurde korrigiert: fehlende Kilometer und Abfahrt nachtragen. */
        fun startKorrigieren(context: Context, adresse: String) {
            runCatching {
                context.startService(
                    Intent(context, TrackingService::class.java).setAction(ACTION_START_KORRIGIEREN)
                        .putExtra(EXTRA_START_ADRESSE, adresse)
                )
            }
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

    // Automatischer Start per Bluetooth
    private var wartend: LosfahrErkennung? = null
    private var wartenJob: Job? = null

    /** Berechnung des Nachtrags nach korrigiertem Start – eine neuere Korrektur ersetzt sie. */
    private var korrekturJob: Job? = null

    /** Auto, mit dem das Handy verbunden ist – nur bekannt, solange der Dienst die Verbindung mitbekommen hat. */
    private var auto: BtGeraet? = null

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
            ACTION_BEREIT -> {
                val g = autoAus(intent)
                // Nach startForegroundService sofort in den Vordergrund – Pflicht bei Android
                if (fahrt != null && !beendet) vordergrundAktuell() else vordergrundWarten(g?.name ?: "Auto")
                if (g != null) lifecycleScope.launch { bereitMachen(g) } else lifecycleScope.launch { wartenBeenden(ohneWarten = true) }
            }
            ACTION_BT_GETRENNT -> lifecycleScope.launch { getrennt(autoAus(intent)) }
            ACTION_NICHT_AUFZEICHNEN -> lifecycleScope.launch { wartenBeenden() }
            ACTION_START_KORRIGIEREN -> {
                // Mit laufender Fahrt ist der Dienst schon im Vordergrund, nach einem Prozessende noch nicht
                if (fahrt == null) vordergrundAktuell()
                val adresse = intent.getStringExtra(EXTRA_START_ADRESSE).orEmpty()
                korrekturJob?.cancel()
                korrekturJob = lifecycleScope.launch { startKorrigieren(adresse) }
            }
            // Neustart durch das System: nur eine laufende Fahrt fortsetzen, nie eine neue beginnen
            null -> {
                vordergrund(System.currentTimeMillis(), 0.0, "")
                lifecycleScope.launch { wiederherstellen() }
            }
            else -> {
                autoAus(intent)?.let { auto = it }
                Benachrichtigungen.autoStartFrageEntfernen(this)
                vordergrund(System.currentTimeMillis(), 0.0, "")
                val vorgabe = intent.getStringExtra(EXTRA_START_ADRESSE)
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

    private fun vordergrundWarten(name: String) {
        val typ = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, Benachrichtigungen.ID_LAUFEND, Benachrichtigungen.wartet(this, name), typ)
        } catch (e: Exception) {
            // Ohne Standortrecht im Hintergrund lässt Android den Dienst nicht zu
            stopSelf()
        }
    }

    private suspend fun startenOderFortsetzen(startAdresse: String? = null) {
        val vorhanden = mutex.withLock {
            if (fahrt != null && !beendet) return
            // Manueller Start während des Wartens auf das Losfahren: Warten ist erledigt
            wartend = null
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
        wartenJob?.cancel()
        wartenJob = null
        LiveStatus.zustand.update { it.copy(wartetAuf = null, auto = auto?.name) }
        benachrichtigungAktualisieren()
        if (!pausiert) updatesStarten()
        tickerStarten()
        if (!vorhanden) startpositionErmitteln()
    }

    // ------------------------------------------------ Automatischer Start per Bluetooth

    /** Das Handy ist mit dem Auto verbunden: Verbindung merken und auf das Losfahren warten. */
    private suspend fun bereitMachen(g: BtGeraet) {
        auto = g
        Benachrichtigungen.autoStartFrageEntfernen(this)
        // Läuft schon eine Fahrt (auch nach einem Neustart des Dienstes), bleibt sie – mit dem Auto als Verbindung
        val laeuft = mutex.withLock { fahrt != null && !beendet } || repo.laufendeFahrtEinmal()?.also { startenOderFortsetzen() } != null
        if (laeuft) {
            LiveStatus.zustand.update { it.copy(auto = g.name) }
            // Pausiert (z. B. beim Kunden): Beim Losfahren geht dieselbe Fahrt automatisch weiter
            if (pausiert) wartenBeginnen(g)
            benachrichtigungAktualisieren()
            return
        }
        wartenBeginnen(g)
    }

    private suspend fun wartenBeginnen(g: BtGeraet) {
        val ohneFahrt = mutex.withLock {
            wartend = LosfahrErkennung()
            fahrt == null || beendet
        }
        LiveStatus.zustand.update { it.copy(wartetAuf = if (ohneFahrt) g.name else null, auto = g.name) }
        if (ohneFahrt) vordergrundWarten(g.name)
        updatesStarten()
        wartenJob?.cancel()
        wartenJob = lifecycleScope.launch {
            delay(WARTEN_MAX_MS)
            wartenJob = null
            wartenBeenden()
        }
    }

    /**
     * Warten beenden (Zeit abgelaufen, Verbindung getrennt oder „Nicht aufzeichnen“). Ohne Fahrt endet
     * der Dienst, eine pausierte Fahrt bleibt pausiert.
     */
    private suspend fun wartenBeenden(ohneWarten: Boolean = false) {
        val ohneFahrt = mutex.withLock {
            if (wartend == null && !ohneWarten) {
                stoppenWennUntaetig()
                return
            }
            wartend = null
            fahrt == null || beendet
        }
        wartenJob?.cancel()
        wartenJob = null
        LiveStatus.zustand.update { it.copy(wartetAuf = null) }
        if (ohneFahrt) {
            updatesStoppen()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else if (pausiert) {
            updatesStoppen()
        }
    }

    /** Das Auto ist losgefahren: Fahrt am Parkplatz beginnen – oder die pausierte Fahrt fortsetzen. */
    private suspend fun losfahren(loc: Location) {
        val w = mutex.withLock { wartend.also { wartend = null } } ?: return
        wartenJob?.cancel()
        wartenJob = null
        LiveStatus.zustand.update { it.copy(wartetAuf = null) }
        if (mutex.withLock { fahrt != null && !beendet }) {
            if (pausiert) weiterfahren(ab = w.startPunkt?.zeit)
            return
        }
        val start = w.startPunkt ?: return
        val neu = mutex.withLock {
            val f = Fahrt(startZeit = start.zeit, startLat = start.lat, startLon = start.lon, status = FahrtStatus.LAUFEND)
            val id = repo.fahrtAnlegen(f)
            val r = StreckenRechner(0.0, start)
            for (p in w.spur) {
                if (r.hinzufuegen(p)) {
                    repo.punktSpeichern(Trackpunkt(fahrtId = id, zeit = p.zeit, lat = p.lat, lon = p.lon, genauigkeit = p.genauigkeit, geschwindigkeit = 0f))
                }
            }
            repo.distanzSetzen(id, r.meter)
            rechner = r
            stillstand = StillstandErkennung(start.zeit).also { s -> w.spur.forEach(s::punkt) }
            beendet = false
            pausiert = false
            letzteLocation = loc
            kursBasis = null
            f.copy(id = id, distanzMeter = r.meter).also { fahrt = it }
        }
        LiveStatus.zustand.update { it.copy(auto = auto?.name) }
        benachrichtigungAktualisieren()
        tickerStarten()
        // Startadresse: gespeicherter Ort im Umkreis oder die Adresse des Parkplatzes
        val adresse = Adressen.bestimmen(this, start.lat, start.lon, app.einstellungen.orteAktuell())
        mutex.withLock {
            if (repo.fahrt(neu.id)?.startAdresse?.isBlank() != true) return@withLock
            repo.startAdresseSetzen(neu.id, adresse)
            val m = fahrt
            if (m != null && m.id == neu.id) fahrt = m.copy(startAdresse = adresse)
        }
        benachrichtigungAktualisieren()
    }

    /** Verbindung zum Auto getrennt: Warten endet; eine laufende Fahrt endet wieder nach Stillstand. */
    private suspend fun getrennt(g: BtGeraet?) {
        val passt = g != null && auto?.adresse?.equals(g.adresse, ignoreCase = true) == true
        if (passt) {
            auto = null
            LiveStatus.zustand.update { it.copy(auto = null) }
        }
        // Nur das Trennen des Autos, auf das gewartet wird, beendet das Warten
        if (passt && wartend != null) wartenBeenden() else mutex.withLock { stoppenWennUntaetig() }
    }

    /**
     * Kam eine Meldung (Trennen, „Nicht aufzeichnen“) bei einem Dienst ohne Fahrt und ohne Warten an,
     * hat ihn Android nur dafür gestartet – dann gleich wieder beenden. Nur unter [mutex] aufrufen.
     */
    private fun stoppenWennUntaetig() {
        if ((fahrt == null || beendet) && wartend == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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
        // Mit dem Auto verbunden: Fährt es wieder los, geht dieselbe Fahrt automatisch weiter
        auto?.let { wartenBeginnen(it) }
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

    /** @param ab Zeitpunkt der Weiterfahrt, wenn er schon feststeht (automatisch beim Losfahren erkannt) */
    private suspend fun weiterfahren(ab: Long? = null) {
        if (!wiederherstellen()) return
        mutex.withLock {
            val f = fahrt?.let { repo.fahrt(it.id) } ?: return@withLock
            if (beendet || !f.pausiert) return@withLock
            wartend = null
            val jetzt = System.currentTimeMillis()
            val liste = f.zwischenzieleListe.toMutableList()
            liste[liste.lastIndex] = liste.last().copy(ab = (ab ?: jetzt).coerceAtLeast(liste.last().an ?: 0L))
            repo.zwischenzieleSetzen(f.id, liste)
            fahrt = f.copy(zwischenziele = Zwischenziele.schreiben(liste))
            pausiert = false
            // Stillstand zählt ab der Weiterfahrt neu, sonst würde die Fahrt sofort automatisch enden
            stillstand = StillstandErkennung(jetzt)
            kursBasis = null
            updatesStarten()
        }
        wartenJob?.cancel()
        wartenJob = null
        LiveStatus.zustand.update { it.copy(wartetAuf = null) }
        benachrichtigungAktualisieren()
    }

    /**
     * Startadresse korrigiert, weil der Start zu spät gedrückt wurde (die Adresse hat die App schon
     * gespeichert): Die Strecke vom echten Start bis zum Beginn der Aufzeichnung wird über die Straße
     * nachgerechnet und die Abfahrt um die Fahrzeit vorverlegt. Ein früherer Nachtrag wird ersetzt.
     */
    private suspend fun startKorrigieren(adresse: String) {
        if (adresse.isBlank() || !wiederherstellen()) return
        val f = mutex.withLock { fahrt?.let { repo.fahrt(it.id) } } ?: return
        if (f.status != FahrtStatus.LAUFEND) return
        LiveStatus.zustand.update { it.copy(nachtrag = NachtragStand("Kilometer ab dem neuen Start werden berechnet …")) }
        val ergebnis = StartKorrektur.berechnen(app, f, adresse)
        mutex.withLock {
            val db = repo.fahrt(f.id)
            // Inzwischen erneut korrigiert: Dann zählt die neuere Korrektur
            if (db == null || db.startAdresse.trim() != adresse.trim()) return@withLock
            if (ergebnis is NachtragErgebnis.Fehler) {
                LiveStatus.zustand.update { it.copy(nachtrag = NachtragStand("Kilometer nicht ergänzt: ${ergebnis.text}", fehler = true)) }
                return@withLock
            }
            val n = (ergebnis as NachtragErgebnis.Ok).nachtrag
            val r = rechner
            val m = fahrt
            when {
                db.status == FahrtStatus.LAUFEND && !beendet && r != null && m != null && m.id == db.id -> {
                    // Der Streckenrechner zählt ab dem nachgetragenen Stand weiter
                    r.nachtragen(n.meter - db.nachtragMeter)
                    val neu = StartKorrektur.anwenden(db, n).copy(distanzMeter = r.meter)
                    repo.nachtragSetzen(neu)
                    fahrt = m.copy(
                        startZeit = neu.startZeit, startAdresse = neu.startAdresse, distanzMeter = neu.distanzMeter,
                        nachtragMeter = neu.nachtragMeter, nachtragMs = neu.nachtragMs,
                    )
                }
                // Während der Berechnung beendet und noch nicht zugeordnet
                db.status == FahrtStatus.OFFEN -> repo.nachtragSetzen(StartKorrektur.anwenden(db, n))
                db.status == FahrtStatus.FERTIG -> repo.fahrtSpeichern(StartKorrektur.anwenden(db, n))
            }
            LiveStatus.zustand.update { it.copy(nachtrag = null) }
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

    private suspend fun verarbeiten(loc: Location) {
        if (wartend != null) {
            val los = mutex.withLock { wartend?.punkt(GeoPunkt(loc.latitude, loc.longitude, loc.time, loc.accuracy)) == true }
            if (los) losfahren(loc)
            return
        }
        aufzeichnen(loc)
    }

    private suspend fun aufzeichnen(loc: Location) = mutex.withLock {
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
                val still = s.stillstandMillis(System.currentTimeMillis())
                // Mit dem Auto verbunden: Stau, Ampel oder Warten mit laufendem Motor beenden die Fahrt nicht
                val ausgesetzt = auto != null && still < AUSSETZEN_MAX_MS
                if (minuten > 0 && still >= minuten * 60_000L && !ausgesetzt) {
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
    private suspend fun beenden(automatisch: Boolean, startId: Int) {
        if (!beendenIntern(automatisch, startId)) return
        val g = auto
        if (g != null && app.einstellungen.autoStartAktuell().aktiv) {
            // Noch mit dem Auto verbunden: Die nächste Fahrt startet wieder beim Losfahren
            wartenBeginnen(g)
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    /** @return true, wenn der Dienst danach nichts mehr zu tun hat (kein neuer Start dazwischen) */
    private suspend fun beendenIntern(automatisch: Boolean, startId: Int): Boolean = mutex.withLock {
        if (beendet) return@withLock false
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
        LiveStatus.zustand.update { it.copy(kmh = null, voraus = null, nachtrag = null) }
        if (ergebnis != null) {
            Benachrichtigungen.beendet(
                this, ergebnis.id, ergebnis.distanzMeter, ergebnis.endeAdresse,
                repo.alleKategorien().filter { it.aktiv },
            )
        }
        startId == letzteStartId
    }

    override fun onDestroy() {
        updatesStoppen()
        warnausgabe?.beenden()
        warnausgabe = null
        LiveStatus.zuruecksetzen()
        super.onDestroy()
    }
}
