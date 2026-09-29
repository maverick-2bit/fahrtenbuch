package at.zweibit.fahrtenbuch.data

import androidx.room.withTransaction
import at.zweibit.fahrtenbuch.sync.SyncFormat
import kotlinx.coroutines.flow.Flow

/**
 * Zugriff auf Kategorien und Fahrten. Jede Änderung einer abgeschlossenen Fahrt wird im
 * Änderungsprotokoll festgehalten (gleiche Transaktion); [nachAenderung] stößt danach die
 * Online-Sicherung an.
 */
class Repository(private val db: AppDatabase, private val nachAenderung: () -> Unit = {}) {
    private val kategorien = db.kategorieDao()
    private val fahrten = db.fahrtDao()
    private val protokoll = db.protokollDao()

    // Kategorien
    val kategorienFlow: Flow<List<Kategorie>> = kategorien.alle()
    suspend fun alleKategorien(): List<Kategorie> = kategorien.alleEinmal()

    suspend fun kategorieAnlegen(name: String, farbe: Long, privat: Boolean = false): Long =
        kategorien.einfuegen(Kategorie(name = name.trim(), farbe = farbe, sortierung = kategorien.maxSortierung() + 1, privat = privat))

    suspend fun kategorieAendern(kategorie: Kategorie) = kategorien.aktualisieren(kategorie.copy(name = kategorie.name.trim()))

    /**
     * Kategorien mit zugeordneten Fahrten werden nur ausgeblendet (aktiv = false),
     * damit alte Berichte ihre Zuordnung behalten. Unbenutzte werden gelöscht.
     */
    suspend fun kategorieEntfernen(kategorie: Kategorie) {
        if (kategorien.anzahlFahrten(kategorie.id) > 0) {
            kategorien.aktualisieren(kategorie.copy(aktiv = false))
        } else {
            kategorien.loeschen(kategorie)
        }
    }

    // Fahrten
    val laufendeFahrt: Flow<Fahrt?> = fahrten.laufende()
    val offeneFahrten: Flow<List<Fahrt>> = fahrten.offene()
    val alleFahrten: Flow<List<FahrtMitKategorie>> = fahrten.alleMitKategorie()

    suspend fun laufendeFahrtEinmal(): Fahrt? = fahrten.laufendeEinmal()
    suspend fun fahrt(id: Long): Fahrt? = fahrten.holen(id)

    suspend fun fahrtAnlegen(fahrt: Fahrt): Long {
        val id = db.withTransaction {
            val neu = fahrten.einfuegen(fahrt)
            neu to protokollieren(fahrt.copy(id = neu))
        }
        if (id.second) nachAenderung()
        return id.first
    }

    suspend fun fahrtSpeichern(fahrt: Fahrt) {
        val protokolliert = db.withTransaction {
            fahrten.aktualisieren(fahrt)
            protokollieren(fahrt)
        }
        if (protokolliert) nachAenderung()
    }

    suspend fun fahrtLoeschen(id: Long) {
        val protokolliert = db.withTransaction {
            val f = fahrten.holen(id)
            fahrten.loeschen(id)
            f != null && protokollieren(f, ProtokollAktion.GELOESCHT)
        }
        if (protokolliert) nachAenderung()
    }

    // Während der Aufzeichnung (laufende Fahrt) – noch nicht protokolliert
    suspend fun distanzSetzen(id: Long, meter: Double) = fahrten.distanzSetzen(id, meter)
    suspend fun startPositionSetzen(id: Long, lat: Double, lon: Double) = fahrten.startPositionSetzen(id, lat, lon)
    suspend fun startAdresseSetzen(id: Long, adresse: String) = fahrten.startAdresseSetzen(id, adresse)

