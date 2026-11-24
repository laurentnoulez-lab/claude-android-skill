package be.wallonie.intemperies.data

import be.wallonie.intemperies.data.db.SampleDao
import be.wallonie.intemperies.data.db.SampleEntity
import be.wallonie.intemperies.data.remote.KiwisClient
import be.wallonie.intemperies.model.AppSettings
import be.wallonie.intemperies.model.Commune
import be.wallonie.intemperies.model.MonthReport
import be.wallonie.intemperies.model.Sample
import be.wallonie.intemperies.model.StationKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Stockage Room des mesures pour le moteur de synchronisation commun. */
class RoomSampleStore(private val dao: SampleDao) : SampleStore {
    override suspend fun upsert(kind: StationKind, stationNo: String, samples: List<Sample>) =
        dao.upsert(samples.map { SampleEntity(kind.name, stationNo, it.epochSec, it.value, it.quality) })

    override suspend fun lastEpoch(kind: StationKind, stationNo: String): Long? = dao.lastEpoch(kind.name, stationNo)

    override suspend fun purgeBefore(epochSec: Long) {
        dao.purgeBefore(epochSec)
    }

    override suspend fun purgeStationsNotIn(keep: Set<String>) {
        dao.purgeStationsNotIn(keep.toList().ifEmpty { listOf("-") })
    }
}

class WeatherRepository(
    private val sampleDao: SampleDao,
    client: KiwisClient,
    private val catalog: CatalogRepository,
    private val settings: SettingsRepository,
    private val zone: ZoneId = BRUSSELS,
    private val clock: () -> Instant = Instant::now,
) {
    private val engine = SyncEngine(client, RoomSampleStore(sampleDao), settings, catalog, zone, clock)
    private val syncMutex = Mutex()
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    fun assignStations(commune: Commune, s: AppSettings, catalogNow: StationCatalog) = Assignment.assign(commune, s, catalogNow)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeMonth(nis: String, month: YearMonth): Flow<MonthReport?> =
        combine(settings.settings, catalog.stations) { s, c -> s to c }
            .flatMapLatest { (s, c) ->
                val commune = catalog.commune(nis) ?: return@flatMapLatest flowOf(null)
                val st = assignStations(commune, s, c)
                val (from, to) = ReportBuilder.monthBounds(month, zone)
                val rainFlow = st.rain?.let {
                    sampleDao.observe(StationKind.RAIN.name, it.station.stationNo, from, to)
                } ?: flowOf(emptyList())
                val tempFlow = st.temperature?.let {
                    sampleDao.observe(StationKind.TEMPERATURE.name, it.station.stationNo, from, to)
                } ?: flowOf(emptyList())
                val lastFlow = st.rain?.let {
                    sampleDao.observeLastEpoch(StationKind.RAIN.name, it.station.stationNo)
                } ?: flowOf(null)
                val lastTempFlow = st.temperature?.let {
                    sampleDao.observeLastEpoch(StationKind.TEMPERATURE.name, it.station.stationNo)
                } ?: flowOf(null)
                val syncFlow = settings.syncStatus.map { it.lastSuccessEpochSec }.distinctUntilChanged()
                combine(rainFlow, tempFlow, lastFlow, lastTempFlow, syncFlow) { rain, temp, last, lastTemp, sync ->
                    ReportBuilder.build(
                        st, month, s, rain.map(SampleEntity::toModel), temp.map(SampleEntity::toModel),
                        last, lastTemp, sync, zone, clock(),
                    )
                }
            }
            .flowOn(Dispatchers.Default)

    suspend fun monthReport(nis: String, month: YearMonth): MonthReport? = observeMonth(nis, month).first()

    fun retentionStartMonth(s: AppSettings): YearMonth = ReportBuilder.retentionStartMonth(s, zone)

    suspend fun sync(forceDeep: Boolean = false): SyncOutcome = syncMutex.withLock {
        _syncing.value = true
        try {
            engine.sync(settings.current(), { catalog.stationsNow() }, forceDeep)
        } finally {
            _syncing.value = false
        }
    }

    fun today(): LocalDate = LocalDate.now(zone)

    companion object {
        val BRUSSELS: ZoneId = be.wallonie.intemperies.data.BRUSSELS
    }
}
