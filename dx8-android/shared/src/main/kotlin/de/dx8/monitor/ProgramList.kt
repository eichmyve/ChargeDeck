package de.dx8.monitor

import java.math.BigDecimal
import java.math.RoundingMode

/** Aufbereitung der Programmliste (Kacheln): Gruppen, Filter, Kurztexte – ohne Android, unit-testbar. */
object ProgramList {
    /** Kuerzel der Betriebsarten in der Reihenfolge von OPERATIONS. */
    val OP_SHORT = listOf("L", "S", "E", "Z", "B")
    const val LEGEND = "L Laden · S Storage · E Entladen · Z Zyklus · B Nur Balancen – farbig = aktiv. " +
            "Strom nur gezeigt, wenn eine Betriebsart ihn nutzt."

    /** Ladestrom wird genutzt von Laden, Storage, Zyklus. */
    fun usesCharge(p: Program) = p.opEnabled(0) || p.opEnabled(1) || p.opEnabled(3)
    /** Entladestrom wird genutzt von Entladen, Storage, Zyklus. */
    fun usesDischarge(p: Program) = p.opEnabled(2) || p.opEnabled(1) || p.opEnabled(3)

    /** 12.0 -> "12 A", 2.5 -> "2.5 A", 1.25 -> "1.25 A". */
    fun amps(a: Double): String =
        BigDecimal(a).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " A"

    fun chargeText(p: Program) = if (usesCharge(p)) amps(p.chargeA) else "–"
    fun dischargeText(p: Program) = if (usesDischarge(p)) amps(p.dischargeA) else "–"

    /** Marke fuer regeneratives Entladen mit Ziel, z. B. "REGEN → Eingang"; null = normal entladen. */
    fun regenTag(p: Program): String? {
        val raw = p.raw
        val load = if (raw.size > ProgramMemory.OFF_REG_CH_MODE) ProgramMemory.get(raw, ProgramMemory.OFF_REG_CH_MODE, 1).toInt() else 0
        return when (p.regen) {
            0 -> null
            1 -> "REGEN → Eingang"
            2 -> if (load == 1) "REGEN → Akku (Akku zu Akku)" else "REGEN → Ausgang"
            else -> "REGEN (${p.regen})"
        }
    }

    /** Zweite Zeile der Typ-Plakette: Zellenzahl bei Li-Typen, sonst leer. */
    fun cellsLabel(p: Program) = if (p.type <= 4) (if (p.liCells == 0) "Auto" else "${p.liCells}S") else ""

    /** Farbe je Akkutyp als Index in AppTheme.series (gleiche Chemie = gleiche Farbe). */
    fun typeColorIndex(type: Int) = when (type) {
        0 -> 0          // LiPo
        1 -> 3          // LiIo
        2 -> 2          // LiFe
        3 -> 6          // LiHV
        4 -> 5          // LTO
        5, 6, 7 -> 1    // NiMH, NiCd, NiZn
        else -> 7       // Pb, Power, User
    }

    private fun match(p: Program, type: Int?) = type == null || p.type == type
    fun custom(list: List<Program>, type: Int? = null) = list.filter { !it.isBuiltIn && match(it, type) }
    fun builtIn(list: List<Program>, type: Int? = null) = list.filter { it.isBuiltIn && match(it, type) }

    /** Filter-Chips: Akkutypen der eigenen Programme, haeufigste zuerst (bei Gleichstand nach Typnummer). */
    fun typeCounts(list: List<Program>): List<Pair<Int, Int>> =
        custom(list).groupingBy { it.type }.eachCount().toList().sortedWith(compareBy({ -it.second }, { it.first }))
}
