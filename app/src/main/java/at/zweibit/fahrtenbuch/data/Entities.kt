package at.zweibit.fahrtenbuch.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

object FahrtStatus {
    const val LAUFEND = "laufend"
    const val OFFEN = "offen"
    const val FERTIG = "fertig"
}

object KategorieFarben {
    val PALETTE: List<Long> = listOf(
        0xFF1E88E5, // Blau
        0xFF43A047, // Grün
        0xFFFB8C00, // Orange
        0xFFE53935, // Rot
        0xFF8E24AA, // Lila
        0xFF00ACC1, // Türkis
        0xFF6D4C41, // Braun
        0xFF546E7A, // Graublau
        0xFFD81B60, // Pink
        0xFFC0CA33, // Limette
    )
}

@Entity(tableName = "kategorien")
data class Kategorie(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val farbe: Long,
    val sortierung: Int = 0,
    val aktiv: Boolean = true,
)

@Entity(
    tableName = "fahrten",
    foreignKeys = [
        ForeignKey(
            entity = Kategorie::class,
            parentColumns = ["id"],
            childColumns = ["kategorieId"],
            onDelete = ForeignKey.SET_NULL,
        )
    ],
    indices = [Index("kategorieId"), Index("startZeit"), Index("status")],
)
data class Fahrt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startZeit: Long,
    val endeZeit: Long? = null,
    val startAdresse: String = "",
    val endeAdresse: String = "",
    val startLat: Double? = null,
    val startLon: Double? = null,
    val endeLat: Double? = null,
    val endeLon: Double? = null,
    val distanzMeter: Double = 0.0,
    val kategorieId: Long? = null,
    val notiz: String = "",
    val status: String = FahrtStatus.LAUFEND,
    /** Zwischenziele als JSON (siehe [Zwischenziele]); leer = direkte Fahrt. Seit DB-Version 2. */
    @ColumnInfo(defaultValue = "")
    val zwischenziele: String = "",
)

@Entity(
    tableName = "trackpunkte",
    foreignKeys = [
        ForeignKey(
            entity = Fahrt::class,
            parentColumns = ["id"],
            childColumns = ["fahrtId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("fahrtId")],
)
data class Trackpunkt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fahrtId: Long,
    val zeit: Long,
    val lat: Double,
    val lon: Double,
    val genauigkeit: Float,
    val geschwindigkeit: Float,
)

data class FahrtMitKategorie(
    @Embedded val fahrt: Fahrt,
    @Relation(parentColumn = "kategorieId", entityColumn = "id")
    val kategorie: Kategorie?,
)
