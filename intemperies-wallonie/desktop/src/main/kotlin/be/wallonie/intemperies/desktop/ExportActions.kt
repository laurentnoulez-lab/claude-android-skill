package be.wallonie.intemperies.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileSystemView

/**
 * Exports Windows : le fichier est d'abord créé dans %APPDATA%\IntemperiesWallonie\exports (toujours autorisé),
 * puis l'utilisateur l'ouvre, le copie (collage dans l'Explorateur : Bureau, OneDrive, réseau…) ou l'enregistre ailleurs.
 * La copie par l'Explorateur fonctionne même lorsque Windows interdit au programme d'écrire dans un dossier protégé
 * (« accès contrôlé aux dossiers » de Windows Defender, stratégie d'entreprise).
 */
object ExportActions {
    val dir: File get() = File(AppPaths.root, "exports").apply { mkdirs() }

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    fun open(file: File): Result<Unit> = runCatching { Desktop.getDesktop().open(file) }

    fun showInExplorer(file: File): Result<Unit> = runCatching {
        if (isWindows) ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
        else Desktop.getDesktop().open(file.parentFile)
    }

    /** Copie le fichier dans le presse-papiers Windows : Ctrl+V dans un dossier de l'Explorateur le colle. */
    fun copyToClipboard(file: File): Result<Unit> = runCatching {
        val t = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(f: DataFlavor) = f == DataFlavor.javaFileListFlavor
            override fun getTransferData(f: DataFlavor): Any =
                if (f == DataFlavor.javaFileListFlavor) listOf(file) else throw UnsupportedFlavorException(f)
        }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(t, null)
    }

    /** « Enregistrer sous… » : renvoie le fichier écrit, null si annulé ; échec explicite si Windows refuse l'écriture. */
    fun saveAs(file: File): Result<File?> = runCatching {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val chooser = JFileChooser(FileSystemView.getFileSystemView().defaultDirectory).apply {
            dialogTitle = "Enregistrer le calendrier"
            selectedFile = File(currentDirectory, file.name)
        }
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return@runCatching null
        val chosen = chooser.selectedFile.let { if (it.name.endsWith(".${file.extension}")) it else File(it.path + ".${file.extension}") }
        try {
            Files.copy(file.toPath(), chosen.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Windows n'autorise pas l'application à écrire dans « ${chosen.parent} » (dossier protégé ou droits). " +
                    "Utilisez « Copier » puis collez le fichier dans ce dossier avec l'Explorateur.",
                e,
            )
        }
        chosen
    }
}

/** Fenêtre affichée après la génération d'un export. */
@Composable
fun ExportDoneDialog(file: File, onClose: () -> Unit) {
    var status by remember(file) { mutableStateOf<String?>(null) }
    fun report(r: Result<*>, ok: String) {
        status = r.fold({ ok }, { it.message ?: it.javaClass.simpleName })
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Calendrier généré") },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(file.name, style = MaterialTheme.typography.titleSmall)
                Button(onClick = { report(ExportActions.open(file), "Fichier ouvert.") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Ouvrir (Excel / lecteur PDF)")
                }
                OutlinedButton(
                    onClick = { report(ExportActions.copyToClipboard(file), "Copié : ouvrez le dossier voulu dans l'Explorateur (Bureau, OneDrive, réseau…) et faites Ctrl+V.") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Copier — puis coller (Ctrl+V) dans le dossier de votre choix") }
                OutlinedButton(onClick = { report(ExportActions.showInExplorer(file), "Dossier ouvert dans l'Explorateur.") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Afficher dans l'Explorateur")
                }
                OutlinedButton(
                    onClick = {
                        ExportActions.saveAs(file).fold(
                            { saved -> status = saved?.let { "Enregistré : ${it.path}" } },
                            { status = it.message },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Enregistrer sous…") }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                Text(
                    "Le fichier reste aussi disponible dans ${ExportActions.dir.path}.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Fermer") } },
    )
}
