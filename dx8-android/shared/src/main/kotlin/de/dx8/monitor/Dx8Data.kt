package de.dx8.monitor

fun s16(v: Int) = if (v >= 0x8000) v - 0x10000 else v
fun u32(lo: Int, hi: Int) = (hi.toLong() shl 16) + lo
fun s32(lo: Int, hi: Int): Long { val v = u32(lo, hi); return if (v >= 0x80000000L) v - 0x100000000L else v }

/** Bisher am DX8 beobachtete Run-Status-Codes. */
val RUN_TEXT = mapOf(
    0 to "Bereit", 1 to "Halten", 2 to "Start", 5 to "Startpruefung", 6 to "Vorbereitung",
    7 to "Laden", 10 to "Nachladen", 11 to "Entladen-Start", 12 to "Rueckfrage am Display",
    13 to "Entladen", 17 to "Balancieren", 39 to "Warten", 40 to "Fertig", 41 to "FEHLER", 43 to "RI-Messung",
    46 to "Power Output"    // Betriebsart Power Output (Lader als Netzteil am Ausgang)
)

/** Fehlercode wie am Display: High-Byte und Low-Byte dezimal (0x0D05 -> 1305). */
fun errCode(e: Int) = (e shr 8) * 100 + (e and 0xFF)

val ERR_TEXT = mapOf(
    1302 to "Verbindungsfehler",
    1305 to "Verbindungsfehler",
    1601 to "Zellspannung über Abschaltspannung",   // neu ab Firmware 1.18
    2001 to "Verbindungsunterbruch am Ausgang",
    2005 to "Verbindungsunterbruch am Ausgang bei Entladen",
    2006 to "Verbindungsunterbruch am Ausgang",
    2403 to "Balancerkabel",
    3000 to "Balancer notwendig",
    3501 to "Anderer Ausgang ist belegt",
    3100 to "Zellenzahl-Autoerkennung fehlgeschlagen - Zellenzahl einstellen"
)

data class ChannelData(
    val ts: Long, val powerW: Double, val currentA: Double, val uinV: Double, val uoutV: Double,
    val capMah: Long, val tInt: Double, val tExt: Double,
    val cells: List<Int>, val balance: List<Int>, val ir: List<Int>,
    val irTotal: Int, val irLine: Int, val ctrl: Int, val run: Int, val err: Int, val dialog: Int
) {
    /** Leerlauf > 36 V ohne Zellen und ohne Strom = kein Akku. */
    val noBattery get() = cells.isEmpty() && uoutV > 36.0 && currentA == 0.0
    val runText get() = if (noBattery && (run == 0 || run == 1)) "Akku n. verbunden" else RUN_TEXT[run] ?: "Code $run"
    /** Aktiv = Programm laeuft; nicht: Bereit (0), Halten (1), Fertig (40), Fehler (41). */
    val isActive get() = run !in listOf(0, 1, 40, 41)
    val isFinished get() = run == 40

    companion object {
        fun parse(g: IntArray): ChannelData {
            val balBytes = IntArray(8)
            for (k in 0 until 4) { balBytes[2 * k] = g[27 + k] shr 8; balBytes[2 * k + 1] = g[27 + k] and 0xFF }
            val cells = ArrayList<Int>(); val bal = ArrayList<Int>(); val ir = ArrayList<Int>()
            for (k in 0 until 8) {
                val v = g[11 + k]
                if (v in 1..4999) { cells += v; bal += balBytes[k]; ir += g[35 + k] }
            }
            return ChannelData(
                ts = u32(g[0], g[1]), powerW = s32(g[2], g[3]) / 100.0, currentA = s16(g[4]) / 100.0,
                uinV = g[5] / 1000.0, uoutV = g[6] / 1000.0, capMah = s32(g[7], g[8]),
                tInt = s16(g[9]) / 10.0, tExt = s16(g[10]) / 10.0,
                cells = cells, balance = bal, ir = ir, irTotal = g[51], irLine = g[52],
                ctrl = g[54], run = g[55], err = g[56], dialog = g[57]
            )
        }
    }
}

val BATTERY_TYPES = listOf("LiPo", "LiIo", "LiFe", "LiHV", "LTO", "NiMH", "NiCd", "NiZn", "Pb", "Power", "User")

