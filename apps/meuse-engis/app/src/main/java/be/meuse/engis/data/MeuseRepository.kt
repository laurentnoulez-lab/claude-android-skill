package be.meuse.engis.data

import android.content.Context
import be.meuse.engis.domain.MeuseSnapshot
import be.meuse.engis.domain.SnapshotBuilder
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Récupère les données SPW et garde la dernière réponse en cache (consultation hors ligne). */
class MeuseRepository(
    context: Context,
    private val client: KiwisClient = KiwisClient(),
    private val zone: ZoneId = ZoneId.of("Europe/Brussels"),
) {
    private val prefs = context.getSharedPreferences("meuse_cache", Context.MODE_PRIVATE)

    suspend fun refresh(): MeuseSnapshot {
        val now = ZonedDateTime.now(zone)
        val from = SnapshotBuilder.windowStart(now.toLocalDate()).atStartOfDay()
        val json = client.fetchValues(Stations.ALL, from, now.toLocalDateTime().plusHours(1))
        val snapshot = build(json, Instant.now())
        prefs.edit()
            .putString(KEY_JSON, json)
            .putLong(KEY_TIME, snapshot.fetchedAt.toEpochMilli())
            .apply()
        return snapshot
    }

    /** Pour le widget : données en cache si elles ont moins de [maxAge], sinon nouvelle requête (cache en secours). */
    suspend fun cachedOrRefresh(maxAge: Duration): MeuseSnapshot? {
        val cache = cached()
        if (cache != null && Duration.between(cache.fetchedAt, Instant.now()) < maxAge) return cache
        return runCatching { refresh() }.getOrElse { cache }
    }

    fun cached(): MeuseSnapshot? {
        val json = prefs.getString(KEY_JSON, null) ?: return null
        val time = Instant.ofEpochMilli(prefs.getLong(KEY_TIME, 0L))
        return runCatching { build(json, time) }.getOrNull()
    }

    private fun build(json: String, fetchedAt: Instant): MeuseSnapshot =
        SnapshotBuilder.build(
            series = KiwisClient.parse(json),
            today = ZonedDateTime.ofInstant(fetchedAt, zone).toLocalDate(),
            zone = zone,
            fetchedAt = fetchedAt,
        )

    private companion object {
        const val KEY_JSON = "json"
        const val KEY_TIME = "time"
    }
}
