package be.wallonie.intemperies.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.wallonie.intemperies.data.SyncStatus
import be.wallonie.intemperies.model.DayStatus
import be.wallonie.intemperies.model.MonthReport
import be.wallonie.intemperies.ui.Fmt
import be.wallonie.intemperies.ui.components.CountBadge
import be.wallonie.intemperies.ui.components.MonthStrip
import be.wallonie.intemperies.ui.components.StatusChip
import be.wallonie.intemperies.ui.sharePdf
import be.wallonie.intemperies.ui.theme.StatusColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    onOpenCommune: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onPickCommunes: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is HomeEvent.Share -> sharePdf(context, e.file)
                is HomeEvent.Message -> snackbar.showSnackbar(e.text)
            }
        }
    }
    HomeScreen(state, syncing, syncStatus, snackbar, viewModel::refresh, viewModel::exportAll, onOpenCommune, onOpenSettings, onPickCommunes)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    syncing: Boolean,
    syncStatus: SyncStatus?,
    snackbar: SnackbarHostState,
    onRefresh: () -> Unit,
    onExportAll: () -> Unit,
    onOpenCommune: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onPickCommunes: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Intempéries", fontWeight = FontWeight.Bold)
                        Text(syncLabel(syncStatus), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (state is HomeUiState.Content) {
                        IconButton(onClick = onExportAll) { Icon(Icons.Default.PictureAsPdf, "PDF de toutes les communes (mois en cours)") }
                    }
                    IconButton(onClick = onRefresh, enabled = !syncing) {
                        if (syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, "Actualiser")
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Paramètres") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = syncing, onRefresh = onRefresh, modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                HomeUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                HomeUiState.Empty -> EmptyState(onPickCommunes)
                is HomeUiState.Content -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.reports, key = { it.commune.nis }) { report ->
                        CommuneCard(report, onClick = { onOpenCommune(report.commune.nis) })
                    }
                    item {
                        Text(
                            "Sources des données : SPW — hydrometrie.wallonie.be",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

private fun syncLabel(s: SyncStatus?): String {
    if (s == null) return ""
    if (s.lastError != null) return "Dernière synchro en erreur"
    val last = s.lastSuccessEpochSec ?: return "Jamais synchronisé"
    val t = Instant.ofEpochSecond(last).atZone(ZoneId.systemDefault())
    return "Synchronisé le " + t.format(DateTimeFormatter.ofPattern("dd/MM à HH:mm"))
}

@Composable
private fun EmptyState(onPickCommunes: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.WaterDrop, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Aucune commune suivie", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Sélectionnez les communes de vos chantiers : l'application se relie automatiquement au pluviomètre SPW le plus proche.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onPickCommunes) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.size(8.dp))
            Text("Choisir des communes")
        }
    }
}

@Composable
private fun CommuneCard(report: MonthReport, onClick: () -> Unit) {
    val today = report.days.firstOrNull { it.date == LocalDate.now(ZoneId.of("Europe/Brussels")) }
    val yesterday = today?.date?.minusDays(1)?.let { y -> report.days.firstOrNull { it.date == y } }
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(report.commune.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        report.rainStation?.let { "Pluviomètre ${it.station.name} · ${Fmt.num(it.distanceKm)} km" } ?: "Aucun pluviomètre",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                today?.let { StatusChip(it.status) }
            }
            Spacer(Modifier.height(10.dp))
            today?.let { d ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Aujourd'hui : ${Fmt.hours(d.rainHours)} de pluie · ${Fmt.mm(d.windowRainMm)}", style = MaterialTheme.typography.bodyMedium)
                    if (d.belowFrostThreshold == true) Icon(Icons.Default.AcUnit, "Gel", tint = StatusColors.Frost, modifier = Modifier.size(18.dp))
                }
            }
            yesterday?.let { d ->
                Text(
                    "Hier : ${Fmt.status(d.status).lowercase()} · ${Fmt.hours(d.rainHours)} · ${Fmt.mm(d.windowRainMm)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(Fmt.month(report.month), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            MonthStrip(report.days.map { it.status })
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CountBadge(report.count(DayStatus.INTEMPERIE), DayStatus.INTEMPERIE, "intempérie(s)")
                CountBadge(report.count(DayStatus.WARNING), DayStatus.WARNING, "pluie")
                CountBadge(report.count(DayStatus.DRY), DayStatus.DRY, "sec")
            }
        }
    }
}
