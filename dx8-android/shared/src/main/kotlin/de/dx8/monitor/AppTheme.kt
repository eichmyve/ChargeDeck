package de.dx8.monitor

/** Farben als ARGB-Int (ohne android.graphics.Color, damit unit-testbar). */
fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
fun argb(a: Int, c: Int) = (a shl 24) or (c and 0xFFFFFF)

/**
 * Farbschema der App. Alle Views lesen ihre Farben aus [Ui.t].
 *  - DUNKEL: bisheriges Design
 *  - HELL:   angelehnt an rcmodelspot.com – hellgrauer Grund, weisse Karten mit runden Ecken,
 *            Blau #1770B8 fuer Ueberschriften/Hauptknopf, dunkle Leiste, dunkelgraue Schrift
 */
data class AppTheme(
    val id: String,
    val title: String,
    val description: String,
    val dark: Boolean,
    val page: Int,            // Seitenhintergrund
    val card: Int,            // Kartenflaeche
    val cardStroke: Int,      // Kartenrand (0 = keiner)
    val cardRadiusDp: Int,
    val text: Int,            // normale Schrift
    val textDim: Int,         // Zusatztext, Labels in Dialogen
    val textMuted: Int,       // inaktiv (z. B. stehende Stoppuhr)
    val heading: Int,         // Abschnittsueberschriften ("EINGANG / GERÄT")
    val accent: Int,          // Akzent: Verlauf-Symbol, Luefter, Spannungskurve
    val divider: Int,
    val chip: Int,            // kleine Knoepfe (Verlauf, Eingangsquelle)
    val chipStroke: Int,
    val bar: Int,             // untere Leiste
    val barText: Int,
    val barTextDim: Int,
    val barChip: Int,         // Knopf "Eingangsquelle" in der Leiste
    val barChipStroke: Int,
    val ok: Int,              // gruen (Laden, verbunden)
    val warn: Int,            // orange (Entladen, Hinweis)
    val error: Int,           // rot (Fehler, Stopp)
    val idle: Int,            // grau (Bereit, Halten)
    val busy: Int,            // sonstige aktive Zustaende
    val startFill: Int,       // Hauptknopf Start/Weiter
    val holdFill: Int,        // Hauptknopf Halten
    val onFill: Int,          // Schrift/Symbol auf dem Hauptknopf
    val ripple: Int,
    val gaugeTrack: Int,
    val gaugeValue: Int,
    val gaugeNoValue: Int,
    val gaugeLabel: Int,
    val chartGrid: Int,
    val chartText: Int,
    val series: List<Int>     // Kurvenfarben (Zellen 1..8; [0] Spannung, [1] Strom)
) {
    companion object {
        val DARK = AppTheme(
            id = "dark", title = "Dunkel", description = "Bisheriges Design: dunkle Flächen, helle Schrift",
            dark = true,
            page = rgb(18, 20, 24), card = rgb(30, 34, 40), cardStroke = 0, cardRadiusDp = 12,
            text = rgb(255, 255, 255), textDim = rgb(160, 170, 180), textMuted = rgb(130, 136, 144),
            heading = rgb(160, 170, 180), accent = rgb(100, 200, 255), divider = rgb(60, 68, 80),
            chip = rgb(58, 66, 78), chipStroke = rgb(110, 120, 135),
            bar = rgb(30, 34, 40), barText = rgb(255, 255, 255), barTextDim = rgb(170, 180, 190),
            barChip = rgb(58, 66, 78), barChipStroke = rgb(110, 120, 135),
            ok = rgb(90, 220, 120), warn = rgb(255, 170, 60), error = rgb(255, 90, 90),
            idle = rgb(170, 170, 170), busy = rgb(100, 200, 255),
            startFill = rgb(46, 125, 79), holdFill = rgb(178, 106, 26), onFill = rgb(255, 255, 255),
            ripple = argb(80, rgb(255, 255, 255)),
            gaugeTrack = rgb(55, 62, 72), gaugeValue = rgb(255, 255, 255), gaugeNoValue = rgb(140, 150, 160),
            gaugeLabel = rgb(170, 180, 190),
            chartGrid = rgb(60, 66, 74), chartText = rgb(170, 178, 188),
            series = listOf(rgb(100, 200, 255), rgb(255, 180, 70), rgb(90, 220, 120), rgb(230, 110, 230),
                rgb(255, 90, 90), rgb(220, 220, 90), rgb(120, 140, 255), rgb(200, 200, 200))
        )

        val LIGHT = AppTheme(
            id = "light", title = "Hell",
            description = "Hellgrauer Grund, weiße Karten, Blau als Akzentfarbe, dunkle Leiste",
            dark = false,
            page = rgb(0xF0, 0xF0, 0xF0), card = rgb(0xFF, 0xFF, 0xFF), cardStroke = rgb(0xE2, 0xE2, 0xE2), cardRadiusDp = 16,
            text = rgb(0x3A, 0x3A, 0x39), textDim = rgb(0x6E, 0x6E, 0x6E), textMuted = rgb(0xA0, 0xA0, 0xA0),
            heading = rgb(0x17, 0x70, 0xB8), accent = rgb(0x17, 0x70, 0xB8), divider = rgb(0xE6, 0xE6, 0xE6),
            chip = rgb(0xFF, 0xFF, 0xFF), chipStroke = rgb(0xD3, 0xD3, 0xD3),
            bar = rgb(0x3A, 0x3A, 0x39), barText = rgb(0xFF, 0xFF, 0xFF), barTextDim = rgb(0xC8, 0xC8, 0xC8),
            barChip = rgb(0x4A, 0x4A, 0x49), barChipStroke = rgb(0x6A, 0x6A, 0x69),
            ok = rgb(0x2E, 0x8B, 0x3E), warn = rgb(0xC7, 0x6A, 0x00), error = rgb(0xD1, 0x3A, 0x3A),
            idle = rgb(0x8A, 0x8A, 0x8A), busy = rgb(0x17, 0x70, 0xB8),
            startFill = rgb(0x17, 0x70, 0xB8), holdFill = rgb(0xC7, 0x6A, 0x00), onFill = rgb(0xFF, 0xFF, 0xFF),
            ripple = argb(60, rgb(0x17, 0x70, 0xB8)),
            gaugeTrack = rgb(0xE4, 0xE4, 0xE4), gaugeValue = rgb(0x3A, 0x3A, 0x39), gaugeNoValue = rgb(0xA0, 0xA0, 0xA0),
            gaugeLabel = rgb(0x6E, 0x6E, 0x6E),
            chartGrid = rgb(0xE0, 0xE0, 0xE0), chartText = rgb(0x6E, 0x6E, 0x6E),
            series = listOf(rgb(0x17, 0x70, 0xB8), rgb(0xD9, 0x7A, 0x00), rgb(0x2E, 0x8B, 0x3E), rgb(0x9C, 0x3F, 0xB0),
                rgb(0xD1, 0x3A, 0x3A), rgb(0x8A, 0x7A, 0x00), rgb(0x3F, 0x51, 0xB5), rgb(0x60, 0x60, 0x60))
        )

        val ALL = listOf(DARK, LIGHT)
        fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: DARK
    }

    /** Statusfarbe fuer den Run-Code eines Kanals. */
    fun stateColor(run: Int) = when (run) {
        41 -> error
        7, 10, 17, 40 -> ok
        11, 12, 13 -> warn
        0, 1 -> idle
        else -> busy
    }
}

/** Aktuelles Farbschema (wird beim Start und beim Umschalten gesetzt). */
object Ui {
    @Volatile var t: AppTheme = AppTheme.DARK
}
