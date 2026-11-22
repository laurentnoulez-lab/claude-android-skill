package be.wallonie.intemperies.export

import be.wallonie.intemperies.export.XlsxWriter.Style
import be.wallonie.intemperies.model.DayReport
import be.wallonie.intemperies.model.DayStatus
import be.wallonie.intemperies.model.MonthReport
import be.wallonie.intemperies.ui.Fmt
import be.wallonie.intemperies.ui.legal.HowItWorks
import be.wallonie.intemperies.ui.legal.RegulatoryBasis

/**
 * Classeur Excel : une feuille par commune (calendrier coloré + tableau journalier chiffré)
 * et une feuille « Base réglementaire ». Code Kotlin pur (testable sur JVM).
 */
object CalendarWorkbook {
    private const val LAST_COL = 14 // A..N

    fun build(reports: List<MonthReport>, generatedAt: String): ByteArray {
        require(reports.isNotEmpty())
        val xlsx = XlsxWriter()
        reports.forEach { addCommuneSheet(xlsx, it, generatedAt, multi = reports.size > 1) }
        addHowItWorksSheet(xlsx, reports.first())
        addRegulatorySheet(xlsx)
        return xlsx.toByteArray()
    }

    private fun addCommuneSheet(xlsx: XlsxWriter, r: MonthReport, generatedAt: String, multi: Boolean) {
        val sh = xlsx.sheet(if (multi) r.commune.name else "${r.commune.name} ${r.month}")
        sh.landscape = true
        listOf(19.0, 19.0, 19.0, 19.0, 19.0, 19.0, 19.0, 12.0, 11.0, 12.0, 12.0, 44.0, 11.0, 13.0)
            .forEachIndexed { i, w -> sh.width(i + 1, w) }

        var row = 1
        sh.text(row, 1, "CALENDRIER DES INTEMPÉRIES", Style.CAPTION)
        row++
        sh.text(row, 1, "${r.commune.name} — ${Fmt.month(r.month)}", Style.TITLE)
        sh.height(row, 24.0)
        row++
        sh.text(row, 1, "Province : ${r.commune.province} · INS ${r.commune.nis}", Style.DEFAULT)
        row++
        sh.text(row, 1, r.rainStation?.let {
            "Pluviomètre : ${it.station.name} (SPW n° ${it.station.stationNo}, ${Fmt.num(it.distanceKm)} km${if (it.manual) ", choix manuel" else ""})"
        } ?: "Pluviomètre : aucun", Style.DEFAULT)
        row++
        sh.text(row, 1, r.temperatureStation?.let {
            "Température de l'air : ${it.station.name} (SPW n° ${it.station.stationNo}, ${Fmt.num(it.distanceKm)} km${if (it.manual) ", choix manuel" else ""})"
        } ?: "Température de l'air : aucune station à moins de ${Fmt.num(r.criteria.maxTemperatureDistanceKm, 0)} km", Style.DEFAULT)
        row++
        sh.text(row, 1, Fmt.freshness(r), Style.DEFAULT)
        row++
        sh.text(row, 1, "Critères : " + Fmt.criteriaSummary(r.criteria), Style.BOX)
        sh.merge(row, 1, row, LAST_COL, Style.BOX)
        sh.height(row, 26.0)
        row++
        sh.text(
            row, 1,
            "Base réglementaire : ${RegulatoryBasis.SHORT_REF} — jour où le travail a été rendu impossible pendant quatre heures " +
                "au moins par suite de conditions météorologiques défavorables, sur reconnaissance de l'adjudicateur " +
                "(voir feuille « Base réglementaire »).",
            Style.BOX,
        )
        sh.merge(row, 1, row, LAST_COL, Style.BOX)
        sh.height(row, 26.0)
        row += 2

        // --- Calendrier (colonnes A à G) ----------------------------------------------------------
        val dayNames = listOf("Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche")
        dayNames.forEachIndexed { i, d -> sh.text(row, i + 1, d, Style.CAL_HEADER) }
        sh.height(row, 18.0)
        row++
        val offset = r.month.atDay(1).dayOfWeek.value - 1
        val cells: List<DayReport?> = List(offset) { null } + r.days
        cells.chunked(7).forEach { week ->
            for (i in 0 until 7) {
                val d = week.getOrNull(i)
                if (d == null) sh.text(row, i + 1, "", Style.CAL_EMPTY) else sh.set(row, i + 1, cellText(d), calStyle(d.status))
            }
            sh.height(row, 66.0)
            row++
        }
        row++

        // --- Légende ---------------------------------------------------------------------------------
        sh.text(row, 1, "Légende :", Style.BOLD)
        sh.text(row, 2, "Sec", Style.ST_DRY)
        sh.text(row, 3, "Précipitations (orange)", Style.ST_WARNING)
        sh.text(row, 4, "Intempérie", Style.ST_INTEMPERIE)
        sh.text(row, 5, "Sans données", Style.ST_NO_DATA)
        sh.text(row, 6, "« gel » / « chaud » : seuil de température atteint · * : précipitations non validées par le SPW", Style.DEFAULT)
        row += 2

        // --- Tableau journalier ------------------------------------------------------------------------
        val tableTitleRow = row
        sh.text(row, 1, "Détail journalier", Style.BOLD)
        row++
        val headers = listOf(
            "Date", "Jour", "Jour ouvrable", "Statut", "Précip. plage (mm)", "Durée précip. (h)", "Précip. consécutives max (h)",
            "Précip. 24 h (mm)", "T moy. plage (°C)", "Durée T < ${Fmt.deg(r.criteria.coldThresholdC)} (h)",
            "Durée T > ${Fmt.deg(r.criteria.heatThresholdC)} (h)", "Motif", "Données dispo.", "Validation SPW",
        )
        headers.forEachIndexed { i, h -> sh.text(row, i + 1, h, Style.HEADER) }
        sh.height(row, 30.0)
        row++
        val first = row
        for (d in r.days) {
            val known = d.status != DayStatus.FUTURE
            sh.date(row, 1, d.date)
            sh.text(row, 2, Fmt.dow(d.date))
            sh.text(row, 3, workingLabel(d))
            sh.text(row, 4, Fmt.status(d.status), statusStyle(d.status))
            if (known) {
                sh.num(row, 5, d.windowRainMm)
                sh.num(row, 6, d.rainHours)
                sh.num(row, 7, d.maxConsecutiveRainHours)
                sh.num(row, 8, d.dayRainMm)
                sh.num(row, 9, d.meanTemperatureC)
                if (d.coldHours != null) sh.num(row, 10, d.coldHours) else sh.text(row, 10, "—")
                if (d.heatHours != null) sh.num(row, 11, d.heatHours) else sh.text(row, 11, "—")
                sh.text(row, 12, d.reasons.joinToString(" + ") { Fmt.reason(it, r.criteria) })
                sh.num(row, 13, d.coverage, Style.PERCENT)
                sh.text(row, 14, if (d.provisional) "Provisoires *" else "Validées")
            } else {
                for (c in 5..LAST_COL) sh.text(row, c, "")
            }
            row++
        }
        val last = row - 1

        // --- Synthèse (formules : se recalculent si l'utilisateur corrige un statut) ---------------------
        row++
        val st = "\$D\$$first:\$D\$$last"
        val wk = "\$C\$$first:\$C\$$last"
        sh.text(row, 1, "Synthèse", Style.BOLD)
        row++
        val summary = listOf(
            "Jours d'intempérie" to "COUNTIF($st,\"Intempérie\")",
            "dont jours ouvrables" to "COUNTIFS($st,\"Intempérie\",$wk,\"Oui\")",
            "Jours de pluie (orange)" to "COUNTIF($st,\"${Fmt.status(DayStatus.WARNING)}\")",
            "Jours secs" to "COUNTIF($st,\"${Fmt.status(DayStatus.DRY)}\")",
            "Jours sans données" to "COUNTIF($st,\"${Fmt.status(DayStatus.NO_DATA)}\")",
            "Précipitations cumulées dans la plage (mm)" to "SUM(\$E\$$first:\$E\$$last)",
        )
        for ((label, f) in summary) {
            sh.text(row, 1, label, Style.TEXT)
            sh.merge(row, 1, row, 3, Style.TEXT)
            sh.formula(row, 4, f)
            row++
        }
        row++
        sh.text(row, 1, "Sources des données : SPW — hydrometrie.wallonie.be (réseau hydrométrique wallon). Données indicatives, sans valeur légale.", Style.SMALL)
        sh.merge(row, 1, row, LAST_COL)
        row++
        sh.text(row, 1, "* : contient des données non encore validées par le SPW (code qualité ≥ 200). Généré le $generatedAt — Intempéries Wallonie.", Style.SMALL)
        sh.merge(row, 1, row, LAST_COL)
        check(tableTitleRow > 0)
    }

