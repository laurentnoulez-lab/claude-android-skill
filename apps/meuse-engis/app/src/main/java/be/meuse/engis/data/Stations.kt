package be.meuse.engis.data

/** Service KiWIS du SPW (https://hydrometrie.wallonie.be) – accès anonyme. */
const val KIWIS_URL = "https://hydrometrie.wallonie.be/services/KiWIS/KiWIS"

/**
 * Séries temporelles utilisées. L'identifiant [defaultId] a été relevé le 22/11/2026 via getTimeseriesList ;
 * s'il devient invalide, l'application retrouve le nouvel identifiant à partir de [stationNo] et [tsNames].
 */
enum class Series(val stationNo: String, val tsNames: List<String>, val defaultId: String) {
    /** AMAY (7132) – débit horaire moyen (m³/s). */
    AMAY_Q_1H("7132", names("10-Debit ultrason.1h.Moyen"), "245548010"),

    /** AMAY – débit moyen interannuel du mois (m³/s). */
    AMAY_Q_NORMALE_MOIS("7132", names("80-Debit ultrason.InterAn.Mois.Moyen"), "245483010"),


    /** IVOZ-RAMET Bief Amont (7117) – hauteur horaire moyenne (m). */
    IVOZ_H_1H("7117", listOf("10-Hauteur.1h.Moyen"), "245351010"),

    /** IVOZ-RAMET Bief Amont – débit "axe hydraulique" horaire moyen (m³/s). */
    IVOZ_Q_1H("7117", listOf("10-Debit.1h.Moyen"), "408403010"),
}

/** Nom de série + variante sans « ultrason » (au cas où le SPW changerait de capteur à Amay). */
private fun names(name: String) = listOf(name, name.replace("Debit ultrason", "Debit"))
