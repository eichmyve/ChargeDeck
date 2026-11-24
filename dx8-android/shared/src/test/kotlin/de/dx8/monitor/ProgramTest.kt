package de.dx8.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/** Programme aus den 20 echten Speicherabbildern des DX8 (Assets) – Werte wie am Display bestaetigt. */
class ProgramTest {
    @Before fun setup() { Locale.setDefault(Locale.US); ProgramMemory.pause = {} }

    private val dir = File(TestData.ASSETS, "programs")
    private val order = File(dir, "order.txt").readText().trim().split(',').map { it.trim().toInt() }
    private val progs = order.mapIndexed { pos, slot -> Program.parse(pos, slot, File(dir, "mem_%02d.bin".format(slot)).readBytes()) }
    private fun byName(n: String) = progs.first { it.name == n }

    @Test fun allProgramsParse() {
        assertEquals(20, progs.size)
        assertTrue(progs.all { it.raw.size == 192 })
        assertEquals("LiPo_12A", progs[0].name)
        assertEquals("LiPo_4A", progs[4].name)
    }

    @Test fun renamedProgramsShowLeadingL() {
        // am Lader umbenannte Programme haben 00 als erstes Zeichen; das Display zeigt dort 'L'
        assertTrue(progs[1].name.startsWith("LiPo_10A"))
    }

    @Test fun builtInVersusCustom() {
        assertTrue(byName("LiIo").isBuiltIn)
        assertTrue(byName("Power").isBuiltIn)
        assertFalse(byName("LiPo_12A").isBuiltIn)
        // LiIo, LiFe, LiHV, LTO, NiMH, NiCd, NiZn, Pb, Power (LiPo-Standard ist am Lader ersetzt)
        assertEquals(9, progs.count { it.isBuiltIn })
        assertEquals(ProgramMemory.USE_CUSTOM, byName("LiPo_12A").useFlag)
        assertEquals(0, byName("LiFe").useFlag)
    }

    @Test fun operationMaskMatchesDisplay() {
        val all = OPERATIONS.map { it.second }
        assertTrue(all.all { byName("LiPo_4A").opEnabled(it) })
        val p12 = byName("LiPo_12A")
        assertEquals(listOf(true, true, false, false, false), all.map { p12.opEnabled(it) })
        val p2 = byName("LiPo_2A")
        assertEquals(listOf(true, true, false, false, true), all.map { p2.opEnabled(it) })
        val ent = progs[7]
        assertEquals(listOf(false, false, true, false, false), all.map { ent.opEnabled(it) })
    }

    @Test fun valuesOfLiPo12A() {
        val p = byName("LiPo_12A")
        assertEquals(0, p.type); assertEquals("LiPo", p.typeName)
        assertEquals(12.0, p.chargeA, 1e-9); assertEquals(10.0, p.dischargeA, 1e-9)
        assertEquals(4.2, p.chargeV!!, 1e-9); assertEquals(3.85, p.storageV!!, 1e-9); assertEquals(3.5, p.dischargeV!!, 1e-9)
        assertEquals(0, p.regen)
        assertTrue(p.toString().startsWith("00  LiPo_12A  (LiPo Auto, 12.0 A / 10.0 A)"))
    }

    @Test fun regenerativeProgram() {
        val p = progs[7]
        assertEquals(1, p.regen)
        assertTrue(p.toString().endsWith(" REGEN)"))
    }

    @Test fun cellsAndOtherTypes() {
        assertEquals(2, byName("LIPO_2S_Unbalanced").liCells)
        assertTrue(byName("LIPO_2S_Unbalanced").toString().contains("2S"))
        val ni = byName("NiMH")
        assertEquals("NiMH", ni.typeName)
        assertNull(ni.chargeV)
        assertFalse(ni.toString().contains("Auto"))
        assertEquals("Typ 42", ni.copy(type = 42).typeName)
    }

    @Test fun voltOffsets() {
        assertEquals(listOf(0x50, 0x56, 0x5C), Program.voltOffsets(0)!!.toList())
        assertEquals(listOf(0x52, 0x58, 0x5E), Program.voltOffsets(1)!!.toList())
        assertEquals(listOf(0x54, 0x5A, 0x60), Program.voltOffsets(2)!!.toList())
        assertEquals(listOf(0xA6, 0xA8, 0xAA), Program.voltOffsets(3)!!.toList())
        assertEquals(listOf(0xAC, 0xAE, 0xB0), Program.voltOffsets(4)!!.toList())
        assertNull(Program.voltOffsets(5))
        assertEquals(3.6, byName("LiFe").chargeV!!, 1e-9)
    }

    @Test fun readAllFromChargerRestoresSelection() {
        val fake = FakeDx8()
        fake.loadAssets()
        fake.ctrl[1] = 7
        val list = Program.readAll(Dx8Device(fake))
        assertEquals(progs.map { it.name }, list.map { it.name })
        assertEquals(progs.map { it.slot }, list.map { it.slot })
        assertEquals(7, fake.ctrl[1])
        assertTrue(fake.orders.isEmpty())              // nur gelesen, nichts gespeichert
    }

    @Test fun readAllSkipsHiddenEntries() {
        val fake = FakeDx8()
        fake.loadAssets()
        fake.setHead(listOf(18, 0xFE, 10))
        val list = Program.readAll(Dx8Device(fake))
        assertEquals(listOf(18, 10), list.map { it.slot })
        assertEquals(listOf(0, 2), list.map { it.pos })
    }
}
