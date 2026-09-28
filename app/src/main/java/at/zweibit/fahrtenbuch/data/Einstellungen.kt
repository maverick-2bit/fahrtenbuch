package at.zweibit.fahrtenbuch.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "einstellungen")

data class EinstellungenWerte(
    /** Minuten ohne Bewegung, nach denen eine Fahrt automatisch beendet wird. 0 = aus. */
    val autoStoppMinuten: Int = 5,
    val kennzeichen: String = "",
    val fahrzeug: String = "",
    val fahrer: String = "",
    /** Kilometerstand laut Tacho zum Stichtag; 0 = keine Kilometerstand-Spalten im Bericht. */
    val kmStandStart: Int = 0,
    /** Stichtag (Epoch-Millis, Tagesbeginn), ab dem der Kilometerstand fortgeschrieben wird. */
    val kmStandAb: Long = 0L,
    /** Warnung vor fixen Blitzern während der Aufzeichnung (nur Österreich). */
    val blitzerWarnung: Boolean = false,
    /** Sprachansage statt nur Warnton. */
    val blitzerAnsage: Boolean = true,
)

class Einstellungen(private val context: Context) {
    private object Keys {
        val AUTO_STOPP = intPreferencesKey("auto_stopp_minuten")
        val KENNZEICHEN = stringPreferencesKey("kennzeichen")
        val FAHRZEUG = stringPreferencesKey("fahrzeug")
        val FAHRER = stringPreferencesKey("fahrer")
        val KM_START = intPreferencesKey("km_stand_start")
        val KM_AB = longPreferencesKey("km_stand_ab")
        val BLITZER = booleanPreferencesKey("blitzer_warnung")
        val BLITZER_ANSAGE = booleanPreferencesKey("blitzer_ansage")
        val ORTE = stringPreferencesKey("orte")
    }

    val werte: Flow<EinstellungenWerte> = context.dataStore.data.map { it.zuWerten() }

    suspend fun aktuell(): EinstellungenWerte = werte.first()

    /** Gespeicherte Orte (Zuhause, Büro, Kunden …), getrennt von den übrigen Einstellungen gespeichert. */
    val orte: Flow<List<Ort>> = context.dataStore.data.map { Orte.lesen(it[Keys.ORTE]) }

    suspend fun orteAktuell(): List<Ort> = orte.first()

    suspend fun orteSpeichern(liste: List<Ort>) {
        context.dataStore.edit { it[Keys.ORTE] = Orte.schreiben(liste) }
    }

    suspend fun speichern(neu: EinstellungenWerte) {
        context.dataStore.edit {
            it[Keys.AUTO_STOPP] = neu.autoStoppMinuten
            it[Keys.KENNZEICHEN] = neu.kennzeichen
            it[Keys.FAHRZEUG] = neu.fahrzeug
            it[Keys.FAHRER] = neu.fahrer
            it[Keys.KM_START] = neu.kmStandStart
            it[Keys.KM_AB] = neu.kmStandAb
            it[Keys.BLITZER] = neu.blitzerWarnung
            it[Keys.BLITZER_ANSAGE] = neu.blitzerAnsage
        }
    }

    private fun Preferences.zuWerten() = EinstellungenWerte(
        autoStoppMinuten = this[Keys.AUTO_STOPP] ?: 5,
        kennzeichen = this[Keys.KENNZEICHEN] ?: "",
        fahrzeug = this[Keys.FAHRZEUG] ?: "",
        fahrer = this[Keys.FAHRER] ?: "",
        kmStandStart = this[Keys.KM_START] ?: 0,
        kmStandAb = this[Keys.KM_AB] ?: 0L,
        blitzerWarnung = this[Keys.BLITZER] ?: false,
        blitzerAnsage = this[Keys.BLITZER_ANSAGE] ?: true,
    )
}
