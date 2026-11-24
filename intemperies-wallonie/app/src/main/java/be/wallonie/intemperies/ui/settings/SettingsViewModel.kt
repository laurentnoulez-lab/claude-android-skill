package be.wallonie.intemperies.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import be.wallonie.intemperies.data.CatalogRepository
import be.wallonie.intemperies.data.SettingsRepository
import be.wallonie.intemperies.model.AppSettings
import be.wallonie.intemperies.model.Commune
import be.wallonie.intemperies.model.Criteria
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val catalog: CatalogRepository,
    /** Déclenche une synchronisation (changement de communes, de rétention…). */
    private val requestSync: (deep: Boolean) -> Unit,
) : ViewModel() {
    val state: StateFlow<AppSettings?> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun communes(nisList: List<String>): List<Commune> = nisList.mapNotNull { catalog.commune(it) }

    fun updateCriteria(transform: (Criteria) -> Criteria) = viewModelScope.launch { settings.updateCriteria(transform) }

    fun resetCriteria() = viewModelScope.launch { settings.resetCriteria() }

    fun setRetention(months: Int) = viewModelScope.launch {
        settings.setRetentionMonths(months)
        requestSync(false)
    }

    fun removeCommune(nis: String) = viewModelScope.launch {
        settings.toggleCommune(nis)
        requestSync(false)
    }

    fun resync() = requestSync(true)

    // --- Sélection des communes ---------------------------------------------------------------
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val pickerItems: StateFlow<List<Pair<Commune, Boolean>>> = combine(_query, settings.settings) { q, s ->
        val nq = CatalogRepository.normalize(q.trim())
        val selected = s.selectedCommunes.toSet()
        catalog.communes
            .filter { nq.isEmpty() || CatalogRepository.normalize(it.name).contains(nq) || it.nis.startsWith(nq) }
            .map { it to (it.nis in selected) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(q: String) {
        _query.value = q
    }

    fun toggle(nis: String) = viewModelScope.launch { settings.toggleCommune(nis) }

    fun onPickerClosed() = requestSync(false)
}
