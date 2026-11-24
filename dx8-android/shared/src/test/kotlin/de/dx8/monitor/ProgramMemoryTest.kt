package de.dx8.monitor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Bytes/Felder des Programmspeichers und der Schreibablauf gegen den nachgebauten Lader. */
class ProgramMemoryTest {
    private val PM = ProgramMemory
    private lateinit var fake: FakeDx8
    private lateinit var dev: Dx8Device
    private lateinit var order: List<Int>

    @Before fun setup() {
        PM.pause = {}
        fake = FakeDx8(); order = fake.loadAssets(); dev = Dx8Device(fake)
    }

    private fun assertFails(msg: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { assertTrue("'${e.message}' enthält nicht '$msg'", e.message!!.contains(msg)); return }
        throw AssertionError("keine Exception")
    }

    // ---------------------------------------------------------------- Bytes
    @Test fun getPutLittleEndian() {
        val b = ByteArray(8)
        PM.put(b, 1, 4, 0x12345678)
        assertEquals(listOf(0, 0x78, 0x56, 0x34, 0x12, 0, 0, 0), b.map { it.toInt() and 0xFF })
        assertEquals(0x12345678L, PM.get(b, 1, 4))
        assertEquals(0x5678L, PM.get(b, 1, 2))
        PM.put(b, 6, 2, 0xFFFF); assertEquals(0xFFFFL, PM.get(b, 6, 2))
        PM.put(b, 0, 1, 0x1AB); assertEquals(0xABL, PM.get(b, 0, 1))   // nur unteres Byte
    }

    @Test fun regsBytesRoundTrip() {
        val b = ByteArray(192) { (it * 7).toByte() }
        assertArrayEquals(b, Program.regsToBytes(PM.bytesToRegs(b)))
        assertEquals(listOf(0x0201, 0x03), PM.bytesToRegs(byteArrayOf(1, 2, 3)).toList())   // ungerade Laenge
    }

    @Test fun nameHandling() {
        val b = ByteArray(192) { 0x41 }
        PM.setName(b, "Test_3S")
        assertEquals("Test_3S", PM.nameOf(b))
        assertEquals(0, b[2 + 7].toInt())                  // Rest geloescht
        assertEquals(0, b[2 + PM.NAME_MAX].toInt())        // Abschluss-Null
        assertEquals(0x41, b[0].toInt()); assertEquals(0x41, b[40].toInt())   // Nachbarn unberuehrt
        PM.setName(b, "X".repeat(50))
        assertEquals(PM.NAME_MAX, PM.nameOf(b).length)
    }

    @Test fun nameRules() {
        assertNull(PM.checkName("LiPo_4S 5000mAh"))
        assertTrue(PM.checkName("  ")!!.contains("leer"))
        assertTrue(PM.checkName("X".repeat(38))!!.contains("37"))
        assertTrue(PM.checkName("Akku_groß")!!.contains("Umlaute"))
        assertNull(PM.checkName("X".repeat(37)))
    }

    // ---------------------------------------------------------------- Felder
    @Test fun fieldsForLiAndOthers() {
        val li = PM.fields(0).map { it.label }
        assertTrue(li.containsAll(listOf("Ladeschluss je Zelle", "Storage je Zelle", "Entlade-Ende je Zelle", "Ladestrom", "Kapazität (0 = ignorieren)")))
        assertEquals(8, li.size)
        val ni = PM.fields(5).map { it.label }
        assertEquals(5, ni.size)
        assertTrue(ni.none { it.contains("Zelle") })
        for (t in 0..4) assertEquals(8, PM.fields(t).size)
        assertEquals(9, PM.CELL_OPTIONS.size); assertEquals("Auto", PM.CELL_OPTIONS[0]); assertEquals("8S", PM.CELL_OPTIONS[8])
    }

