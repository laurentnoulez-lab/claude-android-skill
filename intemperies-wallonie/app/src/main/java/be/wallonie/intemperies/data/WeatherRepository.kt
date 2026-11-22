package be.wallonie.intemperies.data

import be.wallonie.intemperies.data.db.SampleDao
import be.wallonie.intemperies.data.db.SampleEntity
import be.wallonie.intemperies.data.remote.KiwisClient
import be.wallonie.intemperies.domain.DayClassifier
import be.wallonie.intemperies.domain.StationMatcher
import be.wallonie.intemperies.model.AppSettings
import be.wallonie.intemperies.model.Commune
import be.wallonie.intemperies.model.MonthReport
import be.wallonie.intemperies.model.Sample
import be.wallonie.intemperies.model.Station
import be.wallonie.intemperies.model.StationAssignment
import be.wallonie.intemperies.model.StationKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class CommuneStations(
    val commune: Commune,
    val rain: StationAssignment?,
    val temperature: StationAssignment?,
)

sealed interface SyncOutcome {
    data class Success(val stations: Int, val samples: Int) : SyncOutcome
    data class Failure(val message: String) : SyncOutcome
}

class WeatherRepository(
    private val sampleDao: SampleDao,
    private val client: KiwisClient,
    private val catalog: CatalogRepository,
    private val settings: SettingsRepository,
    private val zone: ZoneId = BRUSSELS,
    private val clock: () -> Instant = Instant::now,
) {
    private val syncMutex = Mutex()
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    fun assignStations(commune: Commune, s: AppSettings, catalogNow: StationCatalog) = CommuneStations(
        commune = commune,
        rain = StationMatcher.assign(commune, catalogNow.rain, s.rainStationOverrides[commune.nis]),
        temperature = StationMatcher.assign(
            commune, catalogNow.temperature, s.temperatureStationOverrides[commune.nis],
            maxDistanceKm = s.criteria.maxTemperatureDistanceKm,
        ),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeMonth(nis: String, month: YearMonth): Flow<MonthReport?> =
        combine(settings.settings, catalog.stations) { s, c -> s to c }
            .flatMapLatest { (s, c) ->
                val commune = catalog.commune(nis) ?: return@flatMapLatest flowOf(null)
                val st = assignStations(commune, s, c)
                val from = month.atDay(1).atStartOfDay(zone).toEpochSecond()
                val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toEpochSecond()
                val rainFlow = st.rain?.let {
                    sampleDao.observe(StationKind.RAIN.name, it.station.stationNo, from, to)
                } ?: flowOf(emptyList())
                val tempFlow = st.temperature?.let {
                    sampleDao.observe(StationKind.TEMPERATURE.name, it.station.stationNo, from, to)
                } ?: flowOf(emptyList())
                val lastFlow = st.rain?.let {
                    sampleDao.observeLastEpoch(StationKind.RAIN.name, it.station.stationNo)
                } ?: flowOf(null)
                combine(rainFlow, tempFlow, lastFlow) { rain, temp, last ->
                    buildReport(st, month, s, rain, temp, last)
                }
            }
            .flowOn(Dispatchers.Default)

    suspend fun monthReport(nis: String, month: YearMonth): MonthReport? = observeMonth(nis, month).first()

    fun buildReport(
        st: CommuneStations,
        month: YearMonth,
        s: AppSettings,
        rain: List<SampleEntity>,
        temp: List<SampleEntity>,
        lastRain: Long?,
    ): MonthReport {
        val now = clock()
        val rainByDay = rain.groupBy { Instant.ofEpochSecond(it.epochSec).atZone(zone).toLocalDate() }
        val tempByDay = temp.groupBy { Instant.ofEpochSecond(it.epochSec).atZone(zone).toLocalDate() }
        val days = (1..month.lengthOfMonth()).map { d ->
            val date = month.atDay(d)
            DayClassifier.classify(
                date = date,
                rain = rainByDay[date].orEmpty().map(SampleEntity::toModel),
                temperature = tempByDay[date].orEmpty().map(SampleEntity::toModel),
                criteria = s.criteria,
                zone = zone,
                now = now,
                temperatureAvailable = st.temperature != null,
            )
        }
        return MonthReport(st.commune, month, st.rain, st.temperature, days, s.criteria, lastRain)
    }

    /** Premier mois conservé selon la durée de rétention (mois courant + N mois précédents). */
    fun retentionStartMonth(s: AppSettings): YearMonth = YearMonth.now(zone).minusMonths(s.retentionMonths.toLong())

    /**
     * Synchronisation incrémentale : ne télécharge que ce qui manque, recharge les 35 derniers jours
     * une fois par jour (prise en compte des validations SPW), purge ce qui sort de la rétention.
     */
    suspend fun sync(forceDeep: Boolean = false): SyncOutcome = syncMutex.withLock {
        _syncing.value = true
        try {
            val nowInstant = clock()
            val nowSec = nowInstant.epochSecond
            runCatching { catalog.refreshStationsIfStale(nowSec) }
            val s = settings.current()
            val c = catalog.stationsNow()
            val needed = s.selectedCommunes.mapNotNull { catalog.commune(it) }
                .map { assignStations(it, s, c) }
                .flatMap { listOfNotNull(it.rain?.station, it.temperature?.station) }
                .distinctBy { it.kind to it.stationNo }

            val retentionStart = retentionStartMonth(s).atDay(1).atStartOfDay(zone).toEpochSecond()
            val deep = forceDeep || (settings.lastDeepSync()?.let { nowSec - it > DAY_SEC } ?: true)
            var total = 0
            for (station in needed) {
                total += syncStation(station, retentionStart, nowSec, deep)
            }
            sampleDao.purgeBefore(retentionStart)
            sampleDao.purgeStationsNotIn(needed.map { "${it.kind.name}:${it.stationNo}" }.ifEmpty { listOf("-") })
            settings.markSyncSuccess(nowSec, deep)
            SyncOutcome.Success(needed.size, total)
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            settings.markSyncError(msg)
            SyncOutcome.Failure(msg)
        } finally {
            _syncing.value = false
        }
    }

    private suspend fun syncStation(station: Station, retentionStart: Long, nowSec: Long, deep: Boolean): Int {
        val kind = station.kind
        val history = settings.historyStart(kind, station.stationNo)
        val last = sampleDao.lastEpoch(kind.name, station.stationNo)
        val from = when {
            history == null || history > retentionStart || last == null -> retentionStart
            deep -> maxOf(retentionStart, nowSec - DEEP_DAYS * DAY_SEC)
            else -> maxOf(retentionStart, last - OVERLAP_SEC)
        }
        var count = 0
        var chunkStart = from
        while (chunkStart < nowSec) {
            val chunkEnd = minOf(nowSec + 3600, chunkStart + CHUNK_DAYS * DAY_SEC)
            val samples: List<Sample> = client.fetchValues(
                station.tsId,
                Instant.ofEpochSecond(chunkStart).atZone(zone).toOffsetDateTime(),
                Instant.ofEpochSecond(chunkEnd).atZone(zone).toOffsetDateTime(),
            )
            val entities = samples.filter { !it.value.isNaN() }.map {
                SampleEntity(kind.name, station.stationNo, it.epochSec, it.value, it.quality)
            }
            if (entities.isNotEmpty()) sampleDao.upsert(entities)
            count += entities.size
            chunkStart = chunkEnd
        }
        if (history == null || history > retentionStart) settings.setHistoryStart(kind, station.stationNo, retentionStart)
        return count
    }

    fun today(): LocalDate = LocalDate.now(zone)

    companion object {
        val BRUSSELS: ZoneId = ZoneId.of("Europe/Brussels")
        private const val DAY_SEC = 24L * 3600
        private const val DEEP_DAYS = 35L
        private const val CHUNK_DAYS = 31L
        private const val OVERLAP_SEC = 3L * 3600
    }
}
