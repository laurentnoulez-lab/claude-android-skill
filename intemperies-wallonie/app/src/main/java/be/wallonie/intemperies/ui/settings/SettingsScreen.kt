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

            SectionTitle("Journée d'intempérie (rouge)")
            StepperRow("Pluie consécutive", c.consecutiveRainHours, 0.5, 0.5, 12.0, Fmt::hours, { v -> update { it.copy(consecutiveRainHours = v) } })
            StepperRow("Pluie totale dans la plage", c.totalRainHours, 0.5, 0.5, 12.0, Fmt::hours, { v -> update { it.copy(totalRainHours = v) } })
            SwitchRow(
                "Comparaison stricte (« plus de »)",
                if (c.strictComparison) "Rouge si la durée dépasse le seuil (>)" else "Rouge dès que le seuil est atteint (≥)",
                c.strictComparison,
            ) { v -> update { it.copy(strictComparison = v) } }

            SectionTitle("Journée de pluie (orange)")
            StepperRow("Pluie au moins", c.warningRainHours, 0.5, 0.5, 12.0, Fmt::hours, { v -> update { it.copy(warningRainHours = v) } })

            SectionTitle("Détection de la pluie")
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
                "Seuil d'un pas pluvieux", c.wetStepThresholdMm, 0.1, 0.1, 5.0, Fmt::mm,
                { v -> update { it.copy(wetStepThresholdMm = v) } },
                subtitle = "Cumul minimal sur un pas pour le compter comme « pluie »",
            )

            SectionTitle("Température")
            StepperRow(
                "Seuil de température moyenne", c.frostThresholdC, 0.5, -10.0, 10.0, { Fmt.num(it) + " °C" },
                { v -> update { it.copy(frostThresholdC = v) } },
                subtitle = "Moyenne entre ${c.windowStart} et ${c.windowEnd}",
            )
            SwitchRow(
                "Température sous le seuil = intempérie",
                "Sinon, l'information est seulement indiquée (❄)",
                c.frostCountsAsIntemperie,
            ) { v -> update { it.copy(frostCountsAsIntemperie = v) } }
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
        TimeDialog(initial, onDismiss = { editTime = null }) { t ->
            viewModel.updateCriteria { if (isStart) it.copy(windowStart = t) else it.copy(windowEnd = t) }
            editTime = null
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Rétablir les valeurs par défaut ?") },
            text = { Text("07:30–16:00, pluie consécutive > 3 h ou totale > 4 h, orange ≥ 2 h, seuil 0 °C non bloquant.") },
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
private fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
