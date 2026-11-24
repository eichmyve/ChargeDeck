package de.dx8.monitor

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val actionPerm = "de.dx8.monitor.USB_PERMISSION"
    /** Lesefehler hintereinander, bevor neu verbunden wird; Dauer der Neu-Verbinden-Versuche. */
    private val READ_RETRIES = 3
    private val RECONNECT_MS = 120_000L
    private lateinit var usb: UsbManager
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile private var dev: Dx8Device? = null
    @Volatile private var polling = false
    @Volatile private var demo = false
    @Volatile private var last: Array<ChannelData?> = arrayOfNulls(2)
    @Volatile private var uinLimit = 25.2
    @Volatile private var guardOn = true
    private var programs: List<Program> = emptyList()
    private val lastProgram = IntArray(2) { -1 }      // zuletzt gewaehltes Programm je Kanal (Index in programs)
    private val lastOp = IntArray(2)                  // zuletzt gewaehlte Betriebsart je Kanal
    private val run12Since = LongArray(2)
    private val chargerDialogs = arrayOfNulls<AlertDialog>(2)

    // Views
    private lateinit var tvConn: TextView
    private lateinit var gIn: GaugeView
    private lateinit var gTemp: GaugeView
    private val cGreen get() = Ui.t.ok
    private val cOrange get() = Ui.t.warn
    private val cRed get() = Ui.t.error

    /**
     * Eingang: rot unter der Unterspannung der aktiven Quelle, orange ab STOP-Grenze.
     * Temperatur: gruen bis 40 °C, orange bis 50 °C, darueber rot; Marke = Luefter-Einschaltpunkt.
     */
    private fun updateGauges(uin: Double?, temp: Double?) {
        val ci = chargerInfo
        val low = ci?.let { it.source(it.activeSource).lowV }
        val z = ArrayList<Pair<Double, Int>>()
        if (low != null) { z += 0.0 to cRed; z += low to cGreen } else z += 0.0 to cGreen
        if (guardOn) z += uinLimit - 0.2 to cOrange
        gIn.zones = z
        gIn.marks = listOfNotNull(low?.let { it to cRed }, if (guardOn) uinLimit to cOrange else null)
        gIn.value = uin
        val fan = ci?.sys?.getOrNull(2)?.let { it / 10.0 }
        gTemp.marks = listOfNotNull(fan?.let { it to Ui.t.accent })
        gTemp.value = temp

        // Luefter: der DX8 meldet den Luefterzustand nicht ueber USB. Abgeleitet aus den Systemwerten:
        // an ab "Luefter an ab"-Temperatur, danach Nachlauf (Systemwert 5, Minuten) nach Unterschreiten.
        val now = System.currentTimeMillis()
        if (fan != null && temp != null && temp >= fan) fanRunUntil = now + (ci.sys.getOrElse(5) { 0 }) * 60_000L
        val running = now < fanRunUntil || (fan != null && temp != null && temp >= fan)
        fanView.spinning = running
        fanBox.visibility = if (running) View.VISIBLE else View.INVISIBLE
    }
    private lateinit var fanBox: LinearLayout
    private lateinit var fanView: FanView
    private var fanRunUntil = 0L
    private val tvState = arrayOfNulls<TextView>(2)
    // Hauptknopf (Start -> Halten -> Weiter) und Stopp je Kanal
    private val btnMain = arrayOfNulls<LinearLayout>(2)
    private val btnMainIcon = arrayOfNulls<MediaIconView>(2)
    private val btnMainText = arrayOfNulls<TextView>(2)
    private val btnStop = arrayOfNulls<LinearLayout>(2)
    private val fillGreen get() = Ui.t.startFill
    private val fillOrange get() = Ui.t.holdFill
    /** Programm-Beschreibung des letzten Starts je Kanal (fuer CSV bei "Weiter"). */
    private val lastRunInfo = arrayOfNulls<String>(2)
    /** Beim Start per App gemerkt: (Betriebsart, Programmplatz) je Kanal – fuer "Weiter". */
    @Volatile private var startTarget: Array<Pair<Int, Int>?> = arrayOfNulls(2)
    /** Nutzer hat "Trotzdem starten" bestaetigt (anderer Kanal wartet, Programm unbekannt). */
    private val forceStart = BooleanArray(2)

    /** Wurde Kanal [k] aus der App mit einem Akku-zu-Akku-Programm gestartet? null = unbekannt (am Lader gestartet). */
    private fun otherAkkuZuAkku(k: Int): Boolean? {
        val (op, slot) = startTarget[k] ?: return null
        val p = programs.firstOrNull { it.slot == slot } ?: return null
        return ChannelLogic.isAkkuZuAkku(op, p.raw)
    }
    /** Laufzeit der vorigen Abfrage je Kanal (erkennt laufende Kanaele, die "Bereit" melden). */
    private val prevTs = arrayOfNulls<Long>(2)
    private val curTs = arrayOfNulls<Long>(2)
    private val mismatchWarned = BooleanArray(2)
    private val mismatchSince = LongArray(2)
    /** Vorige Abfrage je Kanal (erkennt neue RI-Messwerte). */
    private val prevSample = arrayOfNulls<ChannelData>(2)

    private fun popup(title: String, msg: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }
    private val tvValues = arrayOfNulls<TextView>(2)
    private val tvCells = arrayOfNulls<TextView>(2)
    private val cellBox = arrayOfNulls<LinearLayout>(2)
    private val tvErr = arrayOfNulls<TextView>(2)
    private val tvClock = arrayOfNulls<TextView>(2)
    private lateinit var tvLog: TextView
    private lateinit var tvSource: TextView
    private lateinit var srcButton: LinearLayout
    private lateinit var btnConnect: Button
    @Volatile private var chargerInfo: ChargerInfo? = null
    /** CSV-Aufzeichnung in Download/DX8: abgeschaltet (keine Dateien mehr). */
    private val csvOn = false
    private val logger by lazy { RunLogger(this) { msg -> ui.post { notify(msg) } } }

    // Stoppuhr: letzter Laufzeitwert vom Lader + lokale Zeit, zu der er kam
    private val clockBaseMs = LongArray(2)
    private val clockAtMs = LongArray(2)
    private val clockRunning = BooleanArray(2)

    // Verlauf je Kanal und offene Grafik-Fenster
    private val histories = Array(2) { History() }
    private class ChartPopup(val dialog: AlertDialog, val summary: TextView, val uiChart: ChartView, val cellChart: ChartView)
    private val chartPopups = arrayOfNulls<ChartPopup>(2)

    private val ticker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            for (k in 0..1) {
                val tv = tvClock[k] ?: continue
                var ms = clockBaseMs[k]
                if (clockRunning[k]) ms += minOf(now - clockAtMs[k], 1500L)   // zwischen den Abfragen weiterzaehlen
                tv.text = "⏱ " + fmtClock(ms)
                tv.setTextColor(if (clockRunning[k]) Ui.t.text else Ui.t.textMuted)
                // "Warten" (wartet auf Partner-Kanal) blinkt wie die Kanalnummer am Lader
                tvState[k]?.alpha = if (ChannelLogic.blinkOn(last[k], now)) 1f else 0.2f
            }
            trialTick()
            ui.postDelayed(this, 250)
        }
    }

    private fun fmtClock(ms: Long): String { val s = ms / 1000; return "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) }
    private val logLines = ArrayList<String>()

    // ================================================================== USB

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                actionPerm -> {
                    val d = deviceFrom(i)
                    if (d != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect(d)
                    else setConn("USB-Berechtigung verweigert", false)
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (!viaWifi) tryConnect()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val d = deviceFrom(i)
                    if (dev?.viaBridge != true && (d == null || (d.vendorId == Dx8Device.VID && d.productId == Dx8Device.PID))) disconnect("DX8 getrennt")
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceFrom(i: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else i.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    // ---- Verbindungsart: USB direkt oder WLAN-Bruecke (Raspberry Pi) ----
    private var viaWifi = false
    private var bridgeHost = "dx8bridge.local"
    private var bridgePort = TcpHidLink.DEFAULT_PORT
    private var bridgeToken = ""

    private fun connectBridge() {
        if (bridgeHost.isBlank()) { setConn("WLAN-Brücke: Adresse in den Einstellungen eintragen", false); return }
        setConn("Verbinde mit WLAN-Brücke $bridgeHost …", false)
        connectWith { Dx8Device(TcpHidLink(bridgeHost.trim(), bridgePort, bridgeToken.trim())) }
    }

    /** Schutzgrenze an die WLAN-Bruecke uebertragen (wirkt dort auch ohne App). */
    private fun pushBridgeGuard(d: Dx8Device) {
        if (!d.viaBridge) return
        try {
            val r = d.setBridgeGuard(guardOn, uinLimit)
            if (r != null) ui.post { log("WLAN-Brücke: Schutz %s, Grenze %.2f V".format(if (r.first) "an" else "aus", r.second)) }
        } catch (e: Exception) {
            ui.post { notify("WLAN-Brücke: Schutz nicht übertragen – ${e.message}") }
        }
    }

    private fun tryConnect() {
        if (demo) stopDemo()
        if (dev != null) return
        if (viaWifi) { connectBridge(); return }
        val d = Dx8Device.find(usb)
        if (d == null) { setConn("Kein DX8 gefunden – per USB-OTG anstecken", false); return }
        if (usb.hasPermission(d)) { connect(d); return }
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(actionPerm).setPackage(packageName), flags)
        usb.requestPermission(d, pi)
        setConn("Warte auf USB-Berechtigung …", false)
    }

    /** Verbindungsaufbau laeuft gerade (verhindert doppeltes Oeffnen durch Anstecken + App-Start). */
    @Volatile private var connecting = false

    private fun connect(d: UsbDevice) = connectWith { Dx8Device.open(usb, d) }

    private fun connectWith(opener: () -> Dx8Device) {
        if (dev != null || connecting) return
        connecting = true
        worker.execute {
            try {
                if (dev != null) return@execute          // inzwischen verbunden
                val x = opener()
                val info = try { x.read(4, 0, 12) } catch (e: Exception) { x.close(); throw e }
                dev = x
                pushBridgeGuard(x)
                val via = if (x.viaBridge) " über WLAN-Brücke $bridgeHost" else ""
                ui.post {
                    setConn("Verbunden$via: DX8 (ID 0x%04X, FW %d, HW %d)".format(info[0], info[7], info[8]), true)
                    // Ueberwachung im Hintergrund: Dienst + Erlaubnis fuer Benachrichtigungen (Android 13+)
                    if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
                    MonitorService.start(this)
                }
                startPolling()
                try { val ci = ChargerInfo.read(x); chargerInfo = ci; ui.post { refreshSourceLabel() } } catch (_: Exception) { }
                // Programme sofort beim Verbinden lesen (wenn kein Kanal laeuft; sonst automatisch, sobald beide frei sind)
                val busy = (1..2).any { ChannelData.parse(x.readMany(4, it * 0x100, 60)).let { c -> c.isActive || c.currentA != 0.0 } }
                if (!busy) ui.post { loadPrograms(quiet = true) }
                else ui.post { log("Programme werden gelesen, sobald kein Kanal läuft – bis dahin ist „Start“ ausgeblendet") }
            } catch (e: Exception) {
                ui.post { setConn("Verbindung fehlgeschlagen: ${e.message}", false) }
            } finally {
                connecting = false
            }
        }
    }

    // ---- automatisch neu verbinden nach Verbindungsverlust (Dienst bleibt so lange aktiv) ----
    @Volatile private var reconnectUntil = 0L
    private val reconnectTick = object : Runnable {
        override fun run() {
            if (reconnectUntil == 0L) return
            if (dev != null) {
                reconnectUntil = 0L
                log("Wieder verbunden – Überwachung läuft weiter")
                MonitorService.alert(this@MainActivity, "Lader wieder verbunden", "Überwachung läuft weiter")
                return
            }
            if (System.currentTimeMillis() > reconnectUntil) {
                reconnectUntil = 0L
                log("Neu verbinden fehlgeschlagen – Überwachung beendet")
                MonitorService.alert(this@MainActivity, "Verbindung zum Lader verloren",
                    "Neu verbinden fehlgeschlagen – Überwachung beendet. Bitte USB prüfen und App öffnen.")
                MonitorService.stop(this@MainActivity)
                return
            }
            if (viaWifi) connectBridge()
            else {
                val d = Dx8Device.find(usb)
                if (d != null && usb.hasPermission(d)) connect(d)
            }
            ui.postDelayed(this, 3000)
        }
    }

    private fun startReconnect() {
        reconnectUntil = System.currentTimeMillis() + RECONNECT_MS
        ui.removeCallbacks(reconnectTick)
        ui.postDelayed(reconnectTick, 2000)
    }

    /** keepService = true: Verbindung kurz weg, Hintergrund-Dienst bleibt fuer das Neu-Verbinden aktiv. */
    private fun disconnect(msg: String, keepService: Boolean = false) {
        polling = false
        if (!keepService) { reconnectUntil = 0L; MonitorService.stop(this) }
        logger.closeAll()
        val d = dev
        dev = null
        try { d?.close() } catch (_: Exception) { }
        ui.post { setConn(msg, false) }
    }

    /** Letzte Bedienung je Kanal in der App (Start/Halten/Weiter/Stopp) – dadurch ausgeloestes Ende nicht melden. */
    private val userAction = LongArray(2)
    private fun markUser(k: Int) { userAction[k] = System.currentTimeMillis() }

    /** Genau ein Abfrage-Thread: ein neuer beendet den alten (sonst stehlen sich zwei Threads die Antworten). */
    @Volatile private var pollGen = 0

    private fun startPolling() {
        polling = true
        val gen = ++pollGen
        Thread {
            val prev = arrayOfNulls<ChannelData>(2)
            val mismatchFrom = LongArray(2)
            val mismatchSent = BooleanArray(2)
            var errors = 0
            while (polling && gen == pollGen) {
                val d = dev ?: break
                try {
                    val chs = Array(2) { k -> ChannelData.parse(d.readMany(4, (k + 1) * 0x100, 60)) }
                    last = arrayOf(chs[0], chs[1])
                    guard(d, chs)
                    // Ereignisse auch im Hintergrund melden (Benachrichtigung mit Ton)
                    val now = System.currentTimeMillis()
                    for (k in 0..1) {
                        for (ev in ChannelEvents.detect(k, prev[k], chs[k], now, userAction[k])) {
                            MonitorService.alert(this, ev.title, ev.text)
                            ui.post { log("${ev.title}: ${ev.text}") }
                        }
                        if (ChannelLogic.mismatchNow(chs[k])) { if (mismatchFrom[k] == 0L) mismatchFrom[k] = now } else { mismatchFrom[k] = 0L; mismatchSent[k] = false }
                        if (ChannelLogic.mismatchConfirmed(mismatchFrom[k], now) && !mismatchSent[k]) {
                            mismatchSent[k] = true
                            MonitorService.alert(this, "Kanal ${k + 1}: Anzeige stimmt nicht",
                                "Meldet „${chs[k].runText}“, liefert aber %+.2f A – am Lader prüfen".format(chs[k].currentA))
                        }
                        prev[k] = chs[k]
                    }
                    MonitorService.update(this, ChannelEvents.short(0, chs[0]) + "\n" + ChannelEvents.short(1, chs[1]))
                    ui.post { render(chs) }
                    errors = 0
                } catch (e: Exception) {
                    if (!polling) break
                    errors++
                    ui.post { log("Lesefehler ($errors/$READ_RETRIES): ${e.message}") }
                    if (errors < READ_RETRIES) {          // einzelne Fehler: einfach erneut lesen
                        try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                        continue
                    }
                    // mehrfach hintereinander: Verbindung neu aufbauen, Ueberwachung bleibt aktiv
                    MonitorService.alert(this, "Verbindung zum Lader unterbrochen", "Neu verbinden läuft …")
                    disconnect("Verbindung unterbrochen – verbinde neu …", keepService = true)
                    ui.post { startReconnect() }
                    break
                }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    /** Schutz: STOP, wenn die Eingangsspannung die Grenze erreicht (nicht waehrend einer offenen Rueckfrage). */
    private fun guard(d: Dx8Device, chs: Array<ChannelData>) {
        if (!guardOn) return
        for (k in 0..1) {
            val c = chs[k]
            if (c.isActive && c.run != 12 && c.uinV >= uinLimit) {
                try {
                    d.stop(k)
                    markUser(k)   // eigenes STOP: Ende nicht zusaetzlich als "fertig" melden
                    MonitorService.alert(this, "Kanal ${k + 1}: STOP durch Schutz Eingangsbatterie",
                        "Eingang %.2f V ≥ Grenze %.2f V".format(c.uinV, uinLimit))
                    ui.post { log("Eingang %.2f V ≥ %.2f V → STOP Kanal %d".format(c.uinV, uinLimit, k + 1)) }
                } catch (_: Exception) { }
            }
        }
    }

    private fun action(name: String, block: (Dx8Device) -> Unit) {
        if (demo) { notify("$name (Demo – nichts gesendet)"); return }
        val d = dev ?: run { notify("Nicht verbunden"); return }
        worker.execute {
            try { block(d); ui.post { log("$name: OK") } }
            catch (e: Exception) { ui.post { notify("$name fehlgeschlagen: ${e.message}") } }
        }
    }

    private fun readChannel(d: Dx8Device, k: Int) = ChannelData.parse(d.readMany(4, (k + 1) * 0x100, 60))

    // ================================================================== Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Lautstaerketasten in der App = Medienlautstaerke (damit laufen auch die Meldungstoene)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        loadSettings()
        applyThemeChrome()
        buildUi()
        ui.post(ticker)
        val f = IntentFilter().apply {
            addAction(actionPerm)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)
        if (intent?.getBooleanExtra("demo", false) == true) { startDemo(); demoEditor(intent) } else tryConnect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("demo", false)) { startDemo(); demoEditor(intent) } else tryConnect()
    }

    /** Nur Demo (Vorfuehrung/Screenshots): Programm-Assistent direkt oeffnen, z. B. --ei editPos 0 --ei step 1 --ez pro true. */
    private fun demoEditor(i: Intent) {
        val pos = i.getIntExtra("editPos", -1)
        if (pos < 0) return
        val step = i.getIntExtra("step", 0); val pro = i.getBooleanExtra("pro", false)
        ui.postDelayed({
            if (demo) programs.getOrNull(pos)?.let { editProgram(it, false, step, pro) }
        }, 1200)
    }

    /** Zurueck-Taste: bei Verbindung nur in den Hintergrund – Ueberwachung und Schutz laufen weiter. */
    @Deprecated("Activity-Zurueck")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (dev != null && !demo) {
            moveTaskToBack(true)
            Toast.makeText(this, "Überwachung läuft im Hintergrund weiter", Toast.LENGTH_SHORT).show()
        } else super.onBackPressed()
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) { }
        ui.removeCallbacks(ticker)
        demo = false
        disconnect("beendet")
        worker.shutdown()
        super.onDestroy()
    }

    // ================================================================== Demo-Modus

    /** Spielt echte Messwerte vom 21.11.2026 ab (K1: regeneratives Entladen 10 A, K2: Laden 2 A). */
    private fun startDemo() {
        if (demo) return
        if (dev != null) disconnect("getrennt für Demo")
        demo = true
        setConn("DEMO – aufgezeichnete Messwerte vom 21.11.2026", true)
        loadDemoPrograms()
        chargerInfo = ChargerInfo.demo(assets.open("system_regs.txt").bufferedReader().readText())
        refreshSourceLabel()
        val k1 = readDemo("demo_k1.csv")
        val k2 = readDemo("demo_k2.csv")
        Thread {
            var i = 0
            while (demo) {
                val chs = arrayOf(k1[i % k1.size], k2[i % k2.size])
                last = arrayOf(chs[0], chs[1])
                ui.post { if (demo) render(chs) }
                i++
                try { Thread.sleep(400) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopDemo() { demo = false }

    private fun readDemo(name: String): List<ChannelData> =
        assets.open(name).bufferedReader().readLines().drop(1).filter { it.isNotBlank() }.map { line ->
            val f = line.split(';')
            fun ints(s: String) = if (s.isBlank()) emptyList() else s.split('/').map { it.trim().toInt() }
            val cells = ints(f[9])
            val ir = ints(f[10]).let { l -> List(cells.size) { l.getOrElse(it) { 0 } } }
            val code = f[2].toInt()
            ChannelData(
                ts = f[0].toLong(), powerW = f[6].toDouble(), currentA = f[5].toDouble(),
                uinV = f[3].toInt() / 1000.0, uoutV = f[4].toInt() / 1000.0, capMah = f[7].toLong(),
                tInt = f[8].toDouble(), tExt = 0.0, cells = cells, balance = List(cells.size) { 0 }, ir = ir,
                irTotal = ir.sum(), irLine = 456, ctrl = 0, run = f[1].toInt(),
                err = ((code / 100) shl 8) or (code % 100), dialog = 0
            )
        }

    private fun loadDemoPrograms() {
        val order = assets.open("programs/order.txt").bufferedReader().readText().trim().split(',').map { it.trim().toInt() }
        programs = order.mapIndexed { pos, slot ->
            Program.parse(pos, slot, assets.open("programs/mem_%02d.bin".format(slot)).readBytes())
        }
        log("Demo: ${programs.size} Programme aus Aufzeichnung geladen")
    }

    // ================================================================== Anzeige

    private fun render(chs: Array<ChannelData>) {
        // Programme nachlesen, sobald beide Kanaele frei sind (falls beim Verbinden ein Kanal lief)
        if (!demo && ChannelLogic.shouldAutoLoadPrograms(dev != null, programs.isNotEmpty(), loadingPrograms, chs.toList()))
            loadPrograms(quiet = true)
        val tmax = maxOf(chs[0].tInt, chs[1].tInt)
        updateGauges(chs[0].uinV, tmax)
        val srcLocked = sourceLockReason(chs.toList()) != null
        srcButton.alpha = if (srcLocked) 0.4f else 1f
        val now = System.currentTimeMillis()
        for (k in 0..1) {
            val c = chs[k]
            // Status-Badge: Text in Statusfarbe auf getoenter Flaeche mit Rahmen
            val sc = stateColor(c)
            tvState[k]!!.text = ChannelLogic.badgeText(c)   // bei bekanntem Fehler direkt der Fehlertext
            tvState[k]!!.setTextColor(sc)
            (tvState[k]!!.background as GradientDrawable).apply {
                setColor(Color.argb(45, Color.red(sc), Color.green(sc), Color.blue(sc)))
                setStroke(dp(1), sc)
            }
            // Laufzeit merken (vorige / aktuelle Abfrage)
            prevTs[k] = curTs[k]; curTs[k] = c.ts
            // RI-Messung am Lader (Status-Taste 2 s): neues Ergebnis melden
            if (!demo && ChannelLogic.newIrResult(prevSample[k], c)) notify("RI-Messung Kanal ${k + 1}: ${ChannelLogic.irSummary(c)}")
            // Lauf beendet (nicht Halten): gemerktes Programm verwerfen – ein spaeterer Start am Lader ist unbekannt
            if (prevSample[k]?.isActive == true && !c.isActive && c.run != 1)
                startTarget = startTarget.copyOf().also { it[k] = null }
            prevSample[k] = c
            updateControls(k, c)
            tvValues[k]!!.text = "%.3f V\n%+.2f A   %+.1f W\n%d mAh   %.1f °C".format(
                c.uoutV, c.currentA, c.powerW, c.capMah, c.tInt)
            tvCells[k]!!.text = colorTarget(ChannelLogic.cellsHeader(c, cellTargetFor(k, c)))
            renderCellRows(k, c)

            // Stoppuhr nachfuehren (Lader liefert die Laufzeit in ganzen Sekunden)
            if (c.ts != clockBaseMs[k]) { clockBaseMs[k] = c.ts; clockAtMs[k] = now }
            clockRunning[k] = c.isActive && c.run != 12

            // Verlauf aufzeichnen, solange ein Programm laeuft
            if (c.isActive) histories[k].add(c)
            // CSV je Ladevorgang (nicht im Demo-Modus)
            if (!demo && csvOn) logger.update(k, c)
            chartPopups[k]?.let { updateChartPopup(k, it, c) }
            // Status "Bereit/Halten", aber Strom fliesst: deutlich warnen (einmal als Pop-up)
            // erst nach 3 s anhaltendem Widerspruch (kurze Impulse wie bei der RI-Messung ignorieren)
            if (!demo && ChannelLogic.mismatchNow(c)) { if (mismatchSince[k] == 0L) mismatchSince[k] = now } else mismatchSince[k] = 0L
            val mismatch = ChannelLogic.mismatchConfirmed(mismatchSince[k], now)
            val errLine = ChannelLogic.errorLine(c)          // nur bei unbekanntem Fehlercode
            if (errLine != null) {
                tvErr[k]!!.text = errLine
                tvErr[k]!!.visibility = View.VISIBLE
            } else if (mismatch) {
                tvErr[k]!!.text = "Achtung: Status „${c.runText}“, aber es fließen %+.2f A".format(c.currentA)
                tvErr[k]!!.visibility = View.VISIBLE
            } else tvErr[k]!!.visibility = View.GONE
            if (mismatch && !mismatchWarned[k]) {
                mismatchWarned[k] = true
                popup("Kanal ${k + 1}: Anzeige stimmt nicht",
                    "Der Lader meldet „${c.runText}“, liefert aber %+.2f A.\n\n".format(c.currentA) +
                            "Bitte am Ladegerät prüfen und mit STOP beenden. Reagiert er nicht, Lader vom Strom trennen.")
            } else if (!mismatch) mismatchWarned[k] = false

            // Rueckfrage am Display: Run 12 und 0 A seit > 1,5 s
            if (c.run == 12 && c.currentA == 0.0) {
                if (run12Since[k] == 0L) run12Since[k] = now
                else if (now - run12Since[k] > 1500 && chargerDialogs[k] == null) showChargerDialog(k)
            } else {
                run12Since[k] = 0L
                chargerDialogs[k]?.dismiss()
                chargerDialogs[k] = null
            }
        }
    }

    private fun stateColor(c: ChannelData) = Ui.t.stateColor(c.run)

    /** Kopf der Zellanzeige; der Zielwert je Zelle steht fett in Akzentfarbe. */
    private fun colorTarget(s: String): CharSequence {
        val sb = android.text.SpannableStringBuilder(s)
        val z = sb.indexOf("Ziel ")
        if (z >= 0) {
            val e = sb.indexOf("\n", z).let { if (it < 0) sb.length else it }
            sb.setSpan(android.text.style.ForegroundColorSpan(Ui.t.accent), z, e, 0)
            sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), z, e, 0)
        }
        return sb
    }

    /** Ziel-Zellspannung des laufenden Programms (nur bekannt, wenn per App gestartet). */
    private fun cellTargetFor(k: Int, c: ChannelData): String? {
        if (!c.isActive && c.run != 1) return null
        val (op, slot) = startTarget[k] ?: return null
        return ChannelLogic.cellTarget(op, programs.firstOrNull { it.slot == slot })
    }

    private fun irColor(st: ChannelLogic.LineState) = when (st) {
        ChannelLogic.LineState.GOOD -> Ui.t.ok
        ChannelLogic.LineState.WARN -> Ui.t.warn
        ChannelLogic.LineState.BAD -> Ui.t.error
        ChannelLogic.LineState.NONE -> Ui.t.text
    }

    /** Balkenende in mV: Ladeschluss des per App gestarteten Programms, wenn hoeher als 4,2 V (z. B. LiHV). */
    private fun barMaxMv(k: Int): Int {
        val slot = startTarget[k]?.second ?: return ChannelLogic.BAR_MAX_MV
        val v = programs.firstOrNull { it.slot == slot }?.chargeV ?: return ChannelLogic.BAR_MAX_MV
        return maxOf(ChannelLogic.BAR_MAX_MV, Math.round(v * 1000).toInt())
    }

    private fun styleIr(tv: TextView, s: String, st: ChannelLogic.LineState) {
        tv.text = s
        tv.setTextColor(irColor(st))
        tv.setTypeface(Typeface.MONOSPACE,
            if (st == ChannelLogic.LineState.GOOD || st == ChannelLogic.LineState.NONE) Typeface.NORMAL else Typeface.BOLD)
    }

    /**
     * Zellbereich: oben links Leitung, rechts Akku-IR gesamt (ueber der IR-Spalte); darunter je Zelle
     * Nummer + Spannung, Balken ueber die freie Breite, Waage bei aktivem Balancer, IR rechtsbuendig.
     */
    private fun renderCellRows(k: Int, c: ChannelData) {
        val box = cellBox[k] ?: return
        val head = ChannelLogic.irHead(c)
        val rows = ChannelLogic.cellRows(c, barMaxMv(k))
        val want = if (head == null) 0 else rows.size + 1
        if (box.childCount != want) {
            box.removeAllViews()
            if (head != null) box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(12f, mono = true))
                addView(View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
                addView(text(12f, mono = true).apply { gravity = Gravity.END })
            })
            // feste Breite der IR-Spalte, damit alle Balken gleich lang sind
            val irW = text(12f, mono = true).paint.measureText("88.8 mΩ ✗").toInt() + dp(2)
            repeat(if (head == null) 0 else rows.size) {
                box.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(12f, mono = true))
                    addView(CellBarView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(9), 1f).apply { setMargins(dp(8), 0, dp(4), 0) }
                    })
                    addView(BalanceIconView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { setMargins(dp(2), 0, dp(6), 0) }
                    })
                    addView(text(12f, mono = true).apply { gravity = Gravity.END; minWidth = irW })
                })
            }
        }
        if (head == null) return
        (box.getChildAt(0) as LinearLayout).let { hr ->
            styleIr(hr.getChildAt(0) as TextView, head.line, head.lineState)
            styleIr(hr.getChildAt(2) as TextView, head.total, head.totalState)
        }
        rows.forEachIndexed { i, r ->
            val row = box.getChildAt(i + 1) as LinearLayout
            (row.getChildAt(0) as TextView).text = r.label
            (row.getChildAt(1) as CellBarView).fill = r.fill
            row.getChildAt(2).visibility = if (r.balancing) View.VISIBLE else View.INVISIBLE
            styleIr(row.getChildAt(3) as TextView, r.ir, r.irState)
        }
    }

    // ================================================================== Verlauf (Grafik-Popup)

    private val cellColors get() = Ui.t.series

    private fun showChart(k: Int) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        val summary = text(14f)
        val h = (resources.displayMetrics.heightPixels * 0.28).toInt()
        val uiChart = ChartView(this).apply {
            leftUnit = "V"; rightUnit = "A"
            series = listOf(
                ChartView.Series("Spannung", Ui.t.series[0], false) { it.u },
                ChartView.Series("Strom", Ui.t.series[1], true) { it.i }
            )
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, h)
        }
        val cellChart = ChartView(this).apply {
            leftUnit = "V"
            series = (0 until 8).map { n ->
                ChartView.Series("Z${n + 1}", cellColors[n], false) { p -> p.cells.getOrNull(n)?.let { it / 1000.0 } }
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, h)
        }
        box.addView(summary)
        box.addView(label("Spannung (blau, links)  ·  Strom (orange, rechts)"))
        box.addView(uiChart)
        box.addView(label("Zellspannungen"))
        box.addView(cellChart)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Kanal ${k + 1}: Verlauf")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Schließen", null)
            .setOnDismissListener { chartPopups[k] = null }
            .create()
        val popup = ChartPopup(dlg, summary, uiChart, cellChart)
        chartPopups[k] = popup
        dlg.show()
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.95).toInt(), WRAP_CONTENT)
        last[k]?.let { updateChartPopup(k, popup, it) }
    }

    private fun updateChartPopup(k: Int, p: ChartPopup, c: ChannelData) {
        val pts = histories[k].points
        val first = pts.firstOrNull()
        p.summary.text = buildString {
            append("${c.runText}   ⏱ ${fmtClock(c.ts)}\n")
            append("%.3f V   %+.2f A   %d mAh".format(c.uoutV, c.currentA, c.capMah))
            if (first != null) append("\nseit Start: %.3f V → %.3f V,  %d Messpunkte".format(first.u, c.uoutV, pts.size))
        }
        p.uiChart.update(pts)
        p.cellChart.update(pts)
    }

    private fun showChargerDialog(k: Int) {
        chargerDialogs[k] = AlertDialog.Builder(this)
            .setTitle("Kanal ${k + 1}: Rückfrage am Lader")
            .setMessage("Der Lader wartet auf eine Bestätigung, z. B. „Regeneratives Entladen starten?“\n\n" +
                    "Ja = regeneratives Entladen in den Eingang\nNein = Standard-Entladen")
            .setCancelable(false)
            .setPositiveButton("Ja") { _, _ -> action("Ja an Kanal ${k + 1}") { it.answerDialog(k, true) } }
            .setNegativeButton("Nein") { _, _ -> action("Nein an Kanal ${k + 1}") { it.answerDialog(k, false) } }
            .show()
    }

    private var connMsg = "…"
    private var connOk = false

    private fun setConn(msg: String, ok: Boolean) {
        connMsg = msg; connOk = ok
        tvConn.text = msg
        tvConn.setTextColor(if (ok) Ui.t.ok else Ui.t.warn)
        // "Verbinden" nur zeigen, solange keine Verbindung zum Lader besteht
        btnConnect.visibility = if (dev != null) View.GONE else View.VISIBLE
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date())
        logLines.add(0, "$ts  $msg")
        while (logLines.size > 12) logLines.removeAt(logLines.size - 1)
        tvLog.text = logLines.joinToString("\n")
    }

    private fun notify(msg: String) {
        log(msg)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ================================================================== Kanal-Knoepfe

    /** Start: Programmauswahl -> Betriebsart -> Start. */
    private fun onStartPressed(k: Int) {
        if (trialBlocked()) return
        if (savingProgram) { notify(ChannelLogic.SAVING_HINT); return }
        if (demo) { if (programs.isEmpty()) loadDemoPrograms(); chooseProgram(k); return }  // Demo: nur Auswahl zeigen
        val c = last[k] ?: run { notify("Noch keine Daten von Kanal ${k + 1}"); return }
        if (c.isActive || c.currentA != 0.0) { notify("Kanal ${k + 1} läuft gerade – erst Halten/Stopp"); return }
        if (c.noBattery || c.uoutV > 36.0) { notify("Kanal ${k + 1}: kein Akku angeschlossen"); return }
        when (ChannelLogic.partnerCheck(k, last.toList(), otherAkkuZuAkku(1 - k))) {
            ChannelLogic.PartnerCheck.BLOCK -> {
                popup("Kanal ${k + 1} nicht starten", ChannelLogic.partnerWaitingReason(k, last.toList()) ?: ""); return
            }
            ChannelLogic.PartnerCheck.ASK -> if (!forceStart[k]) {
                AlertDialog.Builder(this)
                    .setTitle("Kanal ${2 - k} wartet")
                    .setMessage("Kanal ${2 - k} steht auf „Warten“ und wurde am Ladegerät gestartet – die App kennt sein Programm nicht.\n\n" +
                            "Ist es Akku zu Akku, Kanal ${k + 1} bitte am Ladegerät starten: ein Start über die App stört die Kopplung.\n\n" +
                            "Sonst kann Kanal ${k + 1} normal über die App gestartet werden.")
                    .setPositiveButton("Trotzdem starten") { _, _ -> forceStart[k] = true; onStartPressed(k) }
                    .setNegativeButton("Abbrechen", null)
                    .show()
                return
            }
            ChannelLogic.PartnerCheck.OK -> {}
        }
        if (programs.isEmpty()) {
            if (demo) loadDemoPrograms() else { loadPrograms { onStartPressed(k) }; return }
        }
        chooseProgram(k)
    }

    /** Programmwahl fuer den Start: dieselben Kacheln wie in der Programmliste, zuletzt gewaehltes hervorgehoben. */
    private fun chooseProgram(k: Int) = programPicker(PickMode.START, k)

    private fun chooseOperation(k: Int, p: Program) {
        // nur Betriebsarten anbieten, die im Programm am Lader als "aktiv" markiert sind
        val ops = OPERATIONS.filter { p.opEnabled(it.second) }
        if (ops.isEmpty()) { notify("%02d %s: keine Betriebsart aktiv – am Lader im Programm aktivieren".format(p.pos, p.name)); return }
        // zuletzt gewaehlte Betriebsart vorauswaehlen, sonst die erste aktive
        var sel = ops.indexOfFirst { it.second == lastOp[k] }.coerceAtLeast(0)
        val c = last[k]
        val akku = when {
            c == null -> ""
            c.cells.isEmpty() -> "  (Akku %.2f V, ohne Balancer)".format(c.uoutV)
            else -> "  (Akku ${c.cells.size}S, %.2f V)".format(c.uoutV)
        }
        AlertDialog.Builder(this)
            .setTitle("%02d %s – Kanal %d%s".format(p.pos, p.name, k + 1, akku))
            .setSingleChoiceItems(ops.map { it.first }.toTypedArray(), sel) { _, which -> sel = which }
            .setPositiveButton("Starten") { _, _ ->
                lastOp[k] = ops[sel].second
                start(p, ops[sel].second, ops[sel].first, k)
            }
            .setNegativeButton("Zurück") { _, _ -> chooseProgram(k) }
            .show()
    }

    private fun start(p: Program, op: Int, opName: String, k: Int) {
        if (trialBlocked()) return
        if (savingProgram) { notify(ChannelLogic.SAVING_HINT); return }
        action("Start %02d %s (%s) auf Kanal %d".format(p.pos, p.name, opName, k + 1)) { d ->
            // direkt vor dem Start nochmal pruefen
            var c = readChannel(d, k)
            if (c.isActive || c.currentA != 0.0) throw Dx8Exception("Kanal läuft")
            if (c.uoutV > 36.0) throw Dx8Exception("kein Akku")
            // Partner-Kanal wartet (Akku zu Akku)? Dann nicht ueber USB starten – stoert die Kopplung
            val other = readChannel(d, 1 - k)
            val both = if (k == 0) listOf(c, other) else listOf(other, c)
            val check = ChannelLogic.partnerCheck(k, both, otherAkkuZuAkku(1 - k))
            val forced = forceStart[k]; forceStart[k] = false
            if (check == ChannelLogic.PartnerCheck.BLOCK || (check == ChannelLogic.PartnerCheck.ASK && !forced)) {
                ui.post { popup("Kanal ${k + 1} nicht starten", ChannelLogic.partnerWaitingReason(k, both) ?: "") }
                throw Dx8Exception("Partner-Kanal wartet")
            }
            // Aus "Halten" (oder Fehler) wuerde RUN das alte Programm fortsetzen -> erst auf "Bereit" setzen
            if (c.run != 0) {
                ui.post { log("Kanal ${k + 1} steht auf ${c.runText} → erst auf Bereit") }
                d.stop(k)
                c = waitRun(d, k, setOf(0), 4000)
                if (c.run != 0) throw Dx8Exception("Kanal ${k + 1} geht nicht auf Bereit (${c.runText}) – bitte am Lader zur Hauptanzeige")
            }
            // Programm-/Kanalwahl separat setzen, zuruecklesen, dann RUN
            d.write(0x8000, intArrayOf(op, p.slot, k))
            val ctl = d.read(3, 0x8000, 3)
            if (ctl[0] != op || ctl[1] != p.slot || ctl[2] != k)
                throw Dx8Exception("Programmwahl nicht übernommen (gelesen ${ctl.joinToString()})")
            logger.programInfo[k] = "%02d %s %s".format(p.pos, p.name, opName)
            lastRunInfo[k] = logger.programInfo[k]
            markUser(k)
            d.run(op, p.slot, k)
            startTarget = startTarget.copyOf().also { it[k] = op to p.slot }
            // Akku zu Akku: der Kanal wartet jetzt auf seinen Partner – der muss am Lader gestartet werden
            val akkuZuAkku = ChannelLogic.isAkkuZuAkku(op, p.raw)
            // Kontrolle: lehnt der Lader den Start ab (Fehler 1305 = Display nicht in der Hauptanzeige)?
            val r = waitRun(d, k, setOf(2, 5, 6, 7, 10, 11, 12, 13, 17, ChannelLogic.RUN_WAITING, 41), 3000)
            if (r.run == 41) {
                val code = errCode(r.err)
                ui.post { popup("Kanal ${k + 1}: Start abgelehnt (Fehler $code)", ChannelLogic.startRejectedHint(code)) }
                throw Dx8Exception("Lader meldet Fehler $code")
            }
            if (akkuZuAkku) ui.post {
                popup("Kanal ${k + 1}: Akku zu Akku",
                    "Kanal ${k + 1} wartet jetzt auf seinen Partner.\n\n" +
                            "Jetzt Kanal ${2 - k} direkt am Ladegerät mit „Laden“ starten.\n\n" +
                            "Über die App lässt sich Kanal ${2 - k} solange nicht starten – der Lader würde den Start ablehnen (Fehler 1305).")
            }
        }
    }

    private fun onMainPressed(k: Int) {
        when (ChannelLogic.mainAct(last[k])) {
            MainAct.START -> onStartPressed(k)
            MainAct.HOLD, MainAct.CONTINUE -> onHoldPressed(k)
        }
    }

    /** Hauptknopf: gruen Start/Weiter, orange Halten; Stopp gedimmt, wenn der Kanal schon bereit ist. */
    private fun updateControls(k: Int, c: ChannelData?) {
        val act = ChannelLogic.mainAct(c)
        val (label, kind, fill) = when (act) {
            MainAct.START -> Triple("Start", MediaIconView.Kind.PLAY, fillGreen)
            MainAct.CONTINUE -> Triple("Weiter", MediaIconView.Kind.PLAY, fillGreen)
            MainAct.HOLD -> Triple("Halten", MediaIconView.Kind.PAUSE, fillOrange)
        }
        btnMainText[k]!!.text = label
        btnMainIcon[k]!!.kind = kind
        ((btnMain[k]!!.background as RippleDrawable).getDrawable(0) as GradientDrawable).setColor(fill)
        btnMain[k]!!.contentDescription = "$label Kanal ${k + 1}"
        // Start ohne Akku bzw. Halten bei offener Rueckfrage: sichtbar, aber gedimmt
        // Akku zu Akku: Partner wartet -> Start dieses Kanals nur am Ladegeraet (gedimmt, Tippen erklaert warum)
        val partnerWaits = act == MainAct.START &&
                ChannelLogic.partnerCheck(k, last.toList(), otherAkkuZuAkku(1 - k)) == ChannelLogic.PartnerCheck.BLOCK
        btnMain[k]!!.alpha = if (ChannelLogic.mainDimmed(c) || partnerWaits || ChannelLogic.startBlocked(act, savingProgram || trialExpired())) 0.4f else 1f
        // "Start" erst zeigen, wenn die Programme gelesen sind (Halten/Weiter immer)
        btnMain[k]!!.visibility = if (ChannelLogic.startHidden(act, programs.isNotEmpty(), demo)) View.INVISIBLE else View.VISIBLE
        btnStop[k]!!.alpha = if (ChannelLogic.stopDimmed(c, prevTs[k])) 0.35f else 1f
    }

    /** Halten: ein STOP -> Kanal haelt (Run 1). Steht der Kanal auf Halten, wird daraus "Weiter". */
    private fun onHoldPressed(k: Int) {
        val c = last[k]
        when {
            c == null -> notify("Noch keine Daten von Kanal ${k + 1}")
            c.run == 1 -> onContinuePressed(k)
            c.run == 12 -> notify("Rückfrage offen – bitte mit Ja/Nein beantworten")
            !c.isActive -> notify("Kanal ${k + 1} läuft nicht")
            else -> action("Halten Kanal ${k + 1}") { markUser(k); it.stop(k) }
        }
    }

    /**
     * Weiter: aus "Halten" mit RUN fortsetzen. Der Lader startet dabei das angehaltene Programm erneut
     * (Programmplatz in 0x8001 wird in diesem Zustand ignoriert).
     */
    private fun onContinuePressed(k: Int) {
        val c = last[k] ?: return
        if (c.noBattery || c.uoutV > 36.0) { notify("Kanal ${k + 1}: kein Akku angeschlossen"); return }
        action("Weiter Kanal ${k + 1}") { d ->
            val now = readChannel(d, k)
            if (now.run != 1) throw Dx8Exception("Kanal steht nicht mehr auf Halten (${now.runText})")
            // Betriebsart/Platz dieses Kanals: gemerkt beim Start, sonst nur wenn die Register zu diesem Kanal gehoeren
            val (op, slot) = ChannelLogic.continueTarget(k, startTarget[k], d.read(3, 0x8000, 3))
                ?: throw Dx8Exception("Betriebsart unbekannt (am Lader gestartet) – bitte am Ladegerät fortsetzen")
            logger.programInfo[k] = (lastRunInfo[k] ?: "am Lader gestartet") + " (fortgesetzt)"
            markUser(k)
            d.run(op, slot, k)
            val r = waitRun(d, k, setOf(2, 5, 6, 7, 10, 11, 12, 13, 17, 41), 4000)
            if (r.run == 1) throw Dx8Exception("Lader hat nicht fortgesetzt – bitte am Lader zur Hauptanzeige")
            if (r.run == 41) throw Dx8Exception("Fehler ${errCode(r.err)} ${ERR_TEXT[errCode(r.err)] ?: ""}".trim())
        }
    }

    /** Stopp: bis "Bereit" (laufend -> Halten -> Bereit). */
    private fun onStopPressed(k: Int) {
        val c = last[k]
        when {
            c == null -> notify("Noch keine Daten von Kanal ${k + 1}")
            c.run == 12 -> notify("Rückfrage offen – bitte mit Ja/Nein beantworten")
            c.run == 0 && !ChannelLogic.isRunning(c, prevTs[k]) -> notify("Kanal ${k + 1} ist bereits bereit")
            else -> action("Stopp Kanal ${k + 1}") { d ->
                markUser(k)
                // nicht nur dem Statusregister trauen: auch Strom zaehlt als "laeuft"
                if (ChannelLogic.isRunning(readChannel(d, k))) {
                    d.stop(k)
                    waitRun(d, k, setOf(1, 0, 41), 4000)
                }
                if (readChannel(d, k).run != 0) {
                    d.stop(k)
                    val r = waitRun(d, k, setOf(0), 4000)
                    if (r.run != 0) throw Dx8Exception("Kanal steht auf ${r.runText}")
                }
                // Kontrolle: Strom muss weg sein, Laufzeit darf nicht weiterzaehlen
                val a = readChannel(d, k); Thread.sleep(2200); val b = readChannel(d, k)
                if (ChannelLogic.isRunning(b, a.ts)) {
                    ui.post {
                        popup("Kanal ${k + 1} reagiert nicht auf STOP",
                            "Status „${b.runText}“, Strom %+.2f A, Laufzeit zählt weiter.\n\n".format(b.currentA) +
                                    "Bitte am Ladegerät mit STOP beenden. Reagiert er nicht, Lader vom Strom trennen.")
                    }
                    throw Dx8Exception("Kanal ${k + 1} läuft trotz STOP weiter")
                }
            }
        }
    }

    private fun waitRun(d: Dx8Device, k: Int, target: Set<Int>, ms: Long): ChannelData {
        val until = System.currentTimeMillis() + ms
        var c = readChannel(d, k)
        while (c.run !in target && System.currentTimeMillis() < until) {
            Thread.sleep(300)
            c = readChannel(d, k)
        }
        return c
    }

    // ================================================================== Programme lesen

    /** Programme werden gerade gelesen (verhindert doppeltes Lesen). */
    @Volatile private var loadingPrograms = false
    /** Programm wird gerade auf den Lader geschrieben: Start gesperrt bis zur Erfolgs- oder Fehlermeldung. */
    @Volatile private var savingProgram = false
    private fun setSavingProgram(on: Boolean) {
        savingProgram = on
        for (k in 0..1) if (btnMain[k] != null) updateControls(k, last[k])
    }

    private fun loadPrograms(quiet: Boolean = false, then: (() -> Unit)? = null) {
        if (demo) { loadDemoPrograms(); then?.invoke(); return }
        if (loadingPrograms) return
        if (last.any { it?.isActive == true }) { if (!quiet) notify("Programme nur lesbar, wenn beide Kanäle frei sind"); return }
        val d = dev ?: run { if (!quiet) notify("Nicht verbunden"); return }
        loadingPrograms = true
        log("Lese Programme …")
        worker.execute {
            try {
                val list = Program.readAll(d)
                ui.post {
                    programs = list
                    for (k in 0..1) if (lastProgram[k] >= list.size) lastProgram[k] = -1
                    log("${list.size} Programme gelesen")
                    for (k in 0..1) updateControls(k, last[k])   // Start-Knopf sofort einblenden
                    then?.invoke()
                }
            } catch (e: Exception) {
                ui.post { notify("Programme lesen fehlgeschlagen: ${e.message}") }
            } finally {
                loadingPrograms = false
            }
        }
    }

    // ================================================================== Ladeprogramme verwalten

    /** Liste aller Ladeprogramme als Kacheln: antippen = ansehen/aendern. */
    private fun showProgramManager() {
        if (programs.isEmpty()) {
            if (!demo && dev == null) { notify("Nicht verbunden"); return }
            loadPrograms { showProgramManager() }; return
        }
        programPicker(PickMode.MANAGE)
    }

    /** Vorlage fuer ein neues Programm waehlen (gleiche Kacheln, Standardprogramme aufgeklappt). */
    private fun chooseTemplate() {
        if (newProgramLocked()) return
        programPicker(PickMode.TEMPLATE)
    }

    /** Sperre fuer "Neues Programm": Pop-up mit Fehlermeldung, wenn ein Kanal aktiv ist. */
    private fun newProgramLocked(): Boolean = inactivityLocked("Neues Ladeprogramm")

    /** Pop-up "Nur möglich bei Inaktivität des Ladegerätes", wenn ein Kanal arbeitet; true = gesperrt. */
    private fun inactivityLocked(title: String): Boolean {
        val r = ChannelLogic.lockReason(last.toList()) ?: return false
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Nur möglich bei Inaktivität des Ladegerätes.\n\n" + r.substringBefore(" – "))
            .setPositiveButton("OK", null)
            .show()
        log("$title gesperrt: ${r.substringBefore(" – ")}")
        return true
    }

    /** "+" auf der Hauptseite: neues Ladeprogramm aus einer Vorlage anlegen. */
    private fun onNewProgramPressed() {
        if (trialBlocked()) return
        if (newProgramLocked()) return
        if (programs.isEmpty()) {
            if (!demo && dev == null) { notify("Nicht verbunden"); return }
            loadPrograms { chooseTemplate() }
            return
        }
        chooseTemplate()
    }

    private fun roundBg(fill: Int, radiusDp: Int, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat(); setColor(fill); if (stroke != 0) setStroke(dp(1), stroke)
    }

    /** Kachel: Typ-Plakette | Name, Stroeme, Betriebsart-Kuerzel. */
    private fun progTile(p: Program, selected: Boolean = false, onClick: () -> Unit): View {
        val th = Ui.t
        val PL = ProgramList
        val tc = th.series[PL.typeColorIndex(p.type)]
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = RippleDrawable(ColorStateList.valueOf(th.ripple),
                if (selected) roundBg(argb(if (th.dark) 50 else 22, th.accent), 14, th.accent).apply { setStroke(dp(2), th.accent) }
                else roundBg(th.card, 14, if (th.cardStroke != 0) th.cardStroke else th.divider), null)
            isClickable = true; isFocusable = true
            contentDescription = "Programm ${p.name}" + if (selected) " (zuletzt gewählt)" else ""
            setOnClickListener { onClick() }
        }
        // Typ-Plakette
        tile.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundBg(argb(if (th.dark) 60 else 36, tc), 10)
            layoutParams = LinearLayout.LayoutParams(dp(50), dp(50)).apply { setMargins(0, 0, dp(10), 0) }
            addView(TextView(this@MainActivity).apply {
                text = p.typeName; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(tc); gravity = Gravity.CENTER
            })
            val cl = PL.cellsLabel(p)
            if (cl.isNotEmpty()) addView(TextView(this@MainActivity).apply {
                text = cl; textSize = 11f; setTextColor(tc); gravity = Gravity.CENTER
            })
        })
        // Name, Stroeme, Kuerzel
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        mid.addView(TextView(this).apply {
            text = p.name; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(th.text)
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val sb = android.text.SpannableStringBuilder()
        fun part(s: String, color: Int, small: Boolean = false) {
            val st = sb.length; sb.append(s)
            sb.setSpan(android.text.style.ForegroundColorSpan(color), st, sb.length, 0)
            if (small) sb.setSpan(android.text.style.RelativeSizeSpan(0.85f), st, sb.length, 0)
        }
        part(PL.chargeText(p), th.text); part(" laden", th.textDim, true)
        part("  ·  ", th.textDim); part(PL.dischargeText(p), th.text); part(" entl.", th.textDim, true)
        PL.regenTag(p)?.let { part("  ", th.text); part(it, th.warn, true) }
        mid.addView(TextView(this).apply { text = sb; textSize = 13f })
        val ops = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        OPERATIONS.forEachIndexed { i, (name, op) ->
            val on = p.opEnabled(op)
            ops.addView(TextView(this).apply {
                text = PL.OP_SHORT[i]; textSize = 10f; gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (on) th.onFill else th.textMuted)
                background = roundBg(if (on) th.accent else th.divider, 5)
                contentDescription = "$name ${if (on) "aktiv" else "aus"}"
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { setMargins(0, 0, dp(4), 0) }
            })
        }
        mid.addView(ops)
        tile.addView(mid)
        return tile
    }

    /** Kacheln in Zeilen zu je [cols] verteilen. */
    private fun tileGrid(list: List<Program>, cols: Int, selected: Program? = null, onPick: (Program) -> Unit): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.chunked(cols).forEach { rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until cols) {
                val lp = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
                val p = rowItems.getOrNull(i)
                row.addView(if (p != null) progTile(p, p.slot == selected?.slot) { onPick(p) }.apply { layoutParams = lp } else View(this).apply { layoutParams = lp })
            }
            grid.addView(row)
        }
        return grid
    }

    private enum class PickMode { MANAGE, TEMPLATE, START }

    /**
     * Programm-Kacheln mit Typ-Filter und Standard-Gruppe:
     * MANAGE = Liste/Bearbeiten, TEMPLATE = Vorlage fuer neues Programm, START = Programmwahl fuer Kanal [k].
     */
    private fun programPicker(mode: PickMode, k: Int = 0) {
        val th = Ui.t
        val PL = ProgramList
        val all = programs
        val pick = mode == PickMode.TEMPLATE
        val selected = if (mode == PickMode.START) all.getOrNull(lastProgram[k]) else null
        var filter: Int? = null
        var showStd = pick || (selected?.isBuiltIn == true)
        val onPick: (Program) -> Unit = { p ->
            if (mode == PickMode.START) { lastProgram[k] = all.indexOf(p); chooseOperation(k, p) } else editProgram(p, pick)
        }
        val cols = if (resources.configuration.screenWidthDp >= 600) 2 else 1
        lateinit var dlg: AlertDialog
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(4)) }

        fun chip(label: String, on: Boolean, click: () -> Unit) = TextView(this).apply {
            text = label; textSize = 13f
            setTextColor(if (on) th.onFill else th.text)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = RippleDrawable(ColorStateList.valueOf(th.ripple),
                roundBg(if (on) th.accent else th.card, 14, if (on) th.accent else th.chipStroke), null)
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(0, 0, dp(6), 0) }
            setOnClickListener { click() }
        }
        fun section(s: String) = text(13f, bold = true).apply {
            text = s; setTextColor(th.heading); setPadding(dp(3), dp(10), 0, dp(2))
        }

        fun rebuild() {
            box.removeAllViews()
            // Kopf: Neues Programm + Filter
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chips.addView(chip("Alle ${PL.custom(all).size}", filter == null) { filter = null; rebuild() })
            for ((t, n) in PL.typeCounts(all))
                chips.addView(chip("${BATTERY_TYPES.getOrElse(t) { "Typ $t" }} $n", filter == t) { filter = t; rebuild() })
            head.addView(android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            if (mode == PickMode.MANAGE) head.addView(chip("+ Neues Programm", true) {
                if (!newProgramLocked()) { dlg.dismiss(); chooseTemplate() }
            }.apply { if (ChannelLogic.newProgramLockReason(last.toList()) != null) alpha = 0.4f })
            box.addView(head)

            // Eigene Programme
            val own = PL.custom(all, filter)
            box.addView(section("EIGENE PROGRAMME (${own.size})"))
            if (own.isEmpty()) box.addView(label("Keine eigenen Programme dieses Typs."))
            else box.addView(tileGrid(own, cols, selected) { p -> dlg.dismiss(); onPick(p) })

            // Standardprogramme (einklappbar)
            val std = PL.builtIn(all, filter)
            if (std.isNotEmpty()) {
                box.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = RippleDrawable(ColorStateList.valueOf(th.ripple),
                        roundBg(th.card, 14, if (th.cardStroke != 0) th.cardStroke else th.divider), null)
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(3), dp(10), dp(3), dp(3)) }
                    setOnClickListener { showStd = !showStd; rebuild() }
                    addView(TextView(this@MainActivity).apply {
                        textSize = 14f; setTextColor(th.text)
                        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                        text = "Standardprogramme (${std.size})\n" + std.joinToString(", ") { it.typeName } + if (mode == PickMode.START) "" else " – als Vorlage"
                    })
                    addView(TextView(this@MainActivity).apply {
                        textSize = 14f; setTextColor(th.accent); text = if (showStd) "▾ ausblenden" else "▸ anzeigen"
                    })
                })
                if (showStd) box.addView(tileGrid(std, cols, selected) { p -> dlg.dismiss(); onPick(p) })
            }
            box.addView(label(PL.LEGEND).apply { setPadding(dp(3), dp(10), 0, dp(4)); textSize = 12f })
        }
        rebuild()

        val b = AlertDialog.Builder(this)
            .setTitle(when (mode) { PickMode.TEMPLATE -> "Vorlage für das neue Programm"; PickMode.START -> "Kanal ${k + 1}: Programm wählen"; else -> "Ladeprogramme" })
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton(if (mode == PickMode.MANAGE) "Schließen" else "Abbrechen", null)
        if (mode == PickMode.MANAGE) b.setNeutralButton("Neu lesen") { _, _ -> programs = emptyList(); showProgramManager() }
        dlg = b.create()
        dlg.show()
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.95).toInt(), WRAP_CONTENT)
    }

    private val WIZARD_STEPS = listOf("Akku", "Laden", "Storage", "Entladen", "Zyklus", "Fertig")
    private val WIZARD_HINTS = listOf(
        "Name, Zellenzahl und Kapazität",
        "Ladestrom, Ladeart und Ladeschluss",
        "Lagerspannung je Zelle",
        "Entladestrom, Entlade-Ende und Rückspeisung",
        "Ablauf, Anzahl und Pause",
        "Prüfen und auf den Lader schreiben")
    private val WIZARD_PRO = listOf("", "Für Profis: Ladeendstrom, Erweitert, Sicherheit", "Für Profis: Kompensation, beschleunigte Storage",
        "Für Profis: Entlade-Endstrom", "", "")

    /** Schieberegler eines Programmfelds; get() liefert den Rohwert (unberuehrt = gelesener Wert). */
    private class ProgSlider(val get: () -> Long, val touched: () -> Boolean, val setEnabled: (Boolean) -> Unit)

    /**
     * Schieberegler wie in den Systemdialogen: Name mit "min–max", aktueller Wert rechts, darunter [−] Regler [+].
     * Der Regler deckt genau min..max des Felds im Raster [Field.step] ab.
     */
    private fun progSlider(parent: LinearLayout, f: ProgramMemory.Field, raw: Long, enabled: Boolean, onChange: () -> Unit = {}): ProgSlider {
        val sp = f.slider()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        head.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            addView(text(14f).apply { text = f.label })
            addView(text(12f).apply { text = "min–max: ${f.range()}"; setTextColor(Ui.t.textDim) })
        })
        val tvVal = text(16f, bold = true).apply { gravity = Gravity.END; minWidth = dp(100); setTextColor(Ui.t.accent) }
        head.addView(tvVal)
        parent.addView(head)
        var touched = false
        val sb = android.widget.SeekBar(this).apply {
            max = sp.steps; progress = sp.posOf(raw); isEnabled = enabled
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        fun current() = if (touched) sp.rawAt(sb.progress) else raw
        fun refresh() { tvVal.text = "${f.fmt(current())} ${f.unit}".trim(); onChange() }
        sb.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) touched = true; refresh() }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        fun stepBtn(sym: String, d: Int) = TextView(this).apply {
            text = sym; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Ui.t.accent)
            background = RippleDrawable(ColorStateList.valueOf(Ui.t.ripple), roundBg(Ui.t.card, 10, Ui.t.chipStroke), null)
            isClickable = true; isEnabled = enabled
            contentDescription = "${f.label} ${if (d < 0) "kleiner" else "größer"}"
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { touched = true; sb.progress = (sb.progress + d).coerceIn(0, sp.steps); refresh() }
        }
        val minus = stepBtn("−", -1); val plus = stepBtn("+", 1)
        parent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(minus); addView(sb); addView(plus)
        })
        refresh()
        return ProgSlider({ current() }, { touched }, { on ->
            sb.isEnabled = on; minus.isEnabled = on; plus.isEnabled = on
            val a = if (on) 1f else 0.4f; sb.alpha = a; minus.alpha = a; plus.alpha = a; tvVal.alpha = a
        }).also { it.setEnabled(enabled) }
    }

    /** Programm bearbeiten (isNew = Kopie von p als neues Programm). Standardprogramme nur lesen/kopieren. */
    /**
     * Assistent: Akku → Laden → Storage → Entladen → Zyklus → Fertig. Je Schritt die wichtigen Werte,
     * Profi-Einstellungen eingeklappt; im letzten Schritt Zusammenfassung aller Abweichungen.
     */
    private fun editProgram(p: Program, isNew: Boolean, startStep: Int = 0, expandPro: Boolean = false) {
        if (p.raw.size < 0xB2) { notify("Programmdaten unvollständig – bitte neu lesen"); return }
        if (isNew && newProgramLocked()) return            // auch ueber "Als Kopie anlegen"
        if (isNew && trialBlocked()) return                 // Testzeitraum abgelaufen: nichts neu anlegen
        val trialOver = trialExpired()                       // ... und Programme nur noch ansehen
        val readOnly = (!isNew && p.isBuiltIn) || trialOver
        val PM = ProgramMemory
        val pages = WIZARD_STEPS.map { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(8)) } }
        val pros = Array(WIZARD_STEPS.size) { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = if (expandPro) View.VISIBLE else View.GONE } }
        var box = pages[0]
        fun row(label: String, v: View, unit: String = "") {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(text(14f).apply { text = label; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
            r.addView(v)
            if (unit.isNotEmpty() || v is EditText) r.addView(text(14f).apply { text = " $unit"; minWidth = dp(40) })
            box.addView(r)
        }
        val startName = if (isNew) (p.name + "_Kopie").take(PM.NAME_MAX) else p.name
        val etName = EditText(this).apply {
            setText(startName); isEnabled = !readOnly
            filters = arrayOf(android.text.InputFilter.LengthFilter(PM.NAME_MAX))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        box.addView(text(13f).apply { text = "Name"; setTextColor(Ui.t.textDim) })
        box.addView(etName)
        box.addView(text(13f).apply {
            setTextColor(Ui.t.textDim); setPadding(0, 0, 0, dp(6))
            text = "Akkutyp: ${p.typeName}" + (if (isNew) "  ·  Vorlage: %02d %s".format(p.pos, p.name) else "  ·  Platz ${p.slot}") +
                    (if (p.isBuiltIn && !isNew) "  ·  Standardprogramm (nur als Kopie änderbar)" else "")
        })

        // Zellenzahl (nur Li-Typen)
        val spCells = if (p.type <= 4) Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, PM.CELL_OPTIONS)
            setSelection(p.liCells.coerceIn(0, 8)); isEnabled = !readOnly
        }.also { row("Zellenzahl", it) } else null

        // Akkuspannung zur Zellenzahl (aus Akkutyp und den Spannungen je Zelle unten)
        val tvPack = if (spCells != null) label("").apply { setTextColor(Ui.t.accent); setPadding(0, 0, 0, dp(6)) }.also { box.addView(it) } else null

        // Zahlenfelder als Schieberegler (Grenzen und Raster aus ProgramMemory.fields)
        val fields = PM.fields(p.type)
        val sliders = ArrayList<ProgSlider>()
        val vo = Program.voltOffsets(p.type)
        fun perCell(i: Int): Double? {
            val off = vo?.getOrNull(i) ?: return null
            val fi = fields.indexOfFirst { it.off == off }
            return if (fi < 0 || fi >= sliders.size) null else sliders[fi].get() / 1000.0
        }
        fun refreshPack() {
            if (spCells != null && tvPack != null)
                tvPack.text = PM.packVoltageText(p.type, spCells.selectedItemPosition, perCell(0), perCell(1), perCell(2))
        }
        // Arbeitskopie fuer die Lade-Untermenues (Ladeart → Ändern, Ladeendstrom → Ändern, Erweitert, Sicherheit)
        val work = p.raw.copyOf()
        val isLi = p.type <= 4
        var chargeMode = PM.chargeMode(p.raw)
        var endSlider: ProgSlider? = null
        var tvChargeInfo: TextView? = null
        var tvEndInfo: TextView? = null
        var btnBal: TextView? = null
        var btnEnd: TextView? = null
        fun refreshCharge() {
            if (!isLi) return
            val endMode = PM.get(work, PM.OFF_LI_BAL_END, 1).toInt()
            tvChargeInfo?.text = if (chargeMode == PM.MODE_NO_BAL) "ohne Balancer"
                else "Bal. Start: ${PM.balStartName(PM.get(work, PM.OFF_BAL_START, 1).toInt())}" +
                    if (PM.balancerEditable(chargeMode)) "  ·  Differenz ${PM.BAL_DIFF.text(PM.BAL_DIFF.raw(work))}" else "  ·  übrige Werte gibt der Lader vor"
            tvEndInfo?.text = if (PM.endModeEditable(chargeMode)) "Abschaltung: ${PM.endModeName(endMode)}" else "Abschaltung: nur Abschaltstrom (ohne Balancer)"
            btnBal?.let { it.isEnabled = chargeMode != PM.MODE_NO_BAL; it.alpha = if (it.isEnabled) 1f else 0.4f }
            btnEnd?.let { it.isEnabled = PM.endModeEditable(chargeMode); it.alpha = if (it.isEnabled) 1f else 0.4f }
            endSlider?.let { it.setEnabled(!readOnly && PM.endCurrentEditable(chargeMode, endMode)) }
        }
        fun linkButton(t: String, onClick: () -> Unit) = TextView(this).apply {
            text = t; textSize = 14f; setTextColor(Ui.t.accent); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = RippleDrawable(ColorStateList.valueOf(Ui.t.ripple), roundBg(Ui.t.card, 8, Ui.t.chipStroke), null)
            isClickable = true; setOnClickListener { onClick() }
        }
        fun subDialog(title: String, build: (LinearLayout) -> (() -> Unit)) {
            val sb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
            val apply = build(sb)
            val d = AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(sb) })
            if (readOnly) d.setPositiveButton("Schließen", null)
            else d.setPositiveButton("Übernehmen") { _, _ -> apply(); refreshCharge() }.setNegativeButton("Abbrechen", null)
            d.show()
        }
        // Ladeart → Ändern: LiPo Balancer Einstellungen
        fun balancerDialog() = subDialog("LiPo Balancer Einstellungen") { sb ->
            val cur = PM.get(work, PM.OFF_BAL_START, 1).toInt()
            val spStart = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, PM.balStartOptions(cur))
                setSelection(if (cur in PM.BAL_START.indices) cur else PM.BAL_START.size); isEnabled = !readOnly
            }
            sb.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(text(14f).apply { text = "Bal. Start"; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
                addView(spStart)
            })
            val editable = !readOnly && PM.balancerEditable(chargeMode)
            val sl = PM.BAL_FIELDS.map { f -> progSlider(sb, f, f.raw(work), editable) }
            if (!PM.balancerEditable(chargeMode)) sb.addView(label("Bei „${PM.chargeModeName(chargeMode)}“ legt der Lader Differenz, Sollwert, " +
                    "Überladung und Verzögerung selbst fest (z. B. Bal. Langsam: 3 mV / 3 mV / 0 mV / 2 min). Änderbar nur bei Ladeart „Modifiziert“.").apply { setPadding(0, dp(8), 0, 0) })
            val apply: () -> Unit = {
                val idx = spStart.selectedItemPosition
                PM.put(work, PM.OFF_BAL_START, 1, (if (idx in PM.BAL_START.indices) idx else cur).toLong())
                if (editable) PM.BAL_FIELDS.forEachIndexed { i, f -> PM.put(work, f.off, f.size, sl[i].get()) }
            }
            apply
        }
        // Ladeendstrom → Ändern: Abschaltbedingung
        fun endModeDialog() {
            val cur = PM.get(work, PM.OFF_LI_BAL_END, 1).toInt()
            val opts = PM.endModeOptions(cur).toTypedArray()
            var sel = if (cur in PM.END_MODES.indices) cur else PM.END_MODES.size
            val d = AlertDialog.Builder(this).setTitle("Ladeendstrom – Abschaltung")
                .setSingleChoiceItems(opts, sel) { _, w -> sel = w }
            if (readOnly) d.setPositiveButton("Schließen", null)
            else d.setPositiveButton("Übernehmen") { _, _ ->
                PM.put(work, PM.OFF_LI_BAL_END, 1, (if (sel in PM.END_MODES.indices) sel else cur).toLong()); refreshCharge()
            }.setNegativeButton("Abbrechen", null)
            d.show()
        }
        fun advancedDialog() = subDialog("LiPo Erweiterte Einstellung") { sb ->
            val cbKeep = CheckBox(this).apply {
                text = "Verzögertes Nachladen"; isChecked = PM.get(work, PM.OFF_KEEP_CHARGE, 1) != 0L; isEnabled = !readOnly
            }.also { sb.addView(it) }
            sb.addView(text(15f, bold = true).apply { text = "Reaktivierung bei Unterspannung"; setPadding(0, dp(10), 0, 0) })
            val sl = PM.RESTORE_FIELDS.map { f -> progSlider(sb, f, f.raw(work), !readOnly) }
            val apply: () -> Unit = {
                PM.put(work, PM.OFF_KEEP_CHARGE, 1, if (cbKeep.isChecked) 1 else 0)
                PM.RESTORE_FIELDS.forEachIndexed { i, f -> PM.put(work, f.off, f.size, sl[i].get()) }
            }
            apply
        }
        fun safetyDialog() = subDialog("Lade Sicherheitseinstellungen") { sb ->
            val sl = PM.SAFETY_FIELDS.map { f -> progSlider(sb, f, f.raw(work), !readOnly) }
            val apply: () -> Unit = { PM.SAFETY_FIELDS.forEachIndexed { i, f -> PM.put(work, f.off, f.size, sl[i].get()) } }
            apply
        }

        // Storage-, Entlade- und Zyklus-Zusaetze (Werte werden beim Speichern aus diesen Views gelesen)
        var slStoComp: ProgSlider? = null
        var cbFastSto: CheckBox? = null
        var tvDisRate: TextView? = null
        var tvChgRate: TextView? = null
        var spCycle: Spinner? = null
        val slCycle = ArrayList<ProgSlider>()
        val regenBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }   // "Regenerativer Modus", unten befuellt
        val iCap = fields.indexOfFirst { it.off == 0x28 }
        val iDis = fields.indexOfFirst { it.off == 0x64 }
        val iChg = fields.indexOfFirst { it.off == 0x62 }
        fun refreshRate() {
            fun rate(i: Int) = if (iCap < 0 || i < 0 || i >= sliders.size || iCap >= sliders.size) null
                else PM.cRate(sliders[i].get(), sliders[iCap].get())
            tvDisRate?.text = rate(iDis)?.let { "Entladerate: $it" } ?: "Entladerate: Kapazität eingeben"
            tvChgRate?.text = rate(iChg)?.let { "Laderate: $it" } ?: "Laderate: Kapazität eingeben"
        }
        /** Schritt je Feld: Hauptseite oder eingeklappter Profi-Bereich. */
        fun placeFor(off: Int): LinearLayout = when (off) {
            0x28 -> pages[0]
            0x62 -> pages[1]
            0x66 -> pros[1]
            vo?.get(0) -> pages[1]
            vo?.get(1) -> pages[2]
            0x64 -> pages[3]
            vo?.get(2) -> pages[3]
            0x68 -> pros[3]
            else -> pages[0]
        }

        for (f in fields) {
            box = placeFor(f.off)
            val s = progSlider(box, f, f.raw(p.raw), !readOnly) { refreshPack(); refreshRate() }
            sliders += s
            when (f.off) {
                0x62 -> tvChgRate = label("").apply { setTextColor(Ui.t.accent) }.also { box.addView(it) }
                0x64 -> tvDisRate = label("").apply { setTextColor(Ui.t.accent) }.also { box.addView(it) }
                0x68 -> {   // Ende ENTLADEN: Regenerativer Modus (Hauptseite), danach Schritt ZYKLUS
                    pages[3].addView(regenBox)
                    box = pages[4]
                    val cm = PM.get(p.raw, PM.OFF_CYCLE_MODE, 1).toInt()
                    spCycle = Spinner(this).apply {
                        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, PM.cycleModeOptions(cm))
                        setSelection(if (cm in PM.CYCLE_MODES.indices) cm else PM.CYCLE_MODES.size); isEnabled = !readOnly
                    }.also { row("Zyklus Modus", it) }
                    for (cf in PM.CYCLE_FIELDS) slCycle += progSlider(box, cf, cf.raw(p.raw), !readOnly)
                }
            }
            if (isLi && vo != null && f.off == vo[1]) {   // Storage je Zelle -> Profi: Kompensation, beschleunigte Storage
                slStoComp = progSlider(pros[2], PM.STO_COMP, PM.STO_COMP.raw(p.raw), !readOnly)
                cbFastSto = CheckBox(this).apply {
                    text = "Beschleunigte Storage"; isChecked = PM.get(p.raw, PM.OFF_FAST_STO, 1) != 0L; isEnabled = !readOnly
                }.also { pros[2].addView(it) }
            }
            box = placeFor(f.off)
            if (!isLi) continue
            when (f.off) {
                0x62 -> {   // Ladeart + Ändern
                    val cur = chargeMode
                    val spMode = Spinner(this).apply {
                        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, PM.chargeModeOptions(cur))
                        setSelection(if (cur in PM.CHARGE_MODES.indices) cur else PM.CHARGE_MODES.size); isEnabled = !readOnly
                        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                            override fun onItemSelected(a: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                                chargeMode = PM.chargeModeValue(pos, cur); refreshCharge()
                            }
                            override fun onNothingSelected(a: android.widget.AdapterView<*>?) {}
                        }
                    }
                    box.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0)
                        addView(text(14f).apply { text = "Ladeart"; minWidth = dp(110) })
                        addView(spMode)
                        btnBal = linkButton("Ändern …") { balancerDialog() }.also { addView(it) }
                    })
                    tvChargeInfo = label("").also { box.addView(it) }
                }
                0x66 -> {   // Ladeendstrom → Ändern
                    endSlider = s
                    box.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        tvEndInfo = label("").apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }.also { addView(it) }
                        btnEnd = linkButton("Ändern …") { endModeDialog() }.also { addView(it) }
                    })
                }
                vo?.get(0) -> {   // Endsp. Vorgabe -> Profi: Erweitert / Sicherheit
                    pros[1].addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0)
                        addView(linkButton("Erweitert …") { advancedDialog() }.apply {
                            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(6), 0) }; gravity = Gravity.CENTER })
                        addView(linkButton("Sicherheit …") { safetyDialog() }.apply {
                            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(dp(6), 0, 0, 0) }; gravity = Gravity.CENTER })
                    })
                }
            }
        }
        refreshCharge()
        refreshRate()
        spCells?.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(a: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshPack()
            override fun onNothingSelected(a: android.widget.AdapterView<*>?) {}
        }
        refreshPack()

        // Betriebsarten aktiv ("Zeigen" am Lader): jeweils oben im passenden Schritt
        val opPage = mapOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 1)      // Laden, Storage, Entladen, Zyklus, Nur Balancen
        val cbOps = OPERATIONS.map { (n, op) ->
            CheckBox(this).apply { text = "$n am Lader anzeigen (aktiv)"; isChecked = p.opEnabled(op); isEnabled = !readOnly }
        }
        // in umgekehrter Reihenfolge oben einfuegen, damit "Laden" vor "Nur Balancen" steht
        for (i in OPERATIONS.indices.reversed()) pages[opPage[OPERATIONS[i].second] ?: 0].addView(cbOps[i], 0)
        if (pages[2].childCount <= 1) pages[2].addView(label("Für diesen Akkutyp gibt es keine Storage-Spannung."))
        // Regenerativer Modus (Entlade-Modus) – steht im Abschnitt ENTLADEN (regenBox)
        val regen = PM.get(p.raw, PM.OFF_REGEN, 2).toInt()
        val spRegen = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, PM.regenOptions(regen))
            setSelection(if (regen in PM.REGEN_MODES.indices) regen else PM.REGEN_MODES.size)
            isEnabled = !readOnly
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        regenBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0)
            addView(text(14f).apply { text = "Regenerativer Modus"; minWidth = dp(110); setPadding(0, 0, dp(8), 0) })
            addView(spRegen)
        })

        // Unterpunkte fuer "Zu Ausgang": Verbraucher, Spannungs- und Stromgrenze
        val outLoad = PM.get(p.raw, PM.OFF_REG_CH_MODE, 1).toInt()
        val outBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), 0, dp(4))
        }
        fun subRow(label: String, v: View, unit: String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(text(14f).apply { text = label; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
            addView(v)
            if (unit.isNotEmpty()) addView(text(14f).apply { text = " $unit"; minWidth = dp(40) })
        }.also { outBox.addView(it) }
        val spLoad = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, PM.outputLoadOptions(outLoad))
            setSelection(if (outLoad in PM.OUTPUT_LOADS.indices) outLoad else PM.OUTPUT_LOADS.size)
            isEnabled = !readOnly
        }
        subRow("Ausgang verbinden", spLoad, "")
        // Grenzen ausserhalb des Bereichs (z. B. 0 bei bisher nicht genutztem "Zu Ausgang") auf den Bereich setzen
        fun clamped(f: ProgramMemory.Field) = f.slider().let { it.rawAt(it.posOf(f.raw(p.raw))) }.let { r -> if (f.inRange(f.raw(p.raw))) f.raw(p.raw) else r }
        val slOutV = progSlider(outBox, PM.OUT_VOLT, clamped(PM.OUT_VOLT), !readOnly)
        val slOutA = progSlider(outBox, PM.OUT_CURR, clamped(PM.OUT_CURR), !readOnly)
        val tvLoadHint = label("Bei „Von Akku zu Akku“ legt der Lader die Grenzen selbst fest.\n\n" +
                "Ablauf: diesen Kanal in der App mit „Entladen“ starten, dann den anderen Kanal am Ladegerät mit „Laden“.")
        outBox.addView(tvLoadHint)
        regenBox.addView(outBox)
        fun refreshOut() {
            val isOut = PM.regenValue(spRegen.selectedItemPosition, regen) == 2
            outBox.visibility = if (isOut) View.VISIBLE else View.GONE
            val load = PM.outputLoadValue(spLoad.selectedItemPosition, outLoad)
            val editable = !readOnly && PM.outputLimitsEditable(load)
            slOutV.setEnabled(editable); slOutA.setEnabled(editable)
            tvLoadHint.visibility = if (PM.outputLimitsEditable(load)) View.GONE else View.VISIBLE
        }
        val sel = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(a: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshOut()
            override fun onNothingSelected(a: android.widget.AdapterView<*>?) {}
        }
        spRegen.onItemSelectedListener = sel
        // "Von Akku zu Akku" auswaehlen: erlaubt, beim Wechsel darauf einmal den Ablauf erklaeren
        var loadPos = spLoad.selectedItemPosition
        spLoad.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(a: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == PM.AKKU_ZU_AKKU && pos != loadPos) popup("Von Akku zu Akku", PM.AKKU_ZU_AKKU_HINT)
                loadPos = pos
                refreshOut()
            }
            override fun onNothingSelected(a: android.widget.AdapterView<*>?) {}
        }
        refreshOut()
        regenBox.addView(label("Zu Eingang: nur wenn die aktive Eingangsquelle Rückspeisung erlaubt (Einstellungen → Eingangsquellen). " +
                "Zu Ausgang: Verbraucher, Spannungs- und Stromgrenze am Lader einstellen (Ändern). " +
                "Beim Start fragt der Lader nach – Ja = regenerativ, Nein = normal."))
        // Schritt Fertig: Zusammenfassung, dann Ausgang und Speicher Optionen (nur "Daten auf SD speichern" waehlbar)
        box = pages[5]
        box.addView(text(15f, bold = true).apply { text = if (isNew) "Abweichend von der Vorlage" else "Deine Änderungen"; setPadding(0, dp(6), 0, dp(2)) })
        val tvSummary = text(14f).apply { setTextColor(Ui.t.text); setPadding(0, 0, 0, dp(6)) }.also { box.addView(it) }
        box.addView(text(15f, bold = true).apply { text = "Ausgang und Speicher Optionen"; setPadding(0, dp(12), 0, dp(2)) })
        fun infoRow(l: String, v: String) = row(l, text(14f).apply { text = v; setTextColor(Ui.t.textDim) })
        infoRow("Ausgang Modus", if (readOnly) PM.channelModeName(p.raw) else "Asynchron  (fest)")
        // Vorgangszaehler, Log-Intervall, Log nach Fertigstellung: aenderbar
        fun inRangeOr(f: ProgramMemory.Field, raw: Long, fallback: Long) = if (f.inRange(raw)) raw else fallback
        val slRunCount = progSlider(box, PM.RUN_COUNT, if (isNew) 0L else inRangeOr(PM.RUN_COUNT, PM.runCounter(p.raw).toLong(), 0L), !readOnly)
        val slLogInt = progSlider(box, PM.LOG_INTERVAL,
            inRangeOr(PM.LOG_INTERVAL, PM.LOG_INTERVAL.raw(p.raw), PM.LOG_INTERVAL_FIX.toLong()), !readOnly)
        val cbAutoSave = CheckBox(this).apply {
            text = "Log nach Fertigstellung fortführen"; isChecked = PM.autoSave(p.raw); isEnabled = !readOnly
        }.also { box.addView(it) }
        val cbSd = CheckBox(this).apply {
            text = "Daten auf SD speichern"; isChecked = PM.saveToSd(p.raw); isEnabled = !readOnly
        }.also { box.addView(it) }

        box.addView(label("Alle weiteren Werte (Balancer, Sicherheit, Ni/Pb-Optionen) werden unverändert übernommen.").apply { setPadding(0, dp(8), 0, 0) })
        if (!readOnly) box.addView(label("Wird im Lader dauerhaft gespeichert, nur wenn beide Kanäle auf Bereit stehen."))

        /** Alle Eingaben einsammeln und pruefen: (neues Abbild, Aenderungen, Name) oder null (Fehler gemeldet). */
        fun collect(): Triple<ByteArray, List<String>, String>? {
                // Arbeitskopie: enthaelt schon die Werte aus den Lade-Untermenues
                val nm = work.copyOf()
                val changes = ArrayList<String>()
                if (isLi) {
                    PM.setChargeMode(nm, chargeMode)
                    changes.addAll(PM.chargeExtraChanges(p.raw, nm))
                }
                // Storage (Kompensation, beschleunigt) und Zyklus (Modus, Anzahl, Pause)
                slStoComp?.let { PM.put(nm, PM.STO_COMP.off, PM.STO_COMP.size, it.get()) }
                cbFastSto?.let { PM.put(nm, PM.OFF_FAST_STO, 1, if (it.isChecked) 1 else 0) }
                spCycle?.let {
                    val old = PM.get(p.raw, PM.OFF_CYCLE_MODE, 1).toInt()
                    val idx = it.selectedItemPosition
                    PM.put(nm, PM.OFF_CYCLE_MODE, 1, (if (idx in PM.CYCLE_MODES.indices) idx else old).toLong())
                }
                PM.CYCLE_FIELDS.forEachIndexed { i, f -> if (i < slCycle.size) PM.put(nm, f.off, f.size, slCycle[i].get()) }
                changes.addAll(PM.storageCycleChanges(p.raw, nm))
                // Name
                val name = etName.text.toString().trim()
                PM.checkName(name)?.let { notify(it); return null }
                if (isNew || name != p.name) {
                    PM.setName(nm, name)
                    if (!isNew) changes += "Name:  ${p.name} → $name"
                }
                if (isNew && programs.any { it.name == name }) { notify("Name „$name“ gibt es schon"); return null }
                // Zellen
                spCells?.let {
                    val v = it.selectedItemPosition
                    if (v != p.liCells) changes += "Zellenzahl:  ${PM.CELL_OPTIONS[p.liCells.coerceIn(0, 8)]} → ${PM.CELL_OPTIONS[v]}"
                    PM.put(nm, PM.OFF_CELLS, 1, v.toLong())
                }
                // Zahlenfelder
                for ((i, f) in fields.withIndex()) {
                    val raw = sliders[i].get()
                    // Regler liefert nur Werte in min..max; ein unberuehrter, vom Lader gelesener Wert bleibt erhalten
                    if (sliders[i].touched() && !f.inRange(raw)) { notify("${f.label}: erlaubt ${f.range()}"); return null }
                    if (raw != f.raw(p.raw)) changes += "${f.label}:  ${f.show(p.raw)} → ${f.fmt(raw)} ${f.unit}"
                    PM.put(nm, f.off, f.size, raw)
                }
                // Plausibilitaet Li: Entlade-Ende < Storage < Ladeschluss
                Program.voltOffsets(p.type)?.let { vo ->
                    val c = PM.get(nm, vo[0], 2); val s = PM.get(nm, vo[1], 2); val d = PM.get(nm, vo[2], 2)
                    if (!(d < s && s < c)) { notify("Spannungen: Entlade-Ende < Storage < Ladeschluss nötig"); return null }
                }
                // Betriebsarten (Bits 0-4, obere Bits unveraendert)
                var mask = PM.get(p.raw, PM.OFF_OPMASK, 1).toInt() and 0xE0
                OPERATIONS.forEachIndexed { i, (_, op) -> if (cbOps[i].isChecked) mask = mask or (1 shl op) }
                if (mask and 0x1F == 0) { notify("Mindestens eine Betriebsart aktivieren"); return null }
                val oldMask = PM.get(p.raw, PM.OFF_OPMASK, 1).toInt()
                if (mask != oldMask) changes += "Betriebsarten:  " + OPERATIONS.filter { mask shr it.second and 1 == 1 }.joinToString(", ") { it.first }
                PM.put(nm, PM.OFF_OPMASK, 1, mask.toLong())
                // Entlade-Modus
                val newRegen = PM.regenValue(spRegen.selectedItemPosition, regen)
                if (newRegen != regen) changes += "Entlade-Modus:  ${PM.regenName(regen)} → ${PM.regenName(newRegen)}"
                PM.put(nm, PM.OFF_REGEN, 2, newRegen.toLong())
                // "Zu Ausgang": Verbraucher und (nur bei Widerstand / Lampe) Grenzen
                if (newRegen == 2) {
                    val newLoad = PM.outputLoadValue(spLoad.selectedItemPosition, outLoad)
                    if (newLoad != outLoad) changes += "Ausgang verbinden:  ${PM.outputLoadName(outLoad)} → ${PM.outputLoadName(newLoad)}"
                    PM.put(nm, PM.OFF_REG_CH_MODE, 1, newLoad.toLong())
                    if (PM.outputLimitsEditable(newLoad)) for ((f, sl) in listOf(PM.OUT_VOLT to slOutV, PM.OUT_CURR to slOutA)) {
                        val raw = sl.get()
                        // neu auf "Zu Ausgang" gestellt: Grenze muss im erlaubten Bereich liegen (sonst Regler bewegen)
                        if (!f.inRange(raw)) { notify("${f.label}: erlaubt ${f.range()} – bitte Regler einstellen"); return null }
                        if (raw != f.raw(p.raw)) changes += "${f.label}:  ${f.show(p.raw)} → ${f.fmt(raw)} ${f.unit}"
                        PM.put(nm, f.off, f.size, raw)
                    }
                }
                // Ausgang und Speicher Optionen: fest asynchron, Log 1 s, Log nach Fertig aus; SD nach Wahl
                PM.applyOutputOptions(nm, cbSd.isChecked, cbAutoSave.isChecked, slLogInt.get())
                if (PM.channelModeName(nm) != PM.channelModeName(p.raw)) changes += "Ausgang Modus:  ${PM.channelModeName(p.raw)} → ${PM.channelModeName(nm)}"
                if (PM.logIntervalText(nm) != PM.logIntervalText(p.raw)) changes += "Log-Intervall:  ${PM.logIntervalText(p.raw)} → ${PM.logIntervalText(nm)}"
                if (PM.autoSaveName(nm) != PM.autoSaveName(p.raw)) changes += "Log nach Fertigstellung fortführen:  ${PM.autoSaveName(p.raw)} → ${PM.autoSaveName(nm)}"
                if (PM.saveToSd(nm) != PM.saveToSd(p.raw)) changes += "Daten auf SD speichern:  ${if (PM.saveToSd(p.raw)) "Ja" else "Nein"} → ${if (PM.saveToSd(nm)) "Ja" else "Nein"}"
                if (isNew) {
                    PM.put(nm, 0, 2, PM.USE_CUSTOM.toLong())      // eigenes Programm
                }
                // Vorgangszaehler (neues Programm: Regler steht auf 0)
                val rc = slRunCount.get()
                if (!isNew && rc != PM.runCounter(p.raw).toLong()) changes += "Vorgangszähler:  ${PM.runCounter(p.raw)} → $rc"
                PM.put(nm, PM.OFF_RUNCOUNT, 2, rc)
                return Triple(nm, changes, name)
        }

        // ---------------- Assistent: Kopf (Fortschritt + Schritt-Titel), Seiten, Knöpfe
        // Profi-Bereiche: aufklappbar unter den Hauptwerten
        for (i in pros.indices) if (pros[i].childCount > 0) {
            val head = text(14f, bold = true).apply {
                setTextColor(Ui.t.accent); setPadding(0, dp(14), 0, dp(4))
                isClickable = true
                fun upd() { text = (if (pros[i].visibility == View.VISIBLE) "▾ " else "▸ ") + WIZARD_PRO[i] }
                upd()
                setOnClickListener { pros[i].visibility = if (pros[i].visibility == View.VISIBLE) View.GONE else View.VISIBLE; upd() }
            }
            pages[i].addView(head); pages[i].addView(pros[i])
        }
        val pageHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pages.forEach { pageHost.addView(it) }
        val scroll = ScrollView(this).apply { addView(pageHost) }
        val segs = ArrayList<View>(); val segLabels = ArrayList<TextView>()
        val tvStepTitle = text(18f, bold = true)
        val tvStepHint = label("")
        var step = startStep.coerceIn(0, WIZARD_STEPS.size - 1)
        lateinit var dlg: AlertDialog
        var dlgShown = false
        lateinit var showStep: (Int) -> Unit
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                for (i in WIZARD_STEPS.indices) addView(View(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(5), 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
                    segs += this
                })
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(8))
                for (i in WIZARD_STEPS.indices) addView(text(12f).apply {
                    text = WIZARD_STEPS[i]; gravity = Gravity.CENTER; isClickable = true
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    contentDescription = "Schritt ${i + 1}: ${WIZARD_STEPS[i]}"
                    setOnClickListener { showStep(i) }          // direkt zu einem Schritt springen
                    segLabels += this
                })
            })
            addView(tvStepTitle); addView(tvStepHint)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        showStep = { s ->
            step = s.coerceIn(0, WIZARD_STEPS.size - 1)
            pages.forEachIndexed { i, pg -> pg.visibility = if (i == step) View.VISIBLE else View.GONE }
            segs.forEachIndexed { i, v ->
                v.background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(if (i <= step) Ui.t.accent else Ui.t.divider) }
            }
            segLabels.forEachIndexed { i, t ->
                t.setTextColor(if (i == step) Ui.t.accent else Ui.t.textDim)
                t.setTypeface(Typeface.DEFAULT, if (i == step) Typeface.BOLD else Typeface.NORMAL)
            }
            tvStepTitle.text = "Schritt ${step + 1} von ${WIZARD_STEPS.size} · ${WIZARD_STEPS[step]}"
            tvStepHint.text = WIZARD_HINTS[step]
            if (step == WIZARD_STEPS.size - 1) {
                val res = if (readOnly) null else collect()
                tvSummary.text = when {
                    trialOver -> "${Trial.BLOCKED_TEXT} – Programme können nur noch angesehen werden."
                    readOnly -> "Standardprogramm – nur ansehen. Mit „Als Kopie anlegen“ als eigenes Programm übernehmen."
                    res == null -> "Bitte die gemeldete Eingabe korrigieren."
                    res.second.isEmpty() -> if (isNew) "Keine – gleiche Werte wie die Vorlage %02d %s.".format(p.pos, p.name) else "Keine Änderung."
                    else -> res.second.joinToString("\n") { "• $it" }
                }
            }
            scroll.scrollTo(0, 0)
            if (dlgShown) {
                val last = step == WIZARD_STEPS.size - 1
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).text = when {
                    !last -> "Weiter: ${WIZARD_STEPS[step + 1]}"
                    trialOver -> "Schließen"
                    readOnly -> "Als Kopie anlegen"
                    isNew -> "Anlegen …"
                    else -> "Speichern …"
                }
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE).apply { isEnabled = step > 0; alpha = if (step > 0) 1f else 0.4f }
            }
        }
        dlg = AlertDialog.Builder(this)
            .setTitle(if (isNew) "Neues Ladeprogramm" else "%02d %s".format(p.pos, p.name))
            .setView(root)
            .setPositiveButton("Weiter", null)
            .setNegativeButton("Zurück", null)
            .setNeutralButton(if (readOnly) "Schließen" else "Abbrechen", null)
            .create()
        dlg.setOnShowListener {
            dlgShown = true
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { if (step > 0) showStep(step - 1) }
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (step < WIZARD_STEPS.size - 1) { showStep(step + 1); return@setOnClickListener }
                if (trialOver) { dlg.dismiss(); return@setOnClickListener }
                if (readOnly) { dlg.dismiss(); editProgram(p, true); return@setOnClickListener }
                val (nm, changes, name) = collect() ?: return@setOnClickListener
                if (!isNew && nm.contentEquals(p.raw)) { notify("Keine Änderung"); return@setOnClickListener }
                val ask = if (isNew) "„$name“ als neues ${p.typeName}-Programm anlegen?\n\nVorlage: %02d %s".format(p.pos, p.name) +
                        (if (changes.isEmpty()) "" else "\n\nAbweichend von der Vorlage:\n" + changes.joinToString("\n"))
                    else changes.joinToString("\n")
                AlertDialog.Builder(this)
                    .setTitle(if (isNew) "Programm anlegen?" else "%02d %s speichern?".format(p.pos, p.name))
                    .setMessage(ask)
                    .setPositiveButton(if (isNew) "Anlegen" else "Speichern") { _, _ ->
                        dlg.dismiss()
                        saveProgram(p, isNew, nm, name, fields)
                    }
                    .setNegativeButton("Zurück", null)
                    .show()
            }
            showStep(step)
        }
        showStep(step)
        dlg.show()
        // Breite fest, Hoehe nach Inhalt (bei langen Schritten scrollt nur der Inhalt, Kopf und Knoepfe bleiben stehen)
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.95).toInt(), WRAP_CONTENT)
    }

    /** Programm auf den Lader schreiben, zuruecklesen und pruefen; danach Liste neu lesen. */
    private fun saveProgram(p: Program, isNew: Boolean, mem: ByteArray, name: String, fields: List<ProgramMemory.Field>) {
        if (sourceLocked()) return
        val what = if (isNew) "Programm „$name“ anlegen" else "Programm %02d %s speichern".format(p.pos, name)
        if (demo) {
            val np = Program.parse(if (isNew) programs.size else p.pos, if (isNew) 99 else p.slot, mem)
            programs = if (isNew) programs + np else programs.map { if (it.pos == p.pos) np else it }
            notify("$what (Demo – nur angezeigt, nichts gesendet)")
            return
        }
        val d = dev ?: run { notify("Nicht verbunden"); return }
        log("$what …")
        setSavingProgram(true)          // Start gesperrt bis zur Erfolgs- oder Fehlermeldung
        worker.execute {
            try {
                sourceLockReason((0..1).map { readChannel(d, it) })?.let { throw Dx8Exception(it) }
                val head = ProgramMemory.readHead(d)
                val slot = if (isNew) ProgramMemory.freeSlot(head) ?: throw Dx8Exception("kein freier Programmplatz")
                    else p.slot.also { if (it !in head.usedSlots()) throw Dx8Exception("Programmplatz $it nicht mehr in der Liste") }
                val back = ProgramMemory.writeMemory(d, slot, mem)
                // Kontrolle: Kennung, Name, Zellen, Betriebsarten und alle bearbeiteten Felder
                val check = (0 until 2 + ProgramMemory.NAME_MAX + 1).toMutableList()
                check += ProgramMemory.OFF_OPMASK; check += ProgramMemory.OFF_CELLS
                check += ProgramMemory.OFF_REGEN; check += ProgramMemory.OFF_REGEN + 1
                check += (ProgramMemory.OFF_REG_CH_MODE..ProgramMemory.OFF_REG_CH_CURR + 1).toList()
                check += ProgramMemory.OUTPUT_OPTION_BYTES
                check += ProgramMemory.CHARGE_EXTRA_BYTES
                for (f in fields) for (i in 0 until f.size) check += f.off + i
                val bad = check.filter { back[it] != mem[it] }
                if (bad.isNotEmpty())
                    throw Dx8Exception("Kontrolle fehlgeschlagen an Byte " + bad.take(6).joinToString { "0x%02X".format(it) } +
                            if (isNew) " – Programm nicht in die Liste aufgenommen" else "")
                if (isNew) ProgramMemory.appendToHead(d, slot)
                val list = Program.readAll(d)
                ui.post {
                    programs = list
                    for (k in 0..1) if (lastProgram[k] >= list.size) lastProgram[k] = -1
                    notify("$what: auf Lader geschrieben und geprüft" + if (isNew) " (Platz $slot)" else "")
                    setSavingProgram(false)
                }
            } catch (e: Exception) {
                ui.post { notify("$what fehlgeschlagen: ${e.message}"); setSavingProgram(false) }
            }
        }
    }

    // ================================================================== Eingangsquellen

    private fun refreshSourceLabel() {
        val ci = chargerInfo
        tvSource.text = if (ci == null) "Quelle ?" else "Quelle ${ci.activeSource + 1}"
    }

    /**
     * Eingangsquelle darf nur geaendert werden, wenn auf keinem Kanal ein Ladevorgang laeuft
     * (auch nicht angehalten/"Halten" oder mit offener Rueckfrage). Liefert Grund oder null.
     */
    private fun sourceLockReason(chs: List<ChannelData?>) = ChannelLogic.lockReason(chs)

    private fun sourceLocked(): Boolean {
        val r = sourceLockReason(last.map { it }) ?: return false
        notify(r); return true
    }

    /** Auswahl der aktiven Eingangsquelle (Symbol unten). */
    private fun chooseSource() {
        if (inactivityLocked("Eingangsquelle wählen")) return
        val ci = chargerInfo ?: run { notify("Eingangsquellen noch nicht gelesen"); return }
        val th = Ui.t
        lateinit var dlg: AlertDialog
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(4)) }
        for (q in 0..3) {
            val src = ci.source(q)
            val active = q == ci.activeSource
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(8), dp(10))
                background = RippleDrawable(ColorStateList.valueOf(th.ripple), GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(if (active) argb(if (th.dark) 50 else 22, th.accent) else th.card)
                    setStroke(dp(if (active) 2 else 1), if (active) th.accent else if (th.cardStroke != 0) th.cardStroke else th.divider)
                }, null)
                isClickable = true; isFocusable = true
                contentDescription = "Quelle ${q + 1}" + if (active) " aktiv" else ""
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(0, dp(4), 0, dp(4)) }
            }
            // Nummer
            card.addView(TextView(this).apply {
                text = "${q + 1}"; textSize = 22f; gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (active) th.onFill else th.accent)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (active) th.accent else argb(if (th.dark) 60 else 30, th.accent)) }
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { setMargins(0, 0, dp(12), 0) }
            })
            // Werte
            val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
            mid.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(text(16f, bold = true).apply { text = "Quelle ${q + 1}" })
                if (active) addView(TextView(this@MainActivity).apply {
                    text = "AKTIV"; textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(th.onFill)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(th.accent) }
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(8), 0, 0, 0) }
                })
            })
            mid.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4))
                for (c in src.chips()) addView(TextView(this@MainActivity).apply {
                    text = c; textSize = 13f; setTextColor(th.text)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(th.divider) }
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(0, 0, dp(6), 0) }
                })
            })
            mid.addView(TextView(this).apply {
                text = (if (src.regen) "⟲ " else "") + src.regenLine(); textSize = 13f
                setTextColor(if (src.regen) th.warn else th.textDim)
                if (src.regen) setTypeface(typeface, Typeface.BOLD)
            })
            card.addView(mid)
            // Bearbeiten
            card.addView(TextView(this).apply {
                text = "Bearbeiten"; textSize = 13f; setTextColor(th.accent); setPadding(dp(10), dp(10), dp(6), dp(10))
                isClickable = true
                setOnClickListener { dlg.dismiss(); editSource(q) }
            })
            card.setOnClickListener {
                dlg.dismiss()
                if (active) return@setOnClickListener
                AlertDialog.Builder(this)
                    .setTitle("Auf Quelle ${q + 1} umstellen?")
                    .setMessage("Quelle ${ci.activeSource + 1} → Quelle ${q + 1}\n\n" + src.chips().joinToString(" · ") +
                            "\n" + src.regenLine() + "\n\nWird im Lader dauerhaft gespeichert. Ändern nur möglich, wenn kein Kanal arbeitet.")
                    .setPositiveButton("Umstellen") { _, _ -> writeSystem("Eingangsquelle ${q + 1}", 23, intArrayOf(q)) }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
            box.addView(card)
        }
        box.addView(label("Antippen = als Eingangsquelle verwenden").apply { setPadding(dp(4), dp(6), 0, 0); textSize = 12f })
        dlg = AlertDialog.Builder(this)
            .setTitle("Eingangsquelle wählen")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Abbrechen", null)
            .create()
        dlg.show()
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), WRAP_CONTENT)
    }

    /** Werte einer Eingangsquelle bearbeiten. */
    private fun editSource(q: Int) {
        if (inactivityLocked("Quelle ${q + 1} bearbeiten")) return
        val ci = chargerInfo ?: run { notify("Eingangsquellen noch nicht gelesen"); return }
        val old = ci.source(q)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun field(label: String, value: String, unit: String, decimal: Boolean, range: Triple<Double, Double, String>): EditText {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(2)) }
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                addView(text(14f).apply { text = label })
                addView(text(12f).apply { text = "min–max: ${InputSource.range(range)}"; setTextColor(Ui.t.textDim) })
            })
            val et = EditText(this).apply {
                setText(value); setEms(5)
                inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
            }
            row.addView(et); row.addView(text(14f).apply { text = " $unit"; minWidth = dp(40) })
            box.addView(row)
            return et
        }
        fun d1(v: Double) = "%.1f".format(Locale.US, v)
        val etLow = field("Unterspannung", d1(old.lowV), "V", true, InputSource.LOW_V)
        val etA = field("Max. Eingangsstrom", d1(old.maxA), "A", true, InputSource.MAX_A)
        val etW = field("Max. Ladeleistung", "${old.powerW}", "W", false, InputSource.POWER_W)
        val cbRegen = CheckBox(this).apply { text = "Rückspeisung (regeneratives Entladen)"; isChecked = old.regen }
        box.addView(cbRegen)
        val etRV = field("Rückspeise-Spannung bis", d1(old.regenV), "V", true, InputSource.REGEN_V)
        val etRA = field("Rückspeise-Strom", d1(old.regenA), "A", true, InputSource.REGEN_A)
        val etRW = field("Rückspeise-Leistung", "${old.regenW}", "W", false, InputSource.REGEN_W)
        val etRC = field("Rückspeise-Kapazität (0 = ignor.)", "${old.regenCap}", "mAh", false, InputSource.REGEN_CAP)
        // Rueckspeise-Felder nur zeigen, wenn Rueckspeisung aktiv ist
        val regenRows = listOf(etRV, etRA, etRW, etRC).map { it.parent as View }

        // Wirksame Grenzen: der Lader haelt immer die strengste Grenze ein
        fun num(e: EditText) = e.text.toString().replace(',', '.').toDoubleOrNull()
        val tvEff = text(13f).apply { setPadding(0, dp(10), 0, dp(4)); setTextColor(Ui.t.textDim) }
        box.addView(tvEff)
        fun updateEff() {
            val low = num(etLow); val a = num(etA); val w = num(etW)
            val rv = num(etRV); val ra = num(etRA); val rw = num(etRW)
            val sb = StringBuilder()
            if (low != null && a != null && w != null && a > 0) {
                val atLow = low * a
                val vSwitch = w / a                       // ab dieser Eingangsspannung begrenzt die Leistung
                sb.append("Laden: bei %.1f V max %.0f W (Strom %.1f A begrenzt)".format(low, minOf(atLow, w), a))
                if (atLow > w) sb.append("\n  → Leistung %.0f W begrenzt schon ab Unterspannung".format(w))
                else sb.append("\n  → Leistung %.0f W greift erst ab %.1f V Eingang".format(w, vSwitch))
                if (vSwitch > 60) sb.append("\n  ⚠ Ladeleistung wird bei üblichen Eingangsspannungen nie erreicht")
            }
            if (cbRegen.isChecked && rv != null && ra != null && rw != null) {
                val vxa = rv * ra
                val eff = minOf(vxa, rw)
                sb.append("\n\nRückspeisen: bei %.1f V × %.1f A = %.0f W → wirksam %.0f W (%s)".format(
                    rv, ra, vxa, eff, if (rw < vxa) "Leistung begrenzt" else "Strom begrenzt"))
                if (rw > vxa * 1.05) sb.append("\n  ⚠ Rückspeise-Leistung %.0f W wird nie erreicht".format(rw))
            } else if (!cbRegen.isChecked) sb.append("\n\nRückspeisen: aus")
            tvEff.text = sb.toString().trim()
        }
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = updateEff()
        }
        listOf(etLow, etA, etW, etRV, etRA, etRW).forEach { it.addTextChangedListener(watcher) }

        // Leistung aus Spannung x Strom berechnen (nur auf Tippen)
        val calcRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        calcRow.addView(smallButton("Ladeleistung = Unterspannung × Strom") {
            val low = num(etLow); val a = num(etA)
            if (low != null && a != null) etW.setText("${Math.round(low * a)}") else notify("Unterspannung/Strom ungültig")
        })
        val btnRegenCalc = smallButton("Rückspeiseleistung = Spannung × Strom") {
            val rv = num(etRV); val ra = num(etRA)
            if (rv != null && ra != null) etRW.setText("${Math.round(rv * ra)}") else notify("Rückspeise-Spannung/Strom ungültig")
        }
        calcRow.addView(btnRegenCalc)
        box.addView(calcRow)
        fun showRegen() {
            val vis = if (cbRegen.isChecked) View.VISIBLE else View.GONE
            regenRows.forEach { it.visibility = vis }
            btnRegenCalc.visibility = vis
        }
        cbRegen.setOnCheckedChangeListener { _, _ -> showRegen(); updateEff() }
        showRegen()
        updateEff()
        box.addView(label("Wird im Lader dauerhaft gespeichert. Ändern nur möglich, wenn kein Kanal arbeitet."))
        AlertDialog.Builder(this)
            .setTitle("Quelle ${q + 1} bearbeiten" + if (q == ci.activeSource) " (aktiv)" else "")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Auf Lader schreiben") { _, _ ->
                fun dv(e: EditText) = e.text.toString().replace(',', '.').toDoubleOrNull()
                fun iv(e: EditText) = e.text.toString().trim().toLongOrNull()
                val nw = try {
                    InputSource(dv(etLow)!!, dv(etA)!!, iv(etW)!!.toInt(), cbRegen.isChecked,
                        dv(etRV)!!, dv(etRA)!!, iv(etRW)!!.toInt(), iv(etRC)!!)
                } catch (_: Exception) { notify("Ungültige Eingabe"); return@setPositiveButton }
                nw.validate()?.let { notify(it); return@setPositiveButton }
                if (nw.toRegs().contentEquals(old.toRegs())) { notify("Keine Änderung"); return@setPositiveButton }
                AlertDialog.Builder(this)
                    .setTitle("Quelle ${q + 1} schreiben?")
                    .setMessage("alt:  ${old.summary()}\n\nneu:  ${nw.summary()}" +
                            (if (nw.regenCap != old.regenCap) "\nRückspeise-Kapazität: ${old.regenCap} → ${nw.regenCap} mAh" else ""))
                    .setPositiveButton("Schreiben") { _, _ -> writeSystem("Quelle ${q + 1}", InputSource.offset(q), nw.toRegs()) }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** Gruppe von Systemwerten (Pro Kanal, Temperatur, Display/Töne, Servo) bearbeiten. */
    private fun editSysGroup(g: SysGroup) {
        if (inactivityLocked(g.title)) return
        val ci = chargerInfo ?: run { notify("Ladegerät noch nicht gelesen"); return }
        if (ci.sys.size <= g.to) { notify("Systembereich unvollständig gelesen"); return }
        val old = ci.sys
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val readers = ArrayList<() -> Any>()   // liefert Int (Rohwert) oder String (Fehler)
        for (f in g.fields) {
            val raw = old[f.reg]
            val name = f.label
            // Beschriftung mit erlaubtem Bereich darunter (bei Zahlenfeldern)
            fun labelView() = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                addView(text(14f).apply { text = name })
                val r = f.rangeText()
                if (r.isNotEmpty()) addView(text(12f).apply { text = "min–max: $r"; setTextColor(Ui.t.textDim) })
            }
            when {
                f.bool -> {
                    val cb = CheckBox(this).apply { text = name; isChecked = raw != 0 }
                    box.addView(cb)
                    readers += { if (cb.isChecked) 1 else 0 }
                }
                f.options != null -> {
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(text(14f).apply { text = name; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
                    val sp = Spinner(this).apply {
                        val opts = f.options.orEmpty()
                        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, opts)
                        if (raw in opts.indices) setSelection(raw)
                    }
                    row.addView(sp); box.addView(row)
                    readers += { sp.selectedItemPosition }
                }
                else -> {
                    // Schieberegler: Kopf (Name, min–max | aktueller Wert), darunter [−] Regler [+]
                    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
                    head.addView(labelView())
                    val tvVal = text(16f, bold = true).apply { gravity = Gravity.END; minWidth = dp(90); setTextColor(Ui.t.accent) }
                    head.addView(tvVal)
                    box.addView(head)
                    var touched = false
                    val sb = android.widget.SeekBar(this).apply {
                        max = f.steps
                        progress = f.posOf(raw)
                        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    }
                    // unveraendert = gelesener Wert (auch wenn er nicht genau auf dem Raster liegt)
                    fun current() = if (touched) f.rawAt(sb.progress) else raw
                    fun refresh() { tvVal.text = f.show(current()) }
                    sb.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) touched = true; refresh() }
                        override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                        override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
                    })
                    fun stepBtn(sym: String, d: Int) = TextView(this).apply {
                        text = sym; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Ui.t.accent)
                        background = RippleDrawable(ColorStateList.valueOf(Ui.t.ripple), roundBg(Ui.t.card, 10, Ui.t.chipStroke), null)
                        isClickable = true
                        contentDescription = "${f.label} ${if (d < 0) "kleiner" else "größer"}"
                        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                        setOnClickListener { touched = true; sb.progress = (sb.progress + d).coerceIn(0, f.steps); refresh() }
                    }
                    box.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        addView(stepBtn("−", -1)); addView(sb); addView(stepBtn("+", 1))
                    })
                    refresh()
                    readers += { current() }
                }
            }
        }
        if (g.note.isNotEmpty()) box.addView(label(g.note).apply { setPadding(0, dp(8), 0, 0) })
        box.addView(label("Wird im Lader dauerhaft gespeichert. Ändern nur möglich, wenn kein Kanal arbeitet.").apply { setPadding(0, dp(8), 0, 0) })
        AlertDialog.Builder(this)
            .setTitle("${g.title} bearbeiten")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Auf Lader schreiben") { _, _ ->
                if (inactivityLocked(g.title)) return@setPositiveButton
                val nw = old.copyOf()
                for ((i, f) in g.fields.withIndex()) {
                    when (val r = readers[i]()) {
                        is String -> { notify(r); return@setPositiveButton }
                        is Int -> nw[f.reg] = r
                    }
                }
                g.check(nw)?.let { notify(it); return@setPositiveButton }
                val changes = g.fields.filter { nw[it.reg] != old[it.reg] }
                if (changes.isEmpty()) { notify("Keine Änderung"); return@setPositiveButton }
                AlertDialog.Builder(this)
                    .setTitle("${g.title} schreiben?")
                    .setMessage(changes.joinToString("\n") { "${it.label}:  ${it.show(old[it.reg])} → ${it.show(nw[it.reg])}" })
                    .setPositiveButton("Schreiben") { _, _ -> writeSystem(g.title, g.from, nw.copyOfRange(g.from, g.to + 1)) }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /**
     * Systemwerte schreiben: nur wenn beide Kanaele stehen, dann dauerhaft speichern (ORDER_WRITE_SYS)
     * und zur Kontrolle zuruecklesen (Lesen holt die Werte aus dem Flash).
     */
    private fun writeSystem(name: String, offset: Int, regs: IntArray) {
        if (sourceLocked()) return            // Zustand kann sich waehrend des Dialogs geaendert haben
        if (demo) {
            val ci = chargerInfo ?: return
            val sys = ci.sys.copyOf(); regs.copyInto(sys, offset)
            chargerInfo = ChargerInfo(ci.info, sys); refreshSourceLabel()
            notify("$name (Demo – nur angezeigt, nichts gesendet)")
            return
        }
        val d = dev ?: run { notify("Nicht verbunden"); return }
        worker.execute {
            try {
                // unmittelbar vor dem Schreiben live am Lader pruefen
                sourceLockReason((0..1).map { readChannel(d, it) })?.let { throw Dx8Exception(it) }
                d.writeSystem(offset, regs)
                d.saveSystem()
                Thread.sleep(600)
                val ci = ChargerInfo.read(d)
                chargerInfo = ci
                val back = ci.sys.copyOfRange(offset, offset + regs.size)
                ui.post {
                    refreshSourceLabel()
                    if (back.contentEquals(regs)) notify("$name auf Lader geschrieben und geprüft")
                    else notify("$name: Kontrolle fehlgeschlagen – gelesen ${back.joinToString()}, erwartet ${regs.joinToString()}")
                }
            } catch (e: Exception) {
                ui.post { notify("$name schreiben fehlgeschlagen: ${e.message}") }
            }
        }
    }

    // ================================================================== Einstellungen

    private val prefs by lazy { getSharedPreferences("dx8", Context.MODE_PRIVATE) }

    // ---------------- Testversion (Ablauf siehe Trial): Start und Programme anlegen/aendern nach Ablauf gesperrt
    private var trialLastSeen = 0L
    private var trialSavedAt = 0L
    private var tvTrial: TextView? = null
    private fun trialExpired() = BuildConfig.TRIAL && Trial.expired(System.currentTimeMillis(), trialLastSeen)
    /** true = gesperrt (Meldung gezeigt). Stopp, Halten und Weiter rufen das nie auf. */
    private fun trialBlocked(): Boolean {
        if (!trialExpired()) return false
        popup("Testversion abgelaufen", "${Trial.BLOCKED_TEXT}.\n\nStart sowie Programme anlegen und ändern sind gesperrt. " +
                "Stopp, Halten, Weiter und die Überwachung funktionieren weiter.")
        return true
    }
    /** Hinweis oben aktualisieren und den spaetesten gesehenen Zeitpunkt merken (Schutz gegen Zurueckstellen der Uhr). */
    private fun trialTick() {
        if (!BuildConfig.TRIAL) { tvTrial?.visibility = View.GONE; return }   // Vollversion: kein Hinweis, keine Sperre
        val now = System.currentTimeMillis()
        val next = Trial.advance(now, trialLastSeen)
        if (next != trialLastSeen) {
            trialLastSeen = next
            if (now - trialSavedAt > 60_000) { prefs.edit().putLong("trialLastSeen", trialLastSeen).apply(); trialSavedAt = now }
        }
        tvTrial?.apply {
            text = Trial.banner(now, trialLastSeen)
            setTextColor(when (Trial.state(now, trialLastSeen)) {
                Trial.State.ACTIVE -> Ui.t.textDim
                Trial.State.WARN -> Ui.t.warn
                Trial.State.EXPIRED -> Ui.t.error
            })
        }
    }

    private fun loadSettings() {
        trialLastSeen = prefs.getLong("trialLastSeen", 0L)
        guardOn = prefs.getBoolean("guardOn", true)
        uinLimit = prefs.getFloat("uinLimit", 25.2f).toDouble()
        Ui.t = AppTheme.byId(prefs.getString("design", null))
        viaWifi = prefs.getString("connMode", "usb") == "wlan"
        bridgeHost = prefs.getString("bridgeHost", "dx8bridge.local") ?: "dx8bridge.local"
        bridgePort = prefs.getInt("bridgePort", TcpHidLink.DEFAULT_PORT)
        bridgeToken = prefs.getString("bridgeToken", "") ?: ""
    }

    // ================================================================== Design

    /** Android-Grundthema (Dialoge, Standardknoepfe), Titel- und Systemleisten passend zum Design. */
    private fun applyThemeChrome() {
        val th = Ui.t
        setTheme(if (th.dark) android.R.style.Theme_Material else R.style.DX8Light)
        actionBar?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if (th.dark) Color.rgb(33, 33, 33) else th.bar))
        window.statusBarColor = if (th.dark) Color.BLACK else th.bar
        window.navigationBarColor = if (th.dark) Color.BLACK else th.bar
    }

    /** Design umschalten: Oberflaeche neu aufbauen, Verbindung und Verlauf bleiben erhalten. */
    private fun setDesign(th: AppTheme) {
        if (th.id == Ui.t.id) return
        Ui.t = th
        prefs.edit().putString("design", th.id).apply()
        chartPopups.forEach { it?.dialog?.dismiss() }
        applyThemeChrome()
        rebuildUi()
        log("Design: ${th.title}")
    }

    /** Oberflaeche neu aufbauen (Design, Drehen); Verbindung, Abfrage und Verlauf laufen weiter. */
    private fun rebuildUi() {
        buildUi()
        setConn(connMsg, connOk)
        tvLog.text = logLines.joinToString("\n")
        refreshSourceLabel()
        for (k in 0..1) updateControls(k, last[k])
    }

    /**
     * Hoch-/Querformat: die Activity wird nicht neu erzeugt (configChanges im Manifest), sonst wuerde die
     * USB-Verbindung getrennt. Nur das Layout wird fuer die neue Breite neu aufgebaut; Werte kommen mit der
     * naechsten Abfrage (oder sofort im Demo-Modus).
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuildUi()
    }

    private fun chooseDesign() {
        val cur = Ui.t
        lateinit var dlg: AlertDialog
        // Karten nebeneinander (Tablet) bzw. untereinander (schmal), jede mit verkleinerter Vorschau
        val wide = resources.configuration.screenWidthDp >= 600
        val row = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        for (t in AppTheme.ALL) {
            val active = t.id == cur.id
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(12))
                background = RippleDrawable(ColorStateList.valueOf(cur.ripple), GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(if (active) argb(if (cur.dark) 50 else 22, cur.accent) else cur.card)
                    setStroke(dp(if (active) 2 else 1), if (active) cur.accent else if (cur.cardStroke != 0) cur.cardStroke else cur.divider)
                }, null)
                isClickable = true; isFocusable = true
                contentDescription = "Design ${t.title}" + if (active) " aktiv" else ""
                layoutParams = (if (wide) LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) else LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    .apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
                setOnClickListener { dlg.dismiss(); setDesign(t) }
            }
            card.addView(ThemePreviewView(this, t).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(10)) }
            })
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(text(16f, bold = true).apply { text = t.title; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
                if (active) addView(TextView(this@MainActivity).apply {
                    text = "AKTIV"; textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(cur.onFill)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(cur.accent) }
                })
            })
            card.addView(text(13f).apply { text = t.description; setTextColor(cur.textDim) })
            row.addView(card)
        }
        dlg = AlertDialog.Builder(this)
            .setTitle("Design wählen")
            .setView(ScrollView(this).apply { addView(row) })
            .setNegativeButton("Abbrechen", null)
            .create()
        dlg.show()
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * (if (wide) 0.85 else 0.95)).toInt(), WRAP_CONTENT)
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(0))
        }
        val cbGuard = CheckBox(this).apply { text = "STOP bei Eingangsspannung ≥ Grenze"; isChecked = guardOn }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val etLimit = EditText(this).apply {
            setText("%.2f".format(Locale.US, uinLimit))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setEms(5)
        }
        row.addView(text(15f).apply { text = "Grenze  " }); row.addView(etLimit); row.addView(text(15f).apply { text = " V" })
        // Eigener, umrahmter Block: Schutz der Eingangsbatterie
        val guardBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(argb(30, Ui.t.warn))
                setStroke(dp(1), Ui.t.warn)
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(10)) }
        }
        guardBox.addView(text(16f, bold = true).apply { text = "SCHUTZ EINGANGSBATTERIE"; setTextColor(Ui.t.warn) })
        guardBox.addView(text(13f).apply {
            setPadding(0, dp(4), 0, dp(6))
            setTextColor(Ui.t.textDim)
            text = "Beim regenerativen Entladen speist der Lader Energie zurück in die Eingangsquelle. " +
                    "Ist das ein Akku (z. B. 6S-LiPo), steigt dessen Spannung dabei an.\n\n" +
                    "Erreicht die Eingangsspannung diese Grenze, sendet die App STOP an jeden laufenden Kanal – " +
                    "so wird der Eingangsakku nicht überladen. Für 6S-LiPo: 6 × 4,20 V = 25,2 V.\n\n" +
                    "Zusätzliche Sicherung der App, ersetzt nicht die Rückspeise-Grenze der Eingangsquelle im Lader. " +
                    "Wirkt nur, solange die App verbunden ist, und nicht während einer offenen Rückfrage am Lader."
        })
        guardBox.addView(cbGuard); guardBox.addView(row)
        box.addView(guardBox)
        // (Aufzeichnung als CSV: entfernt, es werden keine Dateien mehr geschrieben)
        // Verbindung: USB direkt oder WLAN-Bruecke (Raspberry Pi)
        box.addView(text(16f, bold = true).apply { text = "VERBINDUNG"; setPadding(0, dp(14), 0, dp(2)) })
        val rgConn = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }
        val rbUsb = android.widget.RadioButton(this).apply { text = "USB direkt am Tablet"; id = View.generateViewId() }
        val rbWifi = android.widget.RadioButton(this).apply { text = "Über WLAN-Brücke (Raspberry Pi)"; id = View.generateViewId() }
        rgConn.addView(rbUsb); rgConn.addView(rbWifi)
        rgConn.check(if (viaWifi) rbWifi.id else rbUsb.id)
        box.addView(rgConn)
        val wifiBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        fun wifiField(label: String, value: String, type: Int) = EditText(this).apply {
            setText(value); inputType = type; setEms(12)
        }.also { et ->
            wifiBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(text(14f).apply { text = label; minWidth = dp(90) }); addView(et)
            })
        }
        val etHost = wifiField("Adresse", bridgeHost, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val etPort = wifiField("Port", "$bridgePort", InputType.TYPE_CLASS_NUMBER)
        val etToken = wifiField("Passwort", bridgeToken, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        wifiBox.addView(label("Adresse, Port und Passwort zeigt das Installationsskript auf dem Pi an. " +
                "Der Schutz Eingangsbatterie wird an die Brücke übertragen und wirkt dort auch ohne App."))
        box.addView(wifiBox)
        fun showWifi() { wifiBox.visibility = if (rgConn.checkedRadioButtonId == rbWifi.id) View.VISIBLE else View.GONE }
        rgConn.setOnCheckedChangeListener { _, _ -> showWifi() }
        showWifi()
        // Design (eigener Punkt, Wechsel sofort)
        box.addView(text(16f, bold = true).apply { text = "DESIGN"; setPadding(0, dp(14), 0, dp(2)) })
        val btnDesign = Button(this).apply {
            isAllCaps = false; textSize = 13f; gravity = Gravity.START or Gravity.CENTER_VERTICAL
            text = "Design wählen\naktuell: ${Ui.t.title}"
        }
        box.addView(btnDesign)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Einstellungen")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Speichern") { _, _ ->
                val v = etLimit.text.toString().replace(',', '.').toDoubleOrNull()
                if (v == null || v < 5.0 || v > 60.0) { notify("Ungültige Grenze – nicht gespeichert"); return@setPositiveButton }
                guardOn = cbGuard.isChecked
                uinLimit = v
                prefs.edit().putBoolean("guardOn", guardOn).putFloat("uinLimit", v.toFloat()).apply()
                log("Einstellungen: STOP bei Eingang ≥ %.2f V %s".format(uinLimit, if (guardOn) "aktiv" else "aus"))
                // Verbindung uebernehmen; bei Aenderung neu verbinden
                val newWifi = rgConn.checkedRadioButtonId == rbWifi.id
                val newHost = etHost.text.toString().trim()
                val newPort = etPort.text.toString().trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: TcpHidLink.DEFAULT_PORT
                val newToken = etToken.text.toString().trim()
                val changed = newWifi != viaWifi || (newWifi && (newHost != bridgeHost || newPort != bridgePort || newToken != bridgeToken))
                viaWifi = newWifi; bridgeHost = newHost; bridgePort = newPort; bridgeToken = newToken
                prefs.edit().putString("connMode", if (viaWifi) "wlan" else "usb").putString("bridgeHost", bridgeHost)
                    .putInt("bridgePort", bridgePort).putString("bridgeToken", bridgeToken).apply()
                if (changed && !demo) {
                    if (dev != null) disconnect("Verbindungsart geändert")
                    ui.postDelayed({ tryConnect() }, 500)
                } else dev?.let { d -> if (d.viaBridge) worker.execute { pushBridgeGuard(d) } }
            }
            .setNegativeButton("Abbrechen", null)
            .create()
        btnDesign.setOnClickListener { dlg.dismiss(); chooseDesign() }
        // Ladeprogramme: eigener Einstellungspunkt
        box.addView(text(16f, bold = true).apply { text = "LADEPROGRAMME"; setPadding(0, dp(14), 0, dp(2)) })
        box.addView(Button(this).apply {
            isAllCaps = false; textSize = 13f; gravity = Gravity.START or Gravity.CENTER_VERTICAL
            text = "Ladeprogramme ansehen, ändern, neu anlegen\n" +
                    (if (programs.isEmpty()) "noch nicht gelesen" else "${programs.size} Programme, davon ${programs.count { !it.isBuiltIn }} eigene")
            setOnClickListener { dlg.dismiss(); showProgramManager() }
        })
        box.addView(Button(this).apply {
            text = "Programme neu vom Lader lesen"
            setOnClickListener { dlg.dismiss(); loadPrograms() }
        })

        // Eingangsquellen bearbeiten
        box.addView(text(16f, bold = true).apply { text = "EINGANGSQUELLEN"; setPadding(0, dp(14), 0, dp(2)) })
        val srcBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(srcBox)

        // Weitere Systemwerte einstellen
        box.addView(text(16f, bold = true).apply { text = "LADEGERÄT EINSTELLEN"; setPadding(0, dp(14), 0, dp(2)) })
        val sysBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(sysBox)

        // Lesestatus (die Geraete-Infos selbst zeigt der Info-Knopf oben links)
        val tvInfo = label("Ladegerät wird gelesen …")
        box.addView(tvInfo)
        dlg.show()
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.95).toInt(), WRAP_CONTENT)

        fun show(ci: ChargerInfo, prefix: String) {
            chargerInfo = ci
            refreshSourceLabel()
            tvInfo.visibility = View.GONE
            srcBox.removeAllViews()
            for (q in 0..3) srcBox.addView(Button(this).apply {
                isAllCaps = false; textSize = 13f; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                text = (if (q == ci.activeSource) "► " else "   ") + "Quelle ${q + 1} bearbeiten\n" + ci.source(q).summary()
                setOnClickListener { dlg.dismiss(); editSource(q) }
            })
            sysBox.removeAllViews()
            if (ci.sys.size > 72) for (g in SysSettings.ALL) sysBox.addView(Button(this).apply {
                isAllCaps = false; textSize = 13f; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                val preview = g.fields.take(3).joinToString(" · ") { it.show(ci.sys[it.reg]) }
                text = "${g.title} bearbeiten\n$preview …"
                setOnClickListener { dlg.dismiss(); editSysGroup(g) }
            })
        }
        if (demo) {
            val ci = chargerInfo ?: ChargerInfo.demo(assets.open("system_regs.txt").bufferedReader().readText())
            show(ci, "(Demo – gelesen am 21.11.2026)\n")
        } else {
            val d = dev
            if (d == null) tvInfo.text = "nicht verbunden"
            else worker.execute {
                try {
                    val ci = ChargerInfo.read(d)
                    ui.post { show(ci, "") }
                } catch (e: Exception) {
                    ui.post { tvInfo.text = "Lesen fehlgeschlagen: ${e.message}" }
                }
            }
        }
    }

    /** Info-Knopf oben links: alles, was sich ausser den Ladeprogrammen vom Lader lesen laesst. */
    private fun showChargerInfo() {
        val tv = text(12f, mono = true).apply { text = "wird gelesen …"; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Ladegerät")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Schließen", null)
            .setNeutralButton("Firmware prüfen", null)
            .show()
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.95).toInt(), WRAP_CONTENT)
        var shown: ChargerInfo? = null
        var fwText = ""
        var infoText = ""
        fun render() { tv.text = if (fwText.isEmpty()) infoText else "$fwText\n$infoText" }
        // Firmware-Pruefung: Update-Liste des Junsi-Upgraders + Release Notes lesen (nur lesen, nichts aufspielen)
        val fwBtn = dlg.getButton(AlertDialog.BUTTON_NEUTRAL)
        fwBtn.setOnClickListener {
            fwBtn.isEnabled = false
            fwText = "FIRMWARE-PRÜFUNG\n  frage jun-si.com ab …\n"
            render()
            val installed = shown?.firmware
            Thread {
                val r = try { FirmwareCheck.check(installed) }
                        catch (e: Exception) { "FIRMWARE-PRÜFUNG\n  Junsi-Server nicht erreichbar (${e.message ?: e.javaClass.simpleName}).\n  Internetverbindung prüfen.\n" }
                ui.post { fwText = r; render(); fwBtn.isEnabled = true }
            }.start()
        }
        fun show(ci: ChargerInfo, prefix: String) {
            chargerInfo = ci
            shown = ci
            refreshSourceLabel()
            infoText = prefix + ci.format().trimStart('\n')
            render()
        }
        if (demo) {
            show(chargerInfo ?: ChargerInfo.demo(assets.open("system_regs.txt").bufferedReader().readText()), "(Demo – gelesen am 21.11.2026)\n")
            return
        }
        val d = dev ?: run { infoText = "Nicht verbunden – bitte zuerst den Lader verbinden."; render(); return }
        worker.execute {
            try {
                val ci = ChargerInfo.read(d)
                ui.post { show(ci, "") }
            } catch (e: Exception) {
                ui.post { infoText = "Lesen fehlgeschlagen: ${e.message}"; render() }
            }
        }
    }

    // ================================================================== Layout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply { cornerRadius = dp(Ui.t.cardRadiusDp).toFloat(); setColor(Ui.t.card); if (Ui.t.cardStroke != 0) setStroke(dp(1), Ui.t.cardStroke) }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(6)) }
    }

    private fun text(size: Float, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(Ui.t.text)
        typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun label(s: String) = text(13f).apply { text = s; setTextColor(Ui.t.textDim) }

    /** Abschnittsueberschrift in einer Karte (hell: blau und fett wie bei RCmodelspot). */
    private fun heading(s: String) = text(13f, bold = !Ui.t.dark).apply { text = s; setTextColor(Ui.t.heading) }

    private fun smallButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        textSize = 13f
        isAllCaps = false
        minWidth = 0; minimumWidth = 0
        setPadding(dp(4), 0, dp(4), 0)
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        // Kopf: Info-Knopf "Ladegeraet" links, Titel, "+" (neues Ladeprogramm) rechts oben
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "i"; textSize = 24f; gravity = Gravity.CENTER
                setTypeface(Typeface.SERIF, Typeface.BOLD_ITALIC); setTextColor(Ui.t.accent)
                background = RippleDrawable(ColorStateList.valueOf(Ui.t.ripple),
                    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.t.card); setStroke(dp(2), Ui.t.accent) }, null)
                isClickable = true; isFocusable = true
                contentDescription = "Ladegerät-Infos"
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(0, 0, dp(12), 0) }
                setOnClickListener { showChargerInfo() }
            })
            addView(text(22f, bold = true).apply {
                text = "ChargeDeck"; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = "+"; textSize = 28f; gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD); setTextColor(Ui.t.onFill)
                setPadding(0, 0, 0, dp(3))
                background = RippleDrawable(ColorStateList.valueOf(Ui.t.ripple),
                    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.t.accent) }, null)
                isClickable = true; isFocusable = true
                contentDescription = "Neues Ladeprogramm"
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                setOnClickListener { onNewProgramPressed() }
            })
        })
        tvConn = text(13f).apply { text = "…" }
        root.addView(tvConn)
        tvTrial = text(12f).also { root.addView(it) }
        trialTick()
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnConnect = Button(this).apply { text = "Verbinden"; setOnClickListener { tryConnect() } }
        btnRow.addView(btnConnect)
        root.addView(btnRow)

        // Eingang / Geraet
        val inCard = card()
        inCard.addView(heading("EINGANG / GERÄT"))
        val gRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        gIn = GaugeView(this).apply {
            title = "Eingang"; unit = "V"; min = 10.0; max = 60.0; decimals = 2
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        gTemp = GaugeView(this).apply {
            title = "Gerät"; unit = "°C"; min = 0.0; max = 60.0; decimals = 1
            zones = listOf(0.0 to cGreen, 40.0 to cOrange, 50.0 to cRed)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        // Mitte: Luefterrad, nur sichtbar wenn der Luefter laeuft (Platz bleibt reserviert)
        fanBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(80), MATCH_PARENT)
            visibility = View.INVISIBLE
        }
        fanView = FanView(this).apply { layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)) }
        fanBox.addView(fanView)
        fanBox.addView(text(12f).apply { text = "Lüfter"; gravity = Gravity.CENTER; setTextColor(Ui.t.accent) })
        gRow.addView(gIn); gRow.addView(fanBox); gRow.addView(gTemp)
        inCard.addView(gRow)
        root.addView(inCard)

        // Kanaele: nebeneinander, bei schmalem Display (< 600 dp) untereinander
        val narrow = resources.configuration.screenWidthDp < 600
        val chRow = LinearLayout(this).apply {
            orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        for (k in 0..1) {
            val cc = card().apply {
                layoutParams = if (narrow) LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(6)) }
                else LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply {
                    setMargins(if (k == 0) 0 else dp(5), dp(6), if (k == 0) dp(5) else 0, dp(6))
                }
            }
            // Kopfzeile: Kanal-Titel | Status-Badge | Verlauf-Knopf (rechts)
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(8))
            }
            head.addView(text(20f, bold = true).apply { text = "Kanal ${k + 1}"; setPadding(0, 0, dp(12), 0) })
            tvState[k] = text(15f, bold = true).apply {
                text = "–"
                setPadding(dp(12), dp(3), dp(12), dp(3))
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat() }
            }
            head.addView(tvState[k])
            head.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
            head.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
                val shape = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat(); setColor(Ui.t.chip); setStroke(dp(1), Ui.t.chipStroke)
                }
                background = RippleDrawable(ColorStateList.valueOf(Ui.t.ripple), shape, null)
                isClickable = true; isFocusable = true
                contentDescription = "Verlauf Kanal ${k + 1}"
                setOnClickListener { showChart(k) }
                addView(ChartIconView(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)) })
            })
            // Trennlinie unter dem Kopf
            val sep = View(this).apply {
                setBackgroundColor(Ui.t.divider)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { setMargins(0, 0, 0, dp(8)) }
            }
            tvErr[k] = text(14f, bold = true).apply { setTextColor(Ui.t.error); visibility = View.GONE }
            tvClock[k] = text(26f, bold = true, mono = true).apply { text = "⏱ 00:00:00" }
            tvValues[k] = text(16f).apply { text = "–" }
            tvCells[k] = text(12f, mono = true).apply { text = "" }
            cellBox[k] = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            // Inhalt oben, Knoepfe unten in der Karte
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, if (narrow) WRAP_CONTENT else 0, if (narrow) 0f else 1f)
            }
            content.addView(head); content.addView(sep)
            content.addView(tvErr[k]); content.addView(tvClock[k])
            content.addView(tvValues[k]); content.addView(tvCells[k]); content.addView(cellBox[k])
            cc.addView(content)
            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            val ripple = ColorStateList.valueOf(Ui.t.ripple)
            // Hauptknopf: Start -> Halten -> Weiter
            btnMainIcon[k] = MediaIconView(this).apply {
                color = Ui.t.onFill
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { setMargins(0, 0, dp(10), 0) }
            }
            btnMainText[k] = TextView(this).apply {
                textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(Ui.t.onFill); text = "Start"
            }
            btnMain[k] = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                val shape = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(fillGreen) }
                background = RippleDrawable(ripple, shape, null)
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, dp(52), 3f).apply { setMargins(0, 0, dp(12), 0) }
                addView(btnMainIcon[k]); addView(btnMainText[k])
                setOnClickListener { onMainPressed(k) }
            }
            // Stopp: abgesetzt, nur rot umrandet
            btnStop[k] = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                val shape = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat(); setColor(Color.TRANSPARENT); setStroke(dp(2), cRed)
                }
                background = RippleDrawable(ColorStateList.valueOf(argb(80, Ui.t.error)), shape, null)
                isClickable = true; isFocusable = true
                contentDescription = "Stopp Kanal ${k + 1}"
                layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
                addView(MediaIconView(this@MainActivity).apply {
                    kind = MediaIconView.Kind.STOP; color = cRed
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                })
                setOnClickListener { onStopPressed(k) }
            }
            buttons.addView(btnMain[k]); buttons.addView(btnStop[k])
            cc.addView(buttons)
            updateControls(k, null)
            chRow.addView(cc)
        }
        root.addView(chRow)

        // Abstandhalter: schiebt das Protokoll an den unteren Rand, wenn Platz frei ist
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f) })

        // Log
        val lc = card()
        lc.addView(heading("PROTOKOLL"))
        tvLog = text(12f, mono = true)
        lc.addView(tvLog)
        root.addView(lc)

        // Inhalt scrollt, Leiste mit Zahnrad bleibt unten fest
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.t.bar)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        // Knopf "Eingangsquelle": Stecker-Symbol + aktive Quelle
        srcButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(14), dp(4))
            val shape = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Ui.t.barChip)
                setStroke(dp(1), Ui.t.barChipStroke)
            }
            background = RippleDrawable(ColorStateList.valueOf(Ui.t.ripple), shape, null)
            isClickable = true
            isFocusable = true
            contentDescription = "Eingangsquelle wählen"
            setOnClickListener { chooseSource() }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(2), dp(4), dp(10), dp(4)) }
        }
        srcButton.addView(PlugView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { setMargins(0, 0, dp(6), 0) }
        })
        srcButton.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(11f).apply { text = "Eingangsquelle"; setTextColor(Ui.t.barTextDim) })
            tvSource = text(15f, bold = true).apply { text = "Quelle ?"; setTextColor(Ui.t.barText) }
            addView(tvSource)
        })
        bottomBar.addView(srcButton)
        // Abstandhalter: Eingangsquelle links, Zahnrad rechts
        bottomBar.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        bottomBar.addView(GearView(this).apply {
            contentDescription = "Einstellungen"
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(dp(8), dp(2), dp(8), dp(2)) }
            isClickable = true
            setOnClickListener { showSettings() }
        })
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.t.page)
            addView(scroll)
            addView(bottomBar)
        })
    }
}