    /** Kilometer, Abfahrt und Nachtrag nach korrigiertem Start (laufende oder noch nicht zugeordnete Fahrt). */
    suspend fun nachtragSetzen(f: Fahrt) = fahrten.nachtragSetzen(f.id, f.distanzMeter, f.startZeit, f.nachtragMeter, f.nachtragMs)
    suspend fun zwischenzieleSetzen(id: Long, liste: List<Zwischenziel>) =
        fahrten.zwischenzieleSetzen(id, Zwischenziele.schreiben(liste))
    suspend fun punktSpeichern(punkt: Trackpunkt) = fahrten.punktEinfuegen(punkt)
    suspend fun punkte(fahrtId: Long): List<Trackpunkt> = fahrten.punkte(fahrtId)
    suspend fun letzterPunkt(fahrtId: Long): Trackpunkt? = fahrten.letzterPunkt(fahrtId)

    suspend fun fahrtenImZeitraum(von: Long, bis: Long): List<FahrtMitKategorie> = fahrten.imZeitraum(von, bis)

    suspend fun kategorisieren(
        fahrtId: Long,
        kategorieId: Long,
        notiz: String,
        startAdresse: String,
        endeAdresse: String,
        zwischen: List<Zwischenziel>? = null,
    ) {
        val f = fahrten.holen(fahrtId) ?: return
        fahrtSpeichern(
            f.copy(
                kategorieId = kategorieId,
                notiz = notiz.trim(),
                startAdresse = startAdresse.trim(),
                endeAdresse = endeAdresse.trim(),
                zwischenziele = zwischen?.let { Zwischenziele.schreiben(it.map { z -> z.copy(adresse = z.adresse.trim()) }) }
                    ?: f.zwischenziele,
                status = FahrtStatus.FERTIG,
            )
        )
    }

    // Änderungsprotokoll
    val protokollOffen: Flow<Int> = protokoll.anzahlOffen()
    val protokollAbgelehnt: Flow<Int> = protokoll.anzahlAbgelehnt()
    suspend fun protokollOffeneEintraege(max: Int): List<ProtokollEintrag> = protokoll.offene(max)
    suspend fun protokollAngenommen(ids: List<String>) = protokoll.angenommen(ids)
    suspend fun protokollAbgelehnt(id: String, meldung: String) = protokoll.abgelehnt(id, meldung)
    suspend fun protokollEntfallen(ids: List<String>) = protokoll.entfallen(ids)
    suspend fun protokollZuFahrt(uuid: String): List<ProtokollEintrag> = protokoll.zuFahrt(uuid)

    /** Fahrten aus der Zeit vor dem Protokoll (vor Version 0.4) einmalig als „neu“ eintragen. */
    suspend fun altbestandProtokollieren(): Int = db.withTransaction {
        val alt = protokoll.fahrtenOhneProtokoll()
        alt.forEach { protokollieren(it, ProtokollAktion.NEU) }
        alt.size
    }

    /**
     * Protokolliert erst ab dem Zuordnen einer Kategorie (Status „fertig“): Bis dahin ist nicht bekannt,
     * ob es eine Privatfahrt ist, die nur verschlüsselt den Server erreichen darf.
     * Löschungen nur, wenn die Fahrt schon im Protokoll steht.
     * @return true, wenn ein Eintrag geschrieben wurde
     */
    private suspend fun protokollieren(f: Fahrt, aktion: String? = null): Boolean {
        val bekannt = protokoll.anzahl(f.uuid) > 0
        if (aktion == ProtokollAktion.GELOESCHT) {
            if (!bekannt) return false
        } else if (f.status != FahrtStatus.FERTIG) {
            return false
        }
        val a = aktion ?: if (!bekannt) ProtokollAktion.NEU else ProtokollAktion.GEAENDERT
        protokoll.einfuegen(
            ProtokollEintrag(
                fahrtUuid = f.uuid,
                aktion = a,
                zeit = System.currentTimeMillis(),
                daten = SyncFormat.fahrtDaten(f).toString(),
            )
        )
        return true
    }
}