    @Test fun fieldsReadRealValues() {
        val p = Program.parse(0, 18, fake.memBytes(18))       // LiPo_12A
        val f = PM.fields(0).associateBy { it.label }
        assertEquals("12.00", f["Ladestrom"]!!.show(p.raw))
        assertEquals("10.00", f["Entladestrom"]!!.show(p.raw))
        assertEquals("4.200", f["Ladeschluss je Zelle"]!!.show(p.raw))
        assertEquals("3.850", f["Storage je Zelle"]!!.show(p.raw))
        assertEquals("3.500", f["Entlade-Ende je Zelle"]!!.show(p.raw))
        assertEquals("10", f["Lade-Endstrom"]!!.show(p.raw))
        assertEquals("50", f["Entlade-Endstrom"]!!.show(p.raw))
        assertEquals("0", f["Kapazität (0 = ignorieren)"]!!.show(p.raw))
        assertEquals("0.05–30.00 A", f["Ladestrom"]!!.range())
        assertEquals("1–50 %", f["Lade-Endstrom"]!!.range())
    }

    @Test fun regenModeOptions() {
        assertEquals(3, PM.REGEN_MODES.size)
        assertEquals(PM.REGEN_MODES, PM.regenOptions(1))
        assertEquals(4, PM.regenOptions(7).size)
        assertEquals("Modus 7 (vom Lader)", PM.regenOptions(7).last())
        assertEquals(2, PM.regenValue(2, 0))
        assertEquals(7, PM.regenValue(3, 7))                  // unbekannter Wert bleibt erhalten
        assertEquals("Zu Eingang (zurückspeisen)", PM.regenName(1))
        assertEquals("Zu Ausgang", PM.regenName(2))
        assertEquals("Widerstand / Lampe", PM.outputLoadName(0))
        assertEquals("Von Akku zu Akku", PM.outputLoadName(1))
        assertEquals("Wert 5", PM.outputLoadName(5))
        assertEquals(3, PM.outputLoadOptions(5).size)
        assertEquals(5, PM.outputLoadValue(2, 5))
        assertEquals(1, PM.outputLoadValue(1, 0))
        assertTrue(PM.outputLimitsEditable(0))
        assertTrue(!PM.outputLimitsEditable(1))
        assertEquals("12.0", PM.OUT_VOLT.show(fake.memBytes(16)))
        assertEquals("1.0", PM.OUT_CURR.show(fake.memBytes(16)))
        // Grenzen fuer "Zu Ausgang" wie am Display: Widerstand/Lampe, 12 V, 1 A
        val m = fake.memBytes(16)
        assertEquals(0L, PM.get(m, PM.OFF_REG_CH_MODE, 1))
        assertEquals(12000L, PM.get(m, PM.OFF_REG_CH_VOLT, 2))
        assertEquals(100L, PM.get(m, PM.OFF_REG_CH_CURR, 2))
        assertEquals("Modus 9", PM.regenName(9))
        // echtes Regenerativ-Programm hat Modus 1, LiPo_12A Modus 0
        assertEquals(1L, PM.get(fake.memBytes(17), PM.OFF_REGEN, 2))
        assertEquals(0L, PM.get(fake.memBytes(18), PM.OFF_REGEN, 2))
    }

    @Test fun realProgramsAreInsideAppLimits() {
        // jedes echte eigene Programm muss sich unveraendert speichern lassen
        for (s in order) {
            val p = Program.parse(0, s, fake.memBytes(s))
            if (p.isBuiltIn) continue
            for (f in PM.fields(p.type)) {
                val v = f.raw(p.raw).toDouble() / f.scale
                assertTrue("${p.name} ${f.label} = $v", v in f.min..f.max || (f.off == 0x28 && v == 0.0))
            }
        }
    }

    // ---------------------------------------------------------------- Programmliste
    @Test fun readHeadAndFreeSlot() {
        val h = PM.readHead(dev)
        assertEquals(20, h.count)
        assertEquals(64, h.capacity)
        assertEquals(order, h.order)
        assertEquals(20, PM.freeSlot(h))
    }