    private fun addHowItWorksSheet(xlsx: XlsxWriter, r: MonthReport) {
        val sh = xlsx.sheet("Mode de fonctionnement")
        sh.width(1, 34.0)
        sh.width(2, 100.0)
        var row = 1
        sh.text(row, 1, "Mode de fonctionnement de l'application", Style.TITLE)
        sh.height(row, 24.0)
        row++
        sh.text(row, 1, "Paramètres en vigueur lors de l'export", Style.CAPTION)
        row += 2
        for (section in HowItWorks.sections(r.criteria, null)) {
            val text = section.lines.joinToString("\n") { "• $it" }
            sh.text(row, 1, section.title, Style.WRAP)
            sh.text(row, 2, text, Style.WRAP)
            sh.height(row, rowHeight(section.title to 36, text to 112))
            row++
        }
    }

    private fun addRegulatorySheet(xlsx: XlsxWriter) {
        val sh = xlsx.sheet("Base réglementaire")
        sh.width(1, 34.0)
        sh.width(2, 100.0)
        var row = 1
        sh.text(row, 1, "Jour d'intempérie — base réglementaire", Style.TITLE)
        sh.height(row, 24.0)
        row++
        sh.text(row, 1, RegulatoryBasis.SOURCE_DOC, Style.CAPTION)
        row += 2
        sh.text(row, 1, "TEXTES (citations exactes)", Style.BOLD)
        row++
        for (e in RegulatoryBasis.excerpts) {
            val a = "${e.title}\n${e.reference}"
            val b = "« ${e.quote} »"
            sh.text(row, 1, a, Style.WRAP)
            sh.text(row, 2, b, Style.WRAP)
            sh.height(row, rowHeight(a to 36, b to 112))
            row++
        }
        row++
        sh.text(row, 1, "LECTURE ET MISE EN ŒUVRE (interprétation)", Style.BOLD)
        row++
        for (n in RegulatoryBasis.notes) {
            sh.text(row, 1, n.title, Style.WRAP)
            sh.text(row, 2, n.text, Style.WRAP)
            sh.height(row, rowHeight(n.title to 36, n.text to 112))
            row++
        }
        row++
        sh.text(row, 1, RegulatoryBasis.VERIFY, Style.SMALL)
        sh.merge(row, 1, row, 2)
        sh.height(row, 24.0)
    }

