package de.dx8.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.dx8.monitor.OPERATIONS
import de.dx8.monitor.Program
import de.dx8.monitor.ProgramMemory

/**
 * Arbeitsstand des Programm-Assistenten (gleiche Regeln wie [de.dx8.monitor.MainActivity.editProgram]):
 * Zahlenfelder behalten den gelesenen Rohwert, bis sie bewegt werden; [collect] prueft alles und
 * liefert das neue Speicherabbild samt Aenderungsliste.
 */
class ProgramDraft(val p: Program, val isNew: Boolean, private val existingNames: List<String>, val trialOver: Boolean = false) {
    companion object {
        val STEPS = listOf("Akku", "Laden", "Storage", "Entladen", "Zyklus", "Fertig")
        val HINTS = listOf(
            "Name, Zellenzahl und Kapazität",
            "Ladestrom, Ladeart und Ladeschluss",
            "Lagerspannung je Zelle",
            "Entladestrom, Entlade-Ende und Rückspeisung",
            "Ablauf, Anzahl und Pause",
            "Prüfen und auf den Lader schreiben")
        val PRO = listOf("", "Für Profis: Ladeendstrom, Erweitert, Sicherheit", "Für Profis: Kompensation, beschleunigte Storage",
            "Für Profis: Entlade-Endstrom", "", "")
    }

    private val PM = ProgramMemory
    /** Standardprogramme nur ansehen/kopieren; nach Ablauf der Testversion alles nur ansehen. */
    val readOnly = (!isNew && p.isBuiltIn) || trialOver

    /** Aktueller Schritt und aufgeklappte Profi-Bereiche je Schritt. */
    var step by mutableStateOf(0)
    val proOpen = mutableStateListOf(*Array(STEPS.size) { false })
    val isLi = p.type <= 4
    val fields = PM.fields(p.type)
    val vo = Program.voltOffsets(p.type)

    var name by mutableStateOf(if (isNew) (p.name + "_Kopie").take(PM.NAME_MAX) else p.name)
    var cells by mutableStateOf(p.liCells.coerceIn(0, 8))

    // Zahlenfelder (Rohwerte) und ob der Regler bewegt wurde
    val values = mutableStateListOf(*fields.map { it.raw(p.raw) }.toTypedArray())
    private val touched = BooleanArray(fields.size)
    fun setField(i: Int, raw: Long) { values[i] = raw; touched[i] = true }
    fun indexOf(off: Int) = fields.indexOfFirst { it.off == off }

    /** Arbeitskopie fuer die Lade-Untermenues (Balancer, Abschaltung, Erweitert, Sicherheit). */
    var work by mutableStateOf(p.raw.copyOf())
    var chargeMode by mutableStateOf(PM.chargeMode(p.raw))

    // Storage
    var stoComp by mutableStateOf(PM.STO_COMP.raw(p.raw))
    var fastSto by mutableStateOf(PM.get(p.raw, PM.OFF_FAST_STO, 1) != 0L)

    // Zyklus
    val cycleOld = PM.get(p.raw, PM.OFF_CYCLE_MODE, 1).toInt()
    var cycleIdx by mutableStateOf(if (cycleOld in PM.CYCLE_MODES.indices) cycleOld else PM.CYCLE_MODES.size)
    val cycleVals = mutableStateListOf(*PM.CYCLE_FIELDS.map { it.raw(p.raw) }.toTypedArray())

    // Betriebsarten ("am Lader anzeigen")
    val ops = mutableStateListOf(*OPERATIONS.map { p.opEnabled(it.second) }.toTypedArray())

    // Entlade-Modus und "Zu Ausgang"
    val regenOld = PM.get(p.raw, PM.OFF_REGEN, 2).toInt()
    var regenIdx by mutableStateOf(if (regenOld in PM.REGEN_MODES.indices) regenOld else PM.REGEN_MODES.size)
    val loadOld = PM.get(p.raw, PM.OFF_REG_CH_MODE, 1).toInt()
    var loadIdx by mutableStateOf(if (loadOld in PM.OUTPUT_LOADS.indices) loadOld else PM.OUTPUT_LOADS.size)
    private fun clamped(f: ProgramMemory.Field): Long {
        val r = f.raw(p.raw)
        return if (f.inRange(r)) r else f.slider().let { it.rawAt(it.posOf(r)) }
    }
    var outV by mutableStateOf(clamped(PM.OUT_VOLT))
    var outA by mutableStateOf(clamped(PM.OUT_CURR))

