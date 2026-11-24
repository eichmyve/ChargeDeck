package de.dx8.monitor

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * App <-> WLAN-Bruecke: kleine Test-Bruecke nach dem Protokoll von dx8bridge.py (Anmeldung, 64-Byte-Frames,
 * Steuer-Frame 'G'), dahinter der nachgebaute Lader. Prueft den kompletten Weg Dx8Device -> TCP -> Lader.
 */
class TcpHidLinkTest {
    private lateinit var server: ServerSocket
    private val fake = FakeDx8()
    private var guard = Pair(true, 25.2)
    private val token = "geheim"

    @Before fun start() {
        ProgramMemory.pause = {}
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s: Socket = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { serve(s) }
            }
        }
    }

    @After fun stop() { server.close() }

    private fun serve(s: Socket) = s.use {
        val inp = s.getInputStream(); val out = s.getOutputStream()
        val line = StringBuilder()
        while (true) { val b = inp.read(); if (b < 0 || b == '\n'.code) break; line.append(b.toChar()) }
        val parts = line.toString().split(" ")
        if (parts.size < 3 || parts[0] != "DX8BRIDGE" || parts[2] != token) { out.write("ERR Anmeldung\n".toByteArray()); return }
        out.write("OK 1\n".toByteArray())
        val f = ByteArray(64)
        while (true) {
            var n = 0
            while (n < 64) { val r = inp.read(f, n, 64 - n); if (r < 0) return; n += r }
            if ((f[0].toInt() and 0xFF) == 0xFF) {
                if (f[1] == 'G'.code.toByte()) guard = Pair(f[2].toInt() != 0, (((f[3].toInt() and 0xFF) shl 8) or (f[4].toInt() and 0xFF)) / 1000.0)
                val mv = Math.round(guard.second * 1000).toInt()
                // Bruecke schickt zuerst einen fremden Frame (soll ignoriert werden), dann die Antwort in zwei Teilen
                out.write(ByteArray(64).also { it[0] = 8; it[1] = 0x10 })
                val resp = ByteArray(64).also { it[0] = 0xFF.toByte(); it[1] = 'G'.code.toByte(); it[2] = if (guard.first) 1 else 0; it[3] = (mv shr 8).toByte(); it[4] = mv.toByte() }
                out.write(resp, 0, 10); out.flush(); Thread.sleep(20); out.write(resp, 10, 54); out.flush()
                continue
            }
            fake.send(f)
            val r = ByteArray(64)
            if (fake.receive(r, 100) > 0) { out.write(r); out.flush() }
        }
    }

    private fun device(tok: String = token) = Dx8Device(TcpHidLink("127.0.0.1", server.localPort, tok))

    @Test fun readsAndWritesThroughBridge() {
        fake.input[0x105] = 23900
        val d = device()
        assertTrue(d.viaBridge)
        assertEquals(23900, d.read(4, 0x105, 1)[0])
        d.stop(1)
        assertEquals(listOf(0), fake.orders)
        assertEquals(1, fake.ctrl[2])
        d.close()
    }

    @Test fun readsManyAndProgramsThroughBridge() {
        val order = fake.loadAssets()
        val d = device()
        val list = Program.readAll(d)
        assertEquals(order.size, list.size)
        assertEquals("LiPo_12A", list[0].name)
        d.close()
    }

    @Test fun guardSettingConfirmed() {
        val d = device()
        assertEquals(Pair(false, 24.0), d.setBridgeGuard(false, 24.0))
        assertEquals(Pair(true, 25.2), d.setBridgeGuard(true, 25.2))
        d.close()
    }

    @Test fun wrongPasswordRejected() {
        try { device("falsch"); throw AssertionError("keine Exception") }
        catch (e: Dx8Exception) { assertTrue(e.message!!, e.message!!.contains("Passwort")) }
    }

    @Test fun unreachableBridge() {
        val port = ServerSocket(0).use { it.localPort }      // sicher freier Port
        try { TcpHidLink("127.0.0.1", port, token, 1000); throw AssertionError("keine Exception") }
        catch (e: Dx8Exception) { assertTrue(e.message!!, e.message!!.contains("nicht erreichbar")) }
    }

    @Test fun usbDeviceHasNoBridgeGuard() {
        assertEquals(null, Dx8Device(FakeDx8()).setBridgeGuard(true, 25.2))
    }

    @Test fun guardFrameEncoding() {
        val f = TcpHidLink.guardFrame(true, 25.2)
        assertEquals(64, f.size)
        assertEquals(0xFF, f[0].toInt() and 0xFF); assertEquals('G'.code, f[1].toInt())
        assertEquals(1, f[2].toInt()); assertEquals(25200, ((f[3].toInt() and 0xFF) shl 8) or (f[4].toInt() and 0xFF))
    }
}
