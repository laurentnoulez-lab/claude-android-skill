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
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import be.wallonie.intemperies.data.SyncStatus
import be.wallonie.intemperies.model.DayStatus
import be.wallonie.intemperies.model.MonthReport
import be.wallonie.intemperies.ui.Fmt
import be.wallonie.intemperies.ui.components.AlertBanner
import be.wallonie.intemperies.ui.components.CountBadge
import be.wallonie.intemperies.ui.components.MonthStrip
import be.wallonie.intemperies.ui.components.StatusChip
import be.wallonie.intemperies.ui.components.ExportMenu
import be.wallonie.intemperies.export.ExportFormat
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.FileDownload
import be.wallonie.intemperies.ui.theme.StatusColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter


fun syncLabel(s: SyncStatus?): String {
    if (s == null) return ""
    val last = s.lastSuccessEpochSec
    if (s.lastError != null) return "Échec de la dernière mise à jour" + (last?.let { " — données du ${Fmt.dateTime(it)}" } ?: "")
    if (last == null) return "Données pas encore téléchargées"
    return "Mise à jour le ${Fmt.dateTime(last)}"
}

@Composable
fun EmptyState(onPickCommunes: () -> Unit) {
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
fun CommuneCard(report: MonthReport, onClick: () -> Unit, onOpenSettings: (() -> Unit)? = null) {
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
                    Text("Aujourd'hui : ${Fmt.daySummary(d)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                    if (d.coldReached == true) Icon(Icons.Default.AcUnit, "Seuil bas (gel) atteint", tint = StatusColors.Frost, modifier = Modifier.size(18.dp))
                    if (d.heatReached == true) Icon(Icons.Default.WbSunny, "Seuil haut (canicule) atteint", tint = StatusColors.Heat, modifier = Modifier.size(18.dp))
                }
            }
            yesterday?.let { d ->
                Text(
                    "Hier : ${Fmt.status(d.status).lowercase()}" +
                        (if (d.reasons.isNotEmpty()) " (${d.reasons.joinToString(" + ") { Fmt.reason(it, report.criteria) }})" else "") +
                        " · ${Fmt.daySummary(d)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            FreshnessLine(report)
            val alerts = Fmt.stationAlerts(report)
            if (alerts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                AlertBanner(
                    alerts,
                    actionLabel = if (onOpenSettings != null && report.temperatureStation == null) "Ouvrir les paramètres" else null,
                    onAction = onOpenSettings,
                )
            }
            Spacer(Modifier.height(10.dp))
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

/** Date et heure de la dernière mesure disponible, avec alerte si la station ne transmet plus. */
@Composable
fun FreshnessLine(report: MonthReport, modifier: Modifier = Modifier) {
    val last = report.lastRainDataEpochSec
    val stale = Fmt.isStale(last)
    Text(
        (if (stale) "⚠ " else "") + "Mesures jusqu'au " +
            (last?.let { Fmt.dateTime(Fmt.rainDataEnd(it)) } ?: "—") +
            (if (stale) " — aucune nouvelle mesure depuis plus de ${Fmt.STALE_HOURS} h" else ""),
        style = MaterialTheme.typography.bodySmall,
        color = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
