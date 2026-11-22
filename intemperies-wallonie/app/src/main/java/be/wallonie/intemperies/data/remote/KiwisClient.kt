package be.wallonie.intemperies.data.remote

import be.wallonie.intemperies.model.Sample
import be.wallonie.intemperies.model.Station
import be.wallonie.intemperies.model.StationKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Client du service web KiWIS du portail hydrometrie.wallonie.be (SPW).
 * Données publiques — mention obligatoire « Sources des données : SPW ».
 */
class KiwisClient(
    private val baseUrl: String = BASE_URL,
    private val http: suspend (String) -> String = ::httpGet,
) {
    suspend fun fetchValues(tsId: String, from: OffsetDateTime, to: OffsetDateTime): List<Sample> {
        val url = buildUrl(
            "request" to "getTimeseriesValues",
            "ts_id" to tsId,
            "from" to from.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "to" to to.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "returnfields" to "Timestamp,Value,Quality Code",
        )
        return parseValues(http(url))
    }

    /** Liste des stations actives (pluie 5 min ou température de l'air horaire). */
    suspend fun fetchStations(kind: StationKind): List<Station> {
        val filters = when (kind) {
            StationKind.RAIN -> arrayOf("ts_name" to RAIN_TS_NAME)
            StationKind.TEMPERATURE -> arrayOf(
                "ts_name" to TEMPERATURE_TS_NAME,
                "stationparameter_name" to TEMPERATURE_PARAMETER,
            )
        }
        val url = buildUrl(
            "request" to "getTimeseriesList",
            *filters,
            "returnfields" to "station_name,station_no,station_latitude,station_longitude,ts_id,site_no,coverage",
        )
        return parseStations(http(url), kind)
    }

    private fun buildUrl(vararg params: Pair<String, String>): String {
        val query = (COMMON + params).joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "$baseUrl?$query"
    }

    companion object {
        const val BASE_URL = "https://hydrometrie.wallonie.be/services/KiWIS/KiWIS"
        const val RAIN_TS_NAME = "02b-Precipitation.5min.Production"
        const val TEMPERATURE_TS_NAME = "10-Temperature.1h.Moyen"
        const val TEMPERATURE_PARAMETER = "Température Air"

        /** Une station dont la dernière donnée a plus de 30 jours est considérée comme inactive. */
        private const val STALE_DAYS = 30L

        private val COMMON = listOf(
            "service" to "kisters",
            "type" to "queryServices",
            "datasource" to "0",
            "format" to "json",
        )
        private val json = Json { ignoreUnknownKeys = true }

        fun parseValues(body: String): List<Sample> {
            val root = json.parseToJsonElement(body)
            if (root is JsonObject) throw IOException("KiWIS: ${root["message"] ?: root}")
            val result = ArrayList<Sample>()
            for (series in root.jsonArray) {
                val data = series.jsonObject["data"]?.jsonArray ?: continue
                for (row in data) {
                    val r = row.jsonArray
                    val ts = OffsetDateTime.parse(r[0].jsonPrimitive.content).toEpochSecond()
                    val v = r.getOrNull(1)
                    val value = if (v == null || v is JsonNull) Double.NaN else (v as JsonPrimitive).doubleOrNull ?: Double.NaN
                    val q = r.getOrNull(2)?.let { if (it is JsonNull) null else it.jsonPrimitive.intOrNull } ?: 0
                    result += Sample(ts, value, q)
                }
            }
            return result
        }

        fun parseStations(body: String, kind: StationKind, now: OffsetDateTime = OffsetDateTime.now()): List<Station> {
            val root = json.parseToJsonElement(body)
            if (root !is JsonArray || root.isEmpty()) return emptyList()
            val header = root[0].jsonArray.map { it.jsonPrimitive.content }
            fun idx(name: String) = header.indexOf(name)
            val iName = idx("station_name")
            val iNo = idx("station_no")
            val iLat = idx("station_latitude")
            val iLon = idx("station_longitude")
            val iTs = idx("ts_id")
            val iSite = idx("site_no")
            val iTo = idx("to")
            val limit = now.minusDays(STALE_DAYS)
            return root.drop(1).mapNotNull { row ->
                val r = row.jsonArray.map { (it as? JsonPrimitive)?.content.orEmpty() }
                val lat = r.getOrNull(iLat)?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = r.getOrNull(iLon)?.toDoubleOrNull() ?: return@mapNotNull null
                val to = r.getOrNull(iTo)?.takeIf { it.isNotBlank() }?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
                if (to == null || to.isBefore(limit)) return@mapNotNull null
                Station(
                    stationNo = r[iNo],
                    name = r[iName].trim(),
                    lat = lat,
                    lon = lon,
                    tsId = r[iTs],
                    kind = kind,
                    network = r.getOrNull(iSite).orEmpty(),
                )
            }.distinctBy { it.stationNo }
        }

        suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "IntemperiesWallonie/1.0 (Android)")
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }
}
