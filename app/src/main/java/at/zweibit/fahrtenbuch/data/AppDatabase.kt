package at.zweibit.fahrtenbuch.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Kategorie::class, Fahrt::class, Trackpunkt::class, ProtokollEintrag::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun kategorieDao(): KategorieDao
    abstract fun fahrtDao(): FahrtDao
    abstract fun protokollDao(): ProtokollDao

    companion object {
        /** v0.3: Zwischenziele (Pause unterwegs) je Fahrt. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE fahrten ADD COLUMN zwischenziele TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v0.4: eindeutige Kennung je Fahrt und Änderungsprotokoll für die Online-Sicherung. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE fahrten ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
                // Bestehende Fahrten erhalten eine zufällige Kennung im UUID-Format (Version 4).
                // Bewusst ohne UPDATE … FROM, das erst ab SQLite 3.33 (Android 12+) geht.
                db.execSQL(
                    "UPDATE fahrten SET uuid = lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || " +
                        "substr(lower(hex(randomblob(2))), 2) || '-' || substr('89ab', 1 + (abs(random()) % 4), 1) || " +
                        "substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6)))"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_fahrten_uuid` ON `fahrten` (`uuid`)")
                db.execSQL(SQL_PROTOKOLL)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_protokoll_gesendet` ON `protokoll` (`gesendet`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_protokoll_fahrtUuid` ON `protokoll` (`fahrtUuid`)")
            }
        }

        // Exakt wie von Room erzeugt (siehe schemas/…/3.json) – sonst bricht Room beim Öffnen ab
        private const val SQL_PROTOKOLL =
            "CREATE TABLE IF NOT EXISTS `protokoll` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`eintragId` TEXT NOT NULL, `fahrtUuid` TEXT NOT NULL, `aktion` TEXT NOT NULL, `zeit` INTEGER NOT NULL, " +
                "`daten` TEXT NOT NULL, `gesendet` INTEGER NOT NULL, `meldung` TEXT NOT NULL)"

        fun erstellen(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "fahrtenbuch.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Startkategorien; können in den Einstellungen geändert oder gelöscht werden.
                        val vorgaben = listOf(
                            "Dienstlich" to KategorieFarben.PALETTE[0],
                            "Privat" to KategorieFarben.PALETTE[1],
                            "Arbeitsweg" to KategorieFarben.PALETTE[2],
                        )
                        vorgaben.forEachIndexed { i, (name, farbe) ->
                            db.execSQL(
                                "INSERT INTO kategorien (name, farbe, sortierung, aktiv) VALUES (?, ?, ?, 1)",
                                arrayOf<Any>(name, farbe, i + 1),
                            )
                        }
                    }
                })
                .build()
    }
}
