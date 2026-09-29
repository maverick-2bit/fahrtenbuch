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

    /** Baut eine Datenbank exakt nach dem exportierten Schema der angegebenen (alten) Version auf. */
    private fun alteDatenbankAnlegen(version: Int) {
        val s = schema(version)
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

        // Daten wie am Handy: Kategorien, abgeschlossene Fahrt mit Trackpunkt, laufende Fahrt
        // Ab Version 4 steht in der Datenbank selbst, welche Kategorie privat ist
        val privatSpalte = if (version >= 4) ", privat" else ""
        val (privat1, privat2) = if (version >= 4) ", 0" to ", 1" else "" to ""
        db.execSQL("INSERT INTO kategorien (id, name, farbe, sortierung, aktiv$privatSpalte) VALUES (1, 'Dienstlich', 4280191205, 1, 1$privat1)")
        db.execSQL("INSERT INTO kategorien (id, name, farbe, sortierung, aktiv$privatSpalte) VALUES (2, ' Privat ', 4282622023, 2, 1$privat2)")
        // Ab Version 3 gibt es die eindeutige Kennung – sie muss beim Update erhalten bleiben
        val uuidSpalte = if (version >= 3) "uuid, " else ""
        val uuid1 = if (version >= 3) "'$UUID_1', " else ""
        val uuid2 = if (version >= 3) "'$UUID_2', " else ""
        db.execSQL(
            "INSERT INTO fahrten (${uuidSpalte}id, startZeit, endeZeit, startAdresse, endeAdresse, startLat, startLon, endeLat, endeLon, " +
                "distanzMeter, kategorieId, notiz, status) VALUES " +
                "(${uuid1}1, 1759046400000, 1759050000000, 'Hauptstraße 1, 8700 Leoben', 'Kundenweg 5, 8010 Graz', " +
                "47.38, 15.09, 47.07, 15.44, 62345.6, 1, 'Kunde A', 'fertig')"
        )
        db.execSQL(
            "INSERT INTO trackpunkte (fahrtId, zeit, lat, lon, genauigkeit, geschwindigkeit) " +
                "VALUES (1, 1759046405000, 47.38, 15.09, 5.0, 12.5)"
        )
        db.execSQL(
            "INSERT INTO fahrten (${uuidSpalte}id, startZeit, startAdresse, endeAdresse, distanzMeter, notiz, status) " +
                "VALUES (${uuid2}2, 1759060000000, 'Hauptstraße 1, 8700 Leoben', '', 1200.0, '', 'laufend')"
        )
        db.version = version
        db.close()
    }

    private fun oeffnen() = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
        .allowMainThreadQueries()
        .build()

    private companion object {
        const val UUID_1 = "11111111-2222-4333-8444-555555555555"
        const val UUID_2 = "66666666-7777-4888-9999-000000000000"
        const val AKTUELL = 5
    }

    private val uuidFormat = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    /**
     * Vergleicht die Indizes der migrierten Datenbank mit dem Soll-Schema. Room selbst überspringt
     * diese Prüfung in der Testumgebung, am Gerät würde ein fehlender Index aber zum Absturz führen.
     */
    private fun indizesPruefen(db: AppDatabase) {
        val sql = db.openHelper.readableDatabase
        val entities = schema(AKTUELL).getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val tabelle = e.getString("tableName")
            val erwartet = mutableSetOf<String>()
            e.optJSONArray("indices")?.let { liste ->
                for (j in 0 until liste.length()) {
                    val x = liste.getJSONObject(j)
                    val spalten = x.getJSONArray("columnNames")
                    erwartet += "${x.getString("name")}|${x.getBoolean("unique")}|" +
                        (0 until spalten.length()).joinToString(",") { spalten.getString(it) }
                }
            }
            val vorhanden = mutableSetOf<String>()
            sql.query("PRAGMA index_list(`$tabelle`)").use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(c.getColumnIndexOrThrow("name"))
                    if (name.startsWith("sqlite_autoindex")) continue
                    val unique = c.getInt(c.getColumnIndexOrThrow("unique")) == 1
                    val spalten = mutableListOf<String>()
                    sql.query("PRAGMA index_info(`$name`)").use { ic ->
                        while (ic.moveToNext()) spalten += ic.getString(ic.getColumnIndexOrThrow("name"))
                    }
                    vorhanden += "$name|$unique|${spalten.joinToString(",")}"
                }
            }
            assertEquals("Indizes der Tabelle $tabelle", erwartet, vorhanden)
        }
    }

    private suspend fun datenPruefen(db: AppDatabase, von: Int) {
        indizesPruefen(db)
        val f = db.fahrtDao().holen(1)!!
        assertEquals("Kundenweg 5, 8010 Graz", f.endeAdresse)
        assertEquals(62345.6, f.distanzMeter, 0.001)
        assertEquals(1L, f.kategorieId)
        assertEquals("", f.zwischenziele)
        assertEquals(1, db.fahrtDao().punkte(1).size)
        assertEquals(FahrtStatus.LAUFEND, db.fahrtDao().laufendeEinmal()!!.status)
        assertEquals(AKTUELL, db.openHelper.readableDatabase.version)

        // Jede bestehende Fahrt hat eine eigene Kennung im UUID-Format
        val u1 = db.fahrtDao().holen(1)!!.uuid
        val u2 = db.fahrtDao().holen(2)!!.uuid
        assertTrue(u1, uuidFormat.matches(u1))
        assertTrue(u2, uuidFormat.matches(u2))
        assertTrue(u1 != u2)
        if (von >= 3) assertEquals(listOf(UUID_1, UUID_2), listOf(u1, u2))

        // v4: nur die Kategorie „Privat“ ist privat
        val kategorien = db.kategorieDao().alleEinmal().associate { it.id to it.privat }
        assertEquals(mapOf(1L to false, 2L to true), kategorien)

        // v5: bestehende Fahrten ohne Nachtrag; der Nachtrag nach korrigiertem Start lässt sich setzen
        assertEquals(0.0, f.nachtragMeter, 0.0)
        assertEquals(0L, f.nachtragMs)
        db.fahrtDao().nachtragSetzen(2, 5_400.0, 1759059580000, 4_200.0, 420_000)
        val laufend = db.fahrtDao().holen(2)!!
        assertEquals(listOf(5_400.0, 4_200.0), listOf(laufend.distanzMeter, laufend.nachtragMeter))
        assertEquals(listOf(1759059580000, 420_000L), listOf(laufend.startZeit, laufend.nachtragMs))

        // Zwischenziele (v2) und Protokoll (v3) sind nutzbar
        db.fahrtDao().zwischenzieleSetzen(2, """[{"adresse":"Kunde B","an":1759061000000}]""")
        assertEquals(listOf(Zwischenziel("Kunde B", an = 1759061000000)), db.fahrtDao().holen(2)!!.zwischenzieleListe)
        val alt = db.protokollDao().fahrtenOhneProtokoll()
        assertEquals(listOf(1L), alt.map { it.id }) // nur die abgeschlossene Fahrt, nicht die laufende
    }

    private fun updateVon(version: Int) = runBlocking {
        alteDatenbankAnlegen(version)
        val db = oeffnen()
        try {
            datenPruefen(db, version)
        } finally {
            db.close()
        }
    }

    @Test
    fun updateVonVersion1BehaeltAlleDaten() = updateVon(1)

    @Test
    fun updateVonVersion2BehaeltAlleDaten() = updateVon(2)

    @Test
    fun updateVonVersion3BehaeltAlleDaten() = updateVon(3)

    @Test
    fun updateVonVersion4BehaeltAlleDaten() = updateVon(4)

    @Test
    fun neueInstallationLegtStartkategorienAn() = runBlocking {
        val db = AppDatabase.erstellen(context)
        try {
            val namen = db.kategorieDao().alle().first().map { it.name }
            assertEquals(listOf("Dienstlich", "Privat", "Arbeitsweg"), namen)
            assertEquals(listOf(false, true, false), db.kategorieDao().alle().first().map { it.privat })
            assertTrue(db.fahrtDao().laufendeEinmal() == null)
        } finally {
            db.close()
        }
    }
}
