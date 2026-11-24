package de.dx8.monitor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Dx8DeviceTest {
    private val fake = FakeDx8()
    private val dev = Dx8Device(fake)

    private fun assertFails(msg: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { assertTrue("'${e.message}' enthält nicht '$msg'", e.message!!.contains(msg)); return }
        throw AssertionError("keine Exception")
    }

    @Test fun readInputAndHolding() {
        fake.input[5] = 0x1234; fake.input[6] = 0xFFFF
        assertArrayEquals(intArrayOf(0, 0x1234, 0xFFFF), dev.read(4, 4, 3))
        fake.ctrl[1] = 17
        assertEquals(17, dev.read(3, 0x8000, 2)[1])
    }

    @Test fun readManySplitsInto30RegisterFrames() {
        for (i in 0 until 60) fake.input[0x100 + i] = i * 3
        val r = dev.readMany(4, 0x100, 60)
        assertEquals((0 until 60).map { it * 3 }, r.toList())
        val r2 = dev.readMany(4, 0x100, 31)
        assertEquals(31, r2.size); assertEquals(90, r2[30])
    }

    @Test fun readArgumentChecks() {
        assertFails("nur lesende") { dev.read(0x10, 0, 1) }
        assertFails("Failed requirement") { dev.read(4, 0, 31) }
        assertFails("Failed requirement") { dev.read(4, 0, 0) }
        assertFails("Failed requirement") { dev.write(0x8000, IntArray(29)) }
        assertFails("Failed requirement") { dev.write(0x8000, IntArray(0)) }
    }

    @Test fun modbusExceptionsBecomeErrors() {
        assertFails("Modbus-Fehler 2 bei 0x9500") { dev.read(3, 0x9500, 1) }
        assertFails("Schreibfehler 4 bei 0x9500") { dev.write(0x9500, intArrayOf(1)) }
    }

    @Test fun timeoutWhenChargerSilent() {
        fake.silent = true
        assertFails("Keine Antwort") { dev.read(4, 0, 1) }
    }

    @Test fun skipsLogViewFramesAndStaleData() {
        fake.noiseFrames = 3
        fake.input[0] = 0xA6
        assertEquals(0xA6, dev.read(4, 0, 1)[0])
        assertEquals(0xA6, dev.read(4, 0, 1)[0])    // Reste werden vor dem Senden verworfen
    }

    @Test fun writeEncodesBigEndian() {
        dev.write(0x8400, intArrayOf(0x1234, 0xABCD))
        assertEquals(0x1234, fake.sysRam[0]); assertEquals(0xABCD, fake.sysRam[1])
        assertEquals(0, fake.sysFlash[0])            // erst mit Order 3 dauerhaft
    }

    @Test fun runSendsOrderAndRelocks() {
        dev.run(2, 5, 1)
        assertEquals(listOf(1), fake.orders)
        assertEquals(2, fake.ctrl[0]); assertEquals(5, fake.ctrl[1]); assertEquals(1, fake.ctrl[2])
        assertEquals(0, fake.ctrl[3])                // wieder gesperrt
    }

    @Test fun stopAndDialogAnswers() {
        dev.stop(1)
        dev.answerDialog(0, true)
        dev.answerDialog(1, false)
        assertEquals(listOf(0, 8, 9), fake.orders)
        assertEquals(1, fake.ctrl[2]); assertEquals(0, fake.ctrl[3])
    }

    @Test fun systemWriteAndSave() {
        dev.writeSystem(23, intArrayOf(2))
        assertEquals(0, fake.sysFlash[23])
        dev.saveSystem()
        assertEquals(2, fake.sysFlash[23])
        assertEquals(listOf(3), fake.orders)
    }

    @Test fun memoryOrders() {
        dev.saveMemHead(); dev.saveMemory()
        assertEquals(listOf(4, 5), fake.orders)
    }

    @Test fun orderWithoutUnlockIsIgnored() {
        dev.write(0x8004, intArrayOf(1))
        assertTrue(fake.orders.isEmpty())
    }

    @Test fun closeClosesLink() {
        dev.close()
        assertTrue(fake.closed)
    }
}