    @Test fun readHeadFallsBackTo32Entries() {
        val f17 = FakeDx8(headRegs = 17)
        f17.setHead(listOf(3, 1, 0))
        val h = PM.readHead(Dx8Device(f17))
        assertEquals(32, h.capacity)
        assertEquals(listOf(3, 1, 0), h.order)
        assertEquals(2, PM.freeSlot(h))
    }

    @Test fun freeSlotIgnoresHiddenAndReportsFull() {
        fake.setHead(listOf(0, 0xFE, 1))
        assertEquals(2, PM.freeSlot(PM.readHead(dev)))
        val f17 = FakeDx8(headRegs = 17)
        f17.setHead((0 until 32).toList())
        assertNull(PM.freeSlot(PM.readHead(Dx8Device(f17))))
    }

    // ---------------------------------------------------------------- Schreiben
    @Test fun writeMemoryStoresInFlashAndRestoresSelection() {
        fake.ctrl[1] = 4
        val mem = fake.memBytes(16)                          // LiPo_2A
        PM.put(mem, 0x62, 2, 250)                            // 2.50 A
        val back = PM.writeMemory(dev, 16, mem)
        assertArrayEquals(mem, back)
        assertArrayEquals(mem, fake.memBytes(16))
        assertEquals(listOf(5), fake.orders)
        assertEquals(4, fake.ctrl[1])
        // Register in Bloecken von hoechstens 28
        assertTrue(fake.writes.filter { it.first >= 0x8C00 }.all { it.second.size <= 28 })
        assertEquals(96, fake.writes.filter { it.first >= 0x8C00 }.sumOf { it.second.size })
    }

    @Test fun writeMemoryWithoutSaveOrderIsDetected() {
        fake.ignoreOrders = setOf(5)
        val mem = fake.memBytes(16)
        PM.put(mem, 0x62, 2, 250)
        val back = PM.writeMemory(dev, 16, mem)
        assertEquals(200L, PM.get(back, 0x62, 2))           // Flash unveraendert -> Kontrolle sieht alten Wert
    }

    @Test fun writeMemoryRestoresSelectionOnError() {
        fake.ctrl[1] = 9
        fake.failWriteAt = 0x8C00 + 28
        assertFails("Schreibfehler") { PM.writeMemory(dev, 16, fake.memBytes(16)) }
        assertEquals(9, fake.ctrl[1])
        assertTrue(fake.orders.isEmpty())                    // nichts gespeichert
    }

    @Test fun appendToHeadAddsSlotAndSaves() {
        val h = PM.appendToHead(dev, 20)
        assertEquals(21, h.count)
        assertEquals(order + 20, h.order)
        assertEquals(listOf(4), fake.orders)
        // nur Register bis einschliesslich des neuen Eintrags geschrieben
        val w = fake.writes.single { it.first == 0x8800 }
        assertEquals(1 + 21 / 2 + 1, w.second.size)
    }

    @Test fun appendToHeadKeepsNeighbourByte() {
        fake.setHead(listOf(0, 1))
        val b = Program.regsToBytes(fake.headFlash); b[2 + 3] = 0x33; ProgramMemory.bytesToRegs(b).copyInto(fake.headFlash)
        PM.appendToHead(dev, 5)                               // 3. Eintrag teilt sich das Register mit Byte 4
        val after = Program.regsToBytes(fake.headFlash)
        assertEquals(5, after[2 + 2].toInt()); assertEquals(0x33, after[2 + 3].toInt())
    }

    @Test fun appendToHeadExistingSlotIsNoOp() {
        val h = PM.appendToHead(dev, order[3])
        assertEquals(20, h.count)
        assertTrue(fake.orders.isEmpty())
    }

    @Test fun appendToHeadFullList() {
        val f17 = FakeDx8(headRegs = 17)
        f17.setHead((0 until 32).toList())
        assertFails("voll") { PM.appendToHead(Dx8Device(f17), 40) }
    }

