package de.dx8.monitor

/**
 * Alles, was sich ausser den Ladeprogrammen vom DX8 lesen laesst:
 * Geraeteinfo (Input-Register 0x0000..0x000B) und Systemmenue (Holding-Register ab 0x8400).
 * Zuordnung des Systembereichs nach dem iCharger-X-Protokoll; beim DX8 ab Register 4 um eins verschoben.
 */
/** Eine Eingangsquelle (9 Register ab Systemoffset 24 + 9*q). */
data class InputSource(
    val lowV: Double, val maxA: Double, val powerW: Int,
    val regen: Boolean, val regenV: Double, val regenA: Double, val regenW: Int, val regenCap: Long
) {
    fun toRegs() = intArrayOf(
        Math.round(lowV * 10).toInt(), Math.round(maxA * 10).toInt(), powerW,
        if (regen) 1 else 0, Math.round(regenV * 10).toInt(), Math.round(regenA * 10).toInt(), regenW,
        (regenCap and 0xFFFF).toInt(), ((regenCap shr 16) and 0xFFFF).toInt()
    )

    /** Kurzwerte fuer die Quellen-Karten: 60.0 -> "60", 11.5 -> "11.5". */
    fun chips() = listOf("ab ${num(lowV)} V", "max ${num(maxA)} A", "$powerW W")
    fun regenLine() = if (regen) "Rückspeisung bis ${num(regenV)} V · ${num(regenA)} A · $regenW W" +
            (if (regenCap > 0) " · $regenCap mAh" else "") else "Rückspeisung aus"

    fun summary() ="ab %.1f V · max %.1f A · %d W · Rückspeisung %s".format(lowV, maxA, powerW,
        if (regen) "bis %.1f V / %.1f A / %d W".format(regenV, regenA, regenW) else "aus")

    /**
     * Plausibilitaetsgrenzen; liefert Fehlertext oder null.
     * Rueckspeise-Werte werden nur geprueft, wenn die Rueckspeisung aktiv ist (sonst unveraendert uebernommen).
     * Mindestwerte Rueckspeisung wie am DX8: 9 V, 1 A, 5 W.
     */
    fun validate(): String? = when {
        lowV !in LOW_V.first..LOW_V.second -> "Unterspannung muss zwischen ${range(LOW_V)} liegen"
        maxA !in MAX_A.first..MAX_A.second -> "Max. Strom muss zwischen ${range(MAX_A)} liegen"
        powerW.toDouble() !in POWER_W.first..POWER_W.second -> "Ladeleistung muss zwischen ${range(POWER_W)} liegen"
        !regen -> null
        regenV !in REGEN_V.first..REGEN_V.second -> "Rückspeise-Spannung muss zwischen ${range(REGEN_V)} liegen"
        regenA !in REGEN_A.first..REGEN_A.second -> "Rückspeise-Strom muss zwischen ${range(REGEN_A)} liegen"
        regenW.toDouble() !in REGEN_W.first..REGEN_W.second -> "Rückspeise-Leistung muss zwischen ${range(REGEN_W)} liegen"
        regenCap.toDouble() !in REGEN_CAP.first..REGEN_CAP.second -> "Rückspeise-Kapazität muss zwischen ${range(REGEN_CAP)} liegen"
        regenV <= lowV -> "Rückspeise-Spannung muss über der Unterspannung liegen"
        else -> null
    }

    companion object {
        const val OFFSET = 24
        const val REGEN_MIN_V = 9.0
        const val REGEN_MIN_A = 1.0
        const val REGEN_MIN_W = 5

        /** Erlaubte Bereiche (min, max) und Einheit – fuer Pruefung und Anzeige "min–max" im Dialog. */
        val LOW_V = Triple(5.0, 60.0, "V")
        val MAX_A = Triple(1.0, 80.0, "A")
        val POWER_W = Triple(5.0, 2000.0, "W")
        val REGEN_V = Triple(REGEN_MIN_V, 60.0, "V")
        val REGEN_A = Triple(REGEN_MIN_A, 40.0, "A")
        val REGEN_W = Triple(REGEN_MIN_W.toDouble(), 2000.0, "W")
        val REGEN_CAP = Triple(0.0, 999_900.0, "mAh")

        /** z. B. "5–2000 W", "9–60 V" */
        fun range(r: Triple<Double, Double, String>) = "${num(r.first)}–${num(r.second)} ${r.third}"
        fun num(v: Double): String =
            java.math.BigDecimal(v).setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        fun offset(q: Int) = OFFSET + 9 * q
        fun from(sys: IntArray, q: Int): InputSource {
            val b = offset(q)
            fun s(i: Int) = sys.getOrElse(b + i) { 0 }
            return InputSource(s(0) / 10.0, s(1) / 10.0, s(2), s(3) != 0, s(4) / 10.0, s(5) / 10.0, s(6),
                (s(8).toLong() shl 16) + s(7))
        }
    }
}

class ChargerInfo(val info: IntArray, val sys: IntArray) {

    val activeSource get() = sys.getOrElse(23) { 0 }
    /** Firmware wie am Display, z. B. 1107 -> 1.17 */
    val firmware get() = FirmwareCheck.Ver.fromCode(info.getOrElse(7) { 0 })
    fun source(q: Int) = InputSource.from(sys, q)

    companion object {
        fun read(d: Dx8Device): ChargerInfo {
            val info = d.read(4, 0, 12)
            val sysRegs = ((info[9] + 1) / 2).coerceIn(1, 120)
            return ChargerInfo(info, d.readMany(3, 0x8400, sysRegs))
        }

        /** Demo: Werte, wie sie am 21.11.2026 aus dem DX8 gelesen wurden. */
        fun demo(sysCsv: String): ChargerInfo {
            val info = intArrayOf(0x00A6, 0x3232, 0x3830, 0x3330, 0x3832, 0x3833, 0x0000, 0x0451, 0x03E8, 180, 192, 0x0002)
            val sys = sysCsv.trim().split(',').map { it.trim().toInt() }.toIntArray()
            return ChargerInfo(info, sys)
        }
    }

