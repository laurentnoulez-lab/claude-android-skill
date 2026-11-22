package be.wallonie.intemperies.desktop

import be.wallonie.intemperies.data.Catalogs
import be.wallonie.intemperies.data.SampleStore
import be.wallonie.intemperies.data.StationCatalog
import be.wallonie.intemperies.data.StationStore
import be.wallonie.intemperies.data.SyncStateStore
import be.wallonie.intemperies.model.AppSettings
import be.wallonie.intemperies.model.Criteria
import be.wallonie.intemperies.model.Sample
import be.wallonie.intemperies.model.Station
import be.wallonie.intemperies.model.StationKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalTime
import java.util.TreeMap

/**
 * Dossier de données persistant (« mémoire » de l'application) :
 *  - Windows : %APPDATA%\IntemperiesWallonie
 *  - autres systèmes : ~/.intemperies-wallonie
 * Surchargeable par la propriété système « intemperies.home » (tests).
 */
object AppPaths {
    val root: File by lazy {
        val override = System.getProperty("intemperies.home")
        val dir = when {
            override != null -> File(override)
            System.getenv("APPDATA") != null -> File(System.getenv("APPDATA"), "IntemperiesWallonie")
            else -> File(System.getProperty("user.home"), ".intemperies-wallonie")
        }
        dir.apply { mkdirs() }
    }
    val samples: File get() = File(root, "samples").apply { mkdirs() }
    val settings: File get() = File(root, "settings.json")
    val syncState: File get() = File(root, "sync-state.json")
    val stations: File get() = File(root, "stations.json")
}

internal val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

/** Écriture atomique : fichier temporaire puis renommage (pas de fichier corrompu en cas de coupure). */
internal fun File.writeAtomically(bytes: ByteArray) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeBytes(bytes)
    Files.move(tmp.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

// ------------------------------------------------------------------------------------------------
// Mesures : un fichier binaire par station (≈ 1 Mo pour 6 mois au pas de 5 min), chargé en mémoire à la demande.
// ------------------------------------------------------------------------------------------------

class FileSampleStore(private val dir: File = AppPaths.samples) : SampleStore {
    private val mutex = Mutex()
    private val cache = HashMap<String, TreeMap<Long, Sample>>()

    private fun key(kind: StationKind, stationNo: String) = "${kind.name}:$stationNo"
    private fun file(key: String) = File(dir, key.replace(':', '_').replace(Regex("[^A-Za-z0-9_-]"), "-") + ".bin")

    private fun load(key: String): TreeMap<Long, Sample> = cache.getOrPut(key) {
        val map = TreeMap<Long, Sample>()
        val f = file(key)
        if (f.exists()) {
            runCatching {
                DataInputStream(f.inputStream().buffered()).use { inp ->
                    check(inp.readInt() == MAGIC) { "format inconnu" }
                    repeat(inp.readInt()) {
                        val s = Sample(inp.readLong(), inp.readDouble(), inp.readInt())
                        map[s.epochSec] = s
                    }
                }
            }.onFailure { map.clear() } // fichier illisible : il sera retéléchargé
        }
        map
    }

    private fun save(key: String, map: TreeMap<Long, Sample>) {
        val bytes = java.io.ByteArrayOutputStream(map.size * 20 + 8).also { bos ->
            DataOutputStream(bos).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(map.size)
                map.values.forEach { out.writeLong(it.epochSec); out.writeDouble(it.value); out.writeInt(it.quality) }
            }
        }.toByteArray()
        file(key).writeAtomically(bytes)
    }

    override suspend fun upsert(kind: StationKind, stationNo: String, samples: List<Sample>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val k = key(kind, stationNo)
            val map = load(k)
            samples.forEach { map[it.epochSec] = it }
            save(k, map)
        }
    }

    override suspend fun lastEpoch(kind: StationKind, stationNo: String): Long? = withContext(Dispatchers.IO) {
        mutex.withLock { load(key(kind, stationNo)).lastEntry()?.key }
    }

    suspend fun range(kind: StationKind, stationNo: String, fromSec: Long, toSec: Long): List<Sample> = withContext(Dispatchers.IO) {
        mutex.withLock { load(key(kind, stationNo)).subMap(fromSec, toSec).values.toList() }
    }

    override suspend fun purgeBefore(epochSec: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            existingKeys().forEach { k ->
                val map = load(k)
                if (map.isNotEmpty() && map.firstKey() < epochSec) {
                    map.headMap(epochSec).clear()
                    save(k, map)
                }
            }
        }
    }

    override suspend fun purgeStationsNotIn(keep: Set<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            existingKeys().filter { it !in keep }.forEach { k ->
                cache.remove(k)
                file(k).delete()
            }
        }
    }

    private fun existingKeys(): List<String> =
        (dir.listFiles { f -> f.name.endsWith(".bin") }?.map { it.name.removeSuffix(".bin").replaceFirst('_', ':') } ?: emptyList()) +
            cache.keys

    companion object {
        private const val MAGIC = 0x494E5431 // « INT1 »
    }
}

// ------------------------------------------------------------------------------------------------
// Paramètres (JSON lisible)
// ------------------------------------------------------------------------------------------------

