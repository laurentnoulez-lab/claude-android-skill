package be.wallonie.intemperies.data

import android.content.Context
import be.wallonie.intemperies.data.db.StationDao
import be.wallonie.intemperies.data.db.StationEntity
import be.wallonie.intemperies.data.remote.KiwisClient
import be.wallonie.intemperies.model.Commune
import be.wallonie.intemperies.model.Station
import be.wallonie.intemperies.model.StationKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer

data class StationCatalog(val rain: List<Station>, val temperature: List<Station>)

/**
 * Référentiels : 262 communes wallonnes (centroïdes calculés sur les secteurs statistiques Statbel)
 * et stations SPW (liste embarquée, rafraîchie chaque semaine depuis KiWIS).
 */
class CatalogRepository(
    private val context: Context,
    private val stationDao: StationDao,
    private val client: KiwisClient,
    private val settings: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val seedMutex = Mutex()

    @Serializable
    private data class CommuneDto(val nis: String, val name: String, val province: String, val lat: Double, val lon: Double)

    @Serializable
    private data class StationDto(
        val stationNo: String, val name: String, val lat: Double, val lon: Double,
        val tsId: String, val kind: String, val network: String,
    )

    val communes: List<Commune> by lazy {
        val text = context.assets.open("communes.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        json.decodeFromString<List<CommuneDto>>(text)
            .map { Commune(it.nis, it.name, it.province, it.lat, it.lon) }
            .sortedWith(compareBy { normalize(it.name) })
    }

    private val communeByNis by lazy { communes.associateBy { it.nis } }

    fun commune(nis: String): Commune? = communeByNis[nis]

    val stations: Flow<StationCatalog> = stationDao.observeAll()
        .onStart { ensureSeeded() }
        .map { list ->
            val models = list.map { it.toModel() }
            StationCatalog(
                rain = models.filter { it.kind == StationKind.RAIN },
                temperature = models.filter { it.kind == StationKind.TEMPERATURE },
            )
        }

    suspend fun stationsNow(): StationCatalog {
        ensureSeeded()
        val all = stationDao.byKind(StationKind.RAIN.name).map { it.toModel() } to
            stationDao.byKind(StationKind.TEMPERATURE.name).map { it.toModel() }
        return StationCatalog(all.first, all.second)
    }

    private suspend fun ensureSeeded() = seedMutex.withLock {
        if (stationDao.count() > 0) return@withLock
        val text = withContext(Dispatchers.IO) {
            context.assets.open("stations.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        val seed = json.decodeFromString<List<StationDto>>(text).map {
            StationEntity(it.kind, it.stationNo, it.name, it.lat, it.lon, it.tsId, it.network)
        }
        stationDao.upsert(seed)
    }

    /** Rafraîchit la liste des stations actives (au plus une fois par semaine). */
    suspend fun refreshStationsIfStale(nowSec: Long, force: Boolean = false) {
        val last = settings.lastStationRefresh()
        if (!force && last != null && nowSec - last < WEEK_SEC) return
        for (kind in StationKind.entries) {
            val fetched = client.fetchStations(kind)
            // Garde-fou : on ne remplace la liste que si la réponse est plausible.
            if (fetched.size >= MIN_PLAUSIBLE[kind]!!) {
                stationDao.replaceKind(kind.name, fetched.map(StationEntity::from))
            }
        }
        settings.markStationRefresh(nowSec)
    }

    companion object {
        private const val WEEK_SEC = 7L * 24 * 3600
        private val MIN_PLAUSIBLE = mapOf(StationKind.RAIN to 30, StationKind.TEMPERATURE to 10)

        fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace("\\p{M}+".toRegex(), "").lowercase()
    }
}
