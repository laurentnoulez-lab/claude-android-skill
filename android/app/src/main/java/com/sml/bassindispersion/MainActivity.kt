package com.sml.bassindispersion

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

/**
 * Hôte natif de l'application web "Simulateur de Bassin de Dispersion".
 *
 * L'application reste une page web unique (assets/index.html), adaptée pour
 * mobile. Cette Activity fournit les ponts natifs manquants dans un WebView :
 *  - téléchargement des exports blob: (JSON / Word) vers le dossier Téléchargements,
 *  - impression / export PDF via window.open + window.print et le PrintManager Android,
 *  - sélection de fichiers (<input type="file">) pour l'import d'étude et le logo.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView

    /** WebView temporaire qui porte le document de rapport en cours d'impression. */
    private var pdfChild: WebView? = null

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            val callback = filePathCallback
            filePathCallback = null
            if (callback == null) return@registerForActivityResult
            val uris = if (result.resultCode == Activity.RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            } else {
                null
            }
            callback.onReceiveValue(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this)
        setContentView(root)

        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        root.addView(webView)

        configureWebView(webView, isChild = false)
        webView.addJavascriptInterface(AndroidDownloader(), "AndroidDownloader")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url
                // Garder la navigation interne dans le WebView ; ouvrir les liens
                // externes (mailto, http vers un site tiers) dans une app dédiée.
                val scheme = url.scheme ?: return false
                return if (scheme == "http" || scheme == "https" || scheme == "file") {
                    false
                } else {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }.isSuccess
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                injectBlobDownloadBridge(view)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return runCatching {
                    fileChooserLauncher.launch(params.createIntent())
                    true
                }.getOrElse {
                    filePathCallback = null
                    false
                }
            }

            // Le rapport PDF est généré via window.open(...) puis window.print().
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                // Retirer un éventuel rapport précédent pour éviter l'empilement.
                pdfChild?.let { removeChild(it) }

                val child = WebView(this@MainActivity)
                configureWebView(child, isChild = true)
                child.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                // Attaché mais invisible : la page de rapport est rendue puis imprimée.
                child.visibility = android.view.View.INVISIBLE
                root.addView(child)
                pdfChild = child

                val overridePrint = "window.print = function(){ AndroidPrint.print(); };"
                child.addJavascriptInterface(PrintBridge(child), "AndroidPrint")
                child.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        // Poser la redirection le plus tôt possible (le rapport
                        // appelle window.print() automatiquement après chargement).
                        v.evaluateJavascript(overridePrint, null)
                    }

                    override fun onPageFinished(v: WebView, url: String?) {
                        v.evaluateJavascript(overridePrint, null)
                    }
                }
                child.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView) {
                        removeChild(window)
                    }
                }

                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = child
                resultMsg.sendToTarget()
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 1) Fermer le tiroir de paramètres s'il est ouvert.
                webView.evaluateJavascript(
                    "(function(){var o=document.body.classList.contains('sidebar-open');" +
                        "document.body.classList.remove('sidebar-open');return o;})();",
                ) { wasOpen ->
                    if (wasOpen == "true") return@evaluateJavascript
                    // 2) Sinon, revenir dans l'historique du WebView.
                    runOnUiThread {
                        if (webView.canGoBack()) {
                            webView.goBack()
                        } else {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/index.html")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    private fun configureWebView(wv: WebView, isChild: Boolean) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(!isChild)
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100
        }
        WebView.setWebContentsDebuggingEnabled(true)
    }

    private fun removeChild(child: WebView) {
        runOnUiThread {
            runCatching {
                if (pdfChild === child) pdfChild = null
                root.removeView(child)
                child.destroy()
            }
        }
    }

    private fun injectBlobDownloadBridge(view: WebView) {
        // Intercepte les téléchargements blob:/data: déclenchés par anchor.click()
        // (export JSON et Word) et les transmet au pont natif pour enregistrement.
        val js = """
            (function(){
              if (window.__androidDLPatched) return; window.__androidDLPatched = true;
              var origClick = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function(){
                try {
                  var href = this.href || '';
                  var name = this.getAttribute('download');
                  if (name && href.indexOf('blob:') === 0){
                    var xhr = new XMLHttpRequest();
                    xhr.open('GET', href, true);
                    xhr.responseType = 'blob';
                    xhr.onload = function(){
                      var reader = new FileReader();
                      reader.onloadend = function(){
                        AndroidDownloader.saveDataUrl(reader.result, name);
                      };
                      reader.readAsDataURL(xhr.response);
                    };
                    xhr.send();
                    return;
                  }
                  if (name && href.indexOf('data:') === 0){
                    AndroidDownloader.saveDataUrl(href, name);
                    return;
                  }
                } catch (e) {}
                return origClick.apply(this, arguments);
              };
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    /** Pont JS pour enregistrer les fichiers exportés (data URL base64). */
    private inner class AndroidDownloader {
        @JavascriptInterface
        fun saveDataUrl(dataUrl: String, fileName: String) {
            runOnUiThread { saveDataUrlInternal(dataUrl, fileName) }
        }
    }

    private fun saveDataUrlInternal(dataUrl: String, fileName: String) {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) {
            toast("Échec de l'export : format invalide")
            return
        }
        val header = dataUrl.substring(0, comma)
        val payload = dataUrl.substring(comma + 1)
        val mime = header.removePrefix("data:").substringBefore(';').ifBlank { "application/octet-stream" }
        val bytes = if (header.contains(";base64")) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            Uri.decode(payload).toByteArray(Charsets.UTF_8)
        }

        val saved = runCatching { writeToDownloads(fileName, mime, bytes) }.getOrElse {
            toast("Échec de l'enregistrement : ${it.message}")
            return
        }
        toast("Enregistré : $saved")
    }

    private fun writeToDownloads(fileName: String, mime: String, bytes: ByteArray): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("URI MediaStore null")
            resolver.openOutputStream(uri).use { out ->
                requireNotNull(out).write(bytes)
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Téléchargements/$fileName"
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            FileOutputStream(file).use { it.write(bytes) }
            return file.absolutePath
        }
    }

    /** Pont JS pour imprimer / exporter en PDF la fenêtre de rapport. */
    private inner class PrintBridge(private val target: WebView) {
        @JavascriptInterface
        fun print() {
            runOnUiThread {
                val printManager = getSystemService(PRINT_SERVICE) as PrintManager
                val jobName = getString(R.string.app_name) + " — Rapport"
                val adapter = target.createPrintDocumentAdapter(jobName)
                printManager.print(
                    jobName,
                    adapter,
                    PrintAttributes.Builder().build(),
                )
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        runCatching { pdfChild?.destroy() }
        pdfChild = null
        runCatching { webView.destroy() }
        super.onDestroy()
    }
}
