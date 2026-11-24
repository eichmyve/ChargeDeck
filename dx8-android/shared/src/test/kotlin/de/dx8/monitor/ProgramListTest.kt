package de.dx8.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Kachel-Liste der Ladeprogramme, mit den 20 echten Programmen des DX8. */
class ProgramListTest {
    private val dir = File(TestData.ASSETS, "programs")
    private val order = File(dir, "order.txt").readText().trim().split(',').map { it.trim().toInt() }
    private val progs = order.mapIndexed { pos, slot -> Program.parse(pos, slot, File(dir, "mem_%02d.bin".format(slot)).readBytes()) }
    private fun byName(n: String) = progs.first { it.name == n }
    private val PL = ProgramList

    @Test fun ampsFormatting() {
        assertEquals("12 A", PL.amps(12.0))
        assertEquals("10 A", PL.amps(10.0))
        assertEquals("2.5 A", PL.amps(2.5))
        assertEquals("1.25 A", PL.amps(1.25))
        assertEquals("0.2 A", PL.amps(0.2))
        assertEquals("0.05 A", PL.amps(0.05))
    }

    @Test fun currentsOnlyWhenUsed() {
        val p12 = byName("LiPo_12A")                      // Laden, Storage
        assertEquals("12 A", PL.chargeText(p12)); assertEquals("10 A", PL.dischargeText(p12))
        val regen = progs[7]                               // nur Entladen
        assertEquals("–", PL.chargeText(regen)); assertEquals("10 A", PL.dischargeText(regen))
        val life = byName("LiFe4s_Laden_4A_Unbal")        // nur Laden
        assertEquals("4 A", PL.chargeText(life)); assertEquals("–", PL.dischargeText(life))
        val balOnly = p12.copy(opMask = 0xE0 or (1 shl 4)) // nur Balancen
        assertEquals("–", PL.chargeText(balOnly)); assertEquals("–", PL.dischargeText(balOnly))
        val cyc = p12.copy(opMask = 0xE0 or (1 shl 3))     // Zyklus nutzt beide
        assertTrue(PL.usesCharge(cyc) && PL.usesDischarge(cyc))
    }

    @Test fun regenTags() {
        assertEquals(null, PL.regenTag(byName("LiPo_12A")))
        assertEquals("REGEN → Eingang", PL.regenTag(progs[7]))
        val m = byName("LiPo_2A").raw.copyOf()
        ProgramMemory.put(m, ProgramMemory.OFF_REGEN, 2, 2)
        ProgramMemory.put(m, ProgramMemory.OFF_REG_CH_MODE, 1, 0)
        assertEquals("REGEN → Ausgang", PL.regenTag(Program.parse(0, 16, m)))
        ProgramMemory.put(m, ProgramMemory.OFF_REG_CH_MODE, 1, 1)
        assertEquals("REGEN → Akku (Akku zu Akku)", PL.regenTag(Program.parse(0, 16, m)))
        ProgramMemory.put(m, ProgramMemory.OFF_REGEN, 2, 5)
        assertEquals("REGEN (5)", PL.regenTag(Program.parse(0, 16, m)))
    }

    @Test fun cellsLabel() {
        assertEquals("Auto", PL.cellsLabel(byName("LiPo_12A")))
        assertEquals("4S", PL.cellsLabel(byName("LiFe4s_Laden_4A_Unbal")))
        assertEquals("", PL.cellsLabel(byName("NiMH")))
    }

    @Test fun groupsAndFilter() {
        assertEquals(11, PL.custom(progs).size)
        assertEquals(9, PL.builtIn(progs).size)
        assertTrue(PL.custom(progs).none { it.isBuiltIn })
        assertEquals(9, PL.custom(progs, 0).size)          // LiPo
        assertEquals(1, PL.custom(progs, 2).size)          // LiFe
        assertEquals(1, PL.builtIn(progs, 2).size)
        assertEquals(0, PL.custom(progs, 8).size)          // Pb
        // Reihenfolge wie am Lader bleibt erhalten
        assertEquals(PL.custom(progs).map { it.pos }, PL.custom(progs).map { it.pos }.sorted())
    }

    @Test fun filterChipsSortedByCount() {
        assertEquals(listOf(0 to 9, 1 to 1, 2 to 1), PL.typeCounts(progs))
    }

    @Test fun typeColorsAreValidAndGroupChemistry() {
        for (t in 0..10) assertTrue(PL.typeColorIndex(t) in 0..7)
        assertEquals(PL.typeColorIndex(5), PL.typeColorIndex(6))
        assertEquals(PL.typeColorIndex(8), PL.typeColorIndex(9))
        assertEquals(5, (0..4).map { PL.typeColorIndex(it) }.toSet().size)   // Li-Typen unterscheidbar
        assertEquals(OPERATIONS.size, PL.OP_SHORT.size)
    }
}
