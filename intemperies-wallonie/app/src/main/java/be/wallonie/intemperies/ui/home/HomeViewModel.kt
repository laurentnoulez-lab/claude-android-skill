package be.wallonie.intemperies.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import be.wallonie.intemperies.data.SettingsRepository
import be.wallonie.intemperies.data.SyncOutcome
import be.wallonie.intemperies.data.SyncStatus
import be.wallonie.intemperies.data.WeatherRepository
import be.wallonie.intemperies.model.MonthReport
import be.wallonie.intemperies.export.CalendarExporter
import be.wallonie.intemperies.export.ExportFormat
import be.wallonie.intemperies.export.ExportedFile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data object Empty : HomeUiState
    data class Content(val reports: List<MonthReport>) : HomeUiState
}

sealed interface HomeEvent {
    data class Share(val export: ExportedFile) : HomeEvent
    data class Message(val text: String) : HomeEvent
}

class HomeViewModel(
    private val weather: WeatherRepository,
    settings: SettingsRepository,
    private val exporter: CalendarExporter,
) : ViewModel() {
    private val month = YearMonth.now(WeatherRepository.BRUSSELS)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<HomeUiState> = settings.settings
        .map { it.selectedCommunes }
        .flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(HomeUiState.Empty)
            } else {
                combine(list.map { weather.observeMonth(it, month) }) { arr ->
                    HomeUiState.Content(arr.filterNotNull())
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)

    val syncing: StateFlow<Boolean> = weather.syncing
    val syncStatus: StateFlow<SyncStatus?> = settings.syncStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<HomeEvent> = _events

    fun refresh() = viewModelScope.launch {
        when (val r = weather.sync()) {
            is SyncOutcome.Failure -> _events.emit(HomeEvent.Message("Synchronisation impossible : ${r.message}"))
            is SyncOutcome.Success -> Unit
        }
    }

    fun exportAll(format: ExportFormat) = viewModelScope.launch {
        val reports = (uiState.value as? HomeUiState.Content)?.reports.orEmpty()
        if (reports.isEmpty()) return@launch
        runCatching { exporter.export(reports, format) }
            .onSuccess { _events.emit(HomeEvent.Share(it)) }
            .onFailure { _events.emit(HomeEvent.Message("Erreur d'export : ${it.message}")) }
    }
}