    // Ausgang und Speicher Optionen
    private fun inRangeOr(f: ProgramMemory.Field, raw: Long, fallback: Long) = if (f.inRange(raw)) raw else fallback
    var runCount by mutableStateOf(if (isNew) 0L else inRangeOr(PM.RUN_COUNT, PM.runCounter(p.raw).toLong(), 0L))
    var logInt by mutableStateOf(inRangeOr(PM.LOG_INTERVAL, PM.LOG_INTERVAL.raw(p.raw), PM.LOG_INTERVAL_FIX.toLong()))
    var autoSave by mutableStateOf(PM.autoSave(p.raw))
    var saveSd by mutableStateOf(PM.saveToSd(p.raw))

    // ---------------------------------------------------------------- abgeleitete Anzeigen
    val regen get() = PM.regenValue(regenIdx, regenOld)
    val load get() = PM.outputLoadValue(loadIdx, loadOld)
    val endMode get() = PM.get(work, PM.OFF_LI_BAL_END, 1).toInt()

    private fun perCell(i: Int): Double? {
        val off = vo?.getOrNull(i) ?: return null
        val fi = indexOf(off)
        return if (fi < 0) null else values[fi] / 1000.0
    }
    fun packText(): String = PM.packVoltageText(p.type, cells, perCell(0), perCell(1), perCell(2))

    fun rate(off: Int): String? {
        val iCap = indexOf(0x28); val i = indexOf(off)
        return if (iCap < 0 || i < 0) null else PM.cRate(values[i], values[iCap])
    }

    fun chargeInfo(): String = if (chargeMode == PM.MODE_NO_BAL) "ohne Balancer"
        else "Bal. Start: ${PM.balStartName(PM.get(work, PM.OFF_BAL_START, 1).toInt())}" +
            if (PM.balancerEditable(chargeMode)) "  ·  Differenz ${PM.BAL_DIFF.text(PM.BAL_DIFF.raw(work))}" else "  ·  übrige Werte gibt der Lader vor"

    fun endInfo(): String = if (PM.endModeEditable(chargeMode)) "Abschaltung: ${PM.endModeName(endMode)}"
        else "Abschaltung: nur Abschaltstrom (ohne Balancer)"

    /** Regler "Lade-Endstrom" aktiv? (ohne Balancer immer, sonst nur, wenn die Abschaltung den Strom nutzt) */
    fun endCurrentEnabled() = !readOnly && (!isLi || PM.endCurrentEditable(chargeMode, endMode))

    /** Untermenue uebernehmen: [edit] aendert eine Kopie der Arbeitsdaten. */
    fun editWork(edit: (ByteArray) -> Unit) { val w = work.copyOf(); edit(w); work = w }

    // ---------------------------------------------------------------- Pruefen und Abbild bauen
    sealed class Result {
        class Ok(val mem: ByteArray, val changes: List<String>, val name: String) : Result()
        class Error(val msg: String) : Result()
    }

