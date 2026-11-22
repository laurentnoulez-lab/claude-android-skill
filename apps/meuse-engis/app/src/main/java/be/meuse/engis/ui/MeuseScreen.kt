package be.meuse.engis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import be.meuse.engis.domain.MeuseSnapshot
import be.meuse.engis.domain.Reading
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeuseScreen(
    state: MeuseUiState,
    onRefresh: () -> Unit,
    onReference: (Reference) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("La Meuse à Engis", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Ombret → Ivoz-Ramet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Actualiser")
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = onRefresh,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.error?.let { ErrorCard(it, hasCache = state.snapshot != null) }
                val s = state.snapshot
                if (s == null) {
                    if (!state.loading && state.error == null) Text("Aucune donnée pour l'instant.")
                } else {
                    FlowCard(s)
                    LevelCard(s)
                    ChartCard(s, state.reference, onReference)
                    SourceFooter(s.fetchedAt)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun Caption(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun BigValue(value: String, reading: Reading) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(value, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
        Text(
            reading.trend.label() + " (3 h)",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}

@Composable
private fun StaleWarning(reading: Reading) {
    val age = Duration.between(reading.point.time.toInstant(), Instant.now())
    if (age > Duration.ofHours(3)) {
        Text(
            "⚠ Dernière mesure il y a ${age.toHours()} h — la station ne transmet peut-être plus.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun FlowCard(s: MeuseSnapshot) = InfoCard {
    Caption("DÉBIT DE LA MEUSE")
    val r = s.amayFlow
    if (r == null) {
        Text("Pas de mesure disponible")
        return@InfoCard
    }
    BigValue(flow(r.point.value), r)
    Caption("Station SPW d'Amay · moyenne horaire de ${r.point.time.atZoneSameInstant(BRUSSELS).format(DAY_HOUR)}")
    s.monthNormal?.let { normal ->
        val pct = (r.point.value / normal * 100).roundToInt()
        val month = monthName(r.point.time.atZoneSameInstant(BRUSSELS).toLocalDate())
        Text(
            "$pct % du débit moyen habituel en $month (${flow(normal)})",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    StaleWarning(r)
}

@Composable
private fun LevelCard(s: MeuseSnapshot) = InfoCard {
    Caption("NIVEAU DU BIEF D'ENGIS")
    val h = s.ivozLevel
    if (h == null) {
        Text("Pas de mesure disponible")
    } else {
        BigValue(level(h.point.value), h)
        Caption(
            "Hauteur à l'échelle, station SPW Ivoz-Ramet bief amont · " +
                h.point.time.atZoneSameInstant(BRUSSELS).format(DAY_HOUR),
        )
        StaleWarning(h)
    }
    s.ivozFlow?.let { q ->
        Spacer(Modifier.height(4.dp))
        Text(
            "Débit au barrage d'Ivoz-Ramet : ${flow(q.point.value)} · ${q.trend.label()}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Caption("Le niveau du bief est régulé par le barrage : c'est le débit qui reflète le courant.")
}

@Composable
private fun ChartCard(s: MeuseSnapshot, reference: Reference, onReference: (Reference) -> Unit) = InfoCard {
    Caption("DÉBIT MOYEN JOURNALIER À AMAY — 7 DERNIERS JOURS + AUJOURD'HUI")
    val today = s.bars.lastOrNull()?.date ?: LocalDate.now(BRUSSELS)
    val monthShort = monthName(today)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        SegmentedButton(
            selected = reference == Reference.NORMALE,
            onClick = { onReference(Reference.NORMALE) },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
        ) { Text("Moyenne habituelle") }
        SegmentedButton(
            selected = reference == Reference.MOIS_EN_COURS,
            onClick = { onReference(Reference.MOIS_EN_COURS) },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
        ) { Text("Mois en cours") }
    }
    val (refValue, refLabel) = when (reference) {
        Reference.NORMALE -> s.monthNormal to "Moyenne habituelle de $monthShort"
        Reference.MOIS_EN_COURS -> s.monthToDate to "Moyenne de $monthShort ${today.year}"
    }
    DailyFlowChart(bars = s.bars, reference = refValue, referenceLabel = refLabel)
    Caption(
        when (reference) {
            Reference.NORMALE ->
                "Ligne tiretée : débit moyen de ${monthName(today)} calculé par le SPW sur toutes les années " +
                    "de mesure (série « InterAn.Mois.Moyen »)."
            Reference.MOIS_EN_COURS ->
                "Ligne tiretée : moyenne des débits horaires depuis le 1er ${monthName(today)} jusqu'à la dernière mesure."
        },
    )
    Caption("Barre hachurée : journée en cours (moyenne provisoire). Touchez une barre pour le détail.")
}

@Composable
private fun ErrorCard(message: String, hasCache: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            if (hasCache) {
                Text(
                    "Affichage des dernières données enregistrées.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun SourceFooter(fetchedAt: Instant) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Caption("Mis à jour le ${fetchedAt.atZone(BRUSSELS).format(DAY_HOUR)}")
        Caption(
            "Source : SPW Mobilité et Infrastructures — hydrometrie.wallonie.be (service KiWIS). " +
                "Données brutes en temps réel, non validées.",
        )
    }
}
