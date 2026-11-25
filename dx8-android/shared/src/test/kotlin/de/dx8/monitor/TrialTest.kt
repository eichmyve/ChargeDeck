package de.dx8.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TrialTest {
    private val exp = Trial.EXPIRES_MS
    private val h = 3_600_000L

    @Test fun expiryIs24January2027At1200SwissTime() {
        val z = ZonedDateTime.of(2027, 1, 24, 12, 0, 0, 0, ZoneId.of("Europe/Zurich"))
        assertEquals(z.toInstant().toEpochMilli(), exp)
        assertEquals("24.01.2027 12:00", Trial.EXPIRES_TEXT)
    }

    @Test fun statesOverTime() {
        assertEquals(Trial.State.ACTIVE, Trial.state(exp - 10 * 24 * h, 0))
        assertEquals(Trial.State.WARN, Trial.state(exp - 2 * 24 * h, 0))
        assertEquals(Trial.State.WARN, Trial.state(exp - 1, 0))
        assertEquals(Trial.State.EXPIRED, Trial.state(exp, 0))
        assertFalse(Trial.expired(exp - 1, 0)); assertTrue(Trial.expired(exp, 0))
    }

    @Test fun clockSetBackAfterExpiryStaysLocked() {
        val lastSeen = exp + 5 * 60_000                       // App lief schon nach Ablauf
        val now = exp - 30 * 24 * h                           // Uhr um einen Monat zurueckgestellt
        assertTrue(Trial.expired(now, lastSeen))
        assertTrue(Trial.clockRolledBack(now, lastSeen))
        assertFalse(Trial.clockRolledBack(lastSeen - 60_000, lastSeen))   // kleine Abweichung: ok
        assertEquals(lastSeen, Trial.advance(now, lastSeen))  // lastSeen geht nie zurueck
        assertEquals(exp + 10 * h, Trial.advance(exp + 10 * h, lastSeen))
    }

    @Test fun bannerTexts() {
        assertEquals("Testversion – gültig bis 24.01.2027 12:00", Trial.banner(exp - 10 * 24 * h, 0))
        assertEquals("Testversion – gültig bis 24.01.2027 12:00 (noch 2 Tage)", Trial.banner(exp - 50 * h, 0))
        assertEquals("Testversion – gültig bis 24.01.2027 12:00 (noch 5 Std.)", Trial.banner(exp - 5 * h, 0))
        assertEquals("Testzeitraum abgelaufen (24.01.2027 12:00) – Start und Programme ändern gesperrt", Trial.banner(exp + 1, 0))
    }
}
