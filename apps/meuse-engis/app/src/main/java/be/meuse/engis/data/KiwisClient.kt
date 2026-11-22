package be.meuse.engis.data

import be.meuse.engis.domain.Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Client minimal du service KiWIS (Kisters) du SPW – accès anonyme, réponse JSON. */
class KiwisClient(private val baseUrl: String = Stations.KIWIS_URL) {

    /** Télécharge plusieurs séries en une seule requête et renvoie le JSON brut. */
    suspend fun fetchValues(tsIds: List<String>, from: LocalDateTime, to: LocalDateTime): String =
        withContext(Dispatchers.IO) {
            val fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME
            val params = linkedMapOf(
                "service" to "kisters",
                "type" to "queryServices",
                "request" to "getTimeseriesValues",
                "datasource" to "0",
                "format" to "json",
                "ts_id" to tsIds.joinToString(","),
                "from" to from.format(fmt),
                "to" to to.format(fmt),
                "timezone" to "Europe/Brussels",
            )
            val query = params.entries.joinToString("&") { (k, v) ->
                "$k=" + URLEncoder.encode(v, "UTF-8")
            }
            val conn = URL("$baseUrl?$query").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("Accept", "application/json")
                val code = conn.responseCode
                if (code != 200) throw IOException("Serveur SPW : HTTP $code")
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }

    companion object {
        /**
         * Transforme la réponse KiWIS
         * `[{"ts_id":"…","columns":"Timestamp,Value","data":[["2026-11-22T08:00:00.000+01:00",312.7],…]}]`
         * en séries de points (les valeurs nulles sont ignorées).
         */
        fun parse(json: String): Map<String, List<Point>> {
            val root = JSONArray(json)
            val out = mutableMapOf<String, List<Point>>()
            for (i in 0 until root.length()) {
                val ts = root.getJSONObject(i)
                val data = ts.optJSONArray("data") ?: JSONArray()
                val points = ArrayList<Point>(data.length())
                for (j in 0 until data.length()) {
                    val row = data.getJSONArray(j)
                    if (row.isNull(1)) continue
                    val value = row.optDouble(1)
                    if (value.isNaN()) continue
                    points += Point(OffsetDateTime.parse(row.getString(0)), value)
                }
                out[ts.getString("ts_id")] = points.sortedBy { it.time }
            }
            return out
        }
    }
}
