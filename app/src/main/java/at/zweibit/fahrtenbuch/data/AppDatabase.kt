package at.zweibit.fahrtenbuch.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Kategorie::class, Fahrt::class, Trackpunkt::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun kategorieDao(): KategorieDao
    abstract fun fahrtDao(): FahrtDao

    companion object {
        /** v0.3: Zwischenziele (Pause unterwegs) je Fahrt. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE fahrten ADD COLUMN zwischenziele TEXT NOT NULL DEFAULT ''")
            }
        }

        fun erstellen(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "fahrtenbuch.db")
                .addMigrations(MIGRATION_1_2)
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