data class Program(
    val pos: Int, val slot: Int, val name: String, val type: Int, val liCells: Int,
    val chargeA: Double, val dischargeA: Double,
    val chargeV: Double?, val storageV: Double?, val dischargeV: Double?, val regen: Int,
    /** Byte 0x35: Bit n = Betriebsart n am Lader "aktiv" (0 Laden, 1 Storage, 2 Entladen, 3 Zyklus, 4 Balancen). */
    val opMask: Int = 0xFF,
    /** Byte 0-1: 0x55AA = eigenes Programm, 0x0000 = fest eingebaut (BUILD-IN). */
    val useFlag: Int = 0x55AA,
    /** Vollstaendiges Speicherabbild (192 Byte) fuer Bearbeiten/Kopieren. */
    val raw: ByteArray = ByteArray(0)
) {
    val typeName get() = BATTERY_TYPES.getOrElse(type) { "Typ $type" }
    val isBuiltIn get() = useFlag == 0
    fun opEnabled(op: Int) = (opMask shr op) and 1 == 1
    override fun toString(): String {
        val cells = if (type <= 4) (if (liCells == 0) " Auto" else " ${liCells}S") else ""
        val r = if (regen != 0) " REGEN" else ""
        return "%02d  %s  (%s%s, %.1f A / %.1f A%s)".format(pos, name, typeName, cells, chargeA, dischargeA, r)
    }

    companion object {
        /** MEMORY-Block (192 Byte, Speicherabbild little-endian). */
        fun parse(pos: Int, slot: Int, m: ByteArray): Program {
            fun u8(o: Int) = m[o].toInt() and 0xFF
            fun u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
            val sb = StringBuilder()
            // erstes Zeichen ist bei am Lader umbenannten Programmen 00, das Display zeigt dort 'L'
            sb.append(if (u8(2) == 0) 'L' else u8(2).toChar())
            var k = 3
            while (k < 40 && u8(k) != 0) { sb.append(u8(k).toChar()); k++ }
            val t = u8(0x3D)
            val volts = voltOffsets(t)
            return Program(
                pos = pos, slot = slot, name = sb.toString(), type = t, liCells = u8(0x3E),
                chargeA = u16(0x62) / 100.0, dischargeA = u16(0x64) / 100.0,
                chargeV = volts?.let { u16(it[0]) / 1000.0 }, storageV = volts?.let { u16(it[1]) / 1000.0 },
                dischargeV = volts?.let { u16(it[2]) / 1000.0 }, regen = u16(0x6A),
                opMask = u8(0x35), useFlag = u16(0), raw = m.copyOf()
            )
        }

        /** Byte-Offsets Lade-/Storage-/Entlade-Zellspannung (mV) fuer Li-Typen, sonst null. */
        fun voltOffsets(type: Int): IntArray? = when (type) {
            0 -> intArrayOf(0x50, 0x56, 0x5C); 1 -> intArrayOf(0x52, 0x58, 0x5E); 2 -> intArrayOf(0x54, 0x5A, 0x60)
            3 -> intArrayOf(0xA6, 0xA8, 0xAA); 4 -> intArrayOf(0xAC, 0xAE, 0xB0); else -> null
        }

        fun regsToBytes(r: IntArray): ByteArray {
            val b = ByteArray(r.size * 2)
            for (i in r.indices) { b[2 * i] = (r[i] and 0xFF).toByte(); b[2 * i + 1] = (r[i] shr 8).toByte() }
            return b
        }

        /**
         * Liest alle Programme. Schreibt dafuer nur "Select MEMORY" (0x8001, RAM) und stellt es danach zurueck.
         */
        fun readAll(dev: Dx8Device): List<Program> {
            val memLen = dev.read(4, 0, 12)[10]
            val memRegs = (memLen + 1) / 2
            val order = ProgramMemory.readHead(dev).order
            val orig = dev.read(3, 0x8000, 7)[1]
            val out = ArrayList<Program>()
            try {
                order.forEachIndexed { pos, slot ->
                    if (slot >= 0xFE) return@forEachIndexed
                    dev.write(0x8001, intArrayOf(slot))
                    ProgramMemory.pause(200)
                    out += parse(pos, slot, regsToBytes(dev.readMany(3, 0x8C00, memRegs)))
                }
            } finally {
                dev.write(0x8001, intArrayOf(orig))
            }
            return out
        }
    }
}

val OPERATIONS = listOf("Laden" to 0, "Storage" to 1, "Entladen" to 2, "Zyklus" to 3, "Nur Balancen" to 4)
