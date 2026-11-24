package de.dx8.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class ChannelEventsTest {
    @Before fun locale() = Locale.setDefault(Locale.US)

    private fun ch(run: Int, currentCa: Int = 0, err: Int = 0, capMah: Long = 0, uoutMv: Int = 12600) =
        ChannelData.parse(Samples.channelRegs(run = run, currentCa = currentCa, err = err, capMah = capMah, uoutMv = uoutMv))

    private fun kinds(l: List<ChannelEvent>) = l.map { it.kind }

    @Test fun finishedWhenChargeEnds() {
        val e = ChannelEvents.detect(1, ch(7, 180, capMah = 1500), ch(0, capMah = 1500, uoutMv = 12600), 100_000)
        assertEquals(listOf(ChannelEvent.Kind.FINISHED), kinds(e))
        assertEquals("Kanal 2 fertig", e[0].title)
        assertTrue(e[0].text, e[0].text.startsWith("Laden beendet · 1500 mAh · 12.600 V"))
    }

    @Test fun finishedCode40() {
        // Display "Fertig" = Run 40 (22.11.2026, Storage auf Kanal 1)
        val done = ch(40, capMah = 21, uoutMv = 11568)
        assertEquals("Fertig", done.runText)
        assertTrue(!done.isActive && done.isFinished)
        val e = ChannelEvents.detect(0, ch(10, 10, capMah = 21), done, 1_000, userActionMs = 900)   // auch kurz nach Bedienung
        assertEquals(listOf(ChannelEvent.Kind.FINISHED), kinds(e))
        assertTrue(ChannelEvents.detect(0, done, done, 2_000).isEmpty())          // nur einmal
        assertTrue(ChannelEvents.detect(0, done, ch(0), 3_000).isEmpty())         // Fertig -> Bereit: nichts Neues
        assertEquals(MainAct.START, ChannelLogic.mainAct(done))                   // Hauptknopf: Start statt Halten
        assertEquals(null, ChannelLogic.lockReason(listOf(done, ch(0))))          // Einstellungen erlaubt
    }

    @Test fun noFinishedWhenUserStoppedInApp() {
        assertTrue(ChannelEvents.detect(0, ch(7, 180), ch(0), 100_000, userActionMs = 95_000).isEmpty())
        // lange nach der Bedienung: wieder melden
        assertEquals(1, ChannelEvents.detect(0, ch(7, 180), ch(0), 100_000, userActionMs = 50_000).size)
    }

    @Test fun noFinishedFromWaitingDialogOrIrOrIdle() {
        assertTrue(ChannelEvents.detect(0, ch(ChannelLogic.RUN_WAITING), ch(0), 1).isEmpty())
        assertTrue(ChannelEvents.detect(0, ch(ChannelLogic.RUN_IR), ch(0), 1).isEmpty())
        assertTrue(ChannelEvents.detect(0, ch(0), ch(0), 1).isEmpty())
        assertTrue(ChannelEvents.detect(0, ch(1), ch(0), 1).isEmpty())          // Halten -> Bereit
        assertTrue(ChannelEvents.detect(0, null, ch(0), 1).isEmpty())
    }

    @Test fun errorOnlyOnce() {
        val e = ChannelEvents.detect(0, ch(7), ch(41, err = 0x0D05), 1)
        assertEquals(listOf(ChannelEvent.Kind.ERROR), kinds(e))
        assertEquals("Kanal 1: Fehler 1305", e[0].title)
        assertTrue(ChannelEvents.detect(0, ch(41, err = 0x0D05), ch(41, err = 0x0D05), 2).isEmpty())
    }

    @Test fun dialogOnlyOnce() {
        assertEquals(listOf(ChannelEvent.Kind.DIALOG), kinds(ChannelEvents.detect(0, ch(11), ch(12), 1)))
        assertTrue(ChannelEvents.detect(0, ch(12), ch(12), 2).isEmpty())
    }

    @Test fun shortStatus() {
        assertEquals("K1 Laden +1.80 A 62 mAh", ChannelEvents.short(0, ch(7, 180, capMah = 62)))
        assertEquals("K2 Bereit", ChannelEvents.short(1, ch(0)))
        assertEquals("K1 –", ChannelEvents.short(0, null))
    }
}
