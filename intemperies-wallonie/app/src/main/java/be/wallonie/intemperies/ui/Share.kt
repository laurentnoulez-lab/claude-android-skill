package be.wallonie.intemperies.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import be.wallonie.intemperies.export.ExportedFile

/** Ouvre le sélecteur de partage (mail, Drive, Teams, impression…) pour le fichier exporté. */
fun shareExport(context: Context, export: ExportedFile) {
    val file = export.file
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = export.format.mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension.replace('_', ' '))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, export.format.mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, "Partager — ${export.format.label}").apply {
        putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(view))
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        // Aucune application de partage : rien à faire.
    }
}
