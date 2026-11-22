package be.wallonie.intemperies.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import be.wallonie.intemperies.data.BRUSSELS
import be.wallonie.intemperies.data.Catalogs
import be.wallonie.intemperies.data.SyncOutcome
import be.wallonie.intemperies.domain.StationMatcher
import be.wallonie.intemperies.export.CalendarWorkbook
import be.wallonie.intemperies.export.ExportFormat
import be.wallonie.intemperies.export.ExportNames
import be.wallonie.intemperies.model.DayReport
import be.wallonie.intemperies.model.DayStatus
import be.wallonie.intemperies.model.MonthReport
import be.wallonie.intemperies.model.StationKind
import be.wallonie.intemperies.ui.Fmt
import be.wallonie.intemperies.ui.calendar.DayDetail
import be.wallonie.intemperies.ui.calendar.IntemperieList
import be.wallonie.intemperies.ui.calendar.MonthGrid
import be.wallonie.intemperies.ui.calendar.StationDialog
import be.wallonie.intemperies.ui.calendar.StationsCard
import be.wallonie.intemperies.ui.components.CountBadge
import be.wallonie.intemperies.ui.components.ExportMenu
import be.wallonie.intemperies.ui.home.CommuneCard
import be.wallonie.intemperies.ui.home.EmptyState
import be.wallonie.intemperies.ui.home.FreshnessLine
import be.wallonie.intemperies.ui.home.syncLabel
import be.wallonie.intemperies.ui.legal.HowItWorksContent
import be.wallonie.intemperies.ui.legal.RegulatoryContent
import be.wallonie.intemperies.ui.settings.SettingsContent
import be.wallonie.intemperies.ui.theme.IntemperiesTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.awt.Frame
import java.io.File
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--selfcheck") {
        kotlin.system.exitProcess(SelfCheck.run(File(args.getOrElse(1) { "." })))
    }
    val repo = DesktopRepository()
    repo.startPeriodicSync()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Intempéries Wallonie",
            icon = painterResource("icon.png"),
            state = rememberWindowState(width = 1360.dp, height = 880.dp),
        ) {
            IntemperiesTheme { DesktopApp(repo, window) }
        }
    }
}

private enum class Screen { MAIN, SETTINGS, HOW, REGULATORY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopApp(repo: DesktopRepository, frame: Frame?) {
    val settings by repo.settings.collectAsState()
    val syncStatus by repo.syncStatus.collectAsState()
    val syncing by repo.syncing.collectAsState()
    var screen by remember { mutableStateOf(Screen.MAIN) }
    var selectedNis by remember { mutableStateOf<String?>(null) }
    var month by remember { mutableStateOf(YearMonth.now(BRUSSELS)) }
    var selectedDay by remember { mutableStateOf<DayReport?>(null) }
    var picker by remember { mutableStateOf(false) }
    var stationDialog by remember { mutableStateOf<StationKind?>(null) }
    var exportMenu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val nisList = settings.selectedCommunes
    LaunchedEffect(nisList) { if (selectedNis !in nisList) selectedNis = nisList.firstOrNull() }
    val current = YearMonth.now(BRUSSELS)
    val cards by remember(nisList) {
        if (nisList.isEmpty()) flowOf(emptyList()) else combine(nisList.map { repo.observeMonth(it, current) }) { it.filterNotNull() }
    }.collectAsState(emptyList())
    val report by remember(selectedNis, month) {
        selectedNis?.let { repo.observeMonth(it, month) } ?: flowOf(null)
    }.collectAsState(null)

    var exported by remember { mutableStateOf<File?>(null) }