    @Test fun appendToHeadDetectsMissingSave() {
        fake.ignoreOrders = setOf(4)
        assertFails("nicht übernommen") { PM.appendToHead(dev, 20) }
    }

    /** Kompletter Ablauf "Neu anlegen" wie in der App: Kopie -> freier Platz -> schreiben -> Liste. */
    @Test fun createProgramEndToEnd() {
        val tpl = Program.parse(5, 16, fake.memBytes(16))
        val mem = tpl.raw.copyOf()
        PM.setName(mem, "TEST_3S")
        PM.put(mem, 0, 2, PM.USE_CUSTOM.toLong())
        PM.put(mem, PM.OFF_RUNCOUNT, 2, 0)
        PM.put(mem, PM.OFF_CELLS, 1, 3)
        val slot = PM.freeSlot(PM.readHead(dev))!!
        PM.writeMemory(dev, slot, mem)
        PM.appendToHead(dev, slot)
        val list = Program.readAll(dev)
        val np = list.last()
        assertEquals(21, list.size)
        assertEquals("TEST_3S", np.name)
        assertEquals(slot, np.slot)
        assertEquals(3, np.liCells)
        assertEquals(tpl.chargeA, np.chargeA, 1e-9)
        assertTrue(!np.isBuiltIn)
        assertEquals(listOf(5, 4), fake.orders)
    }

    // ---------------------------------------------------------------- Ausgang und Speicher Optionen
    private fun asset(slot: Int) = java.io.File(TestData.ASSETS, "programs/mem_%02d.bin".format(slot)).readBytes()

    @Test fun outputOptionsReadFromRealDumps() {
        val lipo = asset(0)                     // LIP0_3S_Unbalanced vom 21.11.2026
        assertEquals("Asynchron", PM.channelModeName(lipo))
        assertEquals("1.0 s", PM.logIntervalText(lipo))
        assertEquals("An", PM.autoSaveName(lipo))
        assertTrue(PM.saveToSd(lipo))
        assertEquals(PM.get(lipo, 0x3B, 2).toInt(), PM.runCounter(lipo))
        assertEquals(false, PM.saveToSd(asset(10)))     // iPo_10AA: SD aus
    }

    @Test fun applyOutputOptionsSetsFixedValuesAndKeepsTheRest() {
        val m = asset(0)
        val before = m.copyOf()
        PM.applyOutputOptions(m, saveToSd = false)
        assertEquals("Asynchron", PM.channelModeName(m))
        assertEquals("1.0 s", PM.logIntervalText(m))
        assertEquals("Aus", PM.autoSaveName(m))
        assertEquals(false, PM.saveToSd(m))
        PM.applyOutputOptions(m, saveToSd = true)
        assertTrue(PM.saveToSd(m))
        // nur die fuenf Bytes der Optionen duerfen sich aendern (Laufzaehler, Typ, Zellen bleiben)
        val changed = m.indices.filter { m[it] != before[it] }
        assertTrue(changed.toString(), changed.all { it in PM.OUTPUT_OPTION_BYTES })
        assertEquals(PM.runCounter(before), PM.runCounter(m))
        // Synchron / anderes Intervall werden zurueckgesetzt
        PM.put(m, PM.OFF_CHANNEL_MODE, 1, 1); PM.put(m, PM.OFF_LOG_INTERVAL, 2, 50)
        assertEquals("Synchron", PM.channelModeName(m)); assertEquals("5.0 s", PM.logIntervalText(m))
        PM.applyOutputOptions(m, true)
        assertEquals("Asynchron", PM.channelModeName(m)); assertEquals(10L, PM.get(m, PM.OFF_LOG_INTERVAL, 2))
    }

