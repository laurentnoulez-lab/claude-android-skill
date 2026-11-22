package be.wallonie.intemperies

import be.wallonie.intemperies.data.Catalogs
import be.wallonie.intemperies.data.SampleStore
import be.wallonie.intemperies.data.StationCatalog
import be.wallonie.intemperies.data.StationStore
import be.wallonie.intemperies.data.SyncEngine
import be.wallonie.intemperies.data.SyncOutcome
import be.wallonie.intemperies.data.SyncStateStore
import be.wallonie.intemperies.data.remote.KiwisClient
import be.wallonie.intemperies.model.AppSettings
import be.wallonie.intemperies.model.Sample
import be.wallonie.intemperies.model.Station
import be.wallonie.intemperies.model.StationKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URLDecoder
import java.time.Instant
import java.time.OffsetDateTime

class SyncEngineTest {
    private class MemStore : SampleStore {
        val data = mutableMapOf<String, MutableMap<Long, Sample>>()
        override suspend fun upsert(kind: StationKind, stationNo: String, samples: List<Sample>) {
            val m = data.getOrPut("${kind.name}:$stationNo") { sortedMapOf() }
            samples.forEach { m[it.epochSec] = it }
        }
        override suspend fun lastEpoch(kind: StationKind, stationNo: String) = data["${kind.name}:$stationNo"]?.keys?.maxOrNull()
        override suspend fun purgeBefore(epochSec: Long) = data.values.forEach { m -> m.keys.removeIf { it < epochSec } }
        override suspend fun purgeStationsNotIn(keep: Set<String>) { data.keys.removeIf { it !in keep } }
    }

    private class MemState : SyncStateStore {
        var deep: Long? = null; var refresh: Long? = null; var success: Long? = null; var error: String? = null
        val history = mutableMapOf<String, Long>()
        override suspend fun lastDeepSync() = deep
        override suspend fun lastStationRefresh() = refresh
        override suspend fun markStationRefresh(epochSec: Long) { refresh = epochSec }
        override suspend fun historyStart(kind: StationKind, stationNo: String) = history["$kind:$stationNo"]
        override suspend fun setHistoryStart(kind: StationKind, stationNo: String, epochSec: Long) { history["$kind:$stationNo"] = epochSec }
        override suspend fun markSyncSuccess(epochSec: Long, deep: Boolean) { success = epochSec; error = null; if (deep) this.deep = epochSec }
        override suspend fun markSyncError(message: String) { error = message }
    }

    private val now = Instant.parse("2026-11-22T10:00:00Z")
    private val wavre = Catalogs.seedStations().first { it.kind == StationKind.RAIN && it.stationNo == "1043" }
    private val catalog = StationCatalog(listOf(wavre), emptyList())
    private val settings = AppSettings(selectedCommunes = listOf("25112"), retentionMonths = 1)

    /** Faux serveur KiWIS : une mesure horaire de 0,1 mm sur l'intervalle demandé ; journalise les requêtes. */
    private fun fakeClient(requests: MutableList<Pair<OffsetDateTime, OffsetDateTime>>, fail: Boolean = false) = KiwisClient(http = { url ->
        if (fail) throw IOException("réseau indisponible")
        val q = url.substringAfter('?').split('&').associate { p -> p.substringBefore('=') to URLDecoder.decode(p.substringAfter('='), "UTF-8") }
        if (q["request"] == "getTimeseriesList") return@KiwisClient "[]"
        val from = OffsetDateTime.parse(q.getValue("from")); val to = OffsetDateTime.parse(q.getValue("to"))
        requests += from to to
        val rows = generateSequence(from) { it.plusHours(1) }.takeWhile { it.isBefore(to) && !it.toInstant().isAfter(now) }
            .joinToString(",") { "[\"$it\",0.1,40]" }
        "[{\"ts_id\":\"x\",\"rows\":\"0\",\"columns\":\"Timestamp,Value,Quality Code\",\"data\":[$rows]}]"
    })

    private class NoStations : StationStore { override suspend fun replace(kind: StationKind, stations: List<Station>) = Unit }

    @Test
    fun firstSyncLoadsRetentionThenIncremental() = runBlocking {
        val store = MemStore(); val state = MemState(); val req = mutableListOf<Pair<OffsetDateTime, OffsetDateTime>>()
        val engine = SyncEngine(fakeClient(req), store, state, NoStations(), clock = { now })
        val r1 = engine.sync(settings, { catalog })
        assertTrue(r1 is SyncOutcome.Success)
        // Rétention 1 mois : depuis le 01/10/2026 00:00 (heure belge).
        assertEquals(OffsetDateTime.parse("2026-10-01T00:00+02:00").toInstant(), req.first().first.toInstant())
        assertEquals(now.epochSecond, state.success)
        val count1 = store.data.getValue("RAIN:1043").size
        assertTrue(count1 > 1000)

        // Deuxième synchro (même jour, pas de rechargement profond) : uniquement depuis la dernière mesure - 3 h.
        req.clear()
        engine.sync(settings, { catalog })
        assertEquals(1, req.size)
        val last = store.data.getValue("RAIN:1043").keys.max()
        assertEquals(last - 3 * 3600, req.first().first.toEpochSecond())
    }

    @Test
    fun purgesUnusedStationsAndOldData() = runBlocking {
        val store = MemStore(); val state = MemState()
        store.upsert(StationKind.RAIN, "9999", listOf(Sample(now.epochSecond, 1.0, 40)))
        store.upsert(StationKind.RAIN, "1043", listOf(Sample(Instant.parse("2026-01-01T00:00:00Z").epochSecond, 1.0, 40)))
        SyncEngine(fakeClient(mutableListOf()), store, state, NoStations(), clock = { now }).sync(settings, { catalog })
        assertTrue("RAIN:9999" !in store.data)
        assertTrue(store.data.getValue("RAIN:1043").keys.min() >= OffsetDateTime.parse("2026-10-01T00:00+02:00").toEpochSecond())
    }

    @Test
    fun networkFailureIsReportedNotThrown() = runBlocking {
        val state = MemState()
        val r = SyncEngine(fakeClient(mutableListOf(), fail = true), MemStore(), state, NoStations(), clock = { now }).sync(settings, { catalog })
        assertTrue(r is SyncOutcome.Failure)
        assertNotNull(state.error)
    }
}
