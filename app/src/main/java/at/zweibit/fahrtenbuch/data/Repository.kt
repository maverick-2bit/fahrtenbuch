package at.zweibit.fahrtenbuch.data

import kotlinx.coroutines.flow.Flow

class Repository(db: AppDatabase) {
    private val kategorien = db.kategorieDao()
    private val fahrten = db.fahrtDao()

    // Kategorien
    val kategorienFlow: Flow<List<Kategorie>> = kategorien.alle()
    suspend fun alleKategorien(): List<Kategorie> = kategorien.alleEinmal()

    suspend fun kategorieAnlegen(name: String, farbe: Long): Long =
        kategorien.einfuegen(Kategorie(name = name.trim(), farbe = farbe, sortierung = kategorien.maxSortierung() + 1))

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
    suspend fun fahrtAnlegen(fahrt: Fahrt): Long = fahrten.einfuegen(fahrt)
    suspend fun fahrtSpeichern(fahrt: Fahrt) = fahrten.aktualisieren(fahrt)
    suspend fun fahrtLoeschen(id: Long) = fahrten.loeschen(id)
    suspend fun distanzSetzen(id: Long, meter: Double) = fahrten.distanzSetzen(id, meter)
    suspend fun startPositionSetzen(id: Long, lat: Double, lon: Double) = fahrten.startPositionSetzen(id, lat, lon)
    suspend fun startAdresseSetzen(id: Long, adresse: String) = fahrten.startAdresseSetzen(id, adresse)
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
        fahrten.aktualisieren(
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
}
