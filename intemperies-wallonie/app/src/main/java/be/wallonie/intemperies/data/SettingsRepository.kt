package be.wallonie.intemperies.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import be.wallonie.intemperies.model.AppSettings
import be.wallonie.intemperies.model.Criteria
import be.wallonie.intemperies.model.StationKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
private val Context.syncStore: DataStore<Preferences> by preferencesDataStore(name = "sync_state")

data class SyncStatus(
    val lastSuccessEpochSec: Long?,
    val lastError: String?,
    val running: Boolean = false,
)

class SettingsRepository(context: Context) {
    private val store = context.settingsStore
    private val syncStore = context.syncStore

    private object K {
        val communes = stringPreferencesKey("communes") // NIS séparés par des virgules (ordre conservé)
        val windowStart = stringPreferencesKey("window_start")
        val windowEnd = stringPreferencesKey("window_end")
        val step = intPreferencesKey("step_minutes")
        val wetMmH = doublePreferencesKey("wet_intensity_mmh")
        val consecutive = doublePreferencesKey("consecutive_h")
        val total = doublePreferencesKey("total_h")
        val strict = booleanPreferencesKey("strict")
        val warning = doublePreferencesKey("warning_h")
        val cold = doublePreferencesKey("cold_c")
        val coldHours = doublePreferencesKey("cold_h")
        val coldOn = booleanPreferencesKey("cold_intemperie")
        val heat = doublePreferencesKey("heat_c")
        val heatHours = doublePreferencesKey("heat_h")
        val heatOn = booleanPreferencesKey("heat_intemperie")
        val coverage = doublePreferencesKey("min_coverage")
        val tempDistance = doublePreferencesKey("temp_max_km")
        val retention = intPreferencesKey("retention_months")
        val rainOverrides = stringSetPreferencesKey("rain_overrides")
        val tempOverrides = stringSetPreferencesKey("temp_overrides")
    }

    private object S {
        val lastSuccess = longPreferencesKey("last_success")
        val lastError = stringPreferencesKey("last_error")
        val lastDeep = longPreferencesKey("last_deep")
        val lastStationRefresh = longPreferencesKey("last_station_refresh")
        fun history(kind: StationKind, stationNo: String) = longPreferencesKey("history_${kind.name}_$stationNo")
    }

    val settings: Flow<AppSettings> = store.data.map { p -> p.toSettings() }.distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    suspend fun setCommunes(nisList: List<String>) = store.edit { it[K.communes] = nisList.distinct().joinToString(",") }

    suspend fun toggleCommune(nis: String) = store.edit { p ->
        val list = p[K.communes].toNisList().toMutableList()
        if (!list.remove(nis)) list += nis
        p[K.communes] = list.joinToString(",")
    }

    suspend fun updateCriteria(transform: (Criteria) -> Criteria) = store.edit { p ->
        val c = transform(p.toSettings().criteria)
        p[K.windowStart] = c.windowStart.toString()
        p[K.windowEnd] = c.windowEnd.toString()
        p[K.step] = c.stepMinutes
        p[K.wetMmH] = c.wetIntensityMmH
        p[K.consecutive] = c.consecutiveRainHours
        p[K.total] = c.totalRainHours
        p[K.strict] = c.strictComparison
        p[K.warning] = c.warningRainHours
        p[K.cold] = c.coldThresholdC
        p[K.coldHours] = c.coldMinHours
        p[K.coldOn] = c.coldCountsAsIntemperie
        p[K.heat] = c.heatThresholdC
        p[K.heatHours] = c.heatMinHours
        p[K.heatOn] = c.heatCountsAsIntemperie
        p[K.coverage] = c.minCoverage
        p[K.tempDistance] = c.maxTemperatureDistanceKm
    }

    suspend fun resetCriteria() = updateCriteria { Criteria() }

    suspend fun setRetentionMonths(months: Int) = store.edit { it[K.retention] = months.coerceIn(1, 24) }

    suspend fun setStationOverride(kind: StationKind, nis: String, stationNo: String?) = store.edit { p ->
        val key = if (kind == StationKind.RAIN) K.rainOverrides else K.tempOverrides
        val set = (p[key] ?: emptySet()).filterNot { it.startsWith("$nis|") }.toMutableSet()
        if (stationNo != null) set += "$nis|$stationNo"
        p[key] = set
    }

    // --- État de synchronisation -------------------------------------------------------------

    val syncStatus: Flow<SyncStatus> = syncStore.data.map { SyncStatus(it[S.lastSuccess], it[S.lastError]) }

    suspend fun markSyncSuccess(epochSec: Long, deep: Boolean) = syncStore.edit {
        it[S.lastSuccess] = epochSec
        it.remove(S.lastError)
        if (deep) it[S.lastDeep] = epochSec
    }

    suspend fun markSyncError(message: String) = syncStore.edit { it[S.lastError] = message }

    suspend fun lastDeepSync(): Long? = syncStore.data.first()[S.lastDeep]

    suspend fun lastStationRefresh(): Long? = syncStore.data.first()[S.lastStationRefresh]

    suspend fun markStationRefresh(epochSec: Long) = syncStore.edit { it[S.lastStationRefresh] = epochSec }

    suspend fun historyStart(kind: StationKind, stationNo: String): Long? = syncStore.data.first()[S.history(kind, stationNo)]

    suspend fun setHistoryStart(kind: StationKind, stationNo: String, epochSec: Long) =
        syncStore.edit { it[S.history(kind, stationNo)] = epochSec }

    private fun String?.toNisList(): List<String> = this?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

    private fun Set<String>?.toOverrideMap(): Map<String, String> =
        this?.mapNotNull { e -> e.split('|').takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap() ?: emptyMap()

    private fun Preferences.toSettings(): AppSettings {
        val d = Criteria()
        return AppSettings(
            selectedCommunes = this[K.communes].toNisList(),
            criteria = Criteria(
                windowStart = this[K.windowStart]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: d.windowStart,
                windowEnd = this[K.windowEnd]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: d.windowEnd,
                stepMinutes = this[K.step] ?: d.stepMinutes,
                wetIntensityMmH = this[K.wetMmH] ?: d.wetIntensityMmH,
                consecutiveRainHours = this[K.consecutive] ?: d.consecutiveRainHours,
                totalRainHours = this[K.total] ?: d.totalRainHours,
                strictComparison = this[K.strict] ?: d.strictComparison,
                warningRainHours = this[K.warning] ?: d.warningRainHours,
                coldThresholdC = this[K.cold] ?: d.coldThresholdC,
                coldMinHours = this[K.coldHours] ?: d.coldMinHours,
                coldCountsAsIntemperie = this[K.coldOn] ?: d.coldCountsAsIntemperie,
                heatThresholdC = this[K.heat] ?: d.heatThresholdC,
                heatMinHours = this[K.heatHours] ?: d.heatMinHours,
                heatCountsAsIntemperie = this[K.heatOn] ?: d.heatCountsAsIntemperie,
                minCoverage = this[K.coverage] ?: d.minCoverage,
                maxTemperatureDistanceKm = this[K.tempDistance] ?: d.maxTemperatureDistanceKm,
            ),
            retentionMonths = this[K.retention] ?: 6,
            rainStationOverrides = this[K.rainOverrides].toOverrideMap(),
            temperatureStationOverrides = this[K.tempOverrides].toOverrideMap(),
        )
    }
}
