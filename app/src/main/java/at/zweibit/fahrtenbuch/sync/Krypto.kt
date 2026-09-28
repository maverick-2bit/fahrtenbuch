package at.zweibit.fahrtenbuch.sync

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Verschlüsselung der Privatfahrten – identisch zur Admin-Webseite (server/public/admin/geheim.js):
 * - Datenschlüssel: 32 zufällige Bytes je Fahrer, bleiben auf dem Handy.
 * - Hülle: Datenschlüssel mit AES-GCM verschlüsselt; Schlüssel dafür aus dem PIN per PBKDF2-SHA-256.
 *   Nur die Hülle geht an den Server, der PIN nie.
 * - Details einer Privatfahrt: AES-GCM mit dem Datenschlüssel.
 */
object Krypto {
    const val RUNDEN = 310_000
    const val MIN_PIN = 6
    private val zufall = SecureRandom()

    fun zufall(n: Int): ByteArray = ByteArray(n).also { zufall.nextBytes(it) }
    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun ausB64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private fun huellenSchluessel(pin: String, salt: ByteArray, runden: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, runden, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun gcm(modus: Int, schluessel: ByteArray, iv: ByteArray, daten: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(modus, SecretKeySpec(schluessel, "AES"), GCMParameterSpec(128, iv))
            doFinal(daten)
        }

    data class Huelle(val salt: String, val iter: Int, val iv: String, val ct: String) {
        fun json(): JSONObject = JSONObject().put("v", 1).put("salt", salt).put("iter", iter).put("iv", iv).put("ct", ct)

        companion object {
            fun aus(json: String): Huelle = JSONObject(json).let {
                Huelle(it.getString("salt"), it.getInt("iter"), it.getString("iv"), it.getString("ct"))
            }
        }
    }

    /** Zwei Hüllen (JSON) sind dieselbe – unabhängig von der Reihenfolge der Felder (der Server ordnet sie um). */
    fun huelleGleich(a: String, b: String): Boolean =
        runCatching { Huelle.aus(a) == Huelle.aus(b) }.getOrDefault(false)

    fun huelleErstellen(
        pin: String,
        datenSchluessel: ByteArray,
        salt: ByteArray = zufall(16),
        iv: ByteArray = zufall(12),
        runden: Int = RUNDEN,
    ): Huelle {
        val kek = huellenSchluessel(pin, salt, runden)
        return Huelle(b64(salt), runden, b64(iv), b64(gcm(Cipher.ENCRYPT_MODE, kek, iv, datenSchluessel)))
    }

    /** @throws javax.crypto.AEADBadTagException bei falschem PIN */
    fun huelleOeffnen(h: Huelle, pin: String): ByteArray {
        val kek = huellenSchluessel(pin, ausB64(h.salt), h.iter)
        return gcm(Cipher.DECRYPT_MODE, kek, ausB64(h.iv), ausB64(h.ct))
    }

    /** Verschlüsselt [klartext] (JSON der Fahrtdetails) als `{v, iv, ct}`. */
    fun geheimErstellen(datenSchluessel: ByteArray, klartext: String, iv: ByteArray = zufall(12)): JSONObject =
        JSONObject().put("v", 1).put("iv", b64(iv))
            .put("ct", b64(gcm(Cipher.ENCRYPT_MODE, datenSchluessel, iv, klartext.toByteArray(Charsets.UTF_8))))

    fun geheimOeffnen(datenSchluessel: ByteArray, geheim: JSONObject): String =
        String(gcm(Cipher.DECRYPT_MODE, datenSchluessel, ausB64(geheim.getString("iv")), ausB64(geheim.getString("ct"))), Charsets.UTF_8)
}
