package de.dx8.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

class SettingsTest {
    @Before fun locale() = Locale.setDefault(Locale.US)

    private val sys get() = ChargerInfo.demo(File(TestData.ASSETS, "system_regs.txt").readText()).sys

    // ---------------------------------------------------------------- InputSource
    private val src = InputSource(21.0, 20.0, 420, true, 25.2, 15.1, 380, 70000)

    @Test fun inputSourceRoundTrip() {
        val regs = src.toRegs()
        assertEquals(listOf(210, 200, 420, 1, 252, 151, 380, 70000 and 0xFFFF, 1), regs.toList())
        val s = IntArray(60); regs.copyInto(s, InputSource.offset(2))
        assertEquals(src, InputSource.from(s, 2))
        assertEquals(42, InputSource.offset(2))
    }

    @Test fun inputSourceFromShortArrayUsesZero() {
        val q = InputSource.from(IntArray(10), 3)
        assertEquals(0.0, q.lowV, 0.0); assertEquals(0L, q.regenCap)
    }

    @Test fun inputSourceValidation() {
        assertNull(src.validate())
        assertNull(src.copy(regen = false, regenV = 10.0).validate())     // ohne Rueckspeisung egal
        val bad = mapOf(
            src.copy(lowV = 4.0) to "Unterspannung", src.copy(maxA = 0.5) to "Max. Strom",
            src.copy(powerW = 3000) to "Ladeleistung", src.copy(regenV = 70.0) to "Rückspeise-Spannung",
            src.copy(regenA = 50.0) to "Rückspeise-Strom", src.copy(regenW = 1) to "Rückspeise-Leistung",
            src.copy(regenCap = 1_000_000) to "Rückspeise-Kapazität", src.copy(regenV = 20.0) to "über der Unterspannung"
        )
        for ((s, msg) in bad) assertTrue("$msg: ${s.validate()}", s.validate()!!.contains(msg))
    }

    @Test fun regenMinimumsLikeDx8() {
        val low = src.copy(lowV = 5.0)
        assertNull(low.copy(regenV = 9.0, regenA = 1.0, regenW = 5).validate())           // genau die Minima
        assertTrue(low.copy(regenV = 8.9).validate()!!.contains("zwischen 9–60 V"))
        // Bereichstexte fuer die Anzeige "min–max" im Dialog
        assertEquals("5–60 V", InputSource.range(InputSource.LOW_V))
        assertEquals("1–80 A", InputSource.range(InputSource.MAX_A))
        assertEquals("5–2000 W", InputSource.range(InputSource.POWER_W))
        assertEquals("9–60 V", InputSource.range(InputSource.REGEN_V))
        assertEquals("1–40 A", InputSource.range(InputSource.REGEN_A))
        assertEquals("0–999900 mAh", InputSource.range(InputSource.REGEN_CAP))
        assertTrue(low.copy(regenA = 0.9).validate()!!.contains("Rückspeise-Strom"))
        assertTrue(low.copy(regenW = 4).validate()!!.contains("Rückspeise-Leistung"))
    }

    @Test fun regenValuesIgnoredWhenRegenOff() {
        // ausgeblendete Felder duerfen beliebige (z. B. ab Werk 0) Werte haben
        assertNull(src.copy(regen = false, regenV = 0.0, regenA = 0.0, regenW = 0, regenCap = 5_000_000).validate())
        assertTrue(src.copy(regen = false, lowV = 1.0).validate()!!.contains("Unterspannung"))   // Grundwerte weiter geprueft
    }

    @Test fun inputSourceCardTexts() {
        assertEquals(listOf("ab 21 V", "max 20 A", "420 W"), src.chips())
        assertEquals(listOf("ab 11.5 V", "max 60 A", "500 W"), src.copy(lowV = 11.5, maxA = 60.0, powerW = 500).chips())
        assertEquals("Rückspeisung bis 25.2 V · 15.1 A · 380 W · 70000 mAh", src.regenLine())
        assertEquals("Rückspeisung bis 25.2 V · 6 A · 151 W", src.copy(regenA = 6.0, regenW = 151, regenCap = 0).regenLine())
        assertEquals("Rückspeisung aus", src.copy(regen = false).regenLine())
        assertEquals("12", InputSource.num(12.0)); assertEquals("0.5", InputSource.num(0.5))
    }

