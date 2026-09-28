package at.zweibit.fahrtenbuch

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import at.zweibit.fahrtenbuch.data.AppDatabase
import at.zweibit.fahrtenbuch.data.Fahrt
import at.zweibit.fahrtenbuch.data.FahrtStatus
import at.zweibit.fahrtenbuch.data.Kategorie
import at.zweibit.fahrtenbuch.data.ProtokollAktion
import at.zweibit.fahrtenbuch.data.Repository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Änderungsprotokoll der App: Grundlage der revisionssicheren Online-Sicherung. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProtokollTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository
    private var angestossen = 0

    @Before
    fun anlegen() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db) { angestossen++ }
    }

    @After
    fun schliessen() = db.close()

    @Test
    fun protokolliertAbschlussAenderungUndLoeschung() = runBlocking {
        val katId = db.kategorieDao().einfuegen(Kategorie(name = "Dienstlich", farbe = 0xFF1E88E5))

        // Laufende Fahrt: noch kein Eintrag, keine Sicherung
        val id = repo.fahrtAnlegen(Fahrt(startZeit = 1_000, startAdresse = "Zuhause", status = FahrtStatus.LAUFEND))
        repo.distanzSetzen(id, 5_000.0)
        val uuid = repo.fahrt(id)!!.uuid
        assertEquals(0, repo.protokollZuFahrt(uuid).size)
        assertEquals(0, angestossen)

        // Ende der Fahrt (Status offen) → „neu“
        repo.fahrtSpeichern(repo.fahrt(id)!!.copy(endeZeit = 2_000, endeAdresse = "Kunde A", status = FahrtStatus.OFFEN))
        // Kategorie gewählt → „geändert“
        repo.kategorisieren(id, katId, "Termin", "Zuhause", "Kunde A")
        // Gelöscht → „gelöscht“
        repo.fahrtLoeschen(id)

        val eintraege = repo.protokollZuFahrt(uuid)
        assertEquals(listOf(ProtokollAktion.NEU, ProtokollAktion.GEAENDERT, ProtokollAktion.GELOESCHT), eintraege.map { it.aktion })
        assertEquals(3, eintraege.map { it.eintragId }.toSet().size)
        val daten = JSONObject(eintraege[1].daten)
        assertEquals("Termin", daten.getString("notiz"))
        assertEquals(5_000.0, daten.getDouble("distanzMeter"), 0.0)
        assertEquals(katId, daten.getLong("kategorieId"))
        assertEquals("fertig", daten.getString("status"))
        assertTrue(daten.isNull("startLat"))
        assertEquals(3, angestossen)
        assertEquals(3, repo.protokollOffeneEintraege(100).size)
    }

    @Test
    fun nachgetrageneFahrtIstSofortProtokolliert() = runBlocking {
        val id = repo.fahrtAnlegen(Fahrt(startZeit = 1_000, endeZeit = 2_000, distanzMeter = 12_300.0, status = FahrtStatus.FERTIG))
        val e = repo.protokollZuFahrt(repo.fahrt(id)!!.uuid)
        assertEquals(listOf(ProtokollAktion.NEU), e.map { it.aktion })
    }

    @Test
    fun altbestandWirdGenauEinmalNachgetragen() = runBlocking {
        // Fahrten wie vor Version 0.4: direkt in der Tabelle, ohne Protokoll
        db.fahrtDao().einfuegen(Fahrt(startZeit = 1_000, status = FahrtStatus.FERTIG))
        db.fahrtDao().einfuegen(Fahrt(startZeit = 2_000, status = FahrtStatus.OFFEN))
        db.fahrtDao().einfuegen(Fahrt(startZeit = 3_000, status = FahrtStatus.LAUFEND))
        assertEquals(2, repo.altbestandProtokollieren())
        assertEquals(0, repo.altbestandProtokollieren())
        assertEquals(2, repo.protokollOffeneEintraege(100).size)
    }

    @Test
    fun angenommeneUndAbgelehnteEintraegeWerdenMarkiert() = runBlocking {
        repo.fahrtAnlegen(Fahrt(startZeit = 1_000, status = FahrtStatus.FERTIG))
        repo.fahrtAnlegen(Fahrt(startZeit = 2_000, status = FahrtStatus.FERTIG))
        val (a, b) = repo.protokollOffeneEintraege(100)
        repo.protokollAngenommen(listOf(a.eintragId))
        repo.protokollAbgelehnt(b.eintragId, "ungültig")
        assertEquals(0, repo.protokollOffeneEintraege(100).size)
    }
}
