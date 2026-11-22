package be.wallonie.intemperies.data

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Fuseau de référence des calculs (heure belge). */
val BRUSSELS: ZoneId = ZoneId.of("Europe/Brussels")

data class StationCatalog(val rain: List<Station>, val temperature: List<Station>)

data class CommuneStations(
    val commune: Commune,
    val rain: StationAssignment?,
    val temperature: StationAssignment?,
)

sealed interface SyncOutcome {
    data class Success(val stations: Int, val samples: Int) : SyncOutcome
    data class Failure(val message: String) : SyncOutcome
}

data class SyncStatus(
    val lastSuccessEpochSec: Long?,
    val lastError: String?,
    val running: Boolean = false,
)

/**
 * Référentiels embarqués : 262 communes wallonnes (centroïdes calculés sur les secteurs statistiques Statbel)
 * et stations SPW actives (liste de départ, rafraîchie ensuite chaque semaine depuis KiWIS).
 */
object Catalogs {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class CommuneDto(val nis: String, val name: String, val province: String, val lat: Double, val lon: Double)

    @Serializable
    data class StationDto(
        val stationNo: String, val name: String, val lat: Double, val lon: Double,
        val tsId: String, val kind: String, val network: String,
    ) {
        fun toModel() = Station(stationNo, name, lat, lon, tsId, StationKind.valueOf(kind), network)

        companion object {
            fun from(s: Station) = StationDto(s.stationNo, s.name, s.lat, s.lon, s.tsId, s.kind.name, s.network)
        }
    }

    private fun resource(name: String): String =
        Catalogs::class.java.getResourceAsStream("/be/wallonie/intemperies/$name")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("Ressource manquante : $name")

    val communes: List<Commune> by lazy {
        json.decodeFromString<List<CommuneDto>>(resource("communes.json"))
            .map { Commune(it.nis, it.name, it.province, it.lat, it.lon) }
            .sortedWith(compareBy { normalize(it.name) })
    }

    private val byNis by lazy { communes.associateBy { it.nis } }

    fun commune(nis: String): Commune? = byNis[nis]

    fun seedStations(): List<Station> = json.decodeFromString<List<StationDto>>(resource("stations.json")).map { it.toModel() }

    fun encodeStations(list: List<Station>): String = Json.encodeToString(list.map(StationDto::from))
    fun decodeStations(text: String): List<Station> = json.decodeFromString<List<StationDto>>(text).map { it.toModel() }

    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace("\\p{M}+".toRegex(), "").lowercase()
}

/** Attribution des stations à une commune (station la plus proche ou choix manuel). */
object Assignment {
    fun assign(commune: Commune, s: AppSettings, catalog: StationCatalog) = CommuneStations(
        commune = commune,
        rain = StationMatcher.assign(commune, catalog.rain, s.rainStationOverrides[commune.nis]),
        temperature = StationMatcher.assign(
            commune, catalog.temperature, s.temperatureStationOverrides[commune.nis],
            maxDistanceKm = s.criteria.maxTemperatureDistanceKm,
        ),
    )

    /** Stations à synchroniser pour les communes suivies (sans doublon). */
    fun neededStations(s: AppSettings, catalog: StationCatalog): List<Station> =
        s.selectedCommunes.mapNotNull { Catalogs.commune(it) }
            .map { assign(it, s, catalog) }
            .flatMap { listOfNotNull(it.rain?.station, it.temperature?.station) }
            .distinctBy { it.kind to it.stationNo }
}

/** Construction d'un rapport mensuel à partir des mesures (logique commune Android / Windows). */
object ReportBuilder {
    fun build(
        st: CommuneStations,
        month: YearMonth,
        s: AppSettings,
        rain: List<Sample>,
        temp: List<Sample>,
        lastRain: Long?,
        lastTemp: Long?,
        lastSync: Long?,
        zone: ZoneId = BRUSSELS,
        now: Instant = Instant.now(),
    ): MonthReport {
        val rainByDay = rain.groupBy { Instant.ofEpochSecond(it.epochSec).atZone(zone).toLocalDate() }
        val tempByDay = temp.groupBy { Instant.ofEpochSecond(it.epochSec).atZone(zone).toLocalDate() }
        val days = (1..month.lengthOfMonth()).map { d ->
            val date = month.atDay(d)
            DayClassifier.classify(
                date = date,
                rain = rainByDay[date].orEmpty(),
                temperature = tempByDay[date].orEmpty(),
                criteria = s.criteria,
                zone = zone,
                now = now,
                temperatureAvailable = st.temperature != null,
            )
        }
        return MonthReport(st.commune, month, st.rain, st.temperature, days, s.criteria, lastRain, lastTemp, lastSync)
    }

    fun monthBounds(month: YearMonth, zone: ZoneId = BRUSSELS): Pair<Long, Long> =
        month.atDay(1).atStartOfDay(zone).toEpochSecond() to month.plusMonths(1).atDay(1).atStartOfDay(zone).toEpochSecond()

    /** Premier mois conservé (mois en cours + N mois précédents). */
    fun retentionStartMonth(s: AppSettings, zone: ZoneId = BRUSSELS): YearMonth = YearMonth.now(zone).minusMonths(s.retentionMonths.toLong())
}

