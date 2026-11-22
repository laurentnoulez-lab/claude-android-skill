package be.meuse.engis.ui

import be.meuse.engis.R
import be.meuse.engis.domain.CarnassierRules
import be.meuse.engis.domain.FishingStatus
import be.meuse.engis.domain.FlowClass
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Pictogramme affiché : pêcheur qui dort (pêche interdite) ou pêcheur vert « Fish! » (autorisée + courant normal). */
enum class FisherMood(val drawable: Int?, val label: String?) {
    SLEEP(R.drawable.ic_fisher_sleeping, "Zzz"),
    FISH(R.drawable.ic_fisher_awake, "Fish!"),
    NONE(null, null),
    ;

    companion object {
        fun of(status: FishingStatus, flowClass: FlowClass?): FisherMood = when {
            status is FishingStatus.Closed -> SLEEP
            flowClass == FlowClass.NORMAL -> FISH
            else -> NONE
        }
    }
}

private val DATE_FR: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", FR)
private val DATE_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", FR)

/** Texte principal du statut, ex. « Pêche au carnassier autorisée jusqu'à 17:43 ». */
fun FishingStatus.headline(now: ZonedDateTime): String = when (this) {
    is FishingStatus.Open ->
        "Pêche au carnassier autorisée jusqu'à ${until.format(HOUR)}" +
            if (seasonLastDay) " (dernier jour de la saison)" else ""
    is FishingStatus.Closed -> when (reason) {
        FishingStatus.Reason.NUIT -> "Pêche interdite (nuit) — reprise " + when (reopensAt.toLocalDate()) {
            now.toLocalDate() -> "à ${reopensAt.format(HOUR)}"
            now.toLocalDate().plusDays(1) -> "demain à ${reopensAt.format(HOUR)}"
            else -> "le ${reopensAt.format(DATE_SHORT)} à ${reopensAt.format(HOUR)}"
        }
        FishingStatus.Reason.FERMETURE ->
            "Fermeture du carnassier — réouverture le ${reopensAt.format(DATE_FR)} à ${reopensAt.format(HOUR)}"
    }
}

/** Version courte pour le widget. */
fun FishingStatus.short(now: ZonedDateTime): String = when (this) {
    is FishingStatus.Open -> "Pêche autorisée jusqu'à ${until.format(HOUR)}"
    is FishingStatus.Closed -> when (reason) {
        FishingStatus.Reason.NUIT ->
            "Pêche interdite · reprise " +
                (if (reopensAt.toLocalDate() == now.toLocalDate()) "" else "demain ") + reopensAt.format(HOUR)
        FishingStatus.Reason.FERMETURE -> "Carnassier fermé · réouverture ${reopensAt.format(DATE_SHORT)}"
    }
}

/** Saison carnassier en cours ou prochaine, ex. « du samedi 6 juin 2026 au 31 janvier 2027 ». */
fun seasonText(now: ZonedDateTime): String {
    val d = now.toLocalDate()
    val start = if (d.monthValue == 1) CarnassierRules.firstSaturdayOfJune(d.year - 1)
    else CarnassierRules.firstSaturdayOfJune(d.year)
    val end = java.time.LocalDate.of(start.year + 1, 1, 31)
    return "du ${start.format(DATE_FR)} au ${end.format(DATE_FR)}"
}
