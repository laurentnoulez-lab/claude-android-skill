package be.wallonie.intemperies.ui.legal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.wallonie.intemperies.data.SettingsRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HowItWorksRoute(settings: SettingsRepository, onBack: () -> Unit, onOpenSettings: () -> Unit, onOpenRegulatory: () -> Unit) {
    val s by settings.settings.collectAsStateWithLifecycle(initialValue = null)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fonctionnement") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
    ) { padding ->
        val current = s ?: return@Scaffold
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Text(
                "Valeurs affichées = paramètres actuellement en vigueur",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            HowItWorks.sections(current.criteria, current.retentionMonths).forEach { section ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(section.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        section.lines.forEach { line ->
                            Text("• $line", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }
            TextButton(onClick = onOpenSettings) { Text("Modifier les paramètres") }
            TextButton(onClick = onOpenRegulatory) { Text("Base réglementaire (CCT Qualiroutes)") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