    fun collect(): Result {
        val nm = work.copyOf()
        val changes = ArrayList<String>()
        if (isLi) {
            PM.setChargeMode(nm, chargeMode)
            changes.addAll(PM.chargeExtraChanges(p.raw, nm))
            PM.put(nm, PM.STO_COMP.off, PM.STO_COMP.size, stoComp)
            PM.put(nm, PM.OFF_FAST_STO, 1, if (fastSto) 1 else 0)
        }
        PM.put(nm, PM.OFF_CYCLE_MODE, 1, (if (cycleIdx in PM.CYCLE_MODES.indices) cycleIdx else cycleOld).toLong())
        PM.CYCLE_FIELDS.forEachIndexed { i, f -> PM.put(nm, f.off, f.size, cycleVals[i]) }
        changes.addAll(PM.storageCycleChanges(p.raw, nm))
        // Name
        val nmText = name.trim()
        PM.checkName(nmText)?.let { return Result.Error(it) }
        if (isNew || nmText != p.name) {
            PM.setName(nm, nmText)
            if (!isNew) changes += "Name:  ${p.name} → $nmText"
        }
        if (isNew && existingNames.any { it == nmText }) return Result.Error("Name „$nmText“ gibt es schon")
        // Zellen
        if (isLi) {
            if (cells != p.liCells) changes += "Zellenzahl:  ${PM.CELL_OPTIONS[p.liCells.coerceIn(0, 8)]} → ${PM.CELL_OPTIONS[cells]}"
            PM.put(nm, PM.OFF_CELLS, 1, cells.toLong())
        }
        // Zahlenfelder
        for ((i, f) in fields.withIndex()) {
            val raw = values[i]
            if (touched[i] && !f.inRange(raw)) return Result.Error("${f.label}: erlaubt ${f.range()}")
            if (raw != f.raw(p.raw)) changes += "${f.label}:  ${f.show(p.raw)} → ${f.fmt(raw)} ${f.unit}"
            PM.put(nm, f.off, f.size, raw)
        }
        // Plausibilitaet Li: Entlade-Ende < Storage < Ladeschluss
        vo?.let { v ->
            val c = PM.get(nm, v[0], 2); val s = PM.get(nm, v[1], 2); val d = PM.get(nm, v[2], 2)
            if (!(d < s && s < c)) return Result.Error("Spannungen: Entlade-Ende < Storage < Ladeschluss nötig")
        }
        // Betriebsarten (Bits 0-4, obere Bits unveraendert)
        val oldMask = PM.get(p.raw, PM.OFF_OPMASK, 1).toInt()
        var mask = oldMask and 0xE0
        OPERATIONS.forEachIndexed { i, (_, op) -> if (ops[i]) mask = mask or (1 shl op) }
        if (mask and 0x1F == 0) return Result.Error("Mindestens eine Betriebsart aktivieren")
        if (mask != oldMask) changes += "Betriebsarten:  " + OPERATIONS.filter { mask shr it.second and 1 == 1 }.joinToString(", ") { it.first }
        PM.put(nm, PM.OFF_OPMASK, 1, mask.toLong())
        // Entlade-Modus
        val newRegen = regen
        if (newRegen != regenOld) changes += "Entlade-Modus:  ${PM.regenName(regenOld)} → ${PM.regenName(newRegen)}"
        PM.put(nm, PM.OFF_REGEN, 2, newRegen.toLong())
        if (newRegen == 2) {
            val newLoad = load
            if (newLoad != loadOld) changes += "Ausgang verbinden:  ${PM.outputLoadName(loadOld)} → ${PM.outputLoadName(newLoad)}"
            PM.put(nm, PM.OFF_REG_CH_MODE, 1, newLoad.toLong())
            if (PM.outputLimitsEditable(newLoad)) for ((f, raw) in listOf(PM.OUT_VOLT to outV, PM.OUT_CURR to outA)) {
                if (!f.inRange(raw)) return Result.Error("${f.label}: erlaubt ${f.range()} – bitte Regler einstellen")
                if (raw != f.raw(p.raw)) changes += "${f.label}:  ${f.show(p.raw)} → ${f.fmt(raw)} ${f.unit}"
                PM.put(nm, f.off, f.size, raw)
            }
        }
        // Ausgang und Speicher Optionen
        PM.applyOutputOptions(nm, saveSd, autoSave, logInt)
        if (PM.channelModeName(nm) != PM.channelModeName(p.raw)) changes += "Ausgang Modus:  ${PM.channelModeName(p.raw)} → ${PM.channelModeName(nm)}"
        if (PM.logIntervalText(nm) != PM.logIntervalText(p.raw)) changes += "Log-Intervall:  ${PM.logIntervalText(p.raw)} → ${PM.logIntervalText(nm)}"
        if (PM.autoSaveName(nm) != PM.autoSaveName(p.raw)) changes += "Log nach Fertigstellung fortführen:  ${PM.autoSaveName(p.raw)} → ${PM.autoSaveName(nm)}"
        if (PM.saveToSd(nm) != PM.saveToSd(p.raw)) changes += "Daten auf SD speichern:  ${if (PM.saveToSd(p.raw)) "Ja" else "Nein"} → ${if (PM.saveToSd(nm)) "Ja" else "Nein"}"
        if (isNew) PM.put(nm, 0, 2, PM.USE_CUSTOM.toLong())      // eigenes Programm
        if (!isNew && runCount != PM.runCounter(p.raw).toLong()) changes += "Vorgangszähler:  ${PM.runCounter(p.raw)} → $runCount"
        PM.put(nm, PM.OFF_RUNCOUNT, 2, runCount)
        return Result.Ok(nm, changes, nmText)
    }
}