    /** Hauteur de ligne estimée (pt) pour des textes renvoyés à la ligne : (texte, caractères par ligne). */
    private fun rowHeight(vararg cells: Pair<String, Int>): Double {
        val lines = cells.maxOf { (text, perLine) ->
            text.split('\n').sumOf { maxOf(1, (it.length + perLine - 1) / perLine) }
        }
        return 13.5 * lines + 4
    }

    private fun workingLabel(d: DayReport): String = when {
        d.holidayName != null -> "Férié : ${d.holidayName}"
        d.date.dayOfWeek.value == 6 -> "Samedi"
        d.date.dayOfWeek.value == 7 -> "Dimanche"
        else -> "Oui"
    }

    private fun cellText(d: DayReport): XlsxWriter.Value.Rich {
        val head = d.date.dayOfMonth.toString()
        val detail = buildString {
        if (d.holidayName != null) append("  férié")
        else if (d.isWeekend) append("  ").append(Fmt.dow(d.date).lowercase())
        if (d.provisional && d.status != DayStatus.FUTURE) append("  *")
        when (d.status) {
            DayStatus.FUTURE -> Unit
            DayStatus.NO_DATA -> append("\nSans données")
            else -> {
                append("\nPluie ").append(Fmt.hours(d.rainHours))
                append("\nConsec. ").append(Fmt.hours(d.maxConsecutiveRainHours))
                append("\n").append(Fmt.mm(d.windowRainMm))
                if (d.status == DayStatus.IN_PROGRESS) append(" · en cours")
            }
        }
        d.meanTemperatureC?.let {
            append("\nT ").append(Fmt.temp(it))
            if (d.coldReached == true) append(" gel")
            if (d.heatReached == true) append(" chaud")
        }
        }
        return XlsxWriter.Value.Rich(listOf(head to true, detail to false))
    }

    private fun calStyle(s: DayStatus) = when (s) {
        DayStatus.DRY -> Style.CAL_DRY
        DayStatus.WARNING -> Style.CAL_WARNING
        DayStatus.INTEMPERIE -> Style.CAL_INTEMPERIE
        DayStatus.NO_DATA -> Style.CAL_NO_DATA
        DayStatus.IN_PROGRESS, DayStatus.FUTURE -> Style.CAL_PENDING
    }

    private fun statusStyle(s: DayStatus) = when (s) {
        DayStatus.DRY -> Style.ST_DRY
        DayStatus.WARNING -> Style.ST_WARNING
        DayStatus.INTEMPERIE -> Style.ST_INTEMPERIE
        DayStatus.NO_DATA -> Style.ST_NO_DATA
        DayStatus.IN_PROGRESS, DayStatus.FUTURE -> Style.ST_PENDING
    }
}
