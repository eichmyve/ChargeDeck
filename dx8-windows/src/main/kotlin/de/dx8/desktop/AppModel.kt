package de.dx8.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.dx8.monitor.ChannelData
import de.dx8.monitor.ChannelEvent
import de.dx8.monitor.ChannelEvents
import de.dx8.monitor.ChannelLogic
import de.dx8.monitor.ChargerControl
import de.dx8.monitor.ChargerInfo
import de.dx8.monitor.Dx8Device
import de.dx8.monitor.Dx8Refusal
import de.dx8.monitor.FirmwareCheck
import de.dx8.monitor.Program
import de.dx8.monitor.TcpHidLink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.prefs.Preferences
import javax.swing.SwingUtilities

/** Ein Messpunkt fuer den Verlauf (Spannung, Strom). */
data class Sample(val t: Long, val u: Double, val i: Double, val cells: List<Int> = emptyList())

/** Hinweisfenster (Titel, Text). */
data class Notice(val title: String, val text: String)

/**
 * Zustand und Ablaeufe der Windows-App. Alle Compose-Zustaende werden nur im Swing-/UI-Thread geaendert;
 * USB-Verkehr laeuft im Abfrage-Thread bzw. in [worker] (ein Befehl nach dem anderen).
 */
class AppModel {
    private val prefs = Preferences.userRoot().node("de/dx8/chargedeck")

    // ---------------------------------------------------------------- Einstellungen
    var viaWifi by mutableStateOf(prefs.getBoolean("viaWifi", false))
    var host by mutableStateOf(prefs.get("host", "dx8bridge.local"))
    var port by mutableStateOf(prefs.getInt("port", TcpHidLink.DEFAULT_PORT))
    var token by mutableStateOf(prefs.get("token", ""))

    fun saveConnection(wifi: Boolean, h: String, p: Int, t: String) {
        viaWifi = wifi; host = h.trim(); port = p; token = t.trim()
        prefs.putBoolean("viaWifi", viaWifi); prefs.put("host", host); prefs.putInt("port", port); prefs.put("token", token)
    }

    // ---------------------------------------------------------------- Zustand
    var connText by mutableStateOf("Nicht verbunden")
    var connOk by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var demo by mutableStateOf(false)
    var channels by mutableStateOf<List<ChannelData?>>(listOf(null, null))
    var info by mutableStateOf<ChargerInfo?>(null)
    var programs by mutableStateOf<List<Program>>(emptyList())
    var loadingPrograms by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var notice by mutableStateOf<Notice?>(null)
    /** Kanal, fuer den "Trotzdem starten?" gefragt wird (Partner wartet, Programm unbekannt). */
    var askForce by mutableStateOf<Int?>(null)
    /** Offene Programmwahl zum Start: (Kanal, forced). forced = "Trotzdem starten" bestaetigt. */
    var startDialog by mutableStateOf<Pair<Int, Boolean>?>(null)
    val mismatch = mutableStateListOf(false, false)
    val history = List(2) { mutableStateListOf<Sample>() }
    /** Verlauf je Kanal als Zellansicht statt Spannung/Strom. */
    val cellChart = mutableStateListOf(false, false)
    val log = mutableStateListOf<String>()
    /** Ereignisse fuer Windows-Benachrichtigungen (vom Fenster abgeholt). */
    val events = mutableStateListOf<ChannelEvent>()

    /** Beim Start gemerkte (Betriebsart, Platz) je Kanal – fuer "Weiter" und die Akku-zu-Akku-Sperre. */
    private val startTarget = arrayOfNulls<Pair<Int, Int>>(2)
    private val prevTs = arrayOfNulls<Long>(2)
    private val userAction = LongArray(2)
    private val mismatchFrom = LongArray(2)

    @Volatile private var dev: Dx8Device? = null
    @Volatile private var ctl: ChargerControl? = null
    @Volatile private var pollGen = 0
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dx8-befehl").apply { isDaemon = true } }

