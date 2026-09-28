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

data class SyncStand(
    val server: String = "",
    val code: String = "",
    val fahrer: String = "",
    val zuletzt: Long = 0L,
    val meldung: String = "",
) {
    val verbunden: Boolean get() = server.isNotBlank() && code.isNotBlank()
}

data class PrivatStand(
    val datenSchluessel: String = "",
    val huelle: String = "",
    val huelleGesendet: Boolean = false,
) {
    val hatPin: Boolean get() = datenSchluessel.isNotBlank() && huelle.isNotBlank()
}

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
        val SYNC_SERVER = stringPreferencesKey("sync_server")
        val SYNC_CODE = stringPreferencesKey("sync_code")
        val SYNC_FAHRER = stringPreferencesKey("sync_fahrer")
        val SYNC_ZULETZT = longPreferencesKey("sync_zuletzt")
        val SYNC_MELDUNG = stringPreferencesKey("sync_meldung")
        val PRIVAT_DEK = stringPreferencesKey("privat_datenschluessel")
        val PRIVAT_HUELLE = stringPreferencesKey("privat_huelle")
        val PRIVAT_HUELLE_GESENDET = booleanPreferencesKey("privat_huelle_gesendet")
    }

    /**
     * Schlüssel für Privatfahrten: Der Datenschlüssel bleibt auf dem Handy; an den Server geht nur die
     * mit dem PIN verschlüsselte Hülle. Der PIN selbst wird nirgends gespeichert.
     */
    val privat: Flow<PrivatStand> = context.dataStore.data.map {
        PrivatStand(
            datenSchluessel = it[Keys.PRIVAT_DEK].orEmpty(),
            huelle = it[Keys.PRIVAT_HUELLE].orEmpty(),
            huelleGesendet = it[Keys.PRIVAT_HUELLE_GESENDET] ?: false,
        )
    }

    suspend fun privatAktuell(): PrivatStand = privat.first()

    suspend fun privatSpeichern(datenSchluessel: String, huelle: String) {
        context.dataStore.edit {
            it[Keys.PRIVAT_DEK] = datenSchluessel
            it[Keys.PRIVAT_HUELLE] = huelle
            it[Keys.PRIVAT_HUELLE_GESENDET] = false
        }
    }

    suspend fun privatHuelleGesendet(huelle: String) {
        // Nur bestätigen, wenn inzwischen kein neuer PIN festgelegt wurde
        context.dataStore.edit { if (it[Keys.PRIVAT_HUELLE] == huelle) it[Keys.PRIVAT_HUELLE_GESENDET] = true }
    }

    /** Verbindung zur Online-Sicherung – getrennt gespeichert, damit „Speichern“ anderer Einstellungen sie nie überschreibt. */
    val sync: Flow<SyncStand> = context.dataStore.data.map {
        SyncStand(
            server = it[Keys.SYNC_SERVER].orEmpty(),
            code = it[Keys.SYNC_CODE].orEmpty(),
            fahrer = it[Keys.SYNC_FAHRER].orEmpty(),
            zuletzt = it[Keys.SYNC_ZULETZT] ?: 0L,
            meldung = it[Keys.SYNC_MELDUNG].orEmpty(),
        )
    }

    suspend fun syncAktuell(): SyncStand = sync.first()

    suspend fun syncVerbinden(server: String, code: String, fahrer: String) {
        context.dataStore.edit {
            it[Keys.SYNC_SERVER] = server
            it[Keys.SYNC_CODE] = code
            it[Keys.SYNC_FAHRER] = fahrer
            it[Keys.SYNC_MELDUNG] = ""
            // Neue Verbindung: Schlüsselhülle (falls vorhanden) erneut übertragen
            it[Keys.PRIVAT_HUELLE_GESENDET] = false
        }
    }

    suspend fun syncTrennen() {
        context.dataStore.edit {
            it.remove(Keys.SYNC_SERVER)
            it.remove(Keys.SYNC_CODE)
            it.remove(Keys.SYNC_FAHRER)
            it.remove(Keys.SYNC_MELDUNG)
        }
    }

    /** @param zuletzt Zeitpunkt einer erfolgreichen Sicherung oder null bei Fehler */
    suspend fun syncErgebnis(zuletzt: Long?, meldung: String) {
        context.dataStore.edit {
            if (zuletzt != null) it[Keys.SYNC_ZULETZT] = zuletzt
            it[Keys.SYNC_MELDUNG] = meldung
        }
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