// ---- Stockage : chaque plateforme fournit son implémentation (Room / DataStore sur Android, fichiers sous Windows) ----

interface SampleStore {
    suspend fun upsert(kind: StationKind, stationNo: String, samples: List<Sample>)
    suspend fun lastEpoch(kind: StationKind, stationNo: String): Long?
    suspend fun purgeBefore(epochSec: Long)
    /** Supprime les mesures des stations absentes de [keep] (clés « KIND:n° »). */
    suspend fun purgeStationsNotIn(keep: Set<String>)
}

interface SyncStateStore {
    suspend fun lastDeepSync(): Long?
    suspend fun lastStationRefresh(): Long?
    suspend fun markStationRefresh(epochSec: Long)
    suspend fun historyStart(kind: StationKind, stationNo: String): Long?
    suspend fun setHistoryStart(kind: StationKind, stationNo: String, epochSec: Long)
    suspend fun markSyncSuccess(epochSec: Long, deep: Boolean)
    suspend fun markSyncError(message: String)
}

interface StationStore {
    suspend fun replace(kind: StationKind, stations: List<Station>)
}

/**
 * Synchronisation incrémentale commune aux deux applications : ne télécharge que ce qui manque, recharge
 * les 35 derniers jours une fois par jour (validations SPW), purge ce qui sort de la rétention.
 */
class SyncEngine(
    private val client: KiwisClient,
    private val samples: SampleStore,
    private val state: SyncStateStore,
    private val stations: StationStore,
    private val zone: ZoneId = BRUSSELS,
    private val clock: () -> Instant = Instant::now,
) {
    /** Rafraîchit la liste des stations actives (au plus une fois par semaine). */
    suspend fun refreshStationsIfStale(force: Boolean = false) {
        val nowSec = clock().epochSecond
        val last = state.lastStationRefresh()
        if (!force && last != null && nowSec - last < WEEK_SEC) return
        for (kind in StationKind.entries) {
            val fetched = client.fetchStations(kind)
            // Garde-fou : on ne remplace la liste que si la réponse est plausible.
            if (fetched.size >= MIN_PLAUSIBLE.getValue(kind)) stations.replace(kind, fetched)
        }
        state.markStationRefresh(nowSec)
    }

    suspend fun sync(settings: AppSettings, catalog: suspend () -> StationCatalog, forceDeep: Boolean = false): SyncOutcome = try {
        val nowSec = clock().epochSecond
        runCatching { refreshStationsIfStale() }
        val needed = Assignment.neededStations(settings, catalog())
        val retentionStart = ReportBuilder.retentionStartMonth(settings, zone).atDay(1).atStartOfDay(zone).toEpochSecond()
        val deep = forceDeep || (state.lastDeepSync()?.let { nowSec - it > DAY_SEC } ?: true)
        var total = 0
        for (station in needed) total += syncStation(station, retentionStart, nowSec, deep)
        samples.purgeBefore(retentionStart)
        samples.purgeStationsNotIn(needed.map { "${it.kind.name}:${it.stationNo}" }.toSet())
        state.markSyncSuccess(nowSec, deep)
        SyncOutcome.Success(needed.size, total)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        val msg = e.message ?: e.javaClass.simpleName
        state.markSyncError(msg)
        SyncOutcome.Failure(msg)
    }

    private suspend fun syncStation(station: Station, retentionStart: Long, nowSec: Long, deep: Boolean): Int {
        val kind = station.kind
        val history = state.historyStart(kind, station.stationNo)
        val last = samples.lastEpoch(kind, station.stationNo)
        val from = when {
            history == null || history > retentionStart || last == null -> retentionStart
            deep -> maxOf(retentionStart, nowSec - DEEP_DAYS * DAY_SEC)
            else -> maxOf(retentionStart, last - OVERLAP_SEC)
        }
        var count = 0
        var chunkStart = from
        while (chunkStart < nowSec) {
            val chunkEnd = minOf(nowSec + 3600, chunkStart + CHUNK_DAYS * DAY_SEC)
            val fetched = client.fetchValues(
                station.tsId,
                Instant.ofEpochSecond(chunkStart).atZone(zone).toOffsetDateTime(),
                Instant.ofEpochSecond(chunkEnd).atZone(zone).toOffsetDateTime(),
            ).filter { !it.value.isNaN() }
            if (fetched.isNotEmpty()) samples.upsert(kind, station.stationNo, fetched)
            count += fetched.size
            chunkStart = chunkEnd
        }
        if (history == null || history > retentionStart) state.setHistoryStart(kind, station.stationNo, retentionStart)
        return count
    }

    companion object {
        private const val DAY_SEC = 24L * 3600
        private const val WEEK_SEC = 7 * DAY_SEC
        private const val DEEP_DAYS = 35L
        private const val CHUNK_DAYS = 31L
        private const val OVERLAP_SEC = 3L * 3600
        private val MIN_PLAUSIBLE = mapOf(StationKind.RAIN to 30, StationKind.TEMPERATURE to 10)
    }
}
