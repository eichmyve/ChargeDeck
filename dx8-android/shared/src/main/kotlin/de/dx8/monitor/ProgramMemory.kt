package de.dx8.monitor

/**
 * Programmspeicher des DX8 lesen/schreiben (Junsi MODBUS-Protokoll V2.0):
 *  - 0x8800 MEM_HEAD: u16 Count + u8 Index[] (Reihenfolge der Programmplaetze in der Liste)
 *  - 0x8C00 MEMORY:   Abbild des in 0x8001 gewaehlten Programmplatzes (192 Byte beim DX8)
 * Schreiben aendert nur das RAM-Abbild; dauerhaft erst per ORDER_WRITE_MEM (5) bzw. ORDER_WRITE_MEM_HEAD (4).
 * Lesen holt die Werte aus dem Flash -> Zuruecklesen nach dem Speichern ist eine echte Kontrolle.
 */
object ProgramMemory {
    const val USE_CUSTOM = 0x55AA
    const val NAME_MAX = 37

    /** Wartezeit zwischen Befehlen (in Tests abschaltbar). */
    var pause: (Long) -> Unit = { Thread.sleep(it) }

    // ---------------------------------------------------------------- Byte-Zugriff (little endian)
    fun get(b: ByteArray, off: Int, size: Int): Long {
        var v = 0L
        for (i in 0 until size) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    fun put(b: ByteArray, off: Int, size: Int, v: Long) {
        for (i in 0 until size) b[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    fun nameOf(b: ByteArray): String {
        val sb = StringBuilder()
        var k = 2
        while (k < 2 + NAME_MAX + 1 && b[k].toInt() != 0) { sb.append((b[k].toInt() and 0xFF).toChar()); k++ }
        return sb.toString()
    }

    fun setName(b: ByteArray, name: String) {
        for (k in 2 until 2 + NAME_MAX + 1) b[k] = 0
        name.take(NAME_MAX).forEachIndexed { i, c -> b[2 + i] = c.code.toByte() }
    }

    /** Leer = gueltig, sonst Fehlertext. Erlaubt nur druckbares ASCII (das Display kennt keine Umlaute). */
    fun checkName(name: String): String? = when {
        name.isBlank() -> "Name darf nicht leer sein"
        name.length > NAME_MAX -> "Name höchstens $NAME_MAX Zeichen"
        name.any { it.code !in 0x20..0x7E } -> "Name nur mit A–Z, 0–9 und einfachen Zeichen (keine Umlaute)"
        else -> null
    }

    fun bytesToRegs(b: ByteArray): IntArray =
        IntArray((b.size + 1) / 2) { i -> (b[2 * i].toInt() and 0xFF) or ((b.getOrElse(2 * i + 1) { 0 }.toInt() and 0xFF) shl 8) }

    // ---------------------------------------------------------------- Felder
    /** Schieberegler ueber Rohwerte: Position 0..steps, letzter Schritt landet immer genau auf max. */
    class SliderSpec(val minRaw: Long, val maxRaw: Long, val stepRaw: Long) {
        val steps: Int get() = ((maxRaw - minRaw + stepRaw - 1) / stepRaw).toInt()
        fun rawAt(pos: Int): Long = (minRaw + pos.coerceIn(0, steps) * stepRaw).coerceIn(minRaw, maxRaw)
        fun posOf(raw: Long): Int = (((raw.coerceIn(minRaw, maxRaw) - minRaw) + stepRaw / 2) / stepRaw).toInt().coerceIn(0, steps)
    }

    /** Bearbeitbares Zahlenfeld im Speicherabbild. Anzeige = Rohwert / scale; step = Reglerschritt in Anzeigeeinheit. */
    class Field(
        val label: String, val off: Int, val size: Int, val scale: Int,
        val unit: String, val min: Double, val max: Double, val decimals: Int, val step: Double = 0.0,
        /** Anzeige fuer Rohwert 0 (z. B. "Aus"), sonst Zahl. */
        val zeroText: String? = null
    ) {
        fun raw(b: ByteArray) = get(b, off, size)
        fun show(b: ByteArray) = fmt(raw(b))
        fun fmt(raw: Long) = if (raw == 0L && zeroText != null) zeroText
            else "%.${decimals}f".format(java.util.Locale.US, raw.toDouble() / scale)
        /** Wert mit Einheit, z. B. "45 °C" oder "Aus". */
        fun text(raw: Long) = if (raw == 0L && zeroText != null) zeroText else "${fmt(raw)} $unit".trim()
        fun range() = "%.${decimals}f–%.${decimals}f %s".format(java.util.Locale.US, min, max, unit).trim()
        fun slider() = SliderSpec(Math.round(min * scale), Math.round(max * scale),
            if (step > 0) maxOf(1L, Math.round(step * scale)) else 1L)
        fun inRange(raw: Long) = raw >= Math.round(min * scale) && raw <= Math.round(max * scale)
    }

    /** Zell-Spannungsgrenzen der App je Li-Typ (Laden, Storage, Entladen) – bewusst eng gefasst. */
    private val VOLT_RANGES = mapOf(
        0 to listOf(3.85 to 4.20, 3.70 to 3.90, 3.00 to 4.10),   // LiPo
        1 to listOf(3.75 to 4.10, 3.60 to 3.80, 2.50 to 4.00),   // LiIo
        2 to listOf(3.30 to 3.70, 3.10 to 3.40, 2.00 to 3.50),   // LiFe
        3 to listOf(3.90 to 4.35, 3.75 to 3.95, 3.00 to 4.20),   // LiHV
        4 to listOf(2.40 to 2.85, 2.20 to 2.60, 1.50 to 2.70)    // LTO
    )

    const val OFF_CELLS = 0x3E
    const val OFF_OPMASK = 0x35
    const val OFF_REGEN = 0x6A
    const val OFF_RUNCOUNT = 0x3B
    const val CAPACITY_MAX = 50000.0

    fun fields(type: Int): List<Field> {
        val f = ArrayList<Field>()
        // Kapazitaet fuer den Regler auf 0–50000 mAh begrenzt (der Lader erlaubt bis 999900)
        f += Field("Kapazität (0 = ignorieren)", 0x28, 4, 1, "mAh", 0.0, CAPACITY_MAX, 0, 50.0)
        f += Field("Ladestrom", 0x62, 2, 100, "A", 0.05, 30.0, 2, 0.05)
        f += Field("Lade-Endstrom", 0x66, 2, 1, "%", 1.0, 50.0, 0, 1.0)
        val vo = Program.voltOffsets(type)
        val vr = VOLT_RANGES[type]
        if (vo != null && vr != null) {
            f += Field("Ladeschluss je Zelle", vo[0], 2, 1000, "V", vr[0].first, vr[0].second, 3, 0.01)
            f += Field("Storage je Zelle", vo[1], 2, 1000, "V", vr[1].first, vr[1].second, 3, 0.01)
        }
        f += Field("Entladestrom", 0x64, 2, 100, "A", 0.05, 30.0, 2, 0.05)
        if (vo != null && vr != null)
            f += Field("Entlade-Ende je Zelle", vo[2], 2, 1000, "V", vr[2].first, vr[2].second, 3, 0.01)
        f += Field("Entlade-Endstrom", 0x68, 2, 1, "%", 1.0, 100.0, 0, 1.0)
        return f
    }

    /**
     * Entlade-Modus (RegDchgMode, u16 bei 0x6A), am DX8-Display bestaetigt (22.11.2026):
     * 0 = aus, 1 = "Zu Eingang" (zurueckspeisen), 2 = "Zu Ausgang" (Verbraucher am Ausgang, eigene Grenzen ab 0x99).
     */
    val REGEN_MODES = listOf("Aus (normal entladen)", "Zu Eingang (zurückspeisen)", "Zu Ausgang")

    /** "Zu Ausgang": Verbraucher (u8), Spannungsgrenze (u16 mV), Stromgrenze (u16, 0,01 A) – Werte von LiPo_2A: 0 / 12000 / 100. */
    const val OFF_REG_CH_MODE = 0x99
    const val OFF_REG_CH_VOLT = 0x9A
    const val OFF_REG_CH_CURR = 0x9C

    /**
     * "Ausgang verbinden": 0 = Widerstand / Lampe (am Display bestaetigt), 1 = von Akku zu Akku
     * (einzige weitere Auswahl am Display, Wert daraus abgeleitet). Bei Akku zu Akku sind die Grenzen am Lader nicht einstellbar.
     */
    val OUTPUT_LOADS = listOf("Widerstand / Lampe", "Von Akku zu Akku")
    fun outputLoadOptions(current: Int): List<String> =
        if (current in OUTPUT_LOADS.indices) OUTPUT_LOADS else OUTPUT_LOADS + "Wert $current (vom Lader)"
    fun outputLoadValue(index: Int, current: Int): Int = if (index in OUTPUT_LOADS.indices) index else current
    fun outputLoadName(v: Int) = OUTPUT_LOADS.getOrElse(v) { "Wert $v" }
    /** Grenzen nur bei Widerstand / Lampe einstellbar. */
    fun outputLimitsEditable(load: Int) = load == 0

    /** Spannungs- und Stromgrenze fuer "Zu Ausgang" (App-Grenzen). */
    val OUT_VOLT = Field("Spannungsgrenze", OFF_REG_CH_VOLT, 2, 1000, "V", 1.0, 36.0, 1, 0.1)
    val OUT_CURR = Field("Stromgrenze", OFF_REG_CH_CURR, 2, 100, "A", 0.1, 30.0, 1, 0.1)

    /** Auswahlliste fuer den Dialog; ein unbekannter gelesener Wert bleibt als eigener Eintrag waehlbar. */
    fun regenOptions(current: Int): List<String> =
        if (current in REGEN_MODES.indices) REGEN_MODES else REGEN_MODES + "Modus $current (vom Lader)"

    /** Listenposition -> Registerwert (letzter Eintrag ist ggf. der unbekannte Wert). */
    fun regenValue(index: Int, current: Int): Int = if (index in REGEN_MODES.indices) index else current

    fun regenName(v: Int) = REGEN_MODES.getOrElse(v) { "Modus $v" }

    /** Li-Typen (LiPo, LiIo, LiFe, LiHV, LTO): Zellenzahl 0 = Auto, 1..8. */
    val CELL_OPTIONS = listOf("Auto") + (1..8).map { "${it}S" }

    /** Nennspannung je Zelle nach Akkutyp (LiPo, LiIo, LiFe, LiHV, LTO). */
    val NOMINAL_V = mapOf(0 to 3.7, 1 to 3.6, 2 to 3.3, 3 to 3.85, 4 to 2.4)

    /**
     * Akkuspannung zur gewaehlten Zellenzahl, z. B. "3S: voll 12.60 V · nenn 11.10 V · Storage 11.55 V · leer 10.50 V".
     * Spannungen je Zelle aus dem Programm (Ladeschluss/Storage/Entlade-Ende); fehlende Werte werden weggelassen.
     */
    fun packVoltageText(type: Int, cells: Int, chargeV: Double?, storageV: Double?, dischargeV: Double?): String {
        if (cells <= 0) return "Auto: Zellenzahl erkennt der Lader beim Start"
        val parts = ArrayList<String>()
        fun add(label: String, v: Double?) { if (v != null && v > 0) parts += "$label %.2f V".format(java.util.Locale.US, v * cells) }
        add("voll", chargeV); add("nenn", NOMINAL_V[type]); add("Storage", storageV); add("leer", dischargeV)
        return "${cells}S: " + parts.joinToString(" · ")
    }

    // ---------------------------------------------------------------- Lade-Einstellung Li-Akkus (Menue M6 ff.)
    /*
     * Junsi MODBUS V2.1 struct MEMORY, am DX8-Display mit Programm 20 bestaetigt (23.11.2026):
     *  0x41 LiModeC (1 = ohne Balancer), 0x47 BalSpeed (0 langsam, 1 normal, 2 schnell, 3 modifiziert) = "Ladeart"
     *  0x48 BalStartMode (0 = CV, 1..10 = CV − 0,1..1,0 V, 11 = immer), 0x4B BalDiff, 0x4C BalOverPoint,
     *  0x4D BalSetPoint (mV), 0x4E BalDelay (min) – nur bei "Modifiziert" aenderbar, sonst gibt der Lader sie vor
     *  0x2D LiBalEndMode (Ladeendstrom → Ändern), 0x4F KeepChargeEnable ("Verzoegertes Nachladen")
     *  0x82/0x84/0x86 Reaktivierung bei Unterspannung (mV/Zelle, min, 10 mA)
     *  0x8D/0x8F/0x91 Lade-Sicherheit: Ladezeit-Vorgabe (min, 0 = Aus), max. Kapazitaet (%), Abschalttemp. (0,1 °C)
     */
    const val OFF_LI_MODE_C = 0x41
    const val OFF_BAL_SPEED = 0x47
    const val OFF_BAL_START = 0x48
    const val OFF_LI_BAL_END = 0x2D
    const val OFF_KEEP_CHARGE = 0x4F

    val CHARGE_MODES = listOf("Bal. Langsam", "Bal. Normal", "Bal. Schnell", "Modifiziert", "Ohne Bal.")
    const val MODE_MODIFIED = 3
    const val MODE_NO_BAL = 4

    /** Ladeart aus dem Speicher: ohne Balancer, sonst Balancer-Geschwindigkeit (unbekannte Werte bleiben als Zahl). */
    fun chargeMode(m: ByteArray): Int =
        if (get(m, OFF_LI_MODE_C, 1) != 0L) MODE_NO_BAL else get(m, OFF_BAL_SPEED, 1).toInt()
    fun chargeModeName(v: Int) = CHARGE_MODES.getOrElse(v) { "Wert $v" }
    fun chargeModeOptions(current: Int) = if (current in CHARGE_MODES.indices) CHARGE_MODES else CHARGE_MODES + "Wert $current (vom Lader)"
    fun chargeModeValue(index: Int, current: Int) = if (index in CHARGE_MODES.indices) index else current

    /** Ladeart schreiben; bei "Ohne Bal." bleibt die gespeicherte Balancer-Geschwindigkeit stehen. */
    fun setChargeMode(m: ByteArray, mode: Int) {
        if (mode == MODE_NO_BAL) { put(m, OFF_LI_MODE_C, 1, 1); return }
        put(m, OFF_LI_MODE_C, 1, 0)
        put(m, OFF_BAL_SPEED, 1, mode.toLong())
    }

    /** Balancer-Detailwerte nur bei "Modifiziert" aenderbar (sonst legt sie der Lader je Geschwindigkeit fest). */
    fun balancerEditable(mode: Int) = mode == MODE_MODIFIED
    /** "Ladeendstrom → Ändern" ist ohne Balancer inaktiv. */
    fun endModeEditable(mode: Int) = mode != MODE_NO_BAL
    /** Ladeendstrom-% zaehlt ohne Balancer immer, sonst nur, wenn die Abschaltbedingung den Strom nutzt. */
    fun endCurrentEditable(mode: Int, endMode: Int) = mode == MODE_NO_BAL || endMode != 0

    val BAL_START = listOf("CV") + (1..10).map { "CV − %.1f V".format(java.util.Locale.US, it / 10.0) } + "Immer"
    fun balStartName(v: Int) = BAL_START.getOrElse(v) { "Wert $v" }
    fun balStartOptions(current: Int) = if (current in BAL_START.indices) BAL_START else BAL_START + "Wert $current (vom Lader)"

    val END_MODES = listOf("Abschaltstrom AUS, Balancieren AN", "Abschaltstrom AN, Balancieren AUS",
        "Abschaltstrom ODER Balancer", "Abschaltstrom UND Balancer")
    fun endModeName(v: Int) = END_MODES.getOrElse(v) { "Wert $v" }
    fun endModeOptions(current: Int) = if (current in END_MODES.indices) END_MODES else END_MODES + "Wert $current (vom Lader)"

    // Balancer (Ladeart → Ändern), Grenzen der App
    val BAL_DIFF = Field("Bal. Differenz", 0x4B, 1, 1, "mV", 1.0, 10.0, 0, 1.0)
    val BAL_SET = Field("Bal. Sollwert", 0x4D, 1, 1, "mV", 1.0, 50.0, 0, 1.0)            // am Display: 1–50 mV
    val BAL_OVER = Field("Bal. Überladung", 0x4C, 1, 1, "mV", 0.0, 50.0, 0, 1.0)         // am Display: 0–50 mV
    val BAL_DELAY = Field("Bal. Verzögerung", 0x4E, 1, 1, "min", 0.0, 20.0, 0, 1.0)
    val BAL_FIELDS = listOf(BAL_DIFF, BAL_SET, BAL_OVER, BAL_DELAY)

    // Erweitert: Reaktivierung bei Unterspannung
    val RESTORE_V = Field("Kleinste Spannung je Zelle", 0x82, 2, 1000, "V", 0.5, 2.5, 1, 0.1)
    val RESTORE_T = Field("Reaktivierungsladezeit", 0x84, 2, 1, "min", 1.0, 10.0, 0, 1.0)
    val RESTORE_I = Field("Reaktivierungsladestrom", 0x86, 2, 100, "A", 0.05, 0.5, 2, 0.05)
    val RESTORE_FIELDS = listOf(RESTORE_V, RESTORE_T, RESTORE_I)

    // Sicherheit (Laden)
    val SAFE_TEMP = Field("Abschalttemperatur", 0x91, 2, 10, "°C", 20.0, 80.0, 0, 1.0)
    val SAFE_CAP = Field("Maximale Kapazität", 0x8F, 2, 1, "%", 50.0, 200.0, 0, 5.0)
    val SAFE_TIME = Field("Ladezeit Vorgabe", 0x8D, 2, 1, "min", 0.0, 720.0, 0, 10.0, zeroText = "Aus")
    val SAFETY_FIELDS = listOf(SAFE_TEMP, SAFE_CAP, SAFE_TIME)

    // Storage (Li): Kompensation (u16 mV/Zelle, 0x9F) und beschleunigte Storage (u8, 0x9E) – Programm 20: 10 / 0
    const val OFF_FAST_STO = 0x9E
    val STO_COMP = Field("Storage Kompensation je Zelle", 0x9F, 2, 1000, "V", 0.0, 0.2, 2, 0.01)

    // Zyklus: Modus (u8 0x8C), Zykluszahl (u16 0x88), Pausenzeit (u16 0x8A, min) – Programm 20: 0 / 3 / 3
    const val OFF_CYCLE_MODE = 0x8C
    val CYCLE_MODES = listOf("Laden > Entladen", "Entladen > Laden", "Laden > Entl. > Laden",
        "Entl. > Laden > Entl.", "Laden > Entl. > Storage", "Entl. > Laden > Storage")
    fun cycleModeName(v: Int) = CYCLE_MODES.getOrElse(v) { "Wert $v" }
    fun cycleModeOptions(current: Int) = if (current in CYCLE_MODES.indices) CYCLE_MODES else CYCLE_MODES + "Wert $current (vom Lader)"
    val CYCLE_COUNT = Field("Zykluszahl", 0x88, 2, 1, "", 1.0, 100.0, 0, 1.0)
    val CYCLE_DELAY = Field("Pausenzeit", 0x8A, 2, 1, "min", 0.0, 60.0, 0, 1.0)
    val CYCLE_FIELDS = listOf(CYCLE_COUNT, CYCLE_DELAY)

    /** Entladerate in C aus Strom (0,01 A) und Kapazitaet (mAh), z. B. "1.1 C"; null ohne Kapazitaet. */
    fun cRate(currentCa: Long, capacityMah: Long): String? =
        if (capacityMah <= 0 || currentCa <= 0) null else "%.1f C".format(java.util.Locale.US, currentCa * 10.0 / capacityMah)

    /** Alle Bytes der Untermenues (fuer die Kontrolle nach dem Schreiben). */
    val CHARGE_EXTRA_BYTES: List<Int> = (listOf(OFF_LI_MODE_C, OFF_BAL_SPEED, OFF_BAL_START, OFF_LI_BAL_END, OFF_KEEP_CHARGE,
        OFF_FAST_STO, OFF_CYCLE_MODE) +
        (BAL_FIELDS + RESTORE_FIELDS + SAFETY_FIELDS + STO_COMP + CYCLE_FIELDS).flatMap { f -> (0 until f.size).map { f.off + it } }).distinct().sorted()

    /** Aenderungen bei Storage und Zyklus als Text fuer die Rueckfrage. */
    fun storageCycleChanges(old: ByteArray, new: ByteArray): List<String> {
        val out = ArrayList<String>()
        val a = get(old, OFF_FAST_STO, 1) != 0L; val b = get(new, OFF_FAST_STO, 1) != 0L
        if (a != b) out += "Beschleunigte Storage:  ${if (a) "aktiv" else "inaktiv"} → ${if (b) "aktiv" else "inaktiv"}"
        val cm = get(old, OFF_CYCLE_MODE, 1).toInt(); val nm = get(new, OFF_CYCLE_MODE, 1).toInt()
        if (cm != nm) out += "Zyklus Modus:  ${cycleModeName(cm)} → ${cycleModeName(nm)}"
        for (f in listOf(STO_COMP) + CYCLE_FIELDS)
            if (f.raw(old) != f.raw(new)) out += "${f.label}:  ${f.text(f.raw(old))} → ${f.text(f.raw(new))}"
        return out
    }

    /** Aenderungen im Lade-Untermenue als Text fuer die Rueckfrage. */
    fun chargeExtraChanges(old: ByteArray, new: ByteArray): List<String> {
        val out = ArrayList<String>()
        val om = chargeMode(old); val nm = chargeMode(new)
        if (om != nm) out += "Ladeart:  ${chargeModeName(om)} → ${chargeModeName(nm)}"
        fun byte(off: Int) = get(old, off, 1).toInt() to get(new, off, 1).toInt()
        byte(OFF_BAL_START).let { (a, b) -> if (a != b) out += "Bal. Start:  ${balStartName(a)} → ${balStartName(b)}" }
        byte(OFF_LI_BAL_END).let { (a, b) -> if (a != b) out += "Ladeendstrom-Abschaltung:  ${endModeName(a)} → ${endModeName(b)}" }
        byte(OFF_KEEP_CHARGE).let { (a, b) -> if (a != b) out += "Verzögertes Nachladen:  ${if (a != 0) "aktiv" else "inaktiv"} → ${if (b != 0) "aktiv" else "inaktiv"}" }
        for (f in BAL_FIELDS + RESTORE_FIELDS + SAFETY_FIELDS)
            if (f.raw(old) != f.raw(new)) out += "${f.label}:  ${f.text(f.raw(old))} → ${f.text(f.raw(new))}"
        return out
    }

    // ---------------------------------------------------------------- Ausgang und Speicher Optionen (Menue M2)
    /**
     * Junsi MODBUS V2.1, struct MEMORY (am DX8 mit allen 21 Programmen gegengeprueft):
     * 0x2C AutoSave (M2_2, "Log nach Fertigstellung fortfuehren"), 0x37 ChannelMode (M2_1, 0 = asynchron),
     * 0x38 SaveToSD (M2_5), 0x39 LogInterval (M2_4, u16 in 0,1 s), 0x3B RunCounter (M2_3, u16).
     */
    const val OFF_AUTOSAVE = 0x2C
    const val OFF_CHANNEL_MODE = 0x37
    const val OFF_SAVE_SD = 0x38
    const val OFF_LOG_INTERVAL = 0x39
    const val LOG_INTERVAL_FIX = 10          // 1,0 s
    val OUTPUT_OPTION_BYTES = listOf(OFF_AUTOSAVE, OFF_CHANNEL_MODE, OFF_SAVE_SD, OFF_LOG_INTERVAL, OFF_LOG_INTERVAL + 1)

    /** Log-Intervall (u16 in 0,1 s) und Vorgangszaehler (u16) – im Assistenten aenderbar. */
    val LOG_INTERVAL = Field("Log-Intervall", OFF_LOG_INTERVAL, 2, 10, "s", 0.5, 60.0, 1, 0.5)
    val RUN_COUNT = Field("Vorgangszähler", OFF_RUNCOUNT, 2, 1, "", 0.0, 9999.0, 0, 1.0)

    /** Ausgang Modus fest asynchron; SD, Log nach Fertigstellung und Log-Intervall nach Wahl (Standard: wie bisher fest). */
    fun applyOutputOptions(mem: ByteArray, saveToSd: Boolean, autoSave: Boolean = false, logIntervalRaw: Long = LOG_INTERVAL_FIX.toLong()) {
        put(mem, OFF_CHANNEL_MODE, 1, 0)
        put(mem, OFF_AUTOSAVE, 1, if (autoSave) 1 else 0)
        put(mem, OFF_LOG_INTERVAL, 2, logIntervalRaw)
        put(mem, OFF_SAVE_SD, 1, if (saveToSd) 1 else 0)
    }

    fun autoSave(mem: ByteArray) = get(mem, OFF_AUTOSAVE, 1) != 0L

    /**
     * "Ausgang verbinden → Von Akku zu Akku". Am DX8 (FW 1.19, 23.11.2026) bestaetigt: funktioniert, wenn der
     * entladende Kanal zuerst laeuft (App) und der ladende Partner danach AM LADEGERAET gestartet wird.
     * Partner per USB -> Fehler 1305; zuerst laden, dann entladen -> Fehler 3501.
     */
    const val AKKU_ZU_AKKU = 1
    const val AKKU_ZU_AKKU_HINT = "So funktioniert „Von Akku zu Akku“:\n\n" +
            "1. Diesen Kanal in der App mit „Entladen“ starten – er wartet dann auf seinen Partner.\n" +
            "2. Den anderen Kanal direkt am Ladegerät mit „Laden“ starten.\n\n" +
            "Nicht umgekehrt: Zuerst laden und dann entladen ergibt Fehler 3501, " +
            "den Partner über die App starten ergibt Fehler 1305."

    fun runCounter(mem: ByteArray) = get(mem, OFF_RUNCOUNT, 2).toInt()
    fun saveToSd(mem: ByteArray) = get(mem, OFF_SAVE_SD, 1) != 0L
    fun channelModeName(mem: ByteArray) = if (get(mem, OFF_CHANNEL_MODE, 1) == 0L) "Asynchron" else "Synchron"
    fun logIntervalText(mem: ByteArray) = "%.1f s".format(java.util.Locale.US, get(mem, OFF_LOG_INTERVAL, 2) / 10.0)
    fun autoSaveName(mem: ByteArray) = if (get(mem, OFF_AUTOSAVE, 1) != 0L) "An" else "Aus"

    // ---------------------------------------------------------------- Lesen / Schreiben
    class Head(val count: Int, val index: IntArray, val regs: IntArray) {
        val capacity get() = index.size
        val order get() = (0 until count).map { index[it] }
        fun usedSlots() = order.filter { it < 0xFE }.toSet()
    }

    /** MEM_HEAD lesen: DX8 kennt bis zu 64 Plaetze (33 Register), aeltere Firmware evtl. nur 32 (17 Register). */
    fun readHead(dev: Dx8Device): Head {
        val regs = try { dev.readMany(3, 0x8800, 33) } catch (_: Dx8Exception) { dev.read(3, 0x8800, 17) }
        val b = Program.regsToBytes(regs)
        val count = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
        val idx = IntArray(b.size - 2) { b[2 + it].toInt() and 0xFF }
        return Head(count, idx, regs)
    }

    /** Kleinster Programmplatz, der in der Liste nicht vorkommt. */
    fun freeSlot(h: Head): Int? = (0 until h.capacity).firstOrNull { it !in h.usedSlots() }

    /**
     * Programmplatz schreiben und dauerhaft speichern; liefert das zurueckgelesene Abbild.
     * Die Programmwahl (0x8001) wird danach wieder auf den alten Wert gestellt.
     */
    fun writeMemory(dev: Dx8Device, slot: Int, mem: ByteArray): ByteArray {
        val regs = bytesToRegs(mem)
        val orig = dev.read(3, 0x8000, 2)[1]
        try {
            dev.write(0x8001, intArrayOf(slot))
            pause(200)
            var o = 0
            while (o < regs.size) {
                val n = minOf(28, regs.size - o)
                dev.write(0x8C00 + o, regs.copyOfRange(o, o + n))
                o += n
            }
            dev.saveMemory()
            pause(800)
            dev.write(0x8001, intArrayOf(slot))      // neu waehlen -> Lesen kommt aus dem Flash
            pause(200)
            return Program.regsToBytes(dev.readMany(3, 0x8C00, regs.size)).copyOf(mem.size)
        } finally {
            dev.write(0x8001, intArrayOf(orig))
        }
    }

    /** Programmplatz hinten an die Liste anhaengen und die Liste dauerhaft speichern. */
    fun appendToHead(dev: Dx8Device, slot: Int): Head {
        val h = readHead(dev)
        if (slot in h.usedSlots()) return h
        if (h.count >= h.capacity) throw Dx8Exception("Programmliste voll (${h.capacity})")
        val b = Program.regsToBytes(h.regs)
        val n = h.count + 1
        b[0] = (n and 0xFF).toByte(); b[1] = (n shr 8).toByte()
        b[2 + h.count] = slot.toByte()
        val regs = bytesToRegs(b)
        val upto = 1 + (n + 1) / 2                    // nur Register bis einschliesslich des neuen Eintrags
        dev.write(0x8800, regs.copyOfRange(0, upto))
        dev.saveMemHead()
        pause(800)
        val back = readHead(dev)
        if (back.count != n || back.index[h.count] != slot)
            throw Dx8Exception("Programmliste nicht übernommen (Anzahl ${back.count})")
        return back
    }
}
