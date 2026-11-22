package be.meuse.engis.data

import be.meuse.engis.domain.Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Erreur renvoyée par le service KiWIS (ex. identifiant de série inconnu). */
class KiwisException(message: String) : IOException(message)

/** Client minimal du service KiWIS (Kisters) du SPW – accès anonyme, réponses JSON. */
class KiwisClient(private val baseUrl: String = KIWIS_URL) {

    /** Télécharge plusieurs séries en une seule requête et renvoie le JSON brut (vérifié). */
    suspend fun fetchValues(tsIds: Collection<String>, from: LocalDateTime, to: LocalDateTime): String {
        val fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val json = get(
            "request" to "getTimeseriesValues",
            "ts_id" to tsIds.joinToString(","),
            "from" to from.format(fmt),
            "to" to to.format(fmt),
            "timezone" to "Europe/Brussels",
        )
        checkError(json)
        return json
    }

    /**
     * Recherche les identifiants de séries par numéro de station et nom de série.
     * Renvoie une table (station_no, ts_name) → ts_id.
     */
    suspend fun findTimeseriesIds(stationNos: Collection<String>, tsNames: Collection<String>): Map<Pair<String, String>, String> {
        val json = get(
            "request" to "getTimeseriesList",
            "station_no" to stationNos.joinToString(","),
            "ts_name" to tsNames.joinToString(","),
            "returnfields" to "station_no,ts_id,ts_name",
        )
        checkError(json)
        val rows = JSONArray(json)
        val header = rows.getJSONArray(0)
        val iStation = (0 until header.length()).first { header.getString(it) == "station_no" }
        val iId = (0 until header.length()).first { header.getString(it) == "ts_id" }
        val iName = (0 until header.length()).first { header.getString(it) == "ts_name" }
        return (1 until rows.length()).associate { r ->
            val row = rows.getJSONArray(r)
            (row.getString(iStation) to row.getString(iName)) to row.getString(iId)
        }
    }

    private suspend fun get(vararg extra: Pair<String, String>): String = withContext(Dispatchers.IO) {
        val params = listOf(
            "service" to "kisters",
            "type" to "queryServices",
            "datasource" to "0",
            "format" to "json",
        ) + extra
        val query = params.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val conn = URL("$baseUrl?$query").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != 200) {
                // KiWIS signale ses erreurs (ex. identifiant inconnu) par un HTTP 500 + JSON {"type":"error",…}
                val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                runCatching { checkError(body) }.exceptionOrNull()?.let { throw it }
                throw IOException("Serveur SPW : HTTP $code")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** KiWIS répond `{"type":"error","code":…,"message":…}` en cas de problème. */
        fun checkError(json: String) {
            val root = JSONTokener(json).nextValue()
            if (root is JSONObject && root.optString("type") == "error") {
                throw KiwisException("${root.optString("code")} : ${root.optString("message")}")
            }
        }

        /**
         * Transforme la réponse KiWIS
         * `[{"ts_id":"…","columns":"Timestamp,Value","data":[["2026-11-22T08:00:00.000+01:00",312.7],…]}]`
         * en séries de points indexées par ts_id (les valeurs nulles sont ignorées).
         */
        fun parse(json: String): Map<String, List<Point>> {
            checkError(json)
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

        /** Concatène plusieurs réponses getTimeseriesValues (tableaux JSON) en une seule. */
        fun merge(jsons: List<String>): String {
            val all = JSONArray()
            jsons.forEach { j -> JSONArray(j).let { a -> for (i in 0 until a.length()) all.put(a.get(i)) } }
            return all.toString()
        }
    }
}

/** Associe les séries téléchargées (par ts_id) aux séries logiques de l'application. */
fun Map<String, List<Point>>.bySeries(ids: Map<Series, String>): Map<Series, List<Point>> =
    ids.mapNotNull { (series, id) -> this[id]?.let { series to it } }.toMap()
