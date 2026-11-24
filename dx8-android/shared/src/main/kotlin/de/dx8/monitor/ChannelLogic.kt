package de.dx8.monitor

/** Was der Hauptknopf eines Kanals im aktuellen Zustand tut. */
enum class MainAct { START, HOLD, CONTINUE }

/** Bedienlogik ohne Android-Abhaengigkeit (unit-testbar). */
object ChannelLogic {
    /** Run-Code "Warten": Kanal wartet auf den Partner-Kanal (Entlade-Modus "Zu Ausgang", Akku zu Akku). */
    const val RUN_WAITING = 39

    /** Run-Code waehrend der RI-Messung (am Lader: Status-Taste 2 s, dauert etwa 1 s). */
    const val RUN_IR = 43

    /**
     * Neue RI-Messung erkannt: Kanal steht (Bereit oder RI-Messung) und die Innenwiderstaende haben sich geaendert.
     * Die Messung ist so kurz, dass die Abfrage den Status 43 oft verpasst – die neuen Werte bleiben aber stehen.
     */
    fun newIrResult(prev: ChannelData?, c: ChannelData): Boolean =
        prev != null && c.run in listOf(0, RUN_IR) && c.ir.any { it > 0 } &&
                (c.ir != prev.ir || c.irTotal != prev.irTotal || c.irLine != prev.irLine)

    /** Zustand des Leitungswiderstands (Ladekabel + Stecker), Register in 0,1 mΩ. */
    enum class LineState { NONE, GOOD, WARN, BAD }
    const val LINE_GOOD_MAX = 40.0     // bis 40 mΩ: gut (gemessen 34–37 mΩ mit den Standardkabeln)
    const val LINE_WARN_MAX = 60.0     // bis 60 mΩ: erhoeht – Stecker/Kabel pruefen; darueber: schlecht
    fun lineState(irLineRaw: Int): LineState {
        val mOhm = irLineRaw / 10.0
        return when {
            irLineRaw <= 0 -> LineState.NONE
            mOhm <= LINE_GOOD_MAX -> LineState.GOOD
            mOhm <= LINE_WARN_MAX -> LineState.WARN
            else -> LineState.BAD
        }
    }

    /**
     * Zustand des Akku-Innenwiderstands, bewertet je Zelle (gesamt / Zellenzahl), damit 2S und 6S vergleichbar sind.
     * Bis 10 mΩ/Zelle gut (HeatRC 3S 1800: ca. 8 mΩ), bis 20 mΩ/Zelle erhoeht, darueber gealtert.
     */
    const val IR_CELL_GOOD_MAX = 10.0
    const val IR_CELL_WARN_MAX = 20.0
    fun packIrState(irTotalRaw: Int, cells: Int): LineState {
        if (irTotalRaw <= 0 || cells <= 0) return LineState.NONE
        val perCell = irTotalRaw / 10.0 / cells
        return when {
            perCell <= IR_CELL_GOOD_MAX -> LineState.GOOD
            perCell <= IR_CELL_WARN_MAX -> LineState.WARN
            else -> LineState.BAD
        }
    }

    /** Kurzzeichen hinter einem bewerteten Widerstand: ✓ gut, ! erhoeht, ✗ schlecht. */
    fun stateTag(st: LineState) = when (st) {
        LineState.GOOD -> " ✓"; LineState.WARN -> " !"; LineState.BAD -> " ✗"; LineState.NONE -> ""
    }

    /**
     * Ziel-Zellspannung des laufenden Programms je Betriebsart (Laden 0 / Storage 1 / Entladen 2 / Zyklus 3 /
     * Balancen 4), z. B. "4.200 V". null, wenn Programm unbekannt oder kein Li-Typ (keine Zellspannung).
     */
    fun cellTarget(op: Int, p: Program?): String? {
        p ?: return null
        fun v(x: Double?) = x?.takeIf { it > 0.0 }?.let { "%.3f".format(it) }
        return when (op) {
            0, 4 -> v(p.chargeV)?.let { "$it V" }
            1 -> v(p.storageV)?.let { "$it V" }
            2 -> v(p.dischargeV)?.let { "$it V" }
            3 -> { val c = v(p.chargeV); val d = v(p.dischargeV); if (c != null && d != null) "$c / $d V" else null }
            else -> null
        }
    }

