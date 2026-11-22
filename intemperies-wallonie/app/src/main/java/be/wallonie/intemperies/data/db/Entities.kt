package be.wallonie.intemperies.data.db

import androidx.room.Entity
import be.wallonie.intemperies.model.Sample
import be.wallonie.intemperies.model.Station
import be.wallonie.intemperies.model.StationKind

/** Mesure brute ; kind = RAIN (mm / 5 min) ou TEMPERATURE (°C moyenne horaire). */
@Entity(tableName = "samples", primaryKeys = ["kind", "stationNo", "epochSec"])
data class SampleEntity(
    val kind: String,
    val stationNo: String,
    val epochSec: Long,
    val value: Double,
    val quality: Int,
) {
    fun toModel() = Sample(epochSec, value, quality)
}

@Entity(tableName = "stations", primaryKeys = ["kind", "stationNo"])
data class StationEntity(
    val kind: String,
    val stationNo: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val tsId: String,
    val network: String,
) {
    fun toModel() = Station(stationNo, name, lat, lon, tsId, StationKind.valueOf(kind), network)

    companion object {
        fun from(s: Station) = StationEntity(s.kind.name, s.stationNo, s.name, s.lat, s.lon, s.tsId, s.network)
    }
}