    private fun s(i: Int) = sys.getOrElse(i) { 0 }
    private fun f1(v: Int) = "%.1f".format(v / 10.0)
    private fun onOff(v: Int) = if (v != 0) "an" else "aus"

    /** Erlaubter Bereich (wie in den Bearbeiten-Dialogen) als Zusatz, z. B. "  (50–1100 W)". */
    private fun rng(reg: Int): String =
        SysSettings.ALL.flatMap { it.fields }.firstOrNull { it.reg == reg }?.rangeText()?.takeIf { it.isNotEmpty() }?.let { "  ($it)" } ?: ""

    /** Seriennummer: je Register erst Low-Byte, dann High-Byte (ASCII). */
    val serial: String get() = buildString {
        for (r in info.slice(1..6)) {
            for (b in intArrayOf(r and 0xFF, r shr 8)) if (b in 0x20..0x7E) append(b.toChar())
        }
    }

    private fun statusBits(w: Int): String {
        val names = listOf("läuft", "Fehler", "Steuerung", "Laufstatus", "Dialog", "Zellspannung", "Balancer")
        val set = names.filterIndexed { i, _ -> (w shr i) and 1 == 1 }
        return if (set.isEmpty()) "–" else set.joinToString(", ")
    }

    fun format(): String = buildString {
        fun line(k: String, v: String) = append("  %-24s %s\n".format(k, v))
        fun head(h: String) = append("\n$h\n")

        head("GERÄT")
        line("Modell", "Junsi iCharger DX8 (ID 0x%04X)".format(info[0]))
        line("Seriennummer", serial)
        line("Firmware", "$firmware  (%d, 0x%04X)".format(info[7], info[7]))
        line("Hardware", "%d (0x%04X)".format(info[8], info[8]))
        line("Statuswort", "0x%04X  %s".format(info[11], statusBits(info[11])))
        line("Speicher SYSTEM/MEMORY", "${info[9]} / ${info[10]} Byte")
        line("USB", "VID 0x%04X  PID 0x%04X".format(Dx8Device.VID, Dx8Device.PID))
        if (sys.size < 70) { append("\n  Systembereich unvollständig (${sys.size} Register)\n"); return@buildString }

        head("TEMPERATUR / LÜFTER")
        line("Einheit", if (s(0) == 0) "°C" else "°F")
        line("Abschalttemperatur", "${f1(s(1))} °C" + rng(1))
        line("Lüfter an ab", "${f1(s(2))} °C" + rng(2))
        line("Leistungsreduzierung", "${f1(s(3))} °C unter Abschaltung" + rng(3))
        line("Lüfter-Nachlauf", "${s(5)} min" + rng(5))

        head("DISPLAY / TÖNE")
        line("Kontrast", "${s(6)}" + rng(6))
        line("Helligkeit", "${s(7)}" + rng(7))
        line("Tonart", (8..11).joinToString(" / ") { "${s(it)}" } + "  (Taste/Hinweis/Alarm/Fertig)" + rng(8))
        line("Töne an", (12..15).joinToString(" / ") { onOff(s(it)) })
        line("Lautstärke", (16..19).joinToString(" / ") { "${s(it)}" } + rng(16))

        head("ALLGEMEIN")
        line("Sprache", when (s(20)) { 0 -> "Englisch"; 1 -> "Deutsch"; else -> "Code ${s(20)}" })
        line("Kalibrierung", if (s(21) == 0) "Werkskalibrierung" else "Benutzerkalibrierung")
        line("Parameterversion", "${s(22)}")
        line("Aktive Eingangsquelle", "Quelle ${s(23) + 1}")

        head("EINGANGSQUELLEN")
        for (q in 0..3) {
            val b = 24 + 9 * q
            val cap = (s(b + 8).toLong() shl 16) + s(b + 7)
            val mark = if (s(23) == q) "►" else " "
            append("  $mark Quelle ${q + 1}\n")
            line("   Unterspannung", "${f1(s(b))} V")
            line("   Max. Eingangsstrom", "${f1(s(b + 1))} A")
            line("   Max. Ladeleistung", "${s(b + 2)} W")
            line("   Rückspeisung", onOff(s(b + 3)) +
                    if (s(b + 3) != 0) "  bis ${f1(s(b + 4))} V, ${f1(s(b + 5))} A, ${s(b + 6)} W, Kap. ${if (cap == 0L) "ignor." else "$cap mAh"}" else "")
        }

        head("PRO KANAL                     K1 / K2")
        line("Leistungsgrenze", "${s(60)} / ${s(61)} W" + rng(60))
        line("Interne Entladeleistung", "${s(62)} / ${s(63)} W" + rng(62))
        line("Log-Intervall", "${f1(s(65))} / ${f1(s(66))} s" + rng(65))
        line("Log auf SD-Karte", "${onOff(s(67))} / ${onOff(s(68))}")

        // Servo-Tester vorlaeufig ausgeblendet
        head("UNBEKANNTE REGISTER (roh)")
        val unk = listOf(4, 64) + (73 until minOf(sys.size, 90))
        unk.filter { it < sys.size }.map { "R%-2d=%-6d".format(it, s(it)) }.chunked(4).forEach { append("  " + it.joinToString("  ") + "\n") }
        append("\n  (…) = erlaubter Bereich beim Ändern in der App\n")
    }
}