    @Test fun slidersCoverExactlyMinToMax() {
        for (type in 0..9) for (f in PM.fields(type) + PM.OUT_VOLT + PM.OUT_CURR) {
            val s = f.slider()
            assertEquals("${f.label} min", Math.round(f.min * f.scale), s.rawAt(0))
            assertEquals("${f.label} max", Math.round(f.max * f.scale), s.rawAt(s.steps))
            assertTrue("${f.label} Schritte ${s.steps}", s.steps in 10..2000)       // bedienbar
            for (pos in 0..s.steps) assertTrue(f.inRange(s.rawAt(pos)))
            assertEquals(s.steps, s.posOf(Long.MAX_VALUE / 4)); assertEquals(0, s.posOf(-5))   // ausserhalb -> Rand
        }
    }

    @Test fun sliderStepsPerField() {
        val f = PM.fields(0).associateBy { it.label }
        val cap = f["Kapazität (0 = ignorieren)"]!!.slider()
        assertEquals(0L, cap.rawAt(0)); assertEquals(50L, cap.rawAt(1)); assertEquals(50000L, cap.rawAt(cap.steps))
        assertEquals(1800L, cap.rawAt(cap.posOf(1800)))
        val chg = f["Ladestrom"]!!.slider()
        assertEquals(5L, chg.rawAt(0)); assertEquals(10L, chg.rawAt(1)); assertEquals(180L, chg.rawAt(chg.posOf(180)))   // 1,80 A
        val v = f["Ladeschluss je Zelle"]!!.slider()
        assertEquals(3850L, v.rawAt(0)); assertEquals(3860L, v.rawAt(1)); assertEquals(4200L, v.rawAt(v.steps))
        assertEquals(4200L, v.rawAt(v.posOf(4196)))                                     // auf 0,01 V gerundet
        assertEquals(10L, f["Lade-Endstrom"]!!.slider().rawAt(f["Lade-Endstrom"]!!.slider().posOf(10)))
        // Rest nicht ganzzahlig teilbar: letzter Schritt trifft trotzdem max
        val odd = ProgramMemory.SliderSpec(0, 10, 3)
        assertEquals(4, odd.steps); assertEquals(9L, odd.rawAt(3)); assertEquals(10L, odd.rawAt(4))
    }

    @Test fun realProgramsFitTheSliders() {
        // alle ausgelesenen Programme liegen in den Reglergrenzen (Kapazitaet 0 = ignorieren erlaubt)
        val dir = java.io.File(TestData.ASSETS, "programs")
        for (file in dir.listFiles { x -> x.name.endsWith(".bin") }!!) {
            val m = file.readBytes()
            val type = m[0x3D].toInt() and 0xFF
            for (f in PM.fields(type)) assertTrue("${file.name} ${f.label} = ${f.show(m)}", f.inRange(f.raw(m)))
        }
    }

    // ---------------------------------------------------------------- Lade-Untermenue (am Display bestaetigt)
    @Test fun chargeModeFromRealDumps() {
        assertEquals(PM.MODE_NO_BAL, PM.chargeMode(asset(0)))       // LIP0_3S_Unbalanced: 0x41 = 1
        assertEquals(PM.MODE_NO_BAL, PM.chargeMode(asset(13)))      // iPo_1A_Unbal
        assertEquals(0, PM.chargeMode(asset(18)))                   // LiPo_12A: Bal. Langsam
        assertEquals(1, PM.chargeMode(asset(11)))                   // LiPo_8A: Bal. Normal
        assertEquals("Bal. Langsam", PM.chargeModeName(0)); assertEquals("Ohne Bal.", PM.chargeModeName(4))
        assertEquals("CV − 0.2 V", PM.balStartName(PM.get(asset(18), PM.OFF_BAL_START, 1).toInt()))
        assertEquals(12, PM.BAL_START.size); assertEquals("CV", PM.BAL_START[0]); assertEquals("CV − 1.0 V", PM.BAL_START[10]); assertEquals("Immer", PM.BAL_START[11])
        assertEquals("Abschaltstrom AUS, Balancieren AN", PM.endModeName(PM.get(asset(18), PM.OFF_LI_BAL_END, 1).toInt()))
    }