@Serializable
data class SettingsDto(
    val selectedCommunes: List<String> = emptyList(),
    val retentionMonths: Int = 6,
    val rainStationOverrides: Map<String, String> = emptyMap(),
    val temperatureStationOverrides: Map<String, String> = emptyMap(),
    val windowStart: String = "07:30",
    val windowEnd: String = "16:00",
    val stepMinutes: Int = 30,
    val wetIntensityMmH: Double = 1.0,
    val consecutiveRainHours: Double = 3.0,
    val totalRainHours: Double = 4.0,
    val strictComparison: Boolean = true,
    val warningRainHours: Double = 2.0,
    val coldThresholdC: Double = 0.0,
    val coldMinHours: Double = 3.0,
    val coldCountsAsIntemperie: Boolean = false,
    val heatThresholdC: Double = 32.0,
    val heatMinHours: Double = 3.0,
    val heatCountsAsIntemperie: Boolean = false,
    val minCoverage: Double = 0.8,
    val maxTemperatureDistanceKm: Double = 40.0,
) {
    fun toModel(): AppSettings {
        val d = Criteria()
        return AppSettings(
            selectedCommunes = selectedCommunes.filter { Catalogs.commune(it) != null },
            retentionMonths = retentionMonths.coerceIn(1, 24),
            rainStationOverrides = rainStationOverrides,
            temperatureStationOverrides = temperatureStationOverrides,
            criteria = Criteria(
                windowStart = runCatching { LocalTime.parse(windowStart) }.getOrDefault(d.windowStart),
                windowEnd = runCatching { LocalTime.parse(windowEnd) }.getOrDefault(d.windowEnd),
                stepMinutes = stepMinutes,
                wetIntensityMmH = wetIntensityMmH,
                consecutiveRainHours = consecutiveRainHours,
                totalRainHours = totalRainHours,
                strictComparison = strictComparison,
                warningRainHours = warningRainHours,
                coldThresholdC = coldThresholdC,
                coldMinHours = coldMinHours,
                coldCountsAsIntemperie = coldCountsAsIntemperie,
                heatThresholdC = heatThresholdC,
                heatMinHours = heatMinHours,
                heatCountsAsIntemperie = heatCountsAsIntemperie,
                minCoverage = minCoverage,
                maxTemperatureDistanceKm = maxTemperatureDistanceKm,
            ),
        )
    }

    companion object {
        fun from(s: AppSettings) = with(s.criteria) {
            SettingsDto(
                s.selectedCommunes, s.retentionMonths, s.rainStationOverrides, s.temperatureStationOverrides,
                windowStart.toString(), windowEnd.toString(), stepMinutes, wetIntensityMmH, consecutiveRainHours,
                totalRainHours, strictComparison, warningRainHours, coldThresholdC, coldMinHours, coldCountsAsIntemperie,
                heatThresholdC, heatMinHours, heatCountsAsIntemperie, minCoverage, maxTemperatureDistanceKm,
            )
        }
    }
}

class SettingsFile(private val file: File = AppPaths.settings) {
    fun load(): AppSettings =
        runCatching { json.decodeFromString<SettingsDto>(file.readText()).toModel() }.getOrDefault(AppSettings())

    fun save(s: AppSettings) = file.writeAtomically(json.encodeToString(SettingsDto.from(s)).toByteArray())
}

// ------------------------------------------------------------------------------------------------
// État de synchronisation
// ------------------------------------------------------------------------------------------------

@Serializable
data class SyncStateDto(
    val lastSuccess: Long? = null,
    val lastError: String? = null,
    val lastDeep: Long? = null,
    val lastStationRefresh: Long? = null,
    val history: Map<String, Long> = emptyMap(),
)

class FileSyncStateStore(private val file: File = AppPaths.syncState, private val onChange: (SyncStateDto) -> Unit = {}) : SyncStateStore {
    private val mutex = Mutex()
    @Volatile var current: SyncStateDto = runCatching { json.decodeFromString<SyncStateDto>(file.readText()) }.getOrDefault(SyncStateDto())
        private set

    private suspend fun update(t: (SyncStateDto) -> SyncStateDto) = mutex.withLock {
        current = t(current)
        withContext(Dispatchers.IO) { file.writeAtomically(json.encodeToString(current).toByteArray()) }
        onChange(current)
    }

    override suspend fun lastDeepSync() = current.lastDeep
    override suspend fun lastStationRefresh() = current.lastStationRefresh
    override suspend fun markStationRefresh(epochSec: Long) = update { it.copy(lastStationRefresh = epochSec) }
    override suspend fun historyStart(kind: StationKind, stationNo: String) = current.history["${kind.name}:$stationNo"]
    override suspend fun setHistoryStart(kind: StationKind, stationNo: String, epochSec: Long) =
        update { it.copy(history = it.history + ("${kind.name}:$stationNo" to epochSec)) }
    override suspend fun markSyncSuccess(epochSec: Long, deep: Boolean) =
        update { it.copy(lastSuccess = epochSec, lastError = null, lastDeep = if (deep) epochSec else it.lastDeep) }
    override suspend fun markSyncError(message: String) = update { it.copy(lastError = message) }
}

// ------------------------------------------------------------------------------------------------
// Stations (liste embarquée, puis rafraîchie depuis le SPW)
// ------------------------------------------------------------------------------------------------

class FileStationStore(private val file: File = AppPaths.stations, private val onChange: (StationCatalog) -> Unit = {}) : StationStore {
    private val mutex = Mutex()
    @Volatile var catalog: StationCatalog = load()
        private set

    private fun load(): StationCatalog {
        val list = runCatching { Catalogs.decodeStations(file.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: Catalogs.seedStations()
        return StationCatalog(list.filter { it.kind == StationKind.RAIN }, list.filter { it.kind == StationKind.TEMPERATURE })
    }

    override suspend fun replace(kind: StationKind, stations: List<Station>) = mutex.withLock {
        catalog = when (kind) {
            StationKind.RAIN -> catalog.copy(rain = stations)
            StationKind.TEMPERATURE -> catalog.copy(temperature = stations)
        }
        withContext(Dispatchers.IO) { file.writeAtomically(Catalogs.encodeStations(catalog.rain + catalog.temperature).toByteArray()) }
        onChange(catalog)
    }
}
