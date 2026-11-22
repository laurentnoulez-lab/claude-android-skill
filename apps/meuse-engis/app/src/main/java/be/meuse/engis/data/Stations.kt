package be.meuse.engis.data

/**
 * Séries temporelles du service KiWIS du SPW (https://hydrometrie.wallonie.be).
 * Identifiants relevés via la requête getTimeseriesList (station_no 7132 et 7117).
 */
object Stations {
    const val KIWIS_URL = "https://hydrometrie.wallonie.be/services/KiWIS/KiWIS"

    /** AMAY (7132) – Meuse moyenne – "10-Debit ultrason.1h.Moyen" (m³/s). */
    const val AMAY_Q_1H = "245548010"

    /** AMAY (7132) – "80-Debit ultrason.InterAn.Mois.Moyen" : débit moyen interannuel du mois (m³/s). */
    const val AMAY_Q_NORMALE_MOIS = "245483010"

    /** IVOZ-RAMET Bief Amont (7117) – "10-Hauteur.1h.Moyen" (m). */
    const val IVOZ_H_1H = "245351010"

    /** IVOZ-RAMET Bief Amont (7117) – "10-Debit.1h.Moyen" – débit axe hydraulique (m³/s). */
    const val IVOZ_Q_1H = "408403010"

    val ALL = listOf(AMAY_Q_1H, AMAY_Q_NORMALE_MOIS, IVOZ_H_1H, IVOZ_Q_1H)
}