    @Test fun chargeSubMenuValuesFromRealDumps() {
        val m = asset(18)
        assertEquals("1.0 V", PM.RESTORE_V.text(PM.RESTORE_V.raw(m)))
        assertEquals("3 min", PM.RESTORE_T.text(PM.RESTORE_T.raw(m)))
        assertEquals("0.10 A", PM.RESTORE_I.text(PM.RESTORE_I.raw(m)))
        assertEquals("45 °C", PM.SAFE_TEMP.text(PM.SAFE_TEMP.raw(m)))
        assertEquals("120 %", PM.SAFE_CAP.text(PM.SAFE_CAP.raw(m)))
        assertEquals("Aus", PM.SAFE_TIME.text(PM.SAFE_TIME.raw(m)))
        assertEquals("5 mV", PM.BAL_DIFF.text(PM.BAL_DIFF.raw(m)))
        assertEquals("1 min", PM.BAL_DELAY.text(PM.BAL_DELAY.raw(m)))
        // alle Programme passen in die Reglergrenzen der Untermenues
        val dir = java.io.File(TestData.ASSETS, "programs")
        for (file in dir.listFiles { x -> x.name.endsWith(".bin") }!!) {
            val b = file.readBytes()
            for (f in PM.BAL_FIELDS + PM.RESTORE_FIELDS + PM.SAFETY_FIELDS)
                assertTrue("${file.name} ${f.label} = ${f.text(f.raw(b))}", f.inRange(f.raw(b)))
        }
    }

    @Test fun setChargeModeAndRules() {
        val m = asset(0)                                     // ohne Balancer, BalSpeed 3
        PM.setChargeMode(m, 1)
        assertEquals(1, PM.chargeMode(m)); assertEquals(0L, PM.get(m, PM.OFF_LI_MODE_C, 1)); assertEquals(1L, PM.get(m, PM.OFF_BAL_SPEED, 1))
        PM.setChargeMode(m, PM.MODE_NO_BAL)
        assertEquals(PM.MODE_NO_BAL, PM.chargeMode(m)); assertEquals(1L, PM.get(m, PM.OFF_BAL_SPEED, 1))   // Geschwindigkeit bleibt
        assertTrue(PM.balancerEditable(PM.MODE_MODIFIED))
        for (mode in listOf(0, 1, 2, PM.MODE_NO_BAL)) assertTrue(!PM.balancerEditable(mode))
        assertTrue(!PM.endModeEditable(PM.MODE_NO_BAL)); assertTrue(PM.endModeEditable(0))
        assertTrue(PM.endCurrentEditable(PM.MODE_NO_BAL, 0))           // ohne Balancer: Endstrom aenderbar
        assertTrue(!PM.endCurrentEditable(0, 0))                        // "Abschaltstrom AUS": ausgegraut
        assertTrue(PM.endCurrentEditable(0, 2))
        assertEquals(listOf("Bal. Langsam", "Bal. Normal", "Bal. Schnell", "Modifiziert", "Ohne Bal.", "Wert 7 (vom Lader)"), PM.chargeModeOptions(7))
        assertEquals(7, PM.chargeModeValue(5, 7)); assertEquals(2, PM.chargeModeValue(2, 7))
    }

