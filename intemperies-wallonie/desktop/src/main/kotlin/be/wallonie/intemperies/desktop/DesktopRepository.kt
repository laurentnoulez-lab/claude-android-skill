package be.wallonie.intemperies.desktop

import be.wallonie.intemperies.data.Assignment
import be.wallonie.intemperies.data.BRUSSELS
import be.wallonie.intemperies.data.Catalogs
import be.wallonie.intemperies.data.ReportBuilder
import be.wallonie.intemperies.data.StationCatalog
import be.wallonie.intemperies.data.SyncEngine
import be.wallonie.intemperies.data.SyncOutcome
import be.wallonie.intemperies.data.SyncStatus
import be.wallonie.intemperies.data.remote.KiwisClient
import be.wallonie.intemperies.model.AppSettings
import be.wallonie.intemperies.model.Criteria
import be.wallonie.intemperies.model.MonthReport
import be.wallonie.intemperies.model.StationKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.YearMonth

/** Équivalent Windows des dépôts Android : même moteur de synchronisation et mêmes règles (module core). */
class DesktopRepository(
    private val samples: FileSampleStore = FileSampleStore(),
    private val settingsFile: SettingsFile = SettingsFile(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _settings = MutableStateFlow(settingsFile.load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _stations: MutableStateFlow<StationCatalog>
    private val stationStore = FileStationStore(onChange = { _stations.value = it })
    init { _stations = MutableStateFlow(stationStore.catalog) }
    val stations: StateFlow<StationCatalog> get() = _stations

    private val stateStore = FileSyncStateStore(onChange = { publishState(it) })
    private val _syncStatus = MutableStateFlow(SyncStatus(stateStore.current.lastSuccess, stateStore.current.lastError))
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()
    private val dataVersion = MutableStateFlow(0)

    private val engine = SyncEngine(KiwisClient(), samples, stateStore, stationStore)
    private val syncMutex = Mutex()

    private fun publishState(s: SyncStateDto) {
        _syncStatus.value = SyncStatus(s.lastSuccess, s.lastError)
    }

    // ---- Paramètres ----
    private fun edit(syncAfter: Boolean = false, t: (AppSettings) -> AppSettings) {
        val n = t(_settings.value)
        _settings.value = n
        runCatching { settingsFile.save(n) }
        if (syncAfter) syncInBackground()
    }

    fun updateCriteria(t: (Criteria) -> Criteria) = edit { it.copy(criteria = t(it.criteria)) }
    fun resetCriteria() = edit { it.copy(criteria = Criteria()) }
    fun setRetention(months: Int) = edit(syncAfter = true) { it.copy(retentionMonths = months.coerceIn(1, 24)) }
    fun toggleCommune(nis: String) = edit(syncAfter = true) {
        val l = it.selectedCommunes.toMutableList()
        if (!l.remove(nis)) l += nis
        it.copy(selectedCommunes = l)
    }
    fun setStationOverride(kind: StationKind, nis: String, stationNo: String?) = edit(syncAfter = true) {
        fun Map<String, String>.with() = if (stationNo == null) this - nis else this + (nis to stationNo)
        if (kind == StationKind.RAIN) it.copy(rainStationOverrides = it.rainStationOverrides.with())
        else it.copy(temperatureStationOverrides = it.temperatureStationOverrides.with())
    }

    // ---- Rapports ----
    fun observeMonth(nis: String, month: YearMonth): Flow<MonthReport?> =
        combine(settings, stations, dataVersion, syncStatus) { s, c, _, st -> Triple(s, c, st) }
            .mapLatest { (s, c, st) -> monthReport(nis, month, s, c, st.lastSuccessEpochSec) }
            .flowOn(Dispatchers.Default)

    suspend fun monthReport(
        nis: String, month: YearMonth,
        s: AppSettings = settings.value, c: StationCatalog = stations.value, lastSync: Long? = syncStatus.value.lastSuccessEpochSec,
    ): MonthReport? {
        val commune = Catalogs.commune(nis) ?: return null
        val st = Assignment.assign(commune, s, c)
        val (from, to) = ReportBuilder.monthBounds(month)
        val rain = st.rain?.let { samples.range(StationKind.RAIN, it.station.stationNo, from, to) }.orEmpty()
        val temp = st.temperature?.let { samples.range(StationKind.TEMPERATURE, it.station.stationNo, from, to) }.orEmpty()
        val lastRain = st.rain?.let { samples.lastEpoch(StationKind.RAIN, it.station.stationNo) }
        val lastTemp = st.temperature?.let { samples.lastEpoch(StationKind.TEMPERATURE, it.station.stationNo) }
        return ReportBuilder.build(st, month, s, rain, temp, lastRain, lastTemp, lastSync, BRUSSELS, Instant.now())
    }

    fun retentionStartMonth(): YearMonth = ReportBuilder.retentionStartMonth(settings.value)

    // ---- Synchronisation ----
    suspend fun sync(forceDeep: Boolean = false): SyncOutcome = syncMutex.withLock {
        _syncing.value = true
        try {
            engine.sync(settings.value, { stations.value }, forceDeep)
        } finally {
            _syncing.value = false
            dataVersion.value++
        }
    }

    fun syncInBackground(forceDeep: Boolean = false) {
        scope.launch { sync(forceDeep) }
    }

    /** Synchronisation au démarrage puis toutes les heures tant que l'application est ouverte. */
    fun startPeriodicSync() {
        scope.launch {
            while (true) {
                sync()
                delay(60 * 60 * 1000L)
            }
        }
    }
}