    @Test fun inputSourceSummary() {
        assertTrue(src.summary().contains("bis 25.2 V"))
        assertTrue(src.copy(regen = false).summary().endsWith("Rückspeisung aus"))
    }

    // ---------------------------------------------------------------- ChargerInfo
    @Test fun chargerInfoDemo() {
        val ci = ChargerInfo.demo(File(TestData.ASSETS, "system_regs.txt").readText())
        assertTrue(ci.sys.size >= 77)
        assertEquals(ci.sys[23], ci.activeSource)
        assertEquals(InputSource.from(ci.sys, 1), ci.source(1))
        assertTrue(ci.serial.isNotEmpty() && ci.serial.all { it.code in 0x20..0x7E })
        val f = ci.format()
        for (h in listOf("GERÄT", "TEMPERATUR / LÜFTER", "DISPLAY / TÖNE", "ALLGEMEIN", "EINGANGSQUELLEN",
                "PRO KANAL", "UNBEKANNTE REGISTER")) assertTrue(h, f.contains(h))
        assertTrue(!f.contains("SERVO"))                                   // Servo-Tester vorlaeufig ausgeblendet
        assertTrue(SysSettings.ALL.none { it === SysSettings.SERVO })
        assertTrue(f.contains("► Quelle ${ci.activeSource + 1}"))
        assertTrue("keine ~-Markierung mehr", !f.contains("~"))
        assertTrue(f.contains("1100 / 1100 W  (50–1100 W)"))
        assertTrue(f.contains("45 / 45 W  (5–45 W)"))
    }

    @Test fun chargerInfoFormatVariants() {
        val s = sys.copyOf()
        s[0] = 1; s[20] = 0; s[21] = 1; s[69] = 3; s[24 + 3] = 1; s[24 + 7] = 0; s[24 + 8] = 0
        val f = ChargerInfo(IntArray(12), s).format()
        assertTrue(f.contains("°F")); assertTrue(f.contains("Englisch")); assertTrue(f.contains("Benutzerkalibrierung"))
        assertTrue(f.contains("Kap. ignor."))
        assertTrue(!f.contains("µs"))                                      // Servo-Tester ausgeblendet
        s[20] = 7
        val g = ChargerInfo(IntArray(12), s).format()
        assertTrue(g.contains("Code 7"))
    }

    @Test fun chargerInfoShortSystem() {
        val f = ChargerInfo(IntArray(12) { if (it == 11) 0x41 else 0 }, IntArray(10)).format()
        assertTrue(f.contains("Systembereich unvollständig (10 Register)"))
        assertTrue(f.contains("0x0041  läuft, Balancer"))
        assertTrue(ChargerInfo(IntArray(12), IntArray(10)).format().contains("0x0000  –"))
    }

    @Test fun chargerInfoReadFromDevice() {
        val fake = FakeDx8()
        fake.input[9] = 180; fake.input[0] = 0xA6
        sys.copyInto(fake.sysFlash, 0, 0, 90)
        val ci = ChargerInfo.read(Dx8Device(fake))
        assertEquals(90, ci.sys.size)
        assertEquals(sys[23], ci.activeSource)
        assertEquals(0xA6, ci.info[0])
    }

    // ---------------------------------------------------------------- SysSettings
    @Test fun sysFieldShow() {
        assertEquals("an", SysField(0, "x", bool = true).show(1))
        assertEquals("aus", SysField(0, "x", bool = true).show(0))
        assertEquals("°F", SysField(0, "x", options = listOf("°C", "°F")).show(1))
        assertEquals("Code 5", SysField(0, "x", options = listOf("a")).show(5))
        assertEquals("75.0 °C", SysField(0, "x", "°C", 10).show(750))
        assertEquals("1.0", SysField(0, "x", "", 10).show(10))
        assertEquals("1100 W", SysField(0, "x", "W").show(1100))
        assertEquals("7", SysField(0, "x").show(7))
    }