    /** Kopf der Zellanzeige: "3S  Δ 4 mV  Ziel 4.200 V" (ohne Zellen: Hinweis, ggf. Zielwert). */
    fun cellsHeader(c: ChannelData, target: String?): String {
        if (c.cells.isEmpty()) return "keine Zellwerte\n(Balancer nicht verbunden)" + (target?.let { "\nZiel $it" } ?: "")
        val delta = c.cells.maxOrNull()!! - c.cells.minOrNull()!!
        return "%dS  Δ %d mV%s".format(c.cells.size, delta, target?.let { "  Ziel $it" } ?: "")
    }

    /**
     * Zeile ueber den Zellen: links Leitungswiderstand, rechts Akku-IR gesamt – steht genau ueber der
     * IR-Spalte der Zellen. null ohne Zellwerte.
     */
    data class IrHead(val line: String, val lineState: LineState, val total: String, val totalState: LineState)

    fun irHead(c: ChannelData): IrHead? {
        if (c.cells.isEmpty()) return null
        val ls = lineState(c.irLine); val ts = packIrState(c.irTotal, c.cells.size)
        return IrHead("Ltg %.1f mΩ%s".format(c.irLine / 10.0, stateTag(ls)), ls,
            "IR %.1f mΩ%s".format(c.irTotal / 10.0, stateTag(ts)), ts)
    }

    /** Untere/obere Grenze des Zellbalkens (V): leer bei 3,0 V, voll bei 4,2 V (oder beim hoeheren Zielwert). */
    const val BAR_MIN_MV = 3000
    const val BAR_MAX_MV = 4200

    /** Eine Zellzeile: links Nummer + Spannung, Balken (Fuellgrad 0..1), Balancer-Symbol, rechts IR (bewertet). */
    data class CellRow(val label: String, val fill: Float, val balancing: Boolean, val ir: String, val irState: LineState)

    fun cellRows(c: ChannelData, maxMv: Int = BAR_MAX_MV): List<CellRow> = c.cells.mapIndexed { i, v ->
        val top = maxOf(maxMv, BAR_MIN_MV + 100)
        val irRaw = c.ir.getOrElse(i) { 0 }
        val st = packIrState(irRaw, 1)
        CellRow(
            label = "%d %.3f".format(i + 1, v / 1000.0),
            fill = ((v - BAR_MIN_MV).toFloat() / (top - BAR_MIN_MV)).coerceIn(0f, 1f),
            balancing = c.balance.getOrElse(i) { 0 } != 0,
            ir = if (irRaw > 0) "%.1f mΩ%s".format(irRaw / 10.0, stateTag(st)) else "",
            irState = st
        )
    }

    /** z. B. "7.5 / 7.9 / 7.9 mΩ, gesamt 23.3 mΩ, Leitung 37.0 mΩ" */
    fun irSummary(c: ChannelData): String =
        c.ir.joinToString(" / ") { "%.1f".format(java.util.Locale.US, it / 10.0) } +
                " mΩ, gesamt %.1f mΩ, Leitung %.1f mΩ".format(java.util.Locale.US, c.irTotal / 10.0, c.irLine / 10.0)

    /** Erklaerung, wenn der Lader einen Start ueber USB sofort mit Fehler ablehnt. */
    fun startRejectedHint(code: Int): String = when (code) {
        1305 -> "Der Lader nimmt einen Start über USB nur an, wenn sein Display die Hauptanzeige zeigt.\n\n" +
                "Bitte am Ladegerät mit Zurück/Exit zur Hauptanzeige wechseln – z. B. nach einer Rückfrage, " +
                "dem „Fertig“-Bildschirm oder aus einem Menü – und dann erneut starten.\n\n" +
                "Prüfen Sie auch, ob der Akku richtig angesteckt ist."
        3501 -> "Anderer Ausgang ist belegt.\n\nBei „Von Akku zu Akku“ muss zuerst der entladende Kanal laufen; " +
                "den ladenden Partner danach am Ladegerät starten."
        else -> (ERR_TEXT[code] ?: "Unbekannter Fehler") + "\n\nBitte am Ladegerät prüfen und dann erneut starten."
    }

