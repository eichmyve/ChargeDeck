package de.dx8.monitor

import de.dx8.monitor.FirmwareCheck.Ver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class FirmwareCheckTest {
    // Auszug aus iChargerUpdateInfo.inf, Stand 23.11.2026
    private val inf = """
        [Software]
        Ver = V2.7
        URL = http://www.jun-si.com/UploadFiles/Upgrader.zip

        [File List]
        CountEx = 13

        Type9 = iCharger DX12
        FileName9 = iCharger DX12_V117
        Ver9 = V1.17
        Updated9 = 2025/12/05
        URL9 = http://www.jun-si.com/UploadFiles/iCharger DX12_V117.bin

        Type8 = iCharger DX8
        FileName8 = iCharger DX8_V117
        Ver8 = V1.17
        Updated8 = 2025/12/05
        URL8 = http://www.jun-si.com/UploadFiles/iCharger DX8_V117.bin
        Note8 = NULL
        ReleaseURL8 = http://www.jun-si.com/UploadFiles/ReleaseNotes-iChargerDX8.txt

        Type5 = iCharger X8
        Ver5 = V2.13
    """.trimIndent().replace("\n", "\r\n")

    private val notes = """
        ////////////////////////////////////////////////////////////////
        //             Version:          V1.2.1                       //
        //             Date of Release:  2026/11/04                   //
        ////////////////////////////////////////////////////////////////
        020: Resolve the impact of startup timing on HID connections
        //             Version:          V1.1.7                       //
        //             Date of Release:  2025/12/05                   //
        //             Version:          V1.0.8    beta12                   //
        //             Date of Release:  2022/11/22                   //
    """.trimIndent()

    @Test fun versionFromRegisterMatchesDisplay() {
        assertEquals(Ver(1, 1, 7), Ver.fromCode(1107))
        assertEquals("1.17", Ver.fromCode(1107).toString())
        assertEquals("1.15", Ver.fromCode(0x0451).toString())
    }

    @Test fun parsesBothVersionStyles() {
        assertEquals(Ver(1, 1, 7), Ver.parse("V1.17"))
        assertEquals(Ver(2, 1, 3), Ver.parse("V2.13"))
        assertEquals(Ver(1, 2, 1), Ver.parse("V1.2.1"))
        assertEquals(Ver(1, 0, 8), Ver.parse("V1.0.8    beta12"))
        assertNull(Ver.parse("NULL"))
        assertTrue(Ver(1, 2, 1) > Ver(1, 1, 7))
        assertEquals("1.21", Ver(1, 2, 1).toString())
    }

    @Test fun findsDx8NotDx12() {
        val e = FirmwareCheck.parseInfo(inf)!!
        assertEquals("iCharger DX8", e.type)
        assertEquals(Ver(1, 1, 7), e.ver)
        assertEquals("2025/12/05", e.updated)
        assertTrue(e.releaseUrl.endsWith("ReleaseNotes-iChargerDX8.txt"))
        assertNull(FirmwareCheck.parseInfo(inf, "iCharger 4010DUO"))
    }

    @Test fun newestReleaseFromNotes() {
        val r = FirmwareCheck.latestRelease(notes)!!
        assertEquals(Ver(1, 2, 1), r.ver)
        assertEquals("2026/11/04", r.date)
    }

    @Test fun reportFlagsNewerRelease() {
        val t = FirmwareCheck.check(Ver.fromCode(1107)) { url -> if (url.endsWith(".inf")) inf else notes }
        assertTrue(t, t.contains("jun-si.com"))
        assertTrue(t, t.contains("1.17 vom 2025/12/05  → aktuell"))
        assertTrue(t, t.contains("1.21 vom 2026/11/04  → NEUER als am Lader"))
        assertTrue(t, t.contains("Junsi-Upgrader am PC"))
        assertTrue(t, t.contains("bietet sie noch nicht an"))
    }

    @Test fun upToDateWhenInstalledIsNewest() {
        val t = FirmwareCheck.check(Ver(1, 2, 1)) { url -> if (url.endsWith(".inf")) inf else notes }
        assertTrue(t, t.contains("neuesten Stand"))
        assertFalse(t, t.contains("NEUER"))
    }

    @Test fun fallsBackToHillrc() {
        val t = FirmwareCheck.check(Ver.fromCode(1107)) { url ->
            when {
                url.contains("jun-si.com") && url.endsWith(".inf") -> throw java.io.IOException("offline")
                url.endsWith(".inf") -> inf
                else -> throw java.io.IOException("keine Notes")
            }
        }
        assertTrue(t, t.contains("(hillrc.com)"))
        assertTrue(t, t.contains("neuesten Stand"))
    }

    @Test(expected = java.io.IOException::class)
    fun failsWhenBothServersDown() {
        FirmwareCheck.check(null) { throw java.io.IOException("offline") }
    }
}
