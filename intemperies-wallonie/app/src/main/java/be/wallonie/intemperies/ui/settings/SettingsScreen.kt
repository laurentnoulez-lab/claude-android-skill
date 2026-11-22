package be.wallonie.intemperies.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.wallonie.intemperies.model.Criteria
import be.wallonie.intemperies.ui.Fmt
import be.wallonie.intemperies.ui.components.SectionTitle
import be.wallonie.intemperies.ui.components.StepperRow
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onPickCommunes: () -> Unit,
    onOpenRegulatory: () -> Unit,
    onOpenHowItWorks: () -> Unit,
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var editTime by remember { mutableStateOf<Boolean?>(null) } // true = début, false = fin
    var confirmReset by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paramètres") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
    ) { padding ->
        val settings = s ?: return@Scaffold
        val c = settings.criteria
        val update = viewModel::updateCriteria
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            SectionTitle("Communes suivies (${settings.selectedCommunes.size})")
            viewModel.communes(settings.selectedCommunes).forEach { com ->
                ListItem(
                    headlineContent = { Text(com.name) },
                    supportingContent = { Text("${com.province} · INS ${com.nis}") },
                    trailingContent = { IconButton(onClick = { viewModel.removeCommune(com.nis) }) { Icon(Icons.Default.Close, "Retirer") } },
                )
            }
            OutlinedButton(onClick = onPickCommunes, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null)
                Text("  Ajouter / retirer des communes")
            }

            SectionTitle("Fonctionnement")
            ListItem(
                modifier = Modifier.clickable(onClick = onOpenHowItWorks),
                leadingContent = { Icon(Icons.AutoMirrored.Outlined.HelpOutline, null) },
                headlineContent = { Text("Comment l'application qualifie une journée") },
                supportingContent = { Text("Stations, précipitations, température, couleurs — avec les valeurs en vigueur") },
            )

            SectionTitle("Base réglementaire")
            ListItem(
                modifier = Modifier.clickable(onClick = onOpenRegulatory),
                leadingContent = { Icon(Icons.Outlined.Gavel, null) },
                headlineContent = { Text("Jour d’intempérie selon le CCT Qualiroutes") },
                supportingContent = { Text("Chapitre A, art. 76 § 4, 4° (RGE) — textes et lecture") },
            )

            SectionTitle("Plage horaire de travail")
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = { editTime = true }, label = { Text("Début ${c.windowStart}") }, leadingIcon = { Icon(Icons.Default.Schedule, null) })
                Spacer(Modifier.padding(6.dp))
                AssistChip(onClick = { editTime = false }, label = { Text("Fin ${c.windowEnd}") }, leadingIcon = { Icon(Icons.Default.Schedule, null) })
            }

            SectionTitle("Précipitations (pluie, neige, grêle) — intempérie (rouge)")
            StepperRow("Précipitations consécutives", c.consecutiveRainHours, 0.5, 0.5, 12.0, Fmt::hours, { v -> update { it.copy(consecutiveRainHours = v) } })
            StepperRow("Précipitations totales dans la plage", c.totalRainHours, 0.5, 0.5, 12.0, Fmt::hours, { v -> update { it.copy(totalRainHours = v) } })
            SwitchRow(
                "Comparaison stricte (« plus de »)",
                if (c.strictComparison) "Rouge si la durée dépasse le seuil (>)" else "Rouge dès que le seuil est atteint (≥)",
                c.strictComparison,
            ) { v -> update { it.copy(strictComparison = v) } }

            SectionTitle("Précipitations — journée orange")
            StepperRow("Précipitations au moins", c.warningRainHours, 0.5, 0.5, 12.0, Fmt::hours, { v -> update { it.copy(warningRainHours = v) } })

            SectionTitle("Détection des précipitations")
            Text("Pas d'analyse", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            val steps = listOf(15, 30, 60)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                steps.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = c.stepMinutes == m,
                        onClick = { update { it.copy(stepMinutes = m) } },
                        shape = SegmentedButtonDefaults.itemShape(i, steps.size),
                    ) { Text("$m min") }
                }
            }
            StepperRow(
                "Intensité minimale de précipitation", c.wetIntensityMmH, 0.1, 0.1, 10.0, { Fmt.mmPrecise(it) + " mm/h" },
                { v -> update { it.copy(wetIntensityMmH = v) } },
                subtitle = "Soit un cumul ≥ ${Fmt.mmPrecise(c.wetStepThresholdMm)} mm par pas de ${c.stepMinutes} min${Fmt.practicalNote(c)}",
            )
            Text(
                "Comment c'est calculé : la plage ${c.windowStart}–${c.windowEnd} est découpée en pas de ${c.stepMinutes} min. " +
                    "Pour chaque pas, l'application additionne les mesures du pluviomètre (une valeur toutes les 5 min). " +
                    "Le pas compte comme temps de précipitation si ce cumul atteint l'intensité minimale × la durée du pas : " +
                    "${Fmt.intensity(c)} × ${c.stepMinutes} min = ${Fmt.mmPrecise(c.wetStepThresholdMm)} mm. " +
                    "Les mesures SPW étant le plus souvent publiées par paliers de 0,1 mm, ce seuil est en général atteint " +
                    "à ${Fmt.num(c.effectiveStepThresholdMm, 1)} mm.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )

            SectionTitle("Température — seuil bas (gel)")
            StepperRow(
                "Température inférieure à", c.coldThresholdC, 0.5, -15.0, 10.0, { Fmt.deg(it) },
                { v -> update { it.copy(coldThresholdC = v) } },
                subtitle = "Moyennes horaires de la station de température",
            )
            StepperRow(
                "Pendant au moins", c.coldMinHours, 0.5, 0.5, 12.0, Fmt::hours,
                { v -> update { it.copy(coldMinHours = v) } },
                subtitle = "Durée cumulée entre ${c.windowStart} et ${c.windowEnd}",
            )
            SwitchRow(
                "Le gel provoque une intempérie",
                if (c.coldCountsAsIntemperie) "Journée rouge si le seuil bas est atteint" else "Seulement signalé (flocon), sans changer la couleur",
                c.coldCountsAsIntemperie,
            ) { v -> update { it.copy(coldCountsAsIntemperie = v) } }

            SectionTitle("Température — seuil haut (canicule)")
            StepperRow(
                "Température supérieure à", c.heatThresholdC, 0.5, 20.0, 45.0, { Fmt.deg(it) },
                { v -> update { it.copy(heatThresholdC = v) } },
                subtitle = "Moyennes horaires de la station de température",
            )
            StepperRow(
                "Pendant au moins", c.heatMinHours, 0.5, 0.5, 12.0, Fmt::hours,
                { v -> update { it.copy(heatMinHours = v) } },
                subtitle = "Durée cumulée entre ${c.windowStart} et ${c.windowEnd}",
            )
            SwitchRow(
                "La canicule provoque une intempérie",
                if (c.heatCountsAsIntemperie) "Journée rouge si le seuil haut est atteint" else "Seulement signalé (soleil), sans changer la couleur",
                c.heatCountsAsIntemperie,
            ) { v -> update { it.copy(heatCountsAsIntemperie = v) } }

            SectionTitle("Température — station")
            StepperRow(
                "Distance max. station de température", c.maxTemperatureDistanceKm, 5.0, 5.0, 150.0, { Fmt.num(it, 0) + " km" },
                { v -> update { it.copy(maxTemperatureDistanceKm = v) } },
                subtitle = "Le réseau SPW compte peu de stations de température de l'air",
            )

            SectionTitle("Données")
            StepperRow(
                "Historique conservé", settings.retentionMonths.toDouble(), 1.0, 1.0, 12.0, { "${it.toInt()} mois" },
                { v -> viewModel.setRetention(v.toInt()) },
                subtitle = "Mois en cours + N mois précédents",
            )
            StepperRow(
                "Données minimales pour statuer", c.minCoverage * 100, 5.0, 50.0, 100.0, { "${it.toInt()} %" },
                { v -> update { it.copy(minCoverage = v / 100.0) } },
                subtitle = "En dessous : journée grise « données insuffisantes »",
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = viewModel::resync, modifier = Modifier.fillMaxWidth()) { Text("Recharger les 35 derniers jours") }
            TextButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) { Text("Rétablir les critères par défaut") }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text(
                "Sources des données : SPW — hydrometrie.wallonie.be (pluviomètres à pesée, pas de 5 min ; température de l'air horaire). " +
                    "Les données sont indicatives et sans valeur légale ; les données récentes ne sont pas encore validées par le SPW. " +
                    "Les communes sont localisées par leur centroïde ; la station la plus proche est retenue automatiquement.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 32.dp),
            )
        }
    }

    editTime?.let { isStart ->
        val s0 = s?.criteria ?: Criteria()
        val initial = if (isStart) s0.windowStart else s0.windowEnd
        TimeDialog(
            initial = initial,
            title = if (isStart) "Début de la plage de travail" else "Fin de la plage de travail",
            validate = { t ->
                val start = if (isStart) t else s0.windowStart
                val end = if (isStart) s0.windowEnd else t
                if (end.isAfter(start)) null else "La fin doit être postérieure au début (${s0.windowStart}–${s0.windowEnd} actuellement)."
            },
            onDismiss = { editTime = null },
        ) { t ->
            viewModel.updateCriteria { if (isStart) it.copy(windowStart = t) else it.copy(windowEnd = t) }
            editTime = null
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Rétablir les valeurs par défaut ?") },
            text = { Text("07:30–16:00, pas de 30 min, précipitations ≥ 1 mm/h, consécutives > 3 h ou totales > 4 h, orange ≥ 2 h ; gel < 0 °C et canicule > 32 °C pendant ≥ 3 h, signalés sans être bloquants.") },
            confirmButton = { TextButton(onClick = { viewModel.resetCriteria(); confirmReset = false }) { Text("Rétablir") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(
    initial: LocalTime,
    title: String,
    validate: (LocalTime) -> String?,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    val chosen = LocalTime.of(state.hour, state.minute)
    val error = validate(chosen)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                TimePicker(state = state)
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else Text("Les calculs, graphiques et exports suivront cette plage.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(chosen) }, enabled = error == null) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
