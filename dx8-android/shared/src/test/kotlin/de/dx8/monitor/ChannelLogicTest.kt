package de.dx8.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelLogicTest {
    private fun ch(run: Int, currentCa: Int = 0) = Samples.channel(run = run, currentCa = currentCa)

    @Test fun mainButtonFollowsState() {
        assertEquals(MainAct.START, ChannelLogic.mainAct(null))
        assertEquals(MainAct.START, ChannelLogic.mainAct(ch(0)))
        assertEquals(MainAct.START, ChannelLogic.mainAct(ch(41)))            // Fehler -> neu starten
        assertEquals(MainAct.CONTINUE, ChannelLogic.mainAct(ch(1)))          // Halten -> Weiter
        assertEquals(MainAct.START, ChannelLogic.mainAct(Samples.noBattery(1)))  // Halten ohne Akku -> kein Weiter
        for (r in listOf(2, 5, 6, 7, 10, 11, 12, 13)) assertEquals("run=$r", MainAct.HOLD, ChannelLogic.mainAct(ch(r)))
    }

    @Test fun mainButtonDimming() {
        assertFalse(ChannelLogic.mainDimmed(null))
        assertFalse(ChannelLogic.mainDimmed(ch(0)))
        assertTrue(ChannelLogic.mainDimmed(Samples.noBattery()))              // Start ohne Akku
        assertTrue(ChannelLogic.mainDimmed(Samples.channel(uoutMv = 40000)))  // Start bei > 36 V
        assertTrue(ChannelLogic.mainDimmed(ch(12)))                          // Halten bei Rueckfrage
        assertFalse(ChannelLogic.mainDimmed(ch(7)))
        assertFalse(ChannelLogic.mainDimmed(ch(1)))                          // Weiter
    }

    @Test fun startBlockedWhileSavingProgram() {
        assertTrue(ChannelLogic.startBlocked(MainAct.START, true))
        assertFalse(ChannelLogic.startBlocked(MainAct.START, false))
        assertFalse(ChannelLogic.startBlocked(MainAct.HOLD, true))       // Halten/Weiter bleiben moeglich
        assertFalse(ChannelLogic.startBlocked(MainAct.CONTINUE, true))
        assertTrue(ChannelLogic.SAVING_HINT.contains("geschrieben und geprüft"))
    }

    @Test fun errorShownInBadge() {
        fun err(e: Int) = ChannelData.parse(Samples.channelRegs(run = 41, err = e))
        assertEquals("Balancer notwendig", ChannelLogic.badgeText(err(0x1E00)))   // 3000
        assertNull(ChannelLogic.errorLine(err(0x1E00)))                         // keine Zusatzzeile
        assertEquals("FEHLER", ChannelLogic.badgeText(err(0x0909)))             // 909 unbekannt
        assertEquals("Fehler 909 – unbekannt", ChannelLogic.errorLine(err(0x0909)))
        assertEquals("Laden", ChannelLogic.badgeText(ch(7)))
        assertNull(ChannelLogic.errorLine(ch(7)))
        assertEquals("FEHLER", ChannelLogic.badgeText(ch(41)))                  // Fehler ohne Code
    }

    @Test fun errorTexts() {
        assertEquals("Balancer notwendig", ERR_TEXT[errCode(0x1E00)])     // 0x1E00 -> 3000
        assertEquals("Verbindungsfehler", ERR_TEXT[errCode(0x0D05)])     // 0x0D05 -> 1305
        assertEquals("Verbindungsunterbruch am Ausgang", ERR_TEXT[errCode(0x1406)])   // 0x1406 -> 2006
        assertEquals("Verbindungsunterbruch am Ausgang", ERR_TEXT[errCode(0x1401)])   // 2001
        assertEquals("Verbindungsunterbruch am Ausgang bei Entladen", ERR_TEXT[errCode(0x1405)])   // 2005
        assertEquals("Balancerkabel", ERR_TEXT[errCode(0x1803)])                     // 2403
        assertEquals("Zellspannung über Abschaltspannung", ERR_TEXT[errCode(0x1001)])  // 0x1001 -> 1601
    }

    @Test fun stopDimming() {
        assertTrue(ChannelLogic.stopDimmed(null))
        assertTrue(ChannelLogic.stopDimmed(ch(0)))
        assertTrue(ChannelLogic.stopDimmed(ch(12)))
        assertFalse(ChannelLogic.stopDimmed(ch(1)))
        assertFalse(ChannelLogic.stopDimmed(ch(7)))
        assertFalse(ChannelLogic.stopDimmed(ch(41)))
    }

    private fun chTs(run: Int, currentCa: Int, ts: Long) =
        ChannelData.parse(Samples.channelRegs(run = run, currentCa = currentCa, ts = ts))

    @Test fun realRunningStateNotOnlyStatusRegister() {
        // Beobachtet 22.11.2026: Status 0 ("Bereit"), aber 1.79 A und Laufzeit zaehlt
        val ghost = chTs(0, 179, 98)
        assertTrue(ChannelLogic.isRunning(ghost))
        assertTrue(ChannelLogic.statusMismatch(ghost))
        assertTrue(ChannelLogic.isRunning(chTs(0, 0, 100), prevTs = 98))      // nur Laufzeit laeuft
        assertFalse(ChannelLogic.isRunning(chTs(0, 0, 73), prevTs = 73))      // steht wirklich
        assertFalse(ChannelLogic.isRunning(chTs(0, 0, 73)))
        assertFalse(ChannelLogic.statusMismatch(chTs(7, 180, 5)))            // laeuft und meldet es
        // Stopp darf dann nicht gedimmt sein
        assertFalse(ChannelLogic.stopDimmed(ghost))
        assertTrue(ChannelLogic.stopDimmed(chTs(0, 0, 73), prevTs = 73))
        assertFalse(ChannelLogic.stopDimmed(chTs(0, 0, 75), prevTs = 73))
    }

    /** Werte aus der Aufzeichnung vom 22.11.2026 (Status-1 am Lader 2 s gedrueckt). */
    private fun irCh(run: Int, ir: List<Int>, total: Int, line: Int): ChannelData {
        val g = Samples.channelRegs(run = run, cells = listOf(3986, 3986, 3985), ir = ir)
        g[51] = total; g[52] = line
        return ChannelData.parse(g)
    }

    @Test fun irMeasurementDetected() {
        val before = irCh(0, listOf(74, 79, 82), 235, 360)
        val during = irCh(43, listOf(74, 79, 82), 235, 360)
        val after = irCh(0, listOf(77, 80, 81), 238, 349)
        assertEquals("RI-Messung", during.runText)
        assertFalse(ChannelLogic.newIrResult(null, after))              // erste Abfrage: nichts melden
        assertFalse(ChannelLogic.newIrResult(before, during))           // Werte noch unveraendert
        assertTrue(ChannelLogic.newIrResult(during, after))             // neues Ergebnis
        assertTrue(ChannelLogic.newIrResult(before, after))             // auch wenn Status 43 verpasst wurde
        assertFalse(ChannelLogic.newIrResult(after, after))
        assertFalse(ChannelLogic.newIrResult(before, irCh(7, listOf(77, 80, 81), 238, 349)))  // waehrend Laden nicht
        assertEquals("7.7 / 8.0 / 8.1 mΩ, gesamt 23.8 mΩ, Leitung 34.9 mΩ", ChannelLogic.irSummary(after))
    }

    @Test fun mismatchNeedsThreeSeconds() {
        val ri = chTs(0, -10, 3)                        // RI-Messung: kurz -0,10 A bei "Bereit"
        assertTrue(ChannelLogic.mismatchNow(ri))
        assertFalse(ChannelLogic.mismatchNow(chTs(ChannelLogic.RUN_IR, -1, 3)))   // waehrend Status 43 nie
        assertFalse(ChannelLogic.mismatchNow(chTs(0, 0, 3)))
        assertFalse(ChannelLogic.mismatchNow(chTs(7, 180, 3)))
        assertFalse(ChannelLogic.mismatchConfirmed(0, 10_000))          // kein Widerspruch
        assertFalse(ChannelLogic.mismatchConfirmed(1_000, 2_500))       // 1,5 s: noch keine Warnung
        assertTrue(ChannelLogic.mismatchConfirmed(1_000, 4_000))        // 3 s: Warnung
    }

    @Test fun lineResistanceState() {
        assertEquals(ChannelLogic.LineState.NONE, ChannelLogic.lineState(0))
        assertEquals(ChannelLogic.LineState.GOOD, ChannelLogic.lineState(343))     // 34.3 mΩ gemessen
        assertEquals(ChannelLogic.LineState.GOOD, ChannelLogic.lineState(400))
        assertEquals(ChannelLogic.LineState.WARN, ChannelLogic.lineState(401))
        assertEquals(ChannelLogic.LineState.WARN, ChannelLogic.lineState(600))
        assertEquals(ChannelLogic.LineState.BAD, ChannelLogic.lineState(601))
    }

    @Test fun packIrStatePerCell() {
        assertEquals(ChannelLogic.LineState.NONE, ChannelLogic.packIrState(0, 3))
        assertEquals(ChannelLogic.LineState.NONE, ChannelLogic.packIrState(240, 0))
        assertEquals(ChannelLogic.LineState.GOOD, ChannelLogic.packIrState(240, 3))   // 24.0 mΩ / 3S = 8 mΩ (HeatRC)
        assertEquals(ChannelLogic.LineState.GOOD, ChannelLogic.packIrState(300, 3))   // 10 mΩ/Zelle
        assertEquals(ChannelLogic.LineState.WARN, ChannelLogic.packIrState(301, 3))
        assertEquals(ChannelLogic.LineState.WARN, ChannelLogic.packIrState(1200, 6))  // 20 mΩ/Zelle
        assertEquals(ChannelLogic.LineState.BAD, ChannelLogic.packIrState(1260, 6))   // 21 mΩ/Zelle
        assertEquals(ChannelLogic.LineState.GOOD, ChannelLogic.packIrState(160, 2))   // gleiche Bewertung je Zelle
    }

    @Test fun startRejectedHints() {
        assertTrue(ChannelLogic.startRejectedHint(1305).contains("Hauptanzeige"))
        assertTrue(ChannelLogic.startRejectedHint(3100).startsWith("Zellenzahl"))
        assertTrue(ChannelLogic.startRejectedHint(4242).startsWith("Unbekannter Fehler"))
    }

    @Test fun waitingBadgeBlinks() {
        val w = ch(ChannelLogic.RUN_WAITING)
        assertTrue(ChannelLogic.blinkOn(w, 0)); assertFalse(ChannelLogic.blinkOn(w, 500)); assertTrue(ChannelLogic.blinkOn(w, 1000))
        for (t in listOf(0L, 500L, 750L)) { assertTrue(ChannelLogic.blinkOn(ch(7), t)); assertTrue(ChannelLogic.blinkOn(null, t)) }
    }

    @Test fun startHiddenUntilProgramsRead() {
        assertTrue(ChannelLogic.startHidden(MainAct.START, programsLoaded = false, demo = false))
        assertFalse(ChannelLogic.startHidden(MainAct.START, programsLoaded = true, demo = false))
        assertFalse(ChannelLogic.startHidden(MainAct.HOLD, programsLoaded = false, demo = false))      // Halten immer
        assertFalse(ChannelLogic.startHidden(MainAct.CONTINUE, programsLoaded = false, demo = false))  // Weiter immer
        assertFalse(ChannelLogic.startHidden(MainAct.START, programsLoaded = false, demo = true))
    }

    @Test fun autoLoadProgramsOnlyWhenIdle() {
        val idle = listOf(ch(0), ch(40))
        assertTrue(ChannelLogic.shouldAutoLoadPrograms(true, false, false, idle))
        assertFalse(ChannelLogic.shouldAutoLoadPrograms(false, false, false, idle))        // nicht verbunden
        assertFalse(ChannelLogic.shouldAutoLoadPrograms(true, true, false, idle))          // schon gelesen
        assertFalse(ChannelLogic.shouldAutoLoadPrograms(true, false, true, idle))          // liest gerade
        assertFalse(ChannelLogic.shouldAutoLoadPrograms(true, false, false, listOf(ch(7), ch(0))))   // Kanal laeuft
        assertFalse(ChannelLogic.shouldAutoLoadPrograms(true, false, false, listOf(ch(0, currentCa = 10), ch(0))))  // Strom
        assertFalse(ChannelLogic.shouldAutoLoadPrograms(true, false, false, listOf(ch(0), null)))
    }

    @Test fun partnerCheckDependsOnKnownProgram() {
        val w = ch(ChannelLogic.RUN_WAITING)
        val P = ChannelLogic.PartnerCheck.OK
        assertEquals(P, ChannelLogic.partnerCheck(1, listOf(ch(7), ch(0)), null))                       // kein Warten
        assertEquals(ChannelLogic.PartnerCheck.BLOCK, ChannelLogic.partnerCheck(1, listOf(w, ch(0)), true))  // Akku zu Akku bekannt
        assertEquals(P, ChannelLogic.partnerCheck(1, listOf(w, ch(0)), false))                           // anderes Programm: kein Hinweis
        assertEquals(ChannelLogic.PartnerCheck.ASK, ChannelLogic.partnerCheck(1, listOf(w, ch(0)), null))    // am Lader gestartet
        assertEquals(ChannelLogic.PartnerCheck.ASK, ChannelLogic.partnerCheck(0, listOf(ch(0), w), null))
    }

    @Test fun akkuZuAkkuFromProgramBytes() {
        val m = ByteArray(192)
        assertFalse(ChannelLogic.isAkkuZuAkku(2, m))                                     // Modus 0
        ProgramMemory.put(m, ProgramMemory.OFF_REGEN, 2, 2)
        assertFalse(ChannelLogic.isAkkuZuAkku(2, m))                                     // Zu Ausgang, Widerstand/Lampe
        ProgramMemory.put(m, ProgramMemory.OFF_REG_CH_MODE, 1, 1)
        assertTrue(ChannelLogic.isAkkuZuAkku(2, m))                                      // Zu Ausgang, Akku zu Akku
        assertFalse(ChannelLogic.isAkkuZuAkku(0, m))                                     // nur beim Entladen
        ProgramMemory.put(m, ProgramMemory.OFF_REGEN, 2, 1)
        assertFalse(ChannelLogic.isAkkuZuAkku(2, m))                                     // Zu Eingang
    }

    @Test fun partnerWaitingBlocksStart() {
        val waiting = ch(ChannelLogic.RUN_WAITING)
        assertTrue(ChannelLogic.partnerWaitingReason(1, listOf(waiting, ch(0)))!!.startsWith("Kanal 1 wartet"))
        assertTrue(ChannelLogic.partnerWaitingReason(0, listOf(ch(0), waiting))!!.startsWith("Kanal 2 wartet"))
        assertNull(ChannelLogic.partnerWaitingReason(1, listOf(ch(7), ch(0))))
        assertNull(ChannelLogic.partnerWaitingReason(0, listOf(ch(0), null)))
        assertEquals("Warten", waiting.runText)
    }

    @Test fun continueUsesOwnChannelSettings() {
        // gemerkte Werte haben Vorrang
        assertEquals(2 to 20, ChannelLogic.continueTarget(0, 2 to 20, intArrayOf(0, 20, 1)))
        // Register gehoeren zum eigenen Kanal -> verwenden
        assertEquals(2 to 20, ChannelLogic.continueTarget(0, null, intArrayOf(2, 20, 0)))
        // Register zuletzt fuer den anderen Kanal geschrieben (Laden K2) -> nicht verwenden
        assertNull(ChannelLogic.continueTarget(0, null, intArrayOf(0, 20, 1)))
        assertNull(ChannelLogic.continueTarget(1, null, null))
    }

    @Test fun newProgramLock() {
        assertNull(ChannelLogic.newProgramLockReason(listOf(ch(0), ch(0))))
        assertNull(ChannelLogic.newProgramLockReason(listOf(ch(41), ch(0))))
        assertEquals("Kanal 2: Laden – neues Programm erst anlegen, wenn beide Kanäle auf Bereit stehen",
            ChannelLogic.newProgramLockReason(listOf(ch(0), ch(7))))
        assertTrue(ChannelLogic.newProgramLockReason(listOf(ch(1), ch(0)))!!.startsWith("Kanal 1: Halten"))
        assertTrue(ChannelLogic.newProgramLockReason(listOf(ch(0), ch(12)))!!.startsWith("Kanal 2"))
        assertTrue(ChannelLogic.newProgramLockReason(listOf(ch(0, currentCa = 5), ch(0))) != null)
    }

    @Test fun settingsLock() {
        assertNull(ChannelLogic.lockReason(listOf(ch(0), ch(0))))
        assertNull(ChannelLogic.lockReason(listOf(ch(41), ch(0))))
        assertNull(ChannelLogic.lockReason(listOf(null, null)))
        assertNull(ChannelLogic.lockReason(emptyList()))
        assertTrue(ChannelLogic.lockReason(listOf(ch(0), ch(7)))!!.startsWith("Kanal 2: Laden"))
        assertTrue(ChannelLogic.lockReason(listOf(ch(1), ch(0)))!!.startsWith("Kanal 1: Halten"))   // angehalten zaehlt
        assertTrue(ChannelLogic.lockReason(listOf(ch(12), ch(0)))!!.startsWith("Kanal 1"))         // Rueckfrage
        assertTrue(ChannelLogic.lockReason(listOf(ch(0, currentCa = 5), ch(0)))!!.startsWith("Kanal 1"))  // Strom fliesst
    }
}
