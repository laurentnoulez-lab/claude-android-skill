package be.wallonie.intemperies.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.wallonie.intemperies.model.DayReport
import be.wallonie.intemperies.model.DayStatus
import be.wallonie.intemperies.model.MonthReport
import be.wallonie.intemperies.model.StationAssignment
import be.wallonie.intemperies.model.StationKind
import be.wallonie.intemperies.ui.Fmt
import be.wallonie.intemperies.ui.components.CountBadge
import be.wallonie.intemperies.ui.components.StatusChip
import be.wallonie.intemperies.ui.home.FreshnessLine
import be.wallonie.intemperies.ui.shareExport
import be.wallonie.intemperies.ui.components.ExportMenu
import androidx.compose.material.icons.filled.FileDownload
import be.wallonie.intemperies.ui.theme.StatusColors
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarRoute(viewModel: CalendarViewModel, onBack: () -> Unit, onOpenRegulatory: () -> Unit) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val month by viewModel.month.collectAsStateWithLifecycle()
    val minMonth by viewModel.minMonth.collectAsStateWithLifecycle()
    val choices by viewModel.stationChoices.collectAsStateWithLifecycle()
    val exporting by viewModel.exporting.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selectedDay by remember { mutableStateOf<DayReport?>(null) }
    var stationDialog by remember { mutableStateOf<StationKind?>(null) }

    LaunchedEffect(Unit) { viewModel.share.collect { shareExport(context, it) } }
    var exportMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.commune?.name ?: "", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { if (!exporting) exportMenu = true },
                    icon = {
                        if (exporting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.FileDownload, null)
                    },
                    text = { Text("Exporter le mois") },
                )
                ExportMenu(exportMenu, onDismiss = { exportMenu = false }, onSelect = viewModel::export)
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = viewModel::previousMonth, enabled = month > minMonth) { Icon(Icons.Default.ChevronLeft, "Mois précédent") }
                Text(Fmt.month(month), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                IconButton(onClick = viewModel::nextMonth, enabled = month < viewModel.maxMonth) { Icon(Icons.Default.ChevronRight, "Mois suivant") }
            }
            val r = report
            if (r == null) {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    CountBadge(r.count(DayStatus.INTEMPERIE), DayStatus.INTEMPERIE, "intempérie(s)")
                    CountBadge(r.count(DayStatus.WARNING), DayStatus.WARNING, "pluie")
                    CountBadge(r.count(DayStatus.DRY), DayStatus.DRY, "sec")
                    if (r.count(DayStatus.NO_DATA) > 0) CountBadge(r.count(DayStatus.NO_DATA), DayStatus.NO_DATA, "sans données")
                }
                FreshnessLine(r)
                Text(
                    r.lastSyncEpochSec?.let { "Mise à jour depuis le SPW le ${Fmt.dateTime(it)}" } ?: "Données pas encore téléchargées",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                MonthGrid(r, onDayClick = { selectedDay = it })
                Spacer(Modifier.height(16.dp))
                StationsCard(r, onEdit = { stationDialog = it })
                Spacer(Modifier.height(12.dp))
                IntemperieList(r, onDayClick = { selectedDay = it })
                TextButton(onClick = onOpenRegulatory) {
                    Icon(Icons.Outlined.Gavel, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Base réglementaire : CCT Qualiroutes, ch. A, art. 76 § 4, 4°")
                }
                Text(
                    "Sources des données : SPW — hydrometrie.wallonie.be. Données indicatives ; * = non encore validées par le SPW.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 96.dp),
                )
            }
        }
    }

    selectedDay?.let { day ->
        ModalBottomSheet(onDismissRequest = { selectedDay = null }) {
            DayDetail(day, report)
        }
    }
    stationDialog?.let { kind ->
        val list = if (kind == StationKind.RAIN) choices?.rain else choices?.temperature
        val currentNo = if (kind == StationKind.RAIN) report?.rainStation else report?.temperatureStation
        StationDialog(
            kind = kind,
            options = list.orEmpty(),
            current = currentNo,
            onSelect = { no -> viewModel.setStation(kind, no); stationDialog = null },
            onDismiss = { stationDialog = null },
        )
    }
}
