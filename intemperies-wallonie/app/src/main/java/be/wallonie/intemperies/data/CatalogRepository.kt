package be.wallonie.intemperies.data

import be.wallonie.intemperies.data.db.StationDao
import be.wallonie.intemperies.data.db.StationEntity
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

/** Référentiels (module commun) + cache Room des stations, rafraîchi par le moteur de synchronisation. */
class CatalogRepository(private val stationDao: StationDao) : StationStore {
    private val seedMutex = Mutex()

    val communes: List<Commune> get() = Catalogs.communes

    fun commune(nis: String): Commune? = Catalogs.commune(nis)

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
        return StationCatalog(
            rain = stationDao.byKind(StationKind.RAIN.name).map { it.toModel() },
            temperature = stationDao.byKind(StationKind.TEMPERATURE.name).map { it.toModel() },
        )
    }

    override suspend fun replace(kind: StationKind, stations: List<Station>) =
        stationDao.replaceKind(kind.name, stations.map(StationEntity::from))

    private suspend fun ensureSeeded() = seedMutex.withLock {
        if (stationDao.count() > 0) return@withLock
        val seed = withContext(Dispatchers.IO) { Catalogs.seedStations() }
        stationDao.upsert(seed.map(StationEntity::from))
    }

    companion object {
        fun normalize(s: String): String = Catalogs.normalize(s)
    }
}
