package be.meuse.engis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.delay
import be.meuse.engis.domain.CarnassierRules
import be.meuse.engis.domain.FishingStatus
import java.time.ZonedDateTime
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import be.meuse.engis.domain.FlowClass
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
    fixedNow: ZonedDateTime? = null,
) {
    // Horloge : le statut réglementaire est réévalué toutes les 30 s.
    val now by produceState(fixedNow ?: ZonedDateTime.now(BRUSSELS), fixedNow) {
        while (fixedNow == null) {
            delay(30_000)
            value = ZonedDateTime.now(BRUSSELS)
        }
    }
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
                RegulationCard(now, s?.flowClass)
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
    s.flowClass?.let { FlowClassBadge(it) }
    Caption("Station SPW d'Amay · moyenne horaire de ${r.point.time.atZoneSameInstant(BRUSSELS).format(DAY_HOUR)}")
    s.monthNormal?.let { normal ->
        val pct = (r.point.value / normal * 100).roundToInt()
        val month = monthName(r.point.time.atZoneSameInstant(BRUSSELS).toLocalDate())
        Text(
            "$pct % du débit moyen habituel en $month (${flow(normal)})",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    s.dayStats?.let { st ->
        Caption(
            st.thresholdsText(r.point.time.atZoneSameInstant(BRUSSELS).toLocalDate()) +
                " (statistiques SPW de ce jour, toutes années de mesure depuis 1995 : P15, P85, P90).",
        )
    }
    StaleWarning(r)
}

@Composable
private fun FlowClassBadge(c: FlowClass) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(vertical = 4.dp)
            .background(Color(c.argb()).copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(Color(c.argb()), CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(c.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
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

@Composable
private fun RegulationCard(now: ZonedDateTime, flowClass: FlowClass?) = InfoCard {
    val status = CarnassierRules.status(now)
    val mood = FisherMood.of(status, flowClass)
    Caption("PÊCHE AU CARNASSIER — MEUSE (ZONE D'EAUX CALMES)")
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (mood.drawable != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painterResource(mood.drawable),
                    contentDescription = if (mood == FisherMood.FISH) "Pêcheur : Fish!" else "Pêcheur endormi",
                    modifier = Modifier.size(56.dp),
                )
                Text(
                    mood.label.orEmpty(),
                    fontWeight = FontWeight.Bold,
                    color = if (mood == FisherMood.FISH) Color(0xFF0CA30C) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        Text(
            status.headline(now),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
    if (CarnassierRules.isSeasonOpen(now.toLocalDate())) {
        val (start, end) = CarnassierRules.dayWindow(now.toLocalDate(), now.zone)
        Text(
            "Aujourd'hui : pêche de jour de ${start.format(HOUR)} à ${end.format(HOUR)}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Caption("Saison carnassiers ${seasonText(now)}.")

    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text(if (open) "Masquer les règles" else "Voir les règles principales")
    }
    if (open) {
        RULES.forEach { (rule, ref) ->
            Text("• $rule", style = MaterialTheme.typography.bodySmall)
            Caption("   $ref")
        }
        Spacer(Modifier.height(4.dp))
        Caption(
            "Heures : 1 h avant le lever et 1 h après le coucher du soleil (art. 1er, 6°). L'arrêté ne précise pas le " +
                "lieu de référence de l'« heure officielle » : l'application retient la plage la plus courte entre " +
                "Uccle (IRM) et Engis, soit quelques minutes de marge.",
        )
        Caption(
            "Source : AGW du 8 décembre 2016 relatif aux conditions d'ouverture et aux modalités d'exercice de la " +
                "pêche, version en vigueur depuis le 01/01/2025 (wallex.wallonie.be, consultée le 22/11/2026). " +
                "Des interdictions temporaires locales restent possibles (art. 7) : respectez la signalisation sur place. " +
                "Permis de pêche obligatoire.",
        )
    }
}

/** Règles explicites de l'AGW du 08/12/2016 (version au 01/01/2025) utiles au pêcheur de carnassiers en Meuse. */
private val RULES = listOf(
    "Carnassiers (brochet, sandre, perche, aspe = groupe 2) : ouverts du 1er samedi de juin au 31 janvier en eaux calmes ; fermés du 1er février à la veille du 1er samedi de juin." to "Art. 4, 2° ; annexes 2 et 3",
    "Pêche de nuit interdite pour ces espèces (seule la carpe commune peut être pêchée de nuit)." to "Art. 5",
    "Pendant la fermeture du brochet : ni poissons-appâts (vivants ou morts), ni leurres ; seule exception en eaux calmes : leurre ≤ 7 cm à hameçon simple, avec un matériel de pêche à la mouche." to "Art. 10, §2",
    "Interdit de pêcher du haut des ponts et passerelles qui enjambent la Meuse (donc pas depuis le pont d'Engis), dans les écluses, et sur ou à moins de 50 m en aval des barrages." to "Art. 6, 1°, 3° et 4°",
    "Brochet : tout prélèvement interdit en Meuse du 01/01/2025 au 31/12/2027 — remise à l'eau obligatoire." to "Art. 13, al. 1er, 6°",
    "Sandre : au moins 50 cm et 2 par jour au maximum. Perche : au moins 24 cm et 5 par jour au maximum." to "Art. 13",
    "Brochet et sandre : interdiction de les garder vivants (bourriche, panier ou autre contenant)." to "Art. 13",
    "Meuse : poissons pris hors du lit principal non prélevables ; pêche au vif uniquement dans le lit principal." to "Art. 13, 1° ; art. 9, 3°",
)