    /** Status-Marke sichtbar? Bei "Warten" im Takt von 0,5 s an/aus, sonst immer an. */
    fun blinkOn(c: ChannelData?, nowMs: Long) = c?.run != RUN_WAITING || (nowMs / 500) % 2 == 0L

    /**
     * Laeuft der Kanal wirklich? Nicht nur nach Statusregister: am DX8 wurde beobachtet, dass ein Kanal
     * "Bereit" (0) meldet, aber weiter Strom liefert und die Laufzeit hochzaehlt.
     * [prevTs] = Laufzeit der vorigen Abfrage (null = unbekannt).
     */
    fun isRunning(c: ChannelData, prevTs: Long? = null): Boolean =
        c.isActive || c.currentA != 0.0 || (prevTs != null && c.ts != 0L && c.ts != prevTs)

    /**
     * Warnung "Anzeige stimmt nicht" erst, wenn Strom trotz Bereit/Halten laenger anhaelt.
     * Kurze Stromimpulse (z. B. RI-Messung, -0,10 A beim Uebergang auf Bereit) loesen nichts aus.
     * [since] = Zeitpunkt, seit dem der Widerspruch besteht (0 = keiner).
     */
    const val MISMATCH_MS = 3000L
    fun mismatchNow(c: ChannelData) = c.currentA != 0.0 && !c.isActive && c.run != RUN_IR
    fun mismatchConfirmed(since: Long, now: Long) = since != 0L && now - since >= MISMATCH_MS

    /** Status meldet Bereit/Halten, aber es fliesst Strom oder die Laufzeit laeuft: Anzeige stimmt nicht. */
    fun statusMismatch(c: ChannelData, prevTs: Long? = null) = !c.isActive && isRunning(c, prevTs)

    /**
     * Start auf Kanal k sperren, wenn der andere Kanal auf "Warten" steht (Akku-zu-Akku-Kopplung).
     * Ein Start ueber USB wirft den wartenden Kanal auf Halten und bringt den Lader in einen Zustand,
     * der sich nur durch Abstecken beenden liess (Test 22.11.2026). Liefert Grund oder null.
     */
    fun partnerWaitingReason(k: Int, chs: List<ChannelData?>): String? {
        val other = chs.getOrNull(1 - k) ?: return null
        return if (other.run == RUN_WAITING)
            "Kanal ${2 - k} wartet auf seinen Partner (Akku zu Akku). Kanal ${k + 1} bitte direkt am Ladegerät " +
                    "mit „Laden“ starten – einen Start über die App lehnt der Lader ab (Fehler 1305)."
        else null
    }

    /** Was tun, wenn Kanal k gestartet werden soll? */
    enum class PartnerCheck { OK, BLOCK, ASK }

    /**
     * "Warten" (39) meldet der Lader nicht nur bei Akku zu Akku. Daher:
     *  - anderer Kanal wartet nicht -> OK
     *  - Programm des wartenden Kanals bekannt (aus der App gestartet): Akku zu Akku -> BLOCK, sonst OK
     *  - unbekannt (am Lader gestartet) -> ASK (Rueckfrage mit "Trotzdem starten")
     */
    fun partnerCheck(k: Int, chs: List<ChannelData?>, otherIsAkkuZuAkku: Boolean?): PartnerCheck {
        val other = chs.getOrNull(1 - k) ?: return PartnerCheck.OK
        if (other.run != RUN_WAITING) return PartnerCheck.OK
        return when (otherIsAkkuZuAkku) {
            true -> PartnerCheck.BLOCK
            false -> PartnerCheck.OK
            null -> PartnerCheck.ASK
        }
    }

    /** Akku zu Akku: Entladen mit Entlade-Modus 2 ("Zu Ausgang") und Verbraucher 1 ("Von Akku zu Akku"). */
    fun isAkkuZuAkku(op: Int, raw: ByteArray): Boolean =
        op == 2 && raw.size > ProgramMemory.OFF_REG_CH_MODE &&
                ProgramMemory.get(raw, ProgramMemory.OFF_REGEN, 2) == 2L &&
                ProgramMemory.get(raw, ProgramMemory.OFF_REG_CH_MODE, 1) == 1L

