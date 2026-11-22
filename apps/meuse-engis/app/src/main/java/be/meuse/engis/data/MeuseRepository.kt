package be.meuse.engis.data

import android.content.Context
import be.meuse.engis.domain.MeuseSnapshot
import be.meuse.engis.domain.SnapshotBuilder
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Récupère les mesures directement auprès du SPW, sans intermédiaire, et garde la dernière réponse
 * en cache (consultation hors ligne).
 *
 * Auto-réparation : si le SPW refuse un identifiant de série (renumérotation), l'application
 * retrouve les nouveaux identifiants par numéro de station + nom de série et les mémorise ; en
 * dernier recours, chaque série est demandée séparément et celles qui répondent sont affichées.
 */
class MeuseRepository(
    context: Context,
    private val client: KiwisClient = KiwisClient(),
    private val zone: ZoneId = ZoneId.of("Europe/Brussels"),
) {
    private val prefs = context.getSharedPreferences("meuse_cache", Context.MODE_PRIVATE)

    suspend fun refresh(): MeuseSnapshot {
        val now = ZonedDateTime.now(zone)
        val from = SnapshotBuilder.windowStart(now.toLocalDate()).atStartOfDay()
        val to = now.toLocalDateTime().plusHours(1)

        var ids = currentIds()
        val json = try {
            fetchAll(ids, from, to)
        } catch (e: KiwisException) {
            ids = resolveIds(ids)
            try {
                fetchAll(ids, from, to)
            } catch (e2: KiwisException) {
                fetchOneByOne(ids, from, to)
            }
        }

        val fetchedAt = Instant.now()
        val snapshot = build(json, ids, fetchedAt)
        prefs.edit()
            .putString(KEY_JSON, json)
            .putString(KEY_IDS, JSONObject(ids.mapKeys { it.key.name }).toString())
            .putLong(KEY_TIME, fetchedAt.toEpochMilli())
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
        return runCatching { build(json, currentIds(), time) }.getOrNull()
    }

    /** Identifiants mémorisés (après une éventuelle réparation), sinon ceux d'origine. */
    private fun currentIds(): Map<Series, String> {
        val saved = prefs.getString(KEY_IDS, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        return Series.entries.associateWith { s -> saved?.optString(s.name)?.takeIf { it.isNotEmpty() } ?: s.defaultId }
    }

    /** Une requête pour toutes les séries ; échoue si le SPW refuse ou si une série manque. */
    private suspend fun fetchAll(ids: Map<Series, String>, from: LocalDateTime, to: LocalDateTime): String {
        val json = client.fetchValues(ids.values.distinct(), from, to)
        val got = KiwisClient.parse(json).keys
        val missing = ids.filterValues { it !in got }.keys
        if (missing.isNotEmpty()) throw KiwisException("Séries absentes : ${missing.joinToString()}")
        return json
    }

    /** Dernier recours : une requête par série ; on garde celles qui répondent. */
    private suspend fun fetchOneByOne(ids: Map<Series, String>, from: LocalDateTime, to: LocalDateTime): String {
        val parts = ids.values.distinct().mapNotNull { id ->
            runCatching { client.fetchValues(listOf(id), from, to) }.getOrNull()
        }
        if (parts.isEmpty()) throw KiwisException("Aucune série disponible auprès du SPW.")
        return KiwisClient.merge(parts)
    }

    /** Retrouve les identifiants par station + nom de série (getTimeseriesList) et les mémorise. */
    private suspend fun resolveIds(current: Map<Series, String>): Map<Series, String> {
        val found = runCatching {
            client.findTimeseriesIds(
                stationNos = Series.entries.map { it.stationNo }.distinct(),
                tsNames = Series.entries.flatMap { it.tsNames }.distinct(),
            )
        }.getOrElse { return current }
        val resolved = Series.entries.associateWith { s ->
            s.tsNames.firstNotNullOfOrNull { name -> found[s.stationNo to name] } ?: current.getValue(s)
        }
        prefs.edit().putString(KEY_IDS, JSONObject(resolved.mapKeys { it.key.name }).toString()).apply()
        return resolved
    }

    private fun build(json: String, ids: Map<Series, String>, fetchedAt: Instant): MeuseSnapshot =
        SnapshotBuilder.build(
            series = KiwisClient.parse(json).bySeries(ids),
            today = ZonedDateTime.ofInstant(fetchedAt, zone).toLocalDate(),
            zone = zone,
            fetchedAt = fetchedAt,
        )

    private companion object {
        const val KEY_JSON = "json"
        const val KEY_IDS = "ids"
        const val KEY_TIME = "time"
    }
}
