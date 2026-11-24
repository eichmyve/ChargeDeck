package de.dx8.monitor

/** Bausteine fuer Kanaldaten (60 Input-Register wie vom DX8). */
object Samples {
    fun channelRegs(
        run: Int = 0, currentCa: Int = 0, uinMv: Int = 24000, uoutMv: Int = 12600,
        cells: List<Int> = listOf(4200, 4200, 4200), bal: List<Int> = listOf(0, 1, 0),
        ir: List<Int> = listOf(30, 31, 32), err: Int = 0, dialog: Int = 0, ts: Long = 125,
        powerCw: Long = 0, capMah: Long = 0, tInt: Int = 312, tExt: Int = -50
    ): IntArray {
        val g = IntArray(60)
        g[0] = (ts and 0xFFFF).toInt(); g[1] = (ts shr 16).toInt()
        g[2] = (powerCw and 0xFFFF).toInt(); g[3] = ((powerCw shr 16) and 0xFFFF).toInt()
        g[4] = currentCa and 0xFFFF
        g[5] = uinMv; g[6] = uoutMv
        g[7] = (capMah and 0xFFFF).toInt(); g[8] = ((capMah shr 16) and 0xFFFF).toInt()
        g[9] = tInt and 0xFFFF; g[10] = tExt and 0xFFFF
        cells.forEachIndexed { i, v -> g[11 + i] = v }
        bal.forEachIndexed { i, v -> val r = 27 + i / 2; g[r] = if (i % 2 == 0) g[r] or (v shl 8) else g[r] or v }
        ir.forEachIndexed { i, v -> g[35 + i] = v }
        g[51] = ir.sum(); g[52] = 12
        g[54] = 0; g[55] = run; g[56] = err; g[57] = dialog
        return g
    }

    fun channel(
        run: Int = 0, currentCa: Int = 0, uoutMv: Int = 12600, cells: List<Int> = listOf(4200, 4200, 4200)
    ) = ChannelData.parse(channelRegs(run = run, currentCa = currentCa, uoutMv = uoutMv, cells = cells,
        bal = cells.map { 0 }, ir = cells.map { 30 }))

    /** Kein Akku: Leerlaufspannung > 36 V, keine Zellen, kein Strom. */
    fun noBattery(run: Int = 0) = channel(run = run, uoutMv = 45000, cells = emptyList())
}