    /** Im UI-Thread sofort ausfuehren, sonst dort einreihen (Reihenfolge bleibt erhalten). */
    private fun ui(block: () -> Unit) = if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)

    fun log(msg: String) = ui {
        log.add(0, SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date()) + "  " + msg)
        while (log.size > 200) log.removeAt(log.size - 1)
    }

    // ---------------------------------------------------------------- Verbinden
    fun connect() {
        if (connected || demo) return
        connText = if (viaWifi) "Verbinde mit WLAN-Brücke $host:$port …" else "Suche DX8 am USB …"; connOk = false
        val wifi = viaWifi; val h = host; val p = port; val t = token
        Thread {
            try {
                val d = if (wifi) Dx8Device(TcpHidLink(h, p, t)) else WinHidLink.open()
                val ci = try { ChargerInfo.read(d) } catch (_: Exception) { null }
                dev = d; ctl = ChargerControl(d)
                ui {
                    info = ci; connected = true; connOk = true
                    connText = (if (wifi) "WLAN-Brücke $h" else "USB") + (ci?.let { " · Firmware ${it.firmware}" } ?: "")
                }
                log("Verbunden " + (if (wifi) "über WLAN-Brücke $h:$p" else "über USB"))
                startPolling()
            } catch (e: Exception) {
                ui { connText = e.message ?: "Verbindung fehlgeschlagen"; connOk = false }
                log("Verbinden fehlgeschlagen: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    fun disconnect(msg: String = "Getrennt") {
        pollGen++
        val d = dev; dev = null; ctl = null
        try { d?.close() } catch (_: Exception) { }
        ui {
            connected = false; connOk = false; connText = msg
            channels = listOf(null, null); programs = emptyList(); info = null
            mismatch[0] = false; mismatch[1] = false
        }
    }

    private fun startPolling() {
        val gen = ++pollGen
        Thread {
            val prev = arrayOfNulls<ChannelData>(2)
            var errors = 0
            while (gen == pollGen) {
                val c = ctl ?: break
                try {
                    val chs = listOf(c.channel(0), c.channel(1))
                    val now = System.currentTimeMillis()
                    val evs = ArrayList<ChannelEvent>()
                    val mm = BooleanArray(2)
                    for (k in 0..1) {
                        evs += ChannelEvents.detect(k, prev[k], chs[k], now, userAction[k])
                        if (ChannelLogic.mismatchNow(chs[k])) { if (mismatchFrom[k] == 0L) mismatchFrom[k] = now } else mismatchFrom[k] = 0L
                        mm[k] = ChannelLogic.mismatchConfirmed(mismatchFrom[k], now)
                        prev[k] = chs[k]
                    }
                    // Rueckfragen am Lader: keine Windows-Benachrichtigung (Ja/Nein steht ohnehin auf der Kanalkarte)
                    ui { apply(chs, mm, now); events += evs.filter { it.kind != ChannelEvent.Kind.DIALOG } }
                    for (ev in evs) log("${ev.title}: ${ev.text}")
                    errors = 0
                    autoLoadPrograms(chs)
                } catch (e: Exception) {
                    if (gen != pollGen) break
                    errors++
                    log("Lesefehler ($errors/3): ${e.message}")
                    if (errors >= 3) { disconnect("Verbindung unterbrochen – bitte neu verbinden"); break }
                }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; name = "dx8-abfrage" }.start()
    }

    /** Neue Messwerte uebernehmen (UI-Thread). */
    private fun apply(chs: List<ChannelData>, mm: BooleanArray, now: Long) {
        for (k in 0..1) {
            prevTs[k] = channels[k]?.ts
            val c = chs[k]
            val h = history[k]
            // neuer Lauf (Laufzeit springt zurueck) -> Verlauf neu beginnen
            if (h.isNotEmpty() && c.ts < (channels[k]?.ts ?: 0L)) h.clear()
            if (c.isActive || c.currentA != 0.0) { h += Sample(now, c.uoutV, c.currentA, c.cells); if (h.size > 3600) h.removeAt(0) }
            mismatch[k] = mm[k]
        }
        channels = chs
    }

    private fun autoLoadPrograms(chs: List<ChannelData>) {
        if (ChannelLogic.shouldAutoLoadPrograms(connected, programs.isNotEmpty(), loadingPrograms, chs)) loadPrograms()
    }

    fun loadPrograms(then: (() -> Unit)? = null) {
        if (demo) { then?.invoke(); return }
        if (loadingPrograms) return
        if (channels.any { it?.isActive == true }) { notice = Notice("Programme", "Programme nur lesbar, wenn beide Kanäle frei sind"); return }
        val d = dev ?: run { notice = Notice("Programme", "Nicht verbunden"); return }
        ui { loadingPrograms = true }
        worker.execute {
            try {
                val list = Program.readAll(d)
                ui { programs = list; loadingPrograms = false; then?.invoke() }
                log("${list.size} Programme gelesen")
            } catch (e: Exception) {
                ui { loadingPrograms = false }
                log("Programme lesen fehlgeschlagen: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------- Befehle
    /** Befehl im Hintergrund ausfuehren; Ablehnungen als Hinweisfenster, sonstige Fehler im Protokoll. */
    private fun action(name: String, block: (ChargerControl) -> Unit) {
        if (demo) { notice = Notice(name, "Demo – nichts an den Lader gesendet."); return }
        val c = ctl ?: run { notice = Notice(name, "Nicht verbunden"); return }
        busy = true
        worker.execute {
            try { block(c); log("$name: OK") }
            catch (e: Dx8Refusal) { ui { notice = Notice(e.title, e.message ?: "") }; log("$name: ${e.message?.lineSequence()?.first()}") }
            catch (e: Exception) { ui { notice = Notice("$name fehlgeschlagen", e.message ?: "") }; log("$name fehlgeschlagen: ${e.message}") }
            finally { ui { busy = false } }
        }
    }

    /** Ist auf Kanal k ein aus der App gestartetes Akku-zu-Akku-Programm gemerkt? null = unbekannt. */
    fun akkuZuAkku(k: Int): Boolean? {
        val (op, slot) = startTarget[k] ?: return null
        val p = programs.firstOrNull { it.slot == slot } ?: return null
        return ChannelLogic.isAkkuZuAkku(op, p.raw)
    }

    fun partnerCheck(k: Int) = ChannelLogic.partnerCheck(k, channels, akkuZuAkku(1 - k))

    fun start(p: Program, op: Int, k: Int, forced: Boolean = false) {
        if (savingProgram) { notice = Notice("Start", "Programm wird gerade gespeichert – bitte kurz warten."); return }
        lastSlot[k] = p.slot; lastOp[k] = op
        val opName = de.dx8.monitor.OPERATIONS.firstOrNull { it.second == op }?.first ?: "Betriebsart $op"
        action("Start %02d %s (%s) auf Kanal %d".format(p.pos, p.name, opName, k + 1)) { c ->
            userAction[k] = System.currentTimeMillis()
            val a2a = c.start(p, op, k, akkuZuAkku(1 - k), forced)
            startTarget[k] = op to p.slot
            if (a2a) ui { notice = Notice("Kanal ${k + 1}: Akku zu Akku", c.akkuZuAkkuHint(k)) }
        }
    }

    fun hold(k: Int) = action("Halten Kanal ${k + 1}") { userAction[k] = System.currentTimeMillis(); it.hold(k) }

    fun resume(k: Int) = action("Weiter Kanal ${k + 1}") {
        userAction[k] = System.currentTimeMillis()
        startTarget[k] = it.resume(k, startTarget[k])
    }

    fun stop(k: Int) = action("Stopp Kanal ${k + 1}") { userAction[k] = System.currentTimeMillis(); it.stop(k, prevTs[k]) }

    fun answer(k: Int, yes: Boolean) = action((if (yes) "Ja" else "Nein") + " an Kanal ${k + 1}") { it.answer(k, yes) }

    fun prevTs(k: Int) = prevTs[k]

    // ---------------------------------------------------------------- Ladeprogramme (Liste und Assistent)
    enum class ManagerMode { MANAGE, TEMPLATE }
    /** Offene Programmliste (Verwalten oder Vorlage waehlen), null = zu. */
    var managerMode by mutableStateOf<ManagerMode?>(null)
    /** Offener Programm-Assistent, null = zu. */
    var wizard by mutableStateOf<ProgramDraft?>(null)
    /** Programm wird geschrieben: Start gesperrt bis zur Erfolgs- oder Fehlermeldung. */
    var savingProgram by mutableStateOf(false)

    /** "Nur möglich bei Inaktivität des Ladegerätes", wenn ein Kanal arbeitet; true = gesperrt. */
    private fun inactivityLocked(title: String): Boolean {
        val r = ChannelLogic.lockReason(channels) ?: return false
        notice = Notice(title, "Nur möglich bei Inaktivität des Ladegerätes.\n\n" + r.substringBefore(" – "))
        log("$title gesperrt: ${r.substringBefore(" – ")}")
        return true
    }

    fun openPrograms() {
        if (programs.isEmpty()) {
            if (!connected && !demo) { notice = Notice("Ladeprogramme", "Nicht verbunden"); return }
            loadPrograms { managerMode = ManagerMode.MANAGE }; return
        }
        managerMode = ManagerMode.MANAGE
    }

    /** Neues Programm: zuerst Vorlage waehlen. */
    fun newProgram() {
        if (inactivityLocked("Neues Ladeprogramm")) return
        managerMode = ManagerMode.TEMPLATE
    }

    /** Assistent oeffnen (isNew = Kopie von p als neues Programm). Standardprogramme nur ansehen/kopieren. */
    fun editProgram(p: Program, isNew: Boolean, startStep: Int = 0) {
        if (p.raw.size < 0xB2) { notice = Notice("Ladeprogramm", "Programmdaten unvollständig – bitte neu lesen"); return }
        if (isNew && inactivityLocked("Neues Ladeprogramm")) return
        managerMode = null
        wizard = ProgramDraft(p, isNew, programs.map { it.name }).also { it.step = startStep.coerceIn(0, ProgramDraft.STEPS.size - 1) }
    }

    /** Programm auf den Lader schreiben, zuruecklesen und pruefen; danach Liste neu lesen. */
    fun saveProgram(d: ProgramDraft, mem: ByteArray, name: String) {
        val p = d.p; val isNew = d.isNew
        val what = if (isNew) "Programm „$name“ anlegen" else "Programm %02d %s speichern".format(p.pos, name)
        if (inactivityLocked(what)) return
        if (demo) {
            val np = Program.parse(if (isNew) programs.size else p.pos, if (isNew) 99 else p.slot, mem)
            programs = if (isNew) programs + np else programs.map { if (it.pos == p.pos) np else it }
            notice = Notice("Ladeprogramm", "$what (Demo – nur angezeigt, nichts gesendet)")
            return
        }
        val dv = dev; val c = ctl
        if (dv == null || c == null) { notice = Notice(what, "Nicht verbunden"); return }
        log("$what …")
        savingProgram = true; busy = true
        worker.execute {
            try {
                ChannelLogic.lockReason((0..1).map { c.channel(it) })?.let { throw de.dx8.monitor.Dx8Exception(it) }
                val PM = de.dx8.monitor.ProgramMemory
                val head = PM.readHead(dv)
                val slot = if (isNew) PM.freeSlot(head) ?: throw de.dx8.monitor.Dx8Exception("kein freier Programmplatz")
                    else p.slot.also { if (it !in head.usedSlots()) throw de.dx8.monitor.Dx8Exception("Programmplatz $it nicht mehr in der Liste") }
                val back = PM.writeMemory(dv, slot, mem)
                // Kontrolle: Kennung, Name, Zellen, Betriebsarten und alle bearbeiteten Felder
                val check = (0 until 2 + PM.NAME_MAX + 1).toMutableList()
                check += PM.OFF_OPMASK; check += PM.OFF_CELLS
                check += PM.OFF_REGEN; check += PM.OFF_REGEN + 1
                check += (PM.OFF_REG_CH_MODE..PM.OFF_REG_CH_CURR + 1).toList()
                check += PM.OUTPUT_OPTION_BYTES
                check += PM.CHARGE_EXTRA_BYTES
                for (f in d.fields) for (i in 0 until f.size) check += f.off + i
                val bad = check.filter { back[it] != mem[it] }
                if (bad.isNotEmpty())
                    throw de.dx8.monitor.Dx8Exception("Kontrolle fehlgeschlagen an Byte " + bad.take(6).joinToString { "0x%02X".format(it) } +
                            if (isNew) " – Programm nicht in die Liste aufgenommen" else "")
                if (isNew) PM.appendToHead(dv, slot)
                val list = Program.readAll(dv)
                ui {
                    programs = list
                    notice = Notice("Ladeprogramm", "$what: auf Lader geschrieben und geprüft" + if (isNew) " (Platz $slot)" else "")
                }
                log("$what: geschrieben und geprüft" + if (isNew) " (Platz $slot)" else "")
            } catch (e: Exception) {
                ui { notice = Notice("$what fehlgeschlagen", e.message ?: "") }
                log("$what fehlgeschlagen: ${e.message}")
            } finally {
                ui { savingProgram = false; busy = false }
            }
        }
    }

    // ---------------------------------------------------------------- Eingangsquelle
    /** Fenster "Eingangsquelle wählen" offen? */
    var showSources by mutableStateOf(false)

    fun openSources() {
        if (info == null) { notice = Notice("Eingangsquelle", "Eingangsquellen noch nicht gelesen"); return }
        if (inactivityLocked("Eingangsquelle wählen")) return
        showSources = true
    }

    /**
     * Aktive Eingangsquelle umstellen (Systemregister 23): nur wenn beide Kanaele stehen,
     * dauerhaft speichern und zur Kontrolle zuruecklesen – wie in der App.
     */
    fun setSource(q: Int) {
        val name = "Eingangsquelle ${q + 1}"
        if (inactivityLocked(name)) return
        val regs = intArrayOf(q); val offset = 23
        if (demo) {
            val ci = info ?: return
            val sys = ci.sys.copyOf(); regs.copyInto(sys, offset)
            info = ChargerInfo(ci.info, sys)
            notice = Notice(name, "$name (Demo – nur angezeigt, nichts gesendet)")
            return
        }
        val d = dev; val c = ctl
        if (d == null || c == null) { notice = Notice(name, "Nicht verbunden"); return }
        busy = true
        log("$name einstellen …")
        worker.execute {
            try {
                ChannelLogic.lockReason((0..1).map { c.channel(it) })?.let { throw de.dx8.monitor.Dx8Exception(it) }
                d.writeSystem(offset, regs)
                d.saveSystem()
                Thread.sleep(600)
                val ci = ChargerInfo.read(d)
                val back = ci.sys.copyOfRange(offset, offset + regs.size)
                val ok = back.contentEquals(regs)
                ui {
                    info = ci
                    notice = Notice(name, if (ok) "Auf Lader geschrieben und geprüft."
                        else "Kontrolle fehlgeschlagen – gelesen ${back.joinToString()}, erwartet ${regs.joinToString()}")
                }
                log("$name: " + if (ok) "geschrieben und geprüft" else "Kontrolle fehlgeschlagen")
            } catch (e: Exception) {
                ui { notice = Notice("$name fehlgeschlagen", e.message ?: "") }
                log("$name fehlgeschlagen: ${e.message}")
            } finally { ui { busy = false } }
        }
    }

    // ---------------------------------------------------------------- Startwahl merken
    /** Zuletzt gestartetes Programm (Platz) und Betriebsart je Kanal – im Startfenster vorausgewaehlt. */
    val lastSlot = mutableStateListOf(-1, -1)
    val lastOp = mutableStateListOf(0, 0)

    // ---------------------------------------------------------------- Firmware
    var fwText by mutableStateOf<String?>(null)
    fun checkFirmware() {
        fwText = "Prüfe Firmware bei Junsi …"
        val installed = info?.firmware
        Thread {
            val t = try { FirmwareCheck.check(installed) } catch (e: Exception) { "Junsi-Server nicht erreichbar (${e.message})" }
            ui { fwText = t }
        }.apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- Demo
    /** Demo ohne Lader: echte Programme und Systemwerte aus den Assets, Kanal 1 laedt, Kanal 2 bereit. */
    fun startDemo() {
        disconnect("Demo")
        demo = true; connOk = true; connText = "Demo – kein Lader verbunden"
        val res = { n: String -> AppModel::class.java.getResourceAsStream("/$n") ?: error("Demo-Datei $n fehlt") }
        info = ChargerInfo.demo(res("system_regs.txt").bufferedReader().readText())
        val order = res("programs/order.txt").bufferedReader().readText().trim().split(',').map { it.trim().toInt() }
        programs = order.mapIndexed { pos, slot -> Program.parse(pos, slot, res("programs/mem_%02d.bin".format(slot)).readBytes()) }
        val now = System.currentTimeMillis()
        for (s in 0 until 600 step 5) {
            val u = 11.4 + s / 600.0 * 0.95
            val cell = (u * 1000 / 3).toInt()
            history[0] += Sample(now - (600 - s) * 1000L, u, 2.0 - maxOf(0.0, (s - 450) / 150.0) * 0.6,
                listOf(cell - 4 + s / 120, cell + 5 - s / 100, cell - 1))
        }
        channels = listOf(demoChannel(7, 184, 1240, listOf(4115, 4121, 4118), listOf(0, 1, 0), 23000, 12354),
            demoChannel(0, 0, 0, listOf(3842, 3845, 3840), listOf(0, 0, 0), 23000, 11527))
        log("Demo gestartet – ${programs.size} Programme")
    }

    fun stopDemo() {
        demo = false; history.forEach { it.clear() }
        disconnect("Nicht verbunden")
    }

    private fun demoChannel(run: Int, currentCa: Int, capMah: Int, cells: List<Int>, bal: List<Int>, uinMv: Int, uoutMv: Int): ChannelData {
        val g = IntArray(60)
        val ts = if (run == 0) 0L else 2_735_000L          // Laufzeit in ms
        g[0] = (ts and 0xFFFF).toInt(); g[1] = (ts shr 16).toInt()
        val pw = (uoutMv / 1000.0 * currentCa).toLong()
        g[2] = (pw and 0xFFFF).toInt(); g[3] = (pw shr 16).toInt()
        g[4] = currentCa and 0xFFFF; g[5] = uinMv; g[6] = uoutMv; g[7] = capMah
        g[9] = 334; g[10] = 0
        cells.forEachIndexed { i, v -> g[11 + i] = v }
        bal.forEachIndexed { i, v -> val r = 27 + i / 2; g[r] = if (i % 2 == 0) g[r] or (v shl 8) else g[r] or v }
        listOf(79, 81, 80).forEachIndexed { i, v -> g[35 + i] = v }
        g[51] = 240; g[52] = 365; g[55] = run
        return ChannelData.parse(g)
    }
}
