package de.dx8.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelDataTest {

    @Test fun numberHelpers() {
        assertEquals(0, s16(0)); assertEquals(32767, s16(0x7FFF)); assertEquals(-1, s16(0xFFFF)); assertEquals(-32768, s16(0x8000))
        assertEquals(0x0001_0002L, u32(2, 1))
        assertEquals(-1L, s32(0xFFFF, 0xFFFF))
        assertEquals(70000L, s32(70000 and 0xFFFF, 70000 shr 16))
    }

    @Test fun parseAllFields() {
        val c = ChannelData.parse(Samples.channelRegs(run = 7, currentCa = -250, uinMv = 24123, uoutMv = 12456,
            ts = 70000, powerCw = 31234, capMah = 1500, tInt = 312, tExt = -50, err = 0x0D05, dialog = 3))
        assertEquals(70000L, c.ts)
        assertEquals(312.34, c.powerW, 1e-9)
        assertEquals(-2.5, c.currentA, 1e-9)
        assertEquals(24.123, c.uinV, 1e-9)
        assertEquals(12.456, c.uoutV, 1e-9)
        assertEquals(1500L, c.capMah)
        assertEquals(31.2, c.tInt, 1e-9)
        assertEquals(-5.0, c.tExt, 1e-9)
        assertEquals(listOf(4200, 4200, 4200), c.cells)
        assertEquals(listOf(0, 1, 0), c.balance)
        assertEquals(listOf(30, 31, 32), c.ir)
        assertEquals(93, c.irTotal); assertEquals(12, c.irLine)
        assertEquals(7, c.run); assertEquals(0x0D05, c.err); assertEquals(3, c.dialog)
    }

    @Test fun cellsOutsideRangeAreIgnored() {
        val g = Samples.channelRegs(cells = listOf(0, 3800, 5000, 3900, 65535))
        val c = ChannelData.parse(g)
        assertEquals(listOf(3800, 3900), c.cells)
        assertEquals(2, c.balance.size); assertEquals(2, c.ir.size)
    }

    @Test fun allEightCellsAndBalanceBytes() {
        val cells = (1..8).map { 3500 + it }
        val c = ChannelData.parse(Samples.channelRegs(cells = cells, bal = listOf(1, 0, 1, 0, 1, 0, 1, 1), ir = (1..8).toList()))
        assertEquals(cells, c.cells)
        assertEquals(listOf(1, 0, 1, 0, 1, 0, 1, 1), c.balance)
    }

    @Test fun noBatteryDetection() {
        assertTrue(Samples.noBattery().noBattery)
        assertEquals("Akku n. verbunden", Samples.noBattery(0).runText)
        assertEquals("Akku n. verbunden", Samples.noBattery(1).runText)
        assertEquals("Laden", Samples.noBattery(7).runText)                  // laufend: Statustext bleibt
        assertFalse(Samples.channel(uoutMv = 45000).noBattery)              // Zellen vorhanden
        assertFalse(Samples.channel(uoutMv = 45000, cells = emptyList(), currentCa = 10).noBattery)  // Strom fliesst
        assertFalse(Samples.channel(uoutMv = 12000, cells = emptyList()).noBattery)
    }

    @Test fun runTextAndActive() {
        assertEquals("Bereit", Samples.channel(0).runText)
        assertEquals("Halten", Samples.channel(1).runText)
        assertEquals("Entladen", Samples.channel(13).runText)
        assertEquals("Code 99", Samples.channel(99).runText)
        assertEquals("Warten", Samples.channel(39).runText)          // am DX8-Display "Warten"
        assertFalse(Samples.channel(0).isActive)
        assertFalse(Samples.channel(1).isActive)
        assertFalse(Samples.channel(41).isActive)
        assertTrue(Samples.channel(7).isActive)
        assertTrue(Samples.channel(12).isActive)
    }

    @Test fun errorCodes() {
        assertEquals(1305, errCode(0x0D05))
        assertEquals(1302, errCode(0x0D02))
        assertEquals(3100, errCode(0x1F00))
        assertTrue(ERR_TEXT[1305]!!.startsWith("Verbindungsfehler"))
    }
}
