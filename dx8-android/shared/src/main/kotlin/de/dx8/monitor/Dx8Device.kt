package de.dx8.monitor

class Dx8Exception(msg: String) : Exception(msg)

/**
 * Junsi iCharger DX8 ueber USB-HID (Modbus-HID, Frame-Typ 0x30).
 * Frame (64 Byte, ohne Report-ID): [Laenge, 0x30, Funktion, Daten...]
 * Register sind big-endian U16; 32-Bit-Werte: niederwertiges Wort zuerst.
 */
/** Transport fuer 64-Byte-HID-Frames (USB im Geraet, nachgebauter Lader in den Tests). */
interface HidLink {
    /** Sendet genau einen 64-Byte-Frame. */
    fun send(frame: ByteArray)
    /** Liest einen Frame in buf; liefert Anzahl Bytes oder <= 0 bei Timeout. */
    fun receive(buf: ByteArray, timeoutMs: Int): Int
    fun close()
}

class Dx8Device(private val link: HidLink) {
    private val lock = Any()

    /** USB-Kennung des DX8; das Oeffnen ist je System eigen (Android: UsbHidLink, Windows: hidapi). */
    companion object {
        const val VID = 0x0483
        const val PID = 0x5751
    }

    fun close() = link.close()

    /** Verbunden ueber die WLAN-Bruecke (statt USB)? */
    val viaBridge get() = link is TcpHidLink

    /**
     * Schutz Eingangsbatterie auf der WLAN-Bruecke setzen (laeuft dort auch ohne App).
     * Liefert (an, Grenze in V) wie von der Bruecke bestaetigt; null bei USB.
     */
    fun setBridgeGuard(enabled: Boolean, limitV: Double): Pair<Boolean, Double>? = synchronized(lock) {
        if (!viaBridge) return null
        drain()
        link.send(TcpHidLink.guardFrame(enabled, limitV))
        val buf = ByteArray(64)
        repeat(20) {
            if (link.receive(buf, 1500) <= 0) throw Dx8Exception("WLAN-Brücke: keine Antwort auf Schutz-Einstellung")
            if ((buf[0].toInt() and 0xFF) == 0xFF && buf[1] == 'G'.code.toByte())
                return Pair(buf[2].toInt() != 0, (u8(buf, 3) shl 8 or u8(buf, 4)) / 1000.0)
        }
        throw Dx8Exception("WLAN-Brücke: Schutz-Einstellung nicht bestätigt")
    }

    private fun send(frame: ByteArray) {
        val out = ByteArray(64)
        System.arraycopy(frame, 0, out, 0, frame.size)
        link.send(out)
    }

    /** Alte/unaufgeforderte Frames (z. B. LogView-Typ 0x10) verwerfen. */
    private fun drain() {
        val b = ByteArray(64)
        var guard = 0
        while (guard++ < 50 && link.receive(b, 5) > 0) { }
    }

    private fun xfer(frame: ByteArray, match: (ByteArray) -> Boolean): ByteArray {
        drain()
        send(frame)
        val buf = ByteArray(64)
        repeat(80) {
            val n = link.receive(buf, 1500)
            if (n <= 0) throw Dx8Exception("Keine Antwort (Timeout)")
            if ((buf[1].toInt() and 0xFF) == 0x30 && match(buf)) return buf.copyOf()
        }
        throw Dx8Exception("Keine passende Antwort")
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    /** Funktion 0x03 (Holding) oder 0x04 (Input), max. 30 Register je Frame. */
    fun read(fn: Int, addr: Int, qty: Int): IntArray = synchronized(lock) {
        require(fn == 3 || fn == 4) { "nur lesende Funktionen" }
        require(qty in 1..30)
        val r = xfer(byteArrayOf(7, 0x30, fn.toByte(), (addr shr 8).toByte(), addr.toByte(), 0, qty.toByte())) { b ->
            (u8(b, 2) == fn && u8(b, 3) == qty * 2) || u8(b, 2) == (fn or 0x80)
        }
        if (u8(r, 2) and 0x80 != 0) throw Dx8Exception("Modbus-Fehler ${u8(r, 3)} bei 0x%04X".format(addr))
        IntArray(qty) { k -> (u8(r, 4 + 2 * k) shl 8) or u8(r, 5 + 2 * k) }
    }

    fun readMany(fn: Int, addr: Int, qty: Int): IntArray {
        val out = IntArray(qty)
        var o = 0
        while (o < qty) {
            val n = minOf(30, qty - o)
            read(fn, addr + o, n).copyInto(out, o)
            o += n
        }
        return out
    }

    /** Funktion 0x10, max. 28 Register. */
    fun write(addr: Int, vals: IntArray) = synchronized(lock) {
        require(vals.size in 1..28)
        val n = vals.size
        val f = ByteArray(8 + 2 * n)
        f[0] = f.size.toByte(); f[1] = 0x30; f[2] = 0x10
        f[3] = (addr shr 8).toByte(); f[4] = addr.toByte(); f[5] = 0; f[6] = n.toByte(); f[7] = (2 * n).toByte()
        for (k in 0 until n) { f[8 + 2 * k] = (vals[k] shr 8).toByte(); f[9 + 2 * k] = vals[k].toByte() }
        val r = xfer(f) { b -> u8(b, 2) == 0x10 || u8(b, 2) == 0x90 }
        if (u8(r, 2) == 0x90) throw Dx8Exception("Schreibfehler ${u8(r, 3)} bei 0x%04X".format(addr))
    }

    // ---- Steuerbefehle (Register 0x8000: Op, Memory, Kanal, Lock, Order) ----
    private val UNLOCK = 0x55AA

    fun run(op: Int, slot: Int, chIdx: Int) {
        write(0x8000, intArrayOf(op, slot, chIdx, UNLOCK, 1))   // ORDER_RUN
        write(0x8003, intArrayOf(0))
    }

    fun stop(chIdx: Int) {
        write(0x8002, intArrayOf(chIdx, UNLOCK, 0))             // ORDER_STOP
        write(0x8003, intArrayOf(0))
    }

    /** Systembereich (0x8400 + Offset) im RAM des Laders beschreiben. */
    fun writeSystem(offset: Int, vals: IntArray) = write(0x8400 + offset, vals)

    /** RAM-Abbild des Systembereichs dauerhaft im Flash speichern (ORDER_WRITE_SYS = 3). */
    fun saveSystem() {
        write(0x8003, intArrayOf(UNLOCK, 3))
        write(0x8003, intArrayOf(0))
    }

    /** Befehl ueber das Order-Register (entsperren, Befehl, wieder sperren). */
    private fun order(o: Int) {
        write(0x8003, intArrayOf(UNLOCK, o))
        write(0x8003, intArrayOf(0))
    }

    /** RAM-Abbild des gewaehlten Programmspeichers (0x8C00) dauerhaft speichern (ORDER_WRITE_MEM = 5). */
    fun saveMemory() = order(5)

    /** RAM-Abbild der Programmliste (0x8800) dauerhaft speichern (ORDER_WRITE_MEM_HEAD = 4). */
    fun saveMemHead() = order(4)

    fun answerDialog(chIdx: Int, yes: Boolean) {
        write(0x8002, intArrayOf(chIdx, UNLOCK, if (yes) 8 else 9)) // MSGBOX_YES / MSGBOX_NO
        write(0x8003, intArrayOf(0))
    }
}
