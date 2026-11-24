package be.wallonie.intemperies.domain

import be.wallonie.intemperies.model.Commune
import be.wallonie.intemperies.model.Station
import be.wallonie.intemperies.model.StationAssignment

/**
 * Associe une commune à la station la plus proche de son centroïde
 * (ou à la station imposée manuellement par l'utilisateur).
 */
object StationMatcher {
    fun rankByDistance(commune: Commune, stations: List<Station>): List<StationAssignment> =
        stations
            .map { StationAssignment(it, Geo.distanceKm(commune.lat, commune.lon, it.lat, it.lon), manual = false) }
            .sortedBy { it.distanceKm }

    fun assign(
        commune: Commune,
        stations: List<Station>,
        overrideStationNo: String?,
        maxDistanceKm: Double = Double.MAX_VALUE,
    ): StationAssignment? {
        if (overrideStationNo != null) {
            stations.firstOrNull { it.stationNo == overrideStationNo }?.let {
                return StationAssignment(it, Geo.distanceKm(commune.lat, commune.lon, it.lat, it.lon), manual = true)
            }
        }
        return rankByDistance(commune, stations).firstOrNull()?.takeIf { it.distanceKm <= maxDistanceKm }
    }
}
