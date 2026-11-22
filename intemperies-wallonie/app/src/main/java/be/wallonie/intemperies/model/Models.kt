package be.wallonie.intemperies.model

import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** Commune wallonne (code INS/NIS à 5 chiffres) et son centroïde WGS84. */
data class Commune(
    val nis: String,
    val name: String,
    val province: String,
    val lat: Double,
    val lon: Double,
)

enum class StationKind { RAIN, TEMPERATURE }

/** Station de mesure du réseau hydrométrique du SPW (KiWIS). */
data class Station(
    val stationNo: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    /** Identifiant de la série temporelle utilisée (pluie 5 min ou température horaire). */
    val tsId: String,
    val kind: StationKind,
    val network: String,
)

data class StationAssignment(
    val station: Station,
    val distanceKm: Double,
    val manual: Boolean,
)

/** Mesure élémentaire : horodatage de DÉBUT de pas (convention KiWIS vérifiée), valeur, code qualité SPW. */
data class Sample(
    val epochSec: Long,
    val value: Double,
    val quality: Int,
)

/**
 * Critères de qualification d'une journée. Toutes les valeurs sont paramétrables.
 * Valeurs par défaut = demande initiale de l'utilisateur.
 */
data class Criteria(
    val windowStart: LocalTime = LocalTime.of(7, 30),
    val windowEnd: LocalTime = LocalTime.of(16, 0),
    /** Pas d'analyse (min) : un pas est « pluvieux » si le cumul du pas ≥ [wetStepThresholdMm]. */
    val stepMinutes: Int = 30,
    val wetStepThresholdMm: Double = 0.1,
    /** Rouge si pluie consécutive > (ou ≥) ce nombre d'heures. */
    val consecutiveRainHours: Double = 3.0,
    /** Rouge si durée totale de pluie dans la plage > (ou ≥) ce nombre d'heures. */
    val totalRainHours: Double = 4.0,
    /** true : comparaison stricte « plus de » (>) ; false : « au moins » (≥). */
    val strictComparison: Boolean = true,
    /** Orange si durée totale de pluie ≥ ce nombre d'heures. */
    val warningRainHours: Double = 2.0,
    /**
     * Seuil bas (gel) : durée cumulée, dans la plage horaire, pendant laquelle la température
     * (moyenne horaire) est strictement inférieure au seuil. Atteint si durée ≥ [coldMinHours].
     */
    val coldThresholdC: Double = 0.0,
    val coldMinHours: Double = 3.0,
    /** Si coché, le seuil bas atteint rend la journée « intempérie ». */
    val coldCountsAsIntemperie: Boolean = false,
    /** Seuil haut (canicule) : température (moyenne horaire) strictement supérieure au seuil. */
    val heatThresholdC: Double = 32.0,
    val heatMinHours: Double = 3.0,
    /** Si coché, le seuil haut atteint rend la journée « intempérie ». */
    val heatCountsAsIntemperie: Boolean = false,
    /** Part minimale de données disponibles dans la plage pour statuer. */
    val minCoverage: Double = 0.8,
    /** Distance max. de la station de température retenue automatiquement. */
    val maxTemperatureDistanceKm: Double = 40.0,
)

data class AppSettings(
    val selectedCommunes: List<String> = emptyList(),
    val criteria: Criteria = Criteria(),
    val retentionMonths: Int = 6,
    /** NIS -> n° de station imposé manuellement. */
    val rainStationOverrides: Map<String, String> = emptyMap(),
    val temperatureStationOverrides: Map<String, String> = emptyMap(),
)

enum class DayStatus {
    /** Vert : pas d'intempérie. */
    DRY,

    /** Orange : il a plu au moins N heures, sans atteindre le seuil d'intempérie. */
    WARNING,

    /** Rouge : intempérie. */
    INTEMPERIE,

    /** Gris : données insuffisantes. */
    NO_DATA,

    /** Journée en cours (plage horaire non terminée). */
    IN_PROGRESS,

    /** Jour futur. */
    FUTURE,
}

enum class Reason { CONSECUTIVE_RAIN, TOTAL_RAIN, COLD, HEAT }

data class DayReport(
    val date: LocalDate,
    val status: DayStatus,
    /** Cumul (mm) dans la plage horaire. */
    val windowRainMm: Double,
    /** Cumul (mm) sur 24 h (00:00–24:00). */
    val dayRainMm: Double,
    val rainHours: Double,
    val maxConsecutiveRainHours: Double,
    /** Moyenne pondérée dans la plage, null si indisponible. */
    val meanTemperatureC: Double?,
    /** Plus basse / plus haute moyenne horaire dans la plage. */
    val minTemperatureC: Double?,
    val maxTemperatureC: Double?,
    /** Durées (h) dans la plage sous le seuil bas / au-dessus du seuil haut ; null si température indisponible. */
    val coldHours: Double?,
    val heatHours: Double?,
    /** Seuil bas / haut atteint (durée ≥ durée minimale), qu'il compte ou non comme intempérie. */
    val coldReached: Boolean?,
    val heatReached: Boolean?,
    val reasons: Set<Reason>,
    val coverage: Double,
    /** Au moins une donnée non validée par le SPW (code qualité ≥ 200). */
    val provisional: Boolean,
    /** Pas d'analyse pluvieux (index) — utile pour le détail. */
    val wetSteps: List<Boolean>,
    val holidayName: String?,
) {
    val isWeekend: Boolean
        get() = date.dayOfWeek.value >= 6
}

data class MonthReport(
    val commune: Commune,
    val month: YearMonth,
    val rainStation: StationAssignment?,
    val temperatureStation: StationAssignment?,
    val days: List<DayReport>,
    val criteria: Criteria,
    val lastRainDataEpochSec: Long?,
) {
    fun count(status: DayStatus) = days.count { it.status == status }
}
