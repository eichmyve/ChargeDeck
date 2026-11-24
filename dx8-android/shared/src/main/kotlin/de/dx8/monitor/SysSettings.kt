package de.dx8.monitor

/**
 * Einstellbare Werte des Systembereichs (0x8400 + reg).
 * Grenzen bewusst eng um die ausgelesenen Werte gelegt, weil mehrere Zuordnungen nur vermutet sind (guess).
 */
class SysField(
    val reg: Int,
    val label: String,
    val unit: String = "",
    val scale: Int = 1,               // Registerwert = Anzeige * scale
    val min: Double = 0.0,
    val max: Double = 0.0,
    val options: List<String>? = null, // Auswahlliste (Registerwert = Index)
    val bool: Boolean = false,        // an/aus
    val guess: Boolean = false,
    val step: Double = 0.0            // Schrittweite Schieberegler (Anzeigeeinheit); 0 = kleinster Registerschritt
) {
    /** Schieberegler: Rohwert-Grenzen, Schritt (Rohwert) und Anzahl Schritte. */
    val minRaw get() = Math.round(min * scale).toInt()
    val maxRaw get() = Math.round(max * scale).toInt()
    val stepRaw get() = if (step > 0) maxOf(1, Math.round(step * scale).toInt()) else 1
    val steps get() = (maxRaw - minRaw) / stepRaw
    fun rawAt(pos: Int) = (minRaw + pos * stepRaw).coerceIn(minRaw, maxRaw)
    fun posOf(raw: Int) = ((raw.coerceIn(minRaw, maxRaw) - minRaw) + stepRaw / 2) / stepRaw

    fun show(raw: Int): String = when {
        bool -> if (raw != 0) "an" else "aus"
        options != null -> options.getOrElse(raw) { "Code $raw" }
        scale == 10 -> "%.1f %s".format(raw / 10.0, unit).trim()
        else -> "$raw $unit".trim()
    }

    /** Erlaubter Bereich als Text, z. B. "50–1100 W" oder "0.5–60.0 s"; leer bei an/aus und Auswahllisten. */
    fun rangeText(): String = when {
        bool || options != null -> ""
        scale == 10 -> "%.1f–%.1f %s".format(java.util.Locale.US, min, max, unit).trim()
        else -> "%.0f–%.0f %s".format(java.util.Locale.US, min, max, unit).trim()
    }
}

class SysGroup(val title: String, val fields: List<SysField>, val note: String = "", val check: (IntArray) -> String? = { null }) {
    val from get() = fields.minOf { it.reg }
    val to get() = fields.maxOf { it.reg }
}

object SysSettings {
    private val tone = listOf("Taste", "Hinweis", "Alarm", "Fertig")

    val TEMP = SysGroup("Temperatur / Lüfter", listOf(
        SysField(0, "Temperatureinheit", options = listOf("°C", "°F")),
        SysField(1, "Abschalttemperatur", "°C", 10, 60.0, 80.0, step = 0.5),
        SysField(2, "Lüfter an ab", "°C", 10, 25.0, 60.0, step = 0.5),
        SysField(3, "Leistungsreduzierung unter Abschaltung", "°C", 10, 5.0, 20.0, step = 0.5),
        SysField(5, "Lüfter-Nachlauf", "min", 1, 0.0, 10.0, guess = true)
    ), "Temperaturen werden intern immer in °C gespeichert.") { s ->
        if (s[2] >= s[1]) "Lüfter-Einschalttemperatur muss unter der Abschalttemperatur liegen"
        else if (s[1] - s[3] <= s[2]) "Leistungsreduzierung beginnt unter der Lüfter-Einschalttemperatur" else null
    }

    val DISPLAY = SysGroup("Display / Töne",
        listOf(
            SysField(6, "Kontrast", "", 1, 1.0, 31.0, guess = true),
            SysField(7, "Helligkeit", "", 1, 1.0, 32.0, guess = true)
        ) + tone.mapIndexed { i, t -> SysField(12 + i, "Ton $t", bool = true, guess = true) } +
            tone.mapIndexed { i, t -> SysField(16 + i, "Lautstärke $t", "", 1, 0.0, 10.0, guess = true) } +
            tone.mapIndexed { i, t -> SysField(8 + i, "Tonart $t", "", 1, 0.0, 5.0, guess = true) }
    )

    val CHANNEL = SysGroup("Pro Kanal", listOf(
        SysField(60, "Leistungsgrenze Kanal 1", "W", 1, 50.0, 1100.0, guess = true),
        SysField(61, "Leistungsgrenze Kanal 2", "W", 1, 50.0, 1100.0, guess = true),
        SysField(62, "Interne Entladeleistung Kanal 1", "W", 1, 5.0, 45.0, guess = true),
        SysField(63, "Interne Entladeleistung Kanal 2", "W", 1, 5.0, 45.0, guess = true),
        SysField(65, "Log-Intervall Kanal 1", "s", 10, 0.5, 60.0),
        SysField(66, "Log-Intervall Kanal 2", "s", 10, 0.5, 60.0),
        SysField(67, "Log auf SD-Karte Kanal 1", bool = true),
        SysField(68, "Log auf SD-Karte Kanal 2", bool = true)
    ))

    val SERVO = SysGroup("Servo-Tester", listOf(
        SysField(69, "Servotyp", options = listOf("1500 µs analog", "1500 µs digital", "760 µs digital", "benutzerdefiniert"), guess = true),
        SysField(70, "Impulsmitte", "µs", 10, 500.0, 2500.0, guess = true, step = 1.0),
        SysField(71, "Bildrate", "Hz", 1, 50.0, 400.0, guess = true),
        SysField(72, "45°-Impulsbreite", "µs", 10, 100.0, 1000.0, guess = true, step = 1.0)
    ))

    /** Bearbeitbare Gruppen in den Einstellungen (Servo-Tester vorlaeufig ausgeblendet, Definition bleibt). */
    val ALL = listOf(CHANNEL, TEMP, DISPLAY)
}
