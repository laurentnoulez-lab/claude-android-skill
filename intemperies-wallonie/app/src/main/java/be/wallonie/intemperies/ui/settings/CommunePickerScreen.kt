package be.wallonie.intemperies.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CommunePickerRoute(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val items by viewModel.pickerItems.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { viewModel.onPickerClosed() } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Communes wallonnes") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Rechercher (nom ou code INS)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val grouped = items.groupBy { it.first.province }.toSortedMap()
            LazyColumn(Modifier.fillMaxSize()) {
                grouped.forEach { (province, list) ->
                    stickyHeader(key = "h_$province") {
                        Text(
                            "$province (${list.size})",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    items(list, key = { it.first.nis }) { (commune, selected) ->
                        ListItem(
                            modifier = Modifier.clickable { viewModel.toggle(commune.nis) },
                            headlineContent = { Text(commune.name) },
                            supportingContent = { Text("INS ${commune.nis}") },
                            trailingContent = { Checkbox(checked = selected, onCheckedChange = { viewModel.toggle(commune.nis) }) },
                        )
                    }
                }
            }
        }
    }
}
