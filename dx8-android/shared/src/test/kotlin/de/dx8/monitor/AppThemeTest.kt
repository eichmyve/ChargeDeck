package de.dx8.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeTest {
    @Test fun colorHelpers() {
        assertEquals(0xFF1770B8.toInt(), rgb(0x17, 0x70, 0xB8))
        assertEquals(0x501770B8, argb(0x50, rgb(0x17, 0x70, 0xB8)))
    }

    @Test fun lookupById() {
        assertEquals(AppTheme.LIGHT, AppTheme.byId("light"))
        assertEquals(AppTheme.DARK, AppTheme.byId("dark"))
        assertEquals(AppTheme.DARK, AppTheme.byId(null))          // Standard
        assertEquals(AppTheme.DARK, AppTheme.byId("gibtsnicht"))
        assertEquals(2, AppTheme.ALL.map { it.id }.toSet().size)
    }

    @Test fun lightThemeUsesRcModelspotColors() {
        val t = AppTheme.LIGHT
        assertFalse(t.dark)
        assertEquals(rgb(0xF0, 0xF0, 0xF0), t.page)
        assertEquals(rgb(0x17, 0x70, 0xB8), t.accent)
        assertEquals(rgb(0x3A, 0x3A, 0x39), t.text)
        assertEquals(16, t.cardRadiusDp)
        assertTrue(AppTheme.DARK.dark)
    }

    @Test fun stateColorsPerTheme() {
        for (t in AppTheme.ALL) {
            assertEquals(t.error, t.stateColor(41))
            assertEquals(t.ok, t.stateColor(7)); assertEquals(t.ok, t.stateColor(10))
            assertEquals(t.warn, t.stateColor(13)); assertEquals(t.warn, t.stateColor(12))
            assertEquals(t.idle, t.stateColor(0)); assertEquals(t.idle, t.stateColor(1))
            assertEquals(t.busy, t.stateColor(5))
            assertEquals(8, t.series.size)
        }
    }

    /** Lesbarkeit: Schrift muss sich deutlich von Karte und Seite abheben (WCAG-Kontrast >= 4.5). */
    @Test fun textContrast() {
        fun lum(c: Int): Double {
            fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch(c shr 16 and 0xFF) + 0.7152 * ch(c shr 8 and 0xFF) + 0.0722 * ch(c and 0xFF)
        }
        fun contrast(a: Int, b: Int): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }
        for (t in AppTheme.ALL) {
            assertTrue("${t.id} Text/Karte", contrast(t.text, t.card) >= 4.5)
            assertTrue("${t.id} Text/Seite", contrast(t.text, t.page) >= 4.5)
            assertTrue("${t.id} Leiste", contrast(t.barText, t.bar) >= 4.5)
            assertTrue("${t.id} Hauptknopf Start", contrast(t.onFill, t.startFill) >= 4.5)
            assertTrue("${t.id} Ueberschrift", contrast(t.heading, t.card) >= 4.5)
        }
        assertNotEquals(AppTheme.DARK.page, AppTheme.LIGHT.page)
    }
}