    @Test fun chargeExtraChangesListed() {
        val old = asset(18); val new = old.copyOf()
        assertEquals(emptyList<String>(), PM.chargeExtraChanges(old, new))
        PM.setChargeMode(new, PM.MODE_MODIFIED)
        PM.put(new, PM.OFF_BAL_START, 1, 11)
        PM.put(new, PM.OFF_LI_BAL_END, 1, 3)
        PM.put(new, PM.OFF_KEEP_CHARGE, 1, 1)                 // LiPo_12A hat es aus
        PM.put(new, PM.SAFE_TIME.off, 2, 120)
        PM.put(new, PM.SAFE_TEMP.off, 2, 500)
        val c = PM.chargeExtraChanges(old, new)
        assertTrue(c.toString(), c.contains("Ladeart:  Bal. Langsam → Modifiziert"))
        assertTrue(c.toString(), c.contains("Bal. Start:  CV − 0.2 V → Immer"))
        assertTrue(c.toString(), c.contains("Ladeendstrom-Abschaltung:  Abschaltstrom AUS, Balancieren AN → Abschaltstrom UND Balancer"))
        assertTrue(c.toString(), c.contains("Verzögertes Nachladen:  inaktiv → aktiv"))
        assertTrue(c.toString(), c.contains("Ladezeit Vorgabe:  Aus → 120 min"))
        assertTrue(c.toString(), c.contains("Abschalttemperatur:  45 °C → 50 °C"))
        // Kontroll-Bytes decken alle geaenderten Stellen ab
        val diff = old.indices.filter { old[it] != new[it] }
        assertTrue(diff.toString(), diff.all { it in PM.CHARGE_EXTRA_BYTES })
    }

    @Test fun balancerLimitsFromDisplay() {
        assertEquals("1–50 mV", PM.BAL_SET.range())
        assertEquals("0–50 mV", PM.BAL_OVER.range())
    }

    @Test fun storageAndCycleFromRealDumps() {
        val m = asset(18)                                      // LiPo_12A
        assertEquals("0.01 V", PM.STO_COMP.text(PM.STO_COMP.raw(m)))
        assertEquals("0.00–0.20 V", PM.STO_COMP.range())
        assertEquals(1L, PM.get(m, PM.OFF_FAST_STO, 1))          // beschleunigt an
        assertEquals(0L, PM.get(asset(16), PM.OFF_FAST_STO, 1))  // iPo_2A: aus
        assertEquals("Laden > Entladen", PM.cycleModeName(PM.get(m, PM.OFF_CYCLE_MODE, 1).toInt()))
        assertEquals("3", PM.CYCLE_COUNT.text(PM.CYCLE_COUNT.raw(m)))
        assertEquals("3 min", PM.CYCLE_DELAY.text(PM.CYCLE_DELAY.raw(m)))
        assertEquals("1–100", PM.CYCLE_COUNT.range()); assertEquals("0–60 min", PM.CYCLE_DELAY.range())
        assertEquals(6, PM.CYCLE_MODES.size); assertEquals("Entl. > Laden > Storage", PM.CYCLE_MODES[5])
        val dir = java.io.File(TestData.ASSETS, "programs")
        for (file in dir.listFiles { x -> x.name.endsWith(".bin") }!!) {
            val b = file.readBytes()
            for (f in listOf(PM.STO_COMP) + PM.CYCLE_FIELDS) assertTrue("${file.name} ${f.label} = ${f.text(f.raw(b))}", f.inRange(f.raw(b)))
            assertTrue(file.name, PM.get(b, PM.OFF_CYCLE_MODE, 1).toInt() in PM.CYCLE_MODES.indices)
        }
    }

    @Test fun storageCycleChangesAndControlBytes() {
        val old = asset(18); val new = old.copyOf()
        assertEquals(emptyList<String>(), PM.storageCycleChanges(old, new))
        PM.put(new, PM.OFF_FAST_STO, 1, 0)
        PM.put(new, PM.STO_COMP.off, 2, 20)
        PM.put(new, PM.OFF_CYCLE_MODE, 1, 4)
        PM.put(new, PM.CYCLE_COUNT.off, 2, 10)
        PM.put(new, PM.CYCLE_DELAY.off, 2, 0)
        val c = PM.storageCycleChanges(old, new)
        assertTrue(c.toString(), c.contains("Beschleunigte Storage:  aktiv → inaktiv"))
        assertTrue(c.toString(), c.contains("Storage Kompensation je Zelle:  0.01 V → 0.02 V"))
        assertTrue(c.toString(), c.contains("Zyklus Modus:  Laden > Entladen → Laden > Entl. > Storage"))
        assertTrue(c.toString(), c.contains("Zykluszahl:  3 → 10"))
        assertTrue(c.toString(), c.contains("Pausenzeit:  3 min → 0 min"))
        val diff = old.indices.filter { old[it] != new[it] }
        assertTrue(diff.toString(), diff.all { it in PM.CHARGE_EXTRA_BYTES })
    }

