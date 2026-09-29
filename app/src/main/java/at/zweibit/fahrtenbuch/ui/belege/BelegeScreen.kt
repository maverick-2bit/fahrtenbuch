package at.zweibit.fahrtenbuch.ui.belege

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import at.zweibit.fahrtenbuch.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Belege der s/e smarte events OG: eigene Web-App mit eigenem Login, hier als Menüpunkt eingebettet. */
const val BELEGE_ADRESSE = "https://belege.smarte.events/"
private const val BELEGE_HOST = "belege.smarte.events"

/** So erkennt die Belege-Seite, dass sie in der App läuft (eigene Menüleiste oben statt unten). */
private const val KENNUNG = "FahrtenbuchApp"

/**
 * Hält die WebView über Wechsel zwischen den Menüpunkten hinweg – sonst lüde die Seite bei jedem
 * Wechsel neu, und ein halb ausgefüllter Beleg ginge verloren. Lebt so lange wie die Oberfläche.
 */
class BelegeAnsicht {
    internal var webView: WebView? = null
}

/** Ordner für Kamerafotos und geöffnete PDFs (siehe res/xml/file_paths.xml); alte Dateien fliegen raus. */
private fun zwischenablage(context: Context): File {
    val ordner = File(context.cacheDir, "belege").apply { mkdirs() }
    val grenze = System.currentTimeMillis() - 86_400_000L
    ordner.listFiles()?.filter { it.lastModified() < grenze }?.forEach { it.delete() }
    return ordner
}

private fun teilbar(context: Context, datei: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", datei)

/** Export (ZIP, Liste) in den Download-Ordner – mit der Anmeldung der Seite (Cookie). */
private fun herunterladen(context: Context, url: String, disposition: String?, typ: String?) {
    val name = URLUtil.guessFileName(url, disposition, typ)
    val anfrage = DownloadManager.Request(Uri.parse(url))
        .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url).orEmpty())
        .setMimeType(typ)
        .setTitle(name)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
    // Öffentlicher Download-Ordner ohne Speicherrecht erst ab Android 10
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) anfrage.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
    else anfrage.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name)
    context.getSystemService(DownloadManager::class.java).enqueue(anfrage)
    Toast.makeText(context, "$name wird heruntergeladen …", Toast.LENGTH_SHORT).show()
}

/** PDF eines Belegs mit einer PDF-App öffnen (die WebView kann PDFs nicht anzeigen). */
private suspend fun pdfOeffnen(context: Context, url: String, disposition: String?) {
    val datei = withContext(Dispatchers.IO) {
        val ziel = File(zwischenablage(context), URLUtil.guessFileName(url, disposition, "application/pdf"))
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Cookie", CookieManager.getInstance().getCookie(url).orEmpty())
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        try {
            if (con.responseCode != 200) error("Fehler ${con.responseCode}")
            con.inputStream.use { ein -> ziel.outputStream().use { ein.copyTo(it) } }
        } finally {
            con.disconnect()
        }
        ziel
    }
    val ansehen = Intent(Intent.ACTION_VIEW)
        .setDataAndType(teilbar(context, datei), "application/pdf")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(ansehen, "PDF öffnen"))
}

@SuppressLint("SetJavaScriptEnabled")
private fun webViewAnlegen(context: Context): WebView = WebView(context).apply {
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.userAgentString = "${settings.userAgentString} $KENNUNG/${BuildConfig.VERSION_NAME}"
    CookieManager.getInstance().setAcceptCookie(true)
    loadUrl(BELEGE_ADRESSE)
}

@Composable
fun BelegeScreen(ansicht: BelegeAnsicht) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rueckruf by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var foto by rememberSaveable { mutableStateOf<Uri?>(null) }
    var kannZurueck by remember { mutableStateOf(ansicht.webView?.canGoBack() == true) }
    var nichtErreichbar by remember { mutableStateOf(false) }

    fun antworten(uris: Array<Uri>?) {
        rueckruf?.onReceiveValue(uris)
        rueckruf = null
    }

    // Die Belege-Seite fragt mit <input type="file">: „capture“ = Kamera, sonst Fotos und PDFs aus Dateien
    val kamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        antworten(if (ok) foto?.let { arrayOf(it) } else null)
    }
    val mehrere = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { liste ->
        antworten(liste.takeIf { it.isNotEmpty() }?.toTypedArray())
    }
    val eine = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        antworten(uri?.let { arrayOf(it) })
    }

    // Anmeldung (Cookie) sofort sichern, falls Android die App im Hintergrund beendet
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { CookieManager.getInstance().flush() }
    BackHandler(enabled = kannZurueck) { ansicht.webView?.goBack() }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                (ansicht.webView ?: webViewAnlegen(ctx).also { ansicht.webView = it }).also { (it.parent as? ViewGroup)?.removeView(it) }
            },
            update = { w ->
                w.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url
                        if (url.scheme == "https" && url.host == BELEGE_HOST) return false
                        // Alles andere (z. B. Links in Notizen) im Browser, nie in dieser Ansicht
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, url))
                        } catch (_: ActivityNotFoundException) {
                        }
                        return true
                    }

                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                        kannZurueck = view.canGoBack()
                    }

                    // Nach einem Fehler meldet die WebView trotzdem „fertig“ (mit ihrer Fehlerseite)
                    private var fehlgeschlagen = false

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        fehlgeschlagen = false
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) {
                            fehlgeschlagen = true
                            nichtErreichbar = true
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (!fehlgeschlagen) nichtErreichbar = false
                    }
                }
                w.webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                        rueckruf?.onReceiveValue(null) // eine noch offene Auswahl abbrechen
                        rueckruf = callback
                        val typen = params.acceptTypes.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
                        try {
                            if (params.isCaptureEnabled && typen.isNotEmpty() && typen.all { it.startsWith("image/") }) {
                                val datei = File.createTempFile("beleg-", ".jpg", zwischenablage(context))
                                foto = teilbar(context, datei)
                                kamera.launch(foto!!)
                            } else {
                                val mime = typen.ifEmpty { listOf("*/*") }.toTypedArray()
                                if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) mehrere.launch(mime) else eine.launch(mime)
                            }
                        } catch (_: ActivityNotFoundException) {
                            antworten(null)
                            Toast.makeText(context, "Keine App für Kamera bzw. Dateien gefunden.", Toast.LENGTH_LONG).show()
                        }
                        return true
                    }
                }
                w.setDownloadListener { url, _, disposition, typ, _ ->
                    val pfad = Uri.parse(url).path.orEmpty()
                    if (typ == "application/pdf" && pfad.startsWith("/api/dateien/")) {
                        scope.launch {
                            runCatching { pdfOeffnen(context, url, disposition) }.onFailure {
                                Toast.makeText(context, "PDF lässt sich nicht öffnen: ${it.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        herunterladen(context, url, disposition, typ)
                    }
                }
            },
        )
        if (nichtErreichbar) {
            Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Belege nicht erreichbar", style = MaterialTheme.typography.titleLarge)
                    Text("Keine Internetverbindung? Die Belege liegen online unter belege.smarte.events.")
                    Button(onClick = {
                        nichtErreichbar = false
                        ansicht.webView?.reload()
                    }) { Text("Nochmal versuchen") }
                }
            }
        }
    }
}