    @Test fun sliderSteps() {
        val byReg = (SysSettings.ALL + SysSettings.SERVO).flatMap { it.fields }.associateBy { it.reg }
        val bright = byReg[7]!!                     // 1–32, Schritt 1
        assertEquals(31, bright.steps); assertEquals(1, bright.rawAt(0)); assertEquals(32, bright.rawAt(31)); assertEquals(16, bright.posOf(17))
        val log = byReg[65]!!                       // 0.5–60.0 s, Schritt 0.1 s
        assertEquals(5, log.minRaw); assertEquals(600, log.maxRaw); assertEquals(595, log.steps); assertEquals(10, log.rawAt(log.posOf(10)))
        val temp = byReg[1]!!                       // 60–80 °C, Schritt 0.5 °C
        assertEquals(5, temp.stepRaw); assertEquals(40, temp.steps); assertEquals(750, temp.rawAt(temp.posOf(750)))
        val servo = byReg[70]!!                     // 500–2500 µs, Schritt 1 µs
        assertEquals(10, servo.stepRaw); assertEquals(2000, servo.steps); assertEquals(15000, servo.rawAt(servo.posOf(15000)))
        val power = byReg[60]!!                     // 50–1100 W
        assertEquals(1050, power.steps); assertEquals(1100, power.rawAt(power.steps))
        // ausserhalb liegende Werte landen am Rand, rawAt bleibt im Bereich
        assertEquals(0, power.posOf(10)); assertEquals(power.steps, power.posOf(5000)); assertEquals(1100, power.rawAt(9999))
        // alle Felder: Schieberegler deckt genau min..max ab
        for (f in byReg.values) if (!f.bool && f.options == null) {
            assertEquals(f.label, f.minRaw, f.rawAt(0)); assertEquals(f.label, f.maxRaw, f.rawAt(f.steps))
        }
    }

    @Test fun rangeTexts() {
        val f = SysSettings.CHANNEL.fields.associateBy { it.reg }
        assertEquals("50–1100 W", f[60]!!.rangeText())
        assertEquals("5–45 W", f[62]!!.rangeText())
        assertEquals("0.5–60.0 s", f[65]!!.rangeText())
        assertEquals("", f[67]!!.rangeText())                                   // an/aus
        assertEquals("", SysSettings.SERVO.fields.first { it.reg == 69 }.rangeText())   // Auswahlliste
        assertEquals("1–31", SysSettings.DISPLAY.fields.first { it.reg == 6 }.rangeText())
        // keine Hinweise auf "vermutet" mehr in den Gruppen
        assertTrue(SysSettings.ALL.none { it.note.contains("vermutet") || it.note.contains("Grenzen:") })
    }

    @Test fun groupsCoverExpectedRegisters() {
        assertEquals(3, SysSettings.ALL.size)                              // Servo-Tester vorlaeufig ausgeblendet
        assertEquals(0, SysSettings.TEMP.from); assertEquals(5, SysSettings.TEMP.to)
        assertEquals(6, SysSettings.DISPLAY.from); assertEquals(19, SysSettings.DISPLAY.to)
        assertEquals(60, SysSettings.CHANNEL.from); assertEquals(68, SysSettings.CHANNEL.to)
        assertEquals(69, SysSettings.SERVO.from); assertEquals(72, SysSettings.SERVO.to)
        // Die am Lader gelesenen Werte muessen in den erlaubten Bereichen liegen
        val s = sys
        for (g in SysSettings.ALL) for (f in g.fields) if (!f.bool && f.options == null)
            assertTrue("${f.label} = ${s[f.reg]}", s[f.reg] / f.scale.toDouble() in f.min..f.max)
        assertNull(SysSettings.TEMP.check(s))
        assertNull(SysSettings.SERVO.check(s))
    }

    @Test fun temperatureRule() {
        val s = sys.copyOf()
        s[1] = 750; s[2] = 750
        assertTrue(SysSettings.TEMP.check(s)!!.contains("unter der Abschalttemperatur"))
        s[2] = 400; s[3] = 400
        assertTrue(SysSettings.TEMP.check(s)!!.contains("Leistungsreduzierung"))
        s[3] = 100
        assertNull(SysSettings.TEMP.check(s))
    }
}
