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

@Composable
private fun MonthGrid(r: MonthReport, onDayClick: (DayReport) -> Unit) {
    val headers = listOf("L", "M", "M", "J", "V", "S", "D")
    Row(Modifier.fillMaxWidth()) {
        headers.forEach {
            Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(4.dp))
    val offset = r.month.atDay(1).dayOfWeek.value - 1
    val cells: List<DayReport?> = List(offset) { null } + r.days
    cells.chunked(7).forEach { week ->
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                val d = week.getOrNull(i)
                Box(Modifier.weight(1f).padding(2.dp)) {
                    if (d != null) DayCell(d, onClick = { onDayClick(d) })
                }
            }
        }
    }
}

@Composable
private fun DayCell(d: DayReport, onClick: () -> Unit) {
    val color = StatusColors.of(d.status)
    val pending = d.status == DayStatus.FUTURE || d.status == DayStatus.IN_PROGRESS
    val isToday = d.date == LocalDate.now(be.wallonie.intemperies.data.WeatherRepository.BRUSSELS)
    val nonWorking = d.isWeekend || d.holidayName != null
    Column(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.82f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pending) MaterialTheme.colorScheme.surfaceContainer else color)
            .border(
                width = if (isToday) 2.dp else 0.5.dp,
                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(enabled = d.status != DayStatus.FUTURE, onClick = onClick)
            .padding(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                d.date.dayOfMonth.toString(),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = if (pending) MaterialTheme.colorScheme.onSurface else StatusColors.OnStatus.copy(alpha = if (nonWorking) 0.6f else 1f),
            )
            Spacer(Modifier.weight(1f))
            if (d.coldReached == true) Icon(Icons.Default.AcUnit, "Gel", tint = StatusColors.Frost, modifier = Modifier.size(12.dp))
            if (d.heatReached == true) Icon(Icons.Default.WbSunny, "Canicule", tint = StatusColors.Heat, modifier = Modifier.size(12.dp))
        }
        Spacer(Modifier.weight(1f))
        if (!pending || d.status == DayStatus.IN_PROGRESS) {
            if (d.rainHours > 0) {
                Text(Fmt.hours(d.rainHours), fontSize = 10.sp, color = if (pending) MaterialTheme.colorScheme.onSurface else StatusColors.OnStatus, maxLines = 1)
            }
        }
        if (d.holidayName != null) Text("férié", fontSize = 9.sp, color = StatusColors.OnStatus.copy(alpha = 0.7f), maxLines = 1)
        if (d.provisional && !pending) Text("*", fontSize = 10.sp, color = StatusColors.OnStatus, modifier = Modifier.align(Alignment.End))
    }
}

@Composable
private fun StationsCard(r: MonthReport, onEdit: (StationKind) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            StationLine("Pluviomètre", r.rainStation, "Aucun", onEdit = { onEdit(StationKind.RAIN) })
            HorizontalDivider()
            StationLine(
                "Température de l'air", r.temperatureStation,
                "Aucune station à moins de ${Fmt.num(r.criteria.maxTemperatureDistanceKm, 0)} km",
                onEdit = { onEdit(StationKind.TEMPERATURE) },
            )
        }
    }
}

@Composable
private fun StationLine(label: String, a: StationAssignment?, empty: String, onEdit: () -> Unit) {
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = { Text(a?.station?.name ?: empty) },
        supportingContent = {
            a?.let { Text("SPW n° ${it.station.stationNo} · ${Fmt.num(it.distanceKm)} km" + if (it.manual) " · choix manuel" else " · la plus proche") }
        },
        trailingContent = { IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Changer de station") } },
    )
}

@Composable
private fun IntemperieList(r: MonthReport, onDayClick: (DayReport) -> Unit) {
    val red = r.days.filter { it.status == DayStatus.INTEMPERIE }
    Text("Jours d'intempéries (${red.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    if (red.isEmpty()) {
        Text("Aucun jour d'intempérie ce mois-ci.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
    }
    red.forEach { d ->
        ListItem(
            modifier = Modifier.clickable { onDayClick(d) },
            leadingContent = { Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(StatusColors.Intemperie)) },
            headlineContent = { Text(Fmt.longDate(d.date) + if (d.provisional) " *" else "") },
            supportingContent = {
                Text(
                    "${Fmt.mm(d.windowRainMm)} · pluie ${Fmt.hours(d.rainHours)} · consécutive ${Fmt.hours(d.maxConsecutiveRainHours)}" +
                        (d.meanTemperatureC?.let { " · T moy. ${Fmt.temp(it)}" } ?: "") + "\n" +
                        d.reasons.joinToString(" + ") { Fmt.reason(it, r.criteria) },
                )
            },
        )
    }
}

@Composable
private fun DayDetail(d: DayReport, r: MonthReport?) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Text(Fmt.longDate(d.date), style = MaterialTheme.typography.titleLarge)
        d.holidayName?.let { Text("Jour férié : $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(8.dp))
        StatusChip(d.status)
        if (r != null && d.reasons.isNotEmpty()) {
            Text(d.reasons.joinToString(" + ") { Fmt.reason(it, r.criteria) }, modifier = Modifier.padding(top = 6.dp), fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        val c = r?.criteria
        if (c != null && d.status != DayStatus.FUTURE) {
            DayChart(
                day = d,
                criteria = c,
                temperatureStationLabel = r.temperatureStation?.let { "${it.station.name}, ${Fmt.num(it.distanceKm)} km" },
            )
            Spacer(Modifier.height(8.dp))
        }
        Metric("Précipitations dans la plage ${c?.windowStart ?: ""}–${c?.windowEnd ?: ""}", Fmt.mm(d.windowRainMm))
        Metric("Durée de précipitations", Fmt.hours(d.rainHours))
        Metric("Précipitations consécutives max.", Fmt.hours(d.maxConsecutiveRainHours))
        Metric("Précipitations sur 24 h", Fmt.mm(d.dayRainMm))
        Metric("Température moyenne (plage)", Fmt.temp(d.meanTemperatureC))
        if (d.minTemperatureC != null) Metric("T min. / max. (moyennes horaires)", "${Fmt.temp(d.minTemperatureC)} / ${Fmt.temp(d.maxTemperatureC)}")
        if (c != null && d.coldHours != null) Metric("Durée T < ${Fmt.deg(c.coldThresholdC)} (gel)", Fmt.hours(d.coldHours))
        if (c != null && d.heatHours != null) Metric("Durée T > ${Fmt.deg(c.heatThresholdC)} (canicule)", Fmt.hours(d.heatHours))
        Metric("Données disponibles", "${(d.coverage * 100).toInt()} %")
        if (d.provisional) Metric("Validation SPW", "non encore validées")
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StationDialog(
    kind: StationKind,
    options: List<StationAssignment>,
    current: StationAssignment?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (kind == StationKind.RAIN) "Pluviomètre" else "Station de température") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ChoiceRow("Automatique (la plus proche)", current?.manual != true) { onSelect(null) }
                options.forEach { o ->
                    ChoiceRow(
                        "${o.station.name} — ${Fmt.num(o.distanceKm)} km",
                        current?.manual == true && current.station.stationNo == o.station.stationNo,
                    ) { onSelect(o.station.stationNo) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}
