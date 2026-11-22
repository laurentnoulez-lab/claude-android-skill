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
    /** Pas d'analyse (min) : la plage est découpée en pas de cette durée. */
    val stepMinutes: Int = 30,
    /**
     * Intensité minimale (mm/h, équivalent eau) pour qu'un pas compte comme « précipitation ».
     * Le cumul à atteindre sur un pas vaut intensité × durée du pas (cf. [wetStepThresholdMm]).
     */
    val wetIntensityMmH: Double = 1.0,
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
) {
    /** Cumul (mm) à atteindre sur un pas complet : 1 mm/h × 30 min = 0,5 mm. */
    val wetStepThresholdMm: Double
        get() = wetIntensityMmH * stepMinutes / 60.0

    /**
     * Cumul arrondi au 0,1 mm supérieur (0,25 → 0,3 mm). Indicatif : les mesures SPW sont le plus souvent
     * publiées par paliers de 0,1 mm (constat : 98,4 % des valeurs non nulles à Wavre du 22/05 au 22/11/2026).
     * Le calcul compare toujours le cumul au seuil exact [wetStepThresholdMm].
     */
    val effectiveStepThresholdMm: Double
        get() = kotlin.math.ceil(wetStepThresholdMm * 10 - 1e-9) / 10.0
}

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
    /** Cumul (mm) de chaque pas d'analyse de la plage ; NaN si aucune mesure dans le pas. */
    val stepRainMm: List<Double>,
    /** Moyennes horaires de température recouvrant la plage (horodatage = début d'heure). */
    val temperaturePoints: List<Sample>,
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
    /** Horodatage (début de pas) de la dernière mesure de pluie / de température disponible. */
    val lastRainDataEpochSec: Long?,
    val lastTemperatureDataEpochSec: Long? = null,
    /** Dernière synchronisation réussie avec le SPW. */
    val lastSyncEpochSec: Long? = null,
) {
    fun count(status: DayStatus) = days.count { it.status == status }
}