    // Export : création dans le dossier de l'application (toujours autorisé), puis choix de la destination.
    fun export(format: ExportFormat, reports: List<MonthReport>) {
        if (reports.isEmpty()) return
        val target = File(ExportActions.dir, ExportNames.fileName(reports, format))
        scope.launch {
            runCatching {
                when (format) {
                    ExportFormat.PDF -> DesktopPdf.export(reports, target)
                    ExportFormat.XLSX -> target.writeBytes(
                        CalendarWorkbook.build(reports, LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))),
                    )
                }
            }.onSuccess { exported = target }
                .onFailure { snackbar.showSnackbar("Erreur d'export : ${it.message}") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (screen != Screen.MAIN) IconButton(onClick = { screen = Screen.MAIN }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                },
                title = {
                    Column {
                        Text(
                            when (screen) {
                                Screen.MAIN -> "Intempéries Wallonie"
                                Screen.SETTINGS -> "Paramètres"
                                Screen.HOW -> "Fonctionnement"
                                Screen.REGULATORY -> "Base réglementaire"
                            },
                            fontWeight = FontWeight.Bold,
                        )
                        Text(syncLabel(syncStatus), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (screen == Screen.MAIN) {
                        Box {
                            TextButton(onClick = { exportMenu = true }) {
                                Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(6.dp)); Text("Exporter")
                            }
                            DropdownExport(
                                expanded = exportMenu, onDismiss = { exportMenu = false },
                                onCurrent = { f -> report?.let { export(f, listOf(it)) } },
                                onAll = { f -> export(f, cards) },
                                currentLabel = report?.let { "${it.commune.name} — ${Fmt.month(it.month)}" },
                            )
                        }
                    }
                    IconButton(onClick = {
                        scope.launch {
                            val r = repo.sync()
                            if (r is SyncOutcome.Failure) snackbar.showSnackbar("Mise à jour impossible : ${r.message}")
                        }
                    }, enabled = !syncing) {
                        if (syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, "Actualiser")
                    }
                    IconButton(onClick = { screen = Screen.HOW }) { Icon(Icons.AutoMirrored.Outlined.HelpOutline, "Fonctionnement") }
                    IconButton(onClick = { screen = Screen.REGULATORY }) { Icon(Icons.Outlined.Gavel, "Base réglementaire") }
                    IconButton(onClick = { screen = Screen.SETTINGS }) { Icon(Icons.Default.Settings, "Paramètres") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val mod = Modifier.padding(padding).fillMaxSize()
        when (screen) {
            Screen.SETTINGS -> Box(mod, contentAlignment = Alignment.TopCenter) {
                SettingsContent(
                    settings = settings,
                    communes = settings.selectedCommunes.mapNotNull { Catalogs.commune(it) },
                    onRemoveCommune = { repo.toggleCommune(it) },
                    onPickCommunes = { picker = true },
                    onOpenHowItWorks = { screen = Screen.HOW },
                    onOpenRegulatory = { screen = Screen.REGULATORY },
                    onCriteria = { repo.updateCriteria(it) },
                    onRetention = { repo.setRetention(it) },
                    onResync = { repo.syncInBackground(forceDeep = true) },
                    onReset = { repo.resetCriteria() },
                    modifier = Modifier.width(760.dp),
                )
            }
            Screen.HOW -> Box(mod, contentAlignment = Alignment.TopCenter) {
                HowItWorksContent(settings, { screen = Screen.SETTINGS }, { screen = Screen.REGULATORY }, Modifier.width(860.dp))
            }
            Screen.REGULATORY -> Box(mod, contentAlignment = Alignment.TopCenter) { RegulatoryContent(Modifier.width(860.dp)) }
            Screen.MAIN -> Row(mod) {
                // Colonne des communes
                Column(Modifier.width(340.dp).fillMaxHeight()) {
                    if (cards.isEmpty() && nisList.isEmpty()) {
                        EmptyState(onPickCommunes = { picker = true })
                    } else {
                        LazyColumn(
                            Modifier.weight(1f),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(cards, key = { it.commune.nis }) { r ->
                                val sel = r.commune.nis == selectedNis
                                Box(
                                    if (sel) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier,
                                ) {
                                    CommuneCard(r, onClick = { selectedNis = r.commune.nis; month = current; selectedDay = null })
                                }
                            }
                        }
                        OutlinedButton(onClick = { picker = true }, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("Ajouter / retirer des communes")
                        }
                    }
                }
                HorizontalDivider(Modifier.fillMaxHeight().width(1.dp))
                // Calendrier
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    val r = report
                    if (r == null) {
                        Text(
                            if (nisList.isEmpty()) "Choisissez une ou plusieurs communes pour commencer." else "Chargement…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(r.commune.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { month = month.minusMonths(1); selectedDay = null }, enabled = month > repo.retentionStartMonth()) {
                                Icon(Icons.Default.ChevronLeft, "Mois précédent")
                            }
                            Text(Fmt.month(month), style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(220.dp), textAlign = TextAlign.Center)
                            IconButton(onClick = { month = month.plusMonths(1); selectedDay = null }, enabled = month < current) {
                                Icon(Icons.Default.ChevronRight, "Mois suivant")
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                            CountBadge(r.count(DayStatus.INTEMPERIE), DayStatus.INTEMPERIE, "intempérie(s)")
                            Spacer(Modifier.width(12.dp))
                            CountBadge(r.count(DayStatus.WARNING), DayStatus.WARNING, "pluie")
                            Spacer(Modifier.width(12.dp))
                            CountBadge(r.count(DayStatus.DRY), DayStatus.DRY, "sec")
                        }
                        FreshnessLine(r)
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.width(720.dp)) { Column { MonthGrid(r, onDayClick = { selectedDay = it }, cellAspect = 1.45f) } }
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.width(720.dp)) { StationsCard(r, onEdit = { stationDialog = it }) }
                        Spacer(Modifier.height(12.dp))
                        Column(Modifier.width(720.dp)) { IntemperieList(r, onDayClick = { selectedDay = it }) }
                        Text(
                            "Sources des données : SPW — hydrometrie.wallonie.be. Données indicatives ; * = non encore validées par le SPW.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
                // Détail du jour (mis à jour avec les données)
                val day = selectedDay?.let { d -> report?.days?.firstOrNull { it.date == d.date } }
                if (day != null) {
                    HorizontalDivider(Modifier.fillMaxHeight().width(1.dp))
                    Card(Modifier.width(440.dp).fillMaxHeight().padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            IconButton(onClick = { selectedDay = null }) { Icon(Icons.Default.Close, "Fermer") }
                        }
                        DayDetail(day, report)
                    }
                }
            }
        }
    }

    exported?.let { f -> ExportDoneDialog(f, onClose = { exported = null }) }
    if (picker) CommunePickerDialog(settings.selectedCommunes, onToggle = { repo.toggleCommune(it) }, onClose = { picker = false })
    stationDialog?.let { kind ->
        val r = report
        val commune = r?.commune
        if (commune != null) {
            val c = repo.stations.value
            val options = StationMatcher.rankByDistance(commune, if (kind == StationKind.RAIN) c.rain else c.temperature).take(10)
            StationDialog(
                kind = kind,
                options = options,
                current = if (kind == StationKind.RAIN) r.rainStation else r.temperatureStation,
                onSelect = { no -> repo.setStationOverride(kind, commune.nis, no); stationDialog = null },
                onDismiss = { stationDialog = null },
            )
        } else {
            stationDialog = null
        }
    }
}

@Composable
private fun DropdownExport(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCurrent: (ExportFormat) -> Unit,
    onAll: (ExportFormat) -> Unit,
    currentLabel: String?,
) {
    androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (currentLabel != null) {
            ExportFormat.entries.forEach { f ->
                androidx.compose.material3.DropdownMenuItem(text = { Text("${f.label} — $currentLabel") }, onClick = { onDismiss(); onCurrent(f) })
            }
        }
        ExportFormat.entries.forEach { f ->
            androidx.compose.material3.DropdownMenuItem(text = { Text("${f.label} — toutes les communes (mois en cours)") }, onClick = { onDismiss(); onAll(f) })
        }
    }
}

@Composable
private fun CommunePickerDialog(selected: List<String>, onToggle: (String) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Communes wallonnes (${selected.size} suivie(s))") },
        text = {
            Column(Modifier.width(520.dp).height(560.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Rechercher (nom ou code INS)") }, modifier = Modifier.fillMaxWidth(),
                )
                val nq = Catalogs.normalize(query.trim())
                val list = Catalogs.communes.filter { nq.isEmpty() || Catalogs.normalize(it.name).contains(nq) || it.nis.startsWith(nq) }
                LazyColumn(Modifier.weight(1f)) {
                    items(list, key = { it.nis }) { c ->
                        ListItem(
                            modifier = Modifier.clickable { onToggle(c.nis) },
                            headlineContent = { Text(c.name) },
                            supportingContent = { Text("${c.province} · INS ${c.nis}") },
                            trailingContent = { Checkbox(checked = c.nis in selected, onCheckedChange = { onToggle(c.nis) }) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Fermer") } },
    )
}
