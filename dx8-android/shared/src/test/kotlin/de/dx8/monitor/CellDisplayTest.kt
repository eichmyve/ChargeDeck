package de.dx8.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zellanzeige der Kanal-Kachel: Zeilen, Balancer-Symbol, IR rechts, Zielwert je Zelle. */
class CellDisplayTest {
    private fun f(fmt: String, vararg a: Any) = fmt.format(*a)

    private fun lipo(chargeV: Double? = 4.2, storageV: Double? = 3.85, dischargeV: Double? = 3.5) = Program(
        pos = 0, slot = 20, name = "LIPO_HeatRC_3S_1800", type = 0, liCells = 3, chargeA = 1.8, dischargeA = 2.0,
        chargeV = chargeV, storageV = storageV, dischargeV = dischargeV, regen = 0
    )

    @Test fun targetPerOperation() {
        val p = lipo()
        assertEquals(f("%.3f V", 4.2), ChannelLogic.cellTarget(0, p))          // Laden
        assertEquals(f("%.3f V", 3.85), ChannelLogic.cellTarget(1, p))         // Storage
        assertEquals(f("%.3f V", 3.5), ChannelLogic.cellTarget(2, p))          // Entladen
        assertEquals(f("%.3f / %.3f V", 4.2, 3.5), ChannelLogic.cellTarget(3, p))   // Zyklus
        assertEquals(f("%.3f V", 4.2), ChannelLogic.cellTarget(4, p))          // Balancen
        assertNull(ChannelLogic.cellTarget(9, p))
    }

    @Test fun noTargetWithoutProgramOrVoltage() {
        assertNull(ChannelLogic.cellTarget(0, null))                                     // am Lader gestartet
        assertNull(ChannelLogic.cellTarget(0, lipo(chargeV = null, storageV = null, dischargeV = null)))   // NiMH u. a.
        assertNull(ChannelLogic.cellTarget(1, lipo(storageV = 0.0)))
        assertNull(ChannelLogic.cellTarget(3, lipo(dischargeV = null)))
    }

    @Test fun headerWithAndWithoutTarget() {
        val c = ChannelData.parse(Samples.channelRegs(cells = listOf(4196, 4200, 4200), ir = listOf(104, 122, 122)))
        assertEquals("3S  Δ 4 mV  Ziel 4.200 V", ChannelLogic.cellsHeader(c, "4.200 V"))
        assertEquals("3S  Δ 4 mV", ChannelLogic.cellsHeader(c, null))
        val none = ChannelLogic.cellsHeader(Samples.noBattery(), null)
        assertTrue(none.startsWith("keine Zellwerte"))
        assertTrue(ChannelLogic.cellsHeader(Samples.noBattery(), "4.200 V").endsWith("Ziel 4.200 V"))
    }

    @Test fun irHeadSitsAboveTheCellIrColumn() {
        val g = Samples.channelRegs(cells = listOf(4196, 4200, 4200), ir = listOf(104, 122, 122))
        g[52] = 511                                                          // Leitung 51,1 mΩ
        val h = ChannelLogic.irHead(ChannelData.parse(g))!!
        assertEquals(f("Ltg %.1f mΩ !", 51.1), h.line)                     // 40–60 mΩ: erhoeht
        assertEquals(ChannelLogic.LineState.WARN, h.lineState)
        assertEquals(f("IR %.1f mΩ !", 34.8), h.total)                     // 11,6 mΩ/Zelle: erhoeht
        assertEquals(ChannelLogic.LineState.WARN, h.totalState)
        assertNull(ChannelLogic.irHead(Samples.noBattery()))
    }

    @Test fun rowsHaveBalancerFlagBarAndRatedIrOnTheRight() {
        val c = ChannelData.parse(Samples.channelRegs(
            cells = listOf(4196, 4200, 3000), bal = listOf(1, 0, 0), ir = listOf(104, 95, 0)))
        val rows = ChannelLogic.cellRows(c)
        assertEquals(3, rows.size)
        assertTrue(rows[0].balancing); assertFalse(rows[1].balancing)
        assertEquals(f("1 %.3f", 4.196), rows[0].label)                    // nur Nummer + Spannung
        assertEquals(f("%.1f mΩ !", 10.4), rows[0].ir)                     // > 10 mΩ/Zelle: erhoeht
        assertEquals(ChannelLogic.LineState.WARN, rows[0].irState)
        assertEquals(f("%.1f mΩ ✓", 9.5), rows[1].ir)
        assertEquals(ChannelLogic.LineState.GOOD, rows[1].irState)
        assertEquals("", rows[2].ir)                                         // keine RI-Messung
        assertEquals(ChannelLogic.LineState.NONE, rows[2].irState)
        assertEquals(1f, rows[1].fill, 1e-6f)                               // 4,2 V = voll
        assertEquals(0f, rows[2].fill, 1e-6f)                               // 3,0 V = leer
        assertEquals(0.5f, ChannelLogic.cellRows(ChannelData.parse(Samples.channelRegs(cells = listOf(3600))))[0].fill, 1e-6f)
    }

    @Test fun barScalesToHigherTarget() {
        val c = ChannelData.parse(Samples.channelRegs(cells = listOf(4200)))
        assertTrue(ChannelLogic.cellRows(c, 4350)[0].fill < 1f)              // LiHV: 4,2 V noch nicht voll
        assertEquals(1f, ChannelLogic.cellRows(ChannelData.parse(Samples.channelRegs(cells = listOf(4400))), 4350)[0].fill, 1e-6f)
    }

    @Test fun balancingStatusCode17() {
        assertEquals("Balancieren", Samples.channel(run = 17).runText)
        assertTrue(Samples.channel(run = 17).isActive)
        assertEquals(MainAct.HOLD, ChannelLogic.mainAct(Samples.channel(run = 17)))
        assertEquals(AppTheme.DARK.ok, AppTheme.DARK.stateColor(17))
    }

    @Test fun stateTags() {
        assertEquals(" ✓", ChannelLogic.stateTag(ChannelLogic.LineState.GOOD))
        assertEquals(" !", ChannelLogic.stateTag(ChannelLogic.LineState.WARN))
        assertEquals(" ✗", ChannelLogic.stateTag(ChannelLogic.LineState.BAD))
        assertEquals("", ChannelLogic.stateTag(ChannelLogic.LineState.NONE))
    }
}
