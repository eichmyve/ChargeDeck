package de.dx8.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChargerControlTest {
    private val fake = FakeDx8()
    private val ctl = ChargerControl(Dx8Device(fake)) { }   // ohne echtes Warten

    private fun ch(k: Int, run: Int = 0, currentCa: Int = 0, err: Int = 0, ts: Long = 100, uoutMv: Int = 12600) =
        fake.setChannel(k, Samples.channelRegs(run = run, currentCa = currentCa, err = err, ts = ts, uoutMv = uoutMv))

    private fun program(slot: Int = 21, akkuZuAkku: Boolean = false): Program {
        val raw = ByteArray(192)
        if (akkuZuAkku) {
            ProgramMemory.put(raw, ProgramMemory.OFF_REGEN, 2, 2)
            ProgramMemory.put(raw, ProgramMemory.OFF_REG_CH_MODE, 1, 1)
        }
        return Program(pos = 0, slot = slot, name = "Test", type = 0, liCells = 3, chargeA = 1.0, dischargeA = 1.0,
            chargeV = 4.2, storageV = 3.85, dischargeV = 3.5, regen = if (akkuZuAkku) 2 else 0, raw = raw)
    }

    /** Lader-Verhalten: RUN auf Kanal k setzt dessen Run-Code auf [runAfter]. */
    private fun onRun(runAfter: Int, err: Int = 0) {
        fake.onWrite = { a, v -> if (a == 0x8000 && v.size == 5 && v[4] == 1) ch(v[2], run = runAfter, err = err) }
    }

    private fun refusal(block: () -> Unit): Dx8Refusal {
        try { block() } catch (e: Dx8Refusal) { return e }
        fail("Dx8Refusal erwartet"); throw IllegalStateException()
    }

    @Test fun startWritesSelectionThenRun() {
        ch(0); ch(1); onRun(7)
        assertFalse(ctl.start(program(), 0, 0, null))
        val w = fake.writes.map { it.first to it.second.toList() }
        assertEquals(0x8000 to listOf(0, 21, 0), w[0])
        assertEquals(0x8000 to listOf(0, 21, 0, 0x55AA, 1), w[1])
        assertEquals(0x8003 to listOf(0), w[2])
    }

    @Test fun startRefusedWhileRunningOrWithoutBattery() {
        ch(0, run = 7, currentCa = 150); ch(1)
        assertTrue(refusal { ctl.start(program(), 0, 0, null) }.message!!.contains("läuft gerade"))
        ch(0, uoutMv = 45000)
        assertTrue(refusal { ctl.start(program(), 0, 0, null) }.message!!.contains("kein Akku"))
        assertTrue(fake.writes.isEmpty())
    }

    @Test fun startBlockedWhenPartnerWaitsAkkuZuAkku() {
        ch(0); ch(1, run = ChannelLogic.RUN_WAITING)
        assertTrue(refusal { ctl.start(program(), 0, 0, true) }.message!!.contains("am Ladegerät"))
        // unbekanntes Programm: nur mit "Trotzdem starten"
        refusal { ctl.start(program(), 0, 0, null) }
        assertTrue(fake.writes.isEmpty())
        onRun(7)
        ctl.start(program(), 0, 0, null, forced = true)
        assertTrue(fake.writes.isNotEmpty())
    }

    @Test fun startRejectedByChargerShowsHint() {
        ch(0); ch(1); onRun(41, err = 0x0D05)
        val r = refusal { ctl.start(program(), 0, 0, null) }
        assertTrue(r.title.contains("1305"))
        assertTrue(r.message!!.contains("Hauptanzeige"))
    }

    @Test fun akkuZuAkkuStartReportsWaiting() {
        ch(0); ch(1); onRun(ChannelLogic.RUN_WAITING)
        assertTrue(ctl.start(program(akkuZuAkku = true), 2, 1, null))
        assertTrue(ctl.akkuZuAkkuHint(1).contains("Kanal 1 direkt am Ladegerät"))
    }

    @Test fun stopGoesToReadyAndChecksCurrent() {
        ch(0, run = 7, currentCa = 150, ts = 100); ch(1)
        fake.onWrite = { a, v -> if (a == 0x8002 && v.getOrNull(2) == 0) ch(v[0], run = 0, ts = 200) }
        ctl.stop(0)
        assertEquals(0, ctl.channel(0).run)
    }

    @Test fun stopDetectsChannelStillCharging() {
        // Geisterzustand: meldet Bereit, liefert aber weiter Strom
        ch(0, run = 0, currentCa = 177); ch(1)
        val r = refusal { ctl.stop(0) }
        assertTrue(r.title.contains("reagiert nicht auf STOP"))
    }

    @Test fun answerOnlyWithOpenDialog() {
        ch(0, run = 0); ch(1)
        refusal { ctl.answer(0, true) }
        ch(0, run = 12)
        ctl.answer(0, true)
        assertEquals(listOf(0, 0x55AA, 8), fake.writes.first { it.first == 0x8002 }.second.toList())
    }

    @Test fun resumeNeedsKnownTarget() {
        ch(0, run = 1); ch(1)
        fake.ctrl[0] = 0; fake.ctrl[1] = 5; fake.ctrl[2] = 1        // Register gehoeren zu Kanal 2
        refusal { ctl.resume(0, null) }
        onRun(7)
        assertEquals(2 to 21, ctl.resume(0, 2 to 21))
    }
}
