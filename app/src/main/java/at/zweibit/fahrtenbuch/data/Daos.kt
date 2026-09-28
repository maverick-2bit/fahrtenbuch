package at.zweibit.fahrtenbuch.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface KategorieDao {
    @Query("SELECT * FROM kategorien WHERE aktiv = 1 ORDER BY sortierung, name")
    fun alle(): Flow<List<Kategorie>>

    @Query("SELECT * FROM kategorien ORDER BY sortierung, name")
    suspend fun alleEinmal(): List<Kategorie>

    @Query("SELECT COALESCE(MAX(sortierung), 0) FROM kategorien")
    suspend fun maxSortierung(): Int

    @Insert
    suspend fun einfuegen(kategorie: Kategorie): Long

    @Update
    suspend fun aktualisieren(kategorie: Kategorie)

    @Delete
    suspend fun loeschen(kategorie: Kategorie)

    @Query("SELECT COUNT(*) FROM fahrten WHERE kategorieId = :kategorieId")
    suspend fun anzahlFahrten(kategorieId: Long): Int
}

@Dao
interface FahrtDao {
    @Query("SELECT * FROM fahrten WHERE status = 'laufend' ORDER BY startZeit DESC LIMIT 1")
    fun laufende(): Flow<Fahrt?>

    @Query("SELECT * FROM fahrten WHERE status = 'laufend' ORDER BY startZeit DESC LIMIT 1")
    suspend fun laufendeEinmal(): Fahrt?

    @Query("SELECT * FROM fahrten WHERE status = 'offen' ORDER BY startZeit")
    fun offene(): Flow<List<Fahrt>>

    @Transaction
    @Query("SELECT * FROM fahrten WHERE status != 'laufend' ORDER BY startZeit DESC")
    fun alleMitKategorie(): Flow<List<FahrtMitKategorie>>

    @Transaction
    @Query("SELECT * FROM fahrten WHERE status = 'fertig' AND startZeit >= :von AND startZeit < :bis ORDER BY startZeit")
    suspend fun imZeitraum(von: Long, bis: Long): List<FahrtMitKategorie>

    @Query("SELECT * FROM fahrten WHERE id = :id")
    suspend fun holen(id: Long): Fahrt?

    @Insert
    suspend fun einfuegen(fahrt: Fahrt): Long

    @Update
    suspend fun aktualisieren(fahrt: Fahrt)

    @Query("DELETE FROM fahrten WHERE id = :id")
    suspend fun loeschen(id: Long)

    @Query("UPDATE fahrten SET distanzMeter = :meter WHERE id = :id")
    suspend fun distanzSetzen(id: Long, meter: Double)

    @Query("UPDATE fahrten SET startLat = :lat, startLon = :lon WHERE id = :id")
    suspend fun startPositionSetzen(id: Long, lat: Double, lon: Double)

    @Query("UPDATE fahrten SET startAdresse = :adresse WHERE id = :id")
    suspend fun startAdresseSetzen(id: Long, adresse: String)

    @Query("UPDATE fahrten SET zwischenziele = :json WHERE id = :id")
    suspend fun zwischenzieleSetzen(id: Long, json: String)

    @Insert
    suspend fun punktEinfuegen(punkt: Trackpunkt)

    @Query("SELECT * FROM trackpunkte WHERE fahrtId = :fahrtId ORDER BY zeit")
    suspend fun punkte(fahrtId: Long): List<Trackpunkt>

    @Query("SELECT * FROM trackpunkte WHERE fahrtId = :fahrtId ORDER BY zeit DESC LIMIT 1")
    suspend fun letzterPunkt(fahrtId: Long): Trackpunkt?
}
