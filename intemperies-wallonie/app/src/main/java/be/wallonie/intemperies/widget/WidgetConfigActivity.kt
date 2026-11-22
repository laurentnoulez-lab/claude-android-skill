package be.wallonie.intemperies.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import be.wallonie.intemperies.IntemperiesApp
import be.wallonie.intemperies.data.CatalogRepository
import be.wallonie.intemperies.ui.theme.IntemperiesTheme
import be.wallonie.intemperies.work.SyncWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Choix de la commune affichée par un widget (une commune par widget). */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val container = (application as IntemperiesApp).container
        val catalog = container.catalogRepository
        setContent {
            IntemperiesTheme {
                val scope = rememberCoroutineScope()
                var query by remember { mutableStateOf("") }
                var selected by remember { mutableStateOf<List<String>>(emptyList()) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    selected = container.settingsRepository.settings.first().selectedCommunes
                }
                Scaffold { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        Text("Commune du widget", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            placeholder = { Text("Rechercher") },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                        val nq = CatalogRepository.normalize(query)
                        val list = catalog.communes
                            .filter { nq.isBlank() || CatalogRepository.normalize(it.name).contains(nq) }
                            .sortedByDescending { it.nis in selected }
                        LazyColumn {
                            items(list, key = { it.nis }) { c ->
                                ListItem(
                                    headlineContent = { Text(c.name) },
                                    supportingContent = { Text(if (c.nis in selected) "Suivie · ${c.province}" else c.province) },
                                    modifier = Modifier.clickable {
                                        scope.launch {
                                            if (c.nis !in selected) container.settingsRepository.toggleCommune(c.nis)
                                            val glanceId = IntemperieWidget.glanceIdOf(this@WidgetConfigActivity, appWidgetId)
                                            updateAppWidgetState(this@WidgetConfigActivity, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                                                prefs.toMutablePreferences().apply { this[IntemperieWidget.NIS_KEY] = c.nis }
                                            }
                                            IntemperieWidget().update(this@WidgetConfigActivity, glanceId)
                                            SyncWorker.syncNow(applicationContext)
                                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                                            finish()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