    /**
     * Betriebsart/Platz fuer "Weiter": bevorzugt die beim Start dieses Kanals gemerkten Werte.
     * Sonst die Steuerregister nur, wenn sie zuletzt fuer diesen Kanal geschrieben wurden (0x8002 == k).
     * null = unbekannt -> nicht fortsetzen.
     */
    fun continueTarget(k: Int, remembered: Pair<Int, Int>?, ctl: IntArray?): Pair<Int, Int>? = when {
        remembered != null -> remembered
        ctl != null && ctl.size >= 3 && ctl[2] == k -> ctl[0] to ctl[1]
        else -> null
    }

    fun mainAct(c: ChannelData?) = when {
        c == null -> MainAct.START
        c.run == 1 && !c.noBattery -> MainAct.CONTINUE
        c.isActive -> MainAct.HOLD
        else -> MainAct.START
    }

    /** Hauptknopf gedimmt: Start ohne Akku bzw. Halten bei offener Rueckfrage. */
    fun mainDimmed(c: ChannelData?): Boolean {
        c ?: return false
        return when (mainAct(c)) {
            MainAct.START -> c.noBattery || c.uoutV > 36.0
            MainAct.HOLD -> c.run == 12
            MainAct.CONTINUE -> false
        }
    }

    /** "Start" ausblenden, solange die Programme noch nicht gelesen sind (Halten/Weiter bleiben sichtbar). */
    fun startHidden(act: MainAct, programsLoaded: Boolean, demo: Boolean) =
        act == MainAct.START && !programsLoaded && !demo

    /** Status-Feld der Kachel: bei bekanntem Fehler direkt dessen Text (z. B. "Balancer notwendig"), sonst Status. */
    fun badgeText(c: ChannelData): String =
        if (c.run == 41 && c.err != 0) ERR_TEXT[errCode(c.err)] ?: c.runText else c.runText

    /** Zusatzzeile nur bei unbekanntem Fehlercode ("Fehler 1234 – unbekannt"); bekannte stehen im Status-Feld. */
    fun errorLine(c: ChannelData): String? {
        if (c.run != 41 || c.err == 0) return null
        val code = errCode(c.err)
        return if (ERR_TEXT[code] != null) null else "Fehler $code – unbekannt"
    }

    /** Start gesperrt, solange ein Programm auf den Lader geschrieben wird (bis "geschrieben und geprueft"). */
    fun startBlocked(act: MainAct, savingProgram: Boolean) = act == MainAct.START && savingProgram
    const val SAVING_HINT = "Programm wird gespeichert – Start erst nach der Meldung „auf Lader geschrieben und geprüft“"

    /** Programme automatisch lesen: verbunden, noch keine gelesen, nicht schon dabei, kein Kanal laeuft. */
    fun shouldAutoLoadPrograms(connected: Boolean, programsLoaded: Boolean, loading: Boolean, chs: List<ChannelData?>) =
        connected && !programsLoaded && !loading && chs.all { it != null && !it.isActive && it.currentA == 0.0 }

    /** Stopp gedimmt: Kanal wirklich bereit (auch kein Strom), keine Daten oder Rueckfrage offen. */
    fun stopDimmed(c: ChannelData?, prevTs: Long? = null) =
        c == null || c.run == 12 || (c.run == 0 && !isRunning(c, prevTs))

    /**
     * Systemwerte/Eingangsquelle/Programme duerfen nur geaendert werden, wenn auf keinem Kanal ein
     * Ladevorgang laeuft (auch nicht angehalten/"Halten" oder mit offener Rueckfrage). Liefert Grund oder null.
     */
    /** Neues Ladeprogramm nur anlegen, wenn beide Kanaele auf Bereit stehen (nicht laufend, gehalten oder mit Rueckfrage). */
    fun newProgramLockReason(chs: List<ChannelData?>): String? = lockReason(chs)?.let {
        it.substringBefore(" – ") + " – neues Programm erst anlegen, wenn beide Kanäle auf Bereit stehen"
    }

    fun lockReason(chs: List<ChannelData?>): String? {
        for (k in 0..1) {
            val c = chs.getOrNull(k) ?: continue
            if (c.run !in listOf(0, 40, 41) || c.currentA != 0.0)
                return "Kanal ${k + 1}: ${c.runText} – Einstellungen erst ändern, wenn beide Kanäle auf Bereit stehen"
        }
        return null
    }
}
