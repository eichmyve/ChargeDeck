package de.dx8.monitor

import java.io.File

/**
 * Nachgebauter DX8 auf Ebene der 64-Byte-HID-Frames (Modbus-HID, Typ 0x30), nach Junsi-Protokoll V2.0:
 *  - Input-Register (fn 4) frei belegbar
 *  - Steuerregister 0x8000..0x8006 mit Order-Lock 0x55AA
 *  - System 0x8400, MEM_HEAD 0x8800, MEMORY 0x8C00: Lesen holt aus dem "Flash" (und frischt das RAM-Abbild auf),
 *    Schreiben aendert nur das RAM-Abbild; erst die Orders 3/4/5 speichern ins Flash.
 *  - Unbekannte Adressen -> Modbus-Exception 2
 */
class FakeDx8(val headRegs: Int = 33, val memRegs: Int = 96) : HidLink {
    val input = HashMap<Int, Int>()
    val ctrl = IntArray(7)
    val sysFlash = IntArray(90); val sysRam = IntArray(90)
    val headFlash = IntArray(headRegs); val headRam = IntArray(headRegs)
    val memFlash = Array(64) { IntArray(memRegs) }
    var memRam = IntArray(memRegs)

    val orders = ArrayList<Int>()
    val writes = ArrayList<Pair<Int, IntArray>>()
    var ignoreOrders = setOf<Int>()
    var failWriteAt: Int? = null
    var silent = false
    var noiseFrames = 0
    var closed = false
    private val queue = ArrayDeque<ByteArray>()

    // ------------------------------------------------------------ Testdaten
    fun setHead(slots: List<Int>) {
        val b = ByteArray(headRegs * 2) { 0xFF.toByte() }
        b[0] = slots.size.toByte(); b[1] = 0
        slots.forEachIndexed { i, s -> b[2 + i] = s.toByte() }
        ProgramMemory.bytesToRegs(b).copyInto(headFlash)
        headFlash.copyInto(headRam)
    }

    fun setMem(slot: Int, bytes: ByteArray) { ProgramMemory.bytesToRegs(bytes).copyInto(memFlash[slot]) }
    fun memBytes(slot: Int): ByteArray = Program.regsToBytes(memFlash[slot])

    /** Alle 20 echten Programme aus den App-Assets laden. */
    fun loadAssets(): List<Int> {
        val dir = File(TestData.ASSETS, "programs")
        val order = File(dir, "order.txt").readText().trim().split(',').map { it.trim().toInt() }
        for (s in order) setMem(s, File(dir, "mem_%02d.bin".format(s)).readBytes())
        setHead(order)
        input[10] = memRegs * 2                   // MEMORY length in Byte
        return order
    }

    /** Kanaldaten (Input-Register ab (k+1)*0x100). */
    fun setChannel(k: Int, regs: IntArray) { regs.forEachIndexed { i, v -> input[(k + 1) * 0x100 + i] = v } }

    // ------------------------------------------------------------ Register
    private fun readReg(fn: Int, a: Int): Int? = when {
        fn == 4 -> if (a in 0..0x2FF) input[a] ?: 0 else null
        a in 0x8000..0x8006 -> ctrl[a - 0x8000]
        a in 0x8400 until 0x8400 + sysFlash.size -> sysFlash[a - 0x8400].also { sysRam[a - 0x8400] = it }
        a in 0x8800 until 0x8800 + headRegs -> headFlash[a - 0x8800].also { headRam[a - 0x8800] = it }
        a in 0x8C00 until 0x8C00 + memRegs -> memRam[a - 0x8C00]
        else -> null
    }

    private fun writable(a: Int) = a in 0x8000..0x8006 || a in 0x8400 until 0x8400 + sysRam.size ||
            a in 0x8800 until 0x8800 + headRegs || a in 0x8C00 until 0x8C00 + memRegs

    private fun writeReg(a: Int, v: Int) {
        when {
            a in 0x8000..0x8006 -> {
                ctrl[a - 0x8000] = v
                if (a == 0x8001 && v in memFlash.indices) memRam = memFlash[v].copyOf()   // Programm waehlen
            }
            a in 0x8400 until 0x8400 + sysRam.size -> sysRam[a - 0x8400] = v
            a in 0x8800 until 0x8800 + headRegs -> headRam[a - 0x8800] = v
            else -> memRam[a - 0x8C00] = v
        }
    }

    private fun order(o: Int) {
        orders += o
        if (o in ignoreOrders) return
        when (o) {
            3 -> sysRam.copyInto(sysFlash)
            4 -> headRam.copyInto(headFlash)
            5 -> memRam.copyInto(memFlash[ctrl[1]])
        }
    }

    // ------------------------------------------------------------ HID
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun reply(vararg bytes: Int) {
        repeat(noiseFrames) { queue.add(ByteArray(64).also { it[0] = 8; it[1] = 0x10 }) }   // LogView-Frames
        val f = ByteArray(64)
        f[0] = (bytes.size + 2).toByte(); f[1] = 0x30
        bytes.forEachIndexed { i, v -> f[2 + i] = v.toByte() }
        queue.add(f)
    }

    override fun send(frame: ByteArray) {
        require(frame.size == 64) { "Frame muss 64 Byte haben" }
        require(u8(frame, 1) == 0x30)
        if (silent) return
        val fn = u8(frame, 2)
        val addr = (u8(frame, 3) shl 8) or u8(frame, 4)
        when (fn) {
            3, 4 -> {
                val qty = u8(frame, 6)
                val vals = (0 until qty).map { readReg(fn, addr + it) }
                if (vals.any { it == null }) { reply(fn or 0x80, 2); return }
                val out = IntArray(2 + 2 * qty)
                out[0] = fn; out[1] = 2 * qty
                vals.forEachIndexed { i, v -> out[2 + 2 * i] = v!! shr 8; out[3 + 2 * i] = v and 0xFF }
                reply(*out)
            }
            0x10 -> {
                val n = u8(frame, 6)
                val vals = IntArray(n) { (u8(frame, 8 + 2 * it) shl 8) or u8(frame, 9 + 2 * it) }
                if (addr == failWriteAt || (0 until n).any { !writable(addr + it) }) { reply(0x90, 4); return }
                writes += addr to vals
                vals.forEachIndexed { i, v -> writeReg(addr + i, v) }
                if (0x8004 in addr until addr + n && ctrl[3] == 0x55AA) order(ctrl[4])
                reply(0x10, addr shr 8, addr and 0xFF, 0, n)
            }
            else -> reply(fn or 0x80, 1)
        }
    }

    override fun receive(buf: ByteArray, timeoutMs: Int): Int {
        val f = queue.removeFirstOrNull() ?: return 0
        f.copyInto(buf)
        return 64
    }

    override fun close() { closed = true }
}
