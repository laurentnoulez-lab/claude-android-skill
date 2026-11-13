package com.tranchees.impetrants;

import android.app.Activity;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Coque WebView de l'application « Gabarit tranchées impétrants ».
 * L'application web (assets/index.html) est 100 % hors-ligne. Le pont natif
 * « TIAndroid » permet d'enregistrer les fichiers exportés (Excel, Word, JSON)
 * dans le dossier « Téléchargements », car les téléchargements blob: ne sont pas
 * gérés par une WebView standard.
 */
public class MainActivity extends Activity {

    private WebView webView;

    @Override
    @SuppressWarnings("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage (sauvegarde du projet en cours)
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new FileBridge(), "TIAndroid");

        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/index.html");
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /** Pont JS -> natif : enregistre un fichier (base64) dans « Téléchargements ». */
    private class FileBridge {
        @JavascriptInterface
        public void saveBase64(final String name, final String mime, final String base64) {
            try {
                final byte[] data = Base64.decode(base64, Base64.DEFAULT);
                final String fileName = (name == null || name.isEmpty()) ? "export" : name;
                final String type = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Downloads.MIME_TYPE, type);
                    values.put(MediaStore.Downloads.IS_PENDING, 1);
                    Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    Uri item = getContentResolver().insert(collection, values);
                    if (item == null) throw new Exception("URI nulle");
                    try (OutputStream os = getContentResolver().openOutputStream(item)) {
                        os.write(data);
                    }
                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(item, values, null, null);
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    File out = new File(dir, fileName);
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        fos.write(data);
                    }
                }
                toast("Enregistré dans Téléchargements : " + fileName);
            } catch (final Exception e) {
                toast("Échec de l'enregistrement : " + e.getMessage());
            }
        }
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); }
        });
    }
}
