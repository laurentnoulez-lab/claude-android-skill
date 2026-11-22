package be.wallonie.intemperies.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import be.wallonie.intemperies.data.CatalogRepository
import be.wallonie.intemperies.data.SettingsRepository
import be.wallonie.intemperies.data.WeatherRepository
import be.wallonie.intemperies.domain.StationMatcher
import be.wallonie.intemperies.model.MonthReport
import be.wallonie.intemperies.model.StationAssignment
import be.wallonie.intemperies.model.StationKind
import be.wallonie.intemperies.export.CalendarExporter
import be.wallonie.intemperies.export.ExportFormat
import be.wallonie.intemperies.export.ExportedFile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth

data class StationChoices(val rain: List<StationAssignment>, val temperature: List<StationAssignment>)

class CalendarViewModel(
    private val nis: String,
    private val weather: WeatherRepository,
    private val settings: SettingsRepository,
    catalog: CatalogRepository,
    private val exporter: CalendarExporter,
    private val onStationsChanged: () -> Unit,
) : ViewModel() {
    private val current = YearMonth.now(WeatherRepository.BRUSSELS)
    private val _month = MutableStateFlow(current)
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    val commune = catalog.commune(nis)

    @OptIn(ExperimentalCoroutinesApi::class)
    val report: StateFlow<MonthReport?> = _month
        .flatMapLatest { weather.observeMonth(nis, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val minMonth: StateFlow<YearMonth> = settings.settings
        .map { weather.retentionStartMonth(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), current.minusMonths(6))

    val maxMonth: YearMonth = current

    val stationChoices: StateFlow<StationChoices?> = combine(catalog.stations, settings.settings) { c, _ ->
        val com = commune ?: return@combine null
        StationChoices(
            rain = StationMatcher.rankByDistance(com, c.rain).take(10),
            temperature = StationMatcher.rankByDistance(com, c.temperature).take(10),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _share = MutableSharedFlow<ExportedFile>(extraBufferCapacity = 1)
    val share: SharedFlow<ExportedFile> = _share
    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    fun previousMonth() {
        if (_month.value > minMonth.value) _month.value = _month.value.minusMonths(1)
    }

    fun nextMonth() {
        if (_month.value < maxMonth) _month.value = _month.value.plusMonths(1)
    }

    fun setStation(kind: StationKind, stationNo: String?) = viewModelScope.launch {
        settings.setStationOverride(kind, nis, stationNo)
        onStationsChanged()
    }

    fun export(format: ExportFormat) = viewModelScope.launch {
        val r = report.value ?: return@launch
        _exporting.value = true
        try {
            _share.emit(exporter.export(listOf(r), format))
        } finally {
            _exporting.value = false
        }
    }
}
