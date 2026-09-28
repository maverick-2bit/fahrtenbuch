package at.zweibit.fahrtenbuch

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import at.zweibit.fahrtenbuch.data.AppDatabase
import at.zweibit.fahrtenbuch.data.FahrtStatus
import at.zweibit.fahrtenbuch.data.Zwischenziel
import at.zweibit.fahrtenbuch.data.zwischenzieleListe
import kotlinx.coroutines.flow.first
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
import java.io.File

/**
 * Prüft das Update einer echten v0.1/v0.2-Datenbank (Schema-Version 1) auf die aktuelle Version:
 * Die alte Datenbank wird exakt nach dem exportierten Schema 1.json aufgebaut und dann mit Room geöffnet.
 * Room bricht ab, wenn das Ergebnis der Migration nicht exakt dem erwarteten Schema entspricht.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "fahrtenbuch.db"

    @Before
    @After
    fun aufraeumen() {
        context.deleteDatabase(name)
    }

    private fun schema(version: Int) =
        JSONObject(File("schemas/at.zweibit.fahrtenbuch.data.AppDatabase/$version.json").readText()).getJSONObject("database")

    private fun alteDatenbankAnlegen() {
        val s = schema(1)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        val entities = s.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val tabelle = e.getString("tableName")
            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", tabelle))
            val indizes = e.optJSONArray("indices") ?: continue
            for (j in 0 until indizes.length()) {
                db.execSQL(indizes.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", tabelle))
            }
        }
        val setup = s.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))

        // Daten wie am Handy: Kategorie, abgeschlossene Fahrt mit Trackpunkt, laufende Fahrt
        db.execSQL("INSERT INTO kategorien (id, name, farbe, sortierung, aktiv) VALUES (1, 'Dienstlich', 4280191205, 1, 1)")
        db.execSQL(
            "INSERT INTO fahrten (id, startZeit, endeZeit, startAdresse, endeAdresse, startLat, startLon, endeLat, endeLon, " +
                "distanzMeter, kategorieId, notiz, status) VALUES " +
                "(1, 1759046400000, 1759050000000, 'Hauptstraße 1, 8700 Leoben', 'Kundenweg 5, 8010 Graz', " +
                "47.38, 15.09, 47.07, 15.44, 62345.6, 1, 'Kunde A', 'fertig')"
        )
        db.execSQL(
            "INSERT INTO trackpunkte (fahrtId, zeit, lat, lon, genauigkeit, geschwindigkeit) " +
                "VALUES (1, 1759046405000, 47.38, 15.09, 5.0, 12.5)"
        )
        db.execSQL(
            "INSERT INTO fahrten (id, startZeit, startAdresse, endeAdresse, distanzMeter, notiz, status) " +
                "VALUES (2, 1759060000000, 'Hauptstraße 1, 8700 Leoben', '', 1200.0, '', 'laufend')"
        )
        db.version = 1
        db.close()
    }

    @Test
    fun updateVonVersion1BehaeltAlleDaten() = runBlocking {
        alteDatenbankAnlegen()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val f = db.fahrtDao().holen(1)!!
            assertEquals("Kundenweg 5, 8010 Graz", f.endeAdresse)
            assertEquals(62345.6, f.distanzMeter, 0.001)
            assertEquals(1L, f.kategorieId)
            assertEquals("", f.zwischenziele)
            assertEquals(1, db.fahrtDao().punkte(1).size)
            assertEquals(FahrtStatus.LAUFEND, db.fahrtDao().laufendeEinmal()!!.status)
            assertEquals(2, db.openHelper.readableDatabase.version)

            // Neue Spalte ist nutzbar
            db.fahrtDao().zwischenzieleSetzen(2, """[{"adresse":"Kunde B","an":1759061000000}]""")
            val z = db.fahrtDao().holen(2)!!.zwischenzieleListe
            assertEquals(listOf(Zwischenziel("Kunde B", an = 1759061000000)), z)
        } finally {
            db.close()
        }
    }

    @Test
    fun neueInstallationLegtStartkategorienAn() = runBlocking {
        val db = AppDatabase.erstellen(context)
        try {
            val namen = db.kategorieDao().alle().first().map { it.name }
            assertEquals(listOf("Dienstlich", "Privat", "Arbeitsweg"), namen)
            assertTrue(db.fahrtDao().laufendeEinmal() == null)
        } finally {
            db.close()
        }
    }
}