    @Test fun dischargeRate() {
        assertEquals("1.1 C", PM.cRate(200, 1800))            // 2,00 A bei 1800 mAh
        assertEquals("10.0 C", PM.cRate(1800, 1800))
        assertNull(PM.cRate(200, 0)); assertNull(PM.cRate(0, 1800))
    }

    @Test fun outputOptionsEditable() {
        val m = asset(0)                                       // AutoSave an, Log 1 s, Zaehler 59
        assertTrue(PM.autoSave(m))
        PM.applyOutputOptions(m, saveToSd = false, autoSave = true, logIntervalRaw = 25)
        assertEquals("Asynchron", PM.channelModeName(m))      // bleibt fest
        assertEquals("An", PM.autoSaveName(m))
        assertEquals("2.5 s", PM.logIntervalText(m))
        assertEquals("0.5–60.0 s", PM.LOG_INTERVAL.range())
        assertEquals("0–9999", PM.RUN_COUNT.range())
        assertEquals(PM.OFF_RUNCOUNT, PM.RUN_COUNT.off)
        // alle echten Programme liegen in den Reglergrenzen
        for (file in java.io.File(TestData.ASSETS, "programs").listFiles { x -> x.name.endsWith(".bin") }!!) {
            val b = file.readBytes()
            assertTrue(file.name, PM.LOG_INTERVAL.inRange(PM.LOG_INTERVAL.raw(b)))
            assertTrue(file.name, PM.RUN_COUNT.inRange(PM.RUN_COUNT.raw(b)))
        }
    }

    @Test fun akkuZuAkkuAllowedWithHint() {
        assertEquals("Von Akku zu Akku", PM.OUTPUT_LOADS[PM.AKKU_ZU_AKKU])
        assertFalse(PM.outputLimitsEditable(PM.AKKU_ZU_AKKU))          // Grenzen ausgegraut
        assertTrue(PM.AKKU_ZU_AKKU_HINT.contains("in der App mit „Entladen“"))
        assertTrue(PM.AKKU_ZU_AKKU_HINT.contains("am Ladegerät mit „Laden“"))
        assertTrue(PM.AKKU_ZU_AKKU_HINT.contains("3501") && PM.AKKU_ZU_AKKU_HINT.contains("1305"))
    }

    @Test fun akkuZuAkkuErrorTexts() {
        assertEquals("Anderer Ausgang ist belegt", ERR_TEXT[errCode(0x2301)])     // 0x2301 -> 3501
        assertTrue(ChannelLogic.startRejectedHint(3501).contains("zuerst der entladende Kanal"))
    }

    @Test fun packVoltageFromTypeAndCells() {
        assertEquals("3S: voll 12.60 V · nenn 11.10 V · Storage 11.55 V · leer 10.50 V",
            PM.packVoltageText(0, 3, 4.2, 3.85, 3.5))
        assertEquals("4S: voll 14.40 V · nenn 13.20 V · Storage 13.20 V · leer 12.00 V",
            PM.packVoltageText(2, 4, 3.6, 3.3, 3.0))                       // LiFe
        assertEquals("2S: voll 8.70 V · nenn 7.70 V", PM.packVoltageText(3, 2, 4.35, null, 0.0))   // LiHV, fehlende Werte weg
        assertTrue(PM.packVoltageText(0, 0, 4.2, 3.85, 3.5).startsWith("Auto"))
    }
}
