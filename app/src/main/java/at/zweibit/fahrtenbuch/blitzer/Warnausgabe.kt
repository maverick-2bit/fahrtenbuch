package at.zweibit.fahrtenbuch.blitzer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Gibt Blitzer-Warnungen aus: Sprachansage wie ein Navi (über Bluetooth auch im Autoradio,
 * andere Audioquellen werden kurz leiser), sonst ein Warnton; dazu Vibration.
 */
class Warnausgabe(context: Context) : TextToSpeech.OnInitListener {
    private val ctx = context.applicationContext
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val attribute = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val fokus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attribute)
        .build()
    private val haupt = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = TextToSpeech(ctx, this)
    private var bereit = false
    private var initFertig = false
    private var wartend: Pair<String, Boolean>? = null

    override fun onInit(status: Int) {
        val t = tts ?: return
        initFertig = true
        if (status == TextToSpeech.SUCCESS) {
            val r = t.setLanguage(Locale.GERMAN)
            bereit = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            t.setAudioAttributes(attribute)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    audio.abandonAudioFocusRequest(fokus)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    audio.abandonAudioFocusRequest(fokus)
                }
            })
        }
        // Eine Warnung, die vor Ende der Initialisierung kam, jetzt ausgeben
        wartend?.let { (text, dringend) ->
            wartend = null
            ausgeben(text, sprache = true, dringend = dringend)
        }
    }

    fun warnen(text: String, sprache: Boolean, dringend: Boolean) {
        vibrieren(dringend)
        if (sprache && !initFertig) {
            wartend = text to dringend
            return
        }
        ausgeben(text, sprache, dringend)
    }

    private fun ausgeben(text: String, sprache: Boolean, dringend: Boolean) {
        val t = tts
        if (sprache && bereit && t != null) {
            audio.requestAudioFocus(fokus)
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "blitzer-${System.nanoTime()}")
        } else {
            ton(dringend)
        }
    }

    private fun ton(dringend: Boolean) {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            tg.startTone(if (dringend) ToneGenerator.TONE_CDMA_HIGH_L else ToneGenerator.TONE_PROP_BEEP2, 700)
            haupt.postDelayed({ tg.release() }, 1_000)
        }
    }

    private fun vibrieren(dringend: Boolean) {
        val vib: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Vibrator::class.java)
        }
        val muster = if (dringend) longArrayOf(0, 300, 150, 300, 150, 300) else longArrayOf(0, 250, 120, 250)
        runCatching { vib?.vibrate(VibrationEffect.createWaveform(muster, -1)) }
    }

    fun beenden() {
        wartend = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        audio.abandonAudioFocusRequest(fokus)
    }
}
