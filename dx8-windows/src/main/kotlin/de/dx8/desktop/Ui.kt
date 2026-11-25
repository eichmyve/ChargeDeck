package de.dx8.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.dx8.monitor.AppTheme
import de.dx8.monitor.ChannelData
import de.dx8.monitor.ChannelLogic
import de.dx8.monitor.MainAct
import de.dx8.monitor.OPERATIONS
import de.dx8.monitor.Program
import kotlinx.coroutines.delay

private fun c(argb: Int) = Color(argb)

private fun fmtClock(ms: Long): String { val s = ms / 1000; return "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) }

/** Stufen fuer "Ansicht → Größe" (Faktor auf Schrift und Abstände). */
val ZOOMS = listOf(1.0f, 1.15f, 1.3f, 1.5f)

@Composable
fun ChargeDeckApp(m: AppModel, t: AppTheme, zoom: Float = 1.15f) {
    val base = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density * zoom, base.fontScale)
    ) { AppContent(m, t) }
}

@Composable
private fun AppContent(m: AppModel, t: AppTheme) {
    // Testversion: Hinweis oben und spaetesten Zeitpunkt jede Minute aktualisieren
    LaunchedEffect(Unit) { while (true) { m.trialTick(); delay(60_000) } }
    val scheme = if (t.dark) darkColorScheme(primary = c(t.accent), background = c(t.page), surface = c(t.card))
    else lightColorScheme(primary = c(t.accent), background = c(t.page), surface = c(t.card))
    // Uhr fuer das Blinken bei "Warten"
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(250); now = System.currentTimeMillis() } }

    MaterialTheme(colorScheme = scheme) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().background(c(t.page))) {
                TopBar(m, t)
                Row(Modifier.weight(1f).fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (k in 0..1) ChannelCard(m, t, k, now, Modifier.weight(1f).fillMaxHeight())
                }
                LogBar(m, t)
            }
            // Fenster ueber der App: Programmliste, Assistent, Programmwahl beim Start
            m.managerMode?.let { ProgramManager(m, t, it) }
            m.wizard?.let { ProgramWizard(m, t, it) }
            m.startDialog?.let { (k, forced) -> StartDialog(m, t, k, forced) { m.startDialog = null } }
            if (m.showSources) SourcePanel(m, t)
        }
        Dialogs(m, t)
    }
}

// ==================================================================== Kopfleiste

@Composable
private fun TopBar(m: AppModel, t: AppTheme) {
    var showConn by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(c(t.bar)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("ChargeDeck", color = c(t.barText), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(16.dp))
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(c(if (m.connOk) t.ok else t.warn)))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(m.connText, color = c(t.barTextDim), fontSize = 14.sp)
            m.trialBanner?.let { Text(it, color = c(if (m.trialWarn) t.warn else t.barTextDim), fontSize = 12.sp) }
        }
        m.info?.let { inp ->
            val src = inp.source(inp.activeSource)
            // Eingangsquelle: antippen = Quelle waehlen
            Row(Modifier.clip(RoundedCornerShape(8.dp)).background(c(t.barChip)).border(1.dp, c(t.barChipStroke), RoundedCornerShape(8.dp))
                .clickable { m.openSources() }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Quelle ${inp.activeSource + 1}", color = c(t.barText), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("  " + src.chips().joinToString(" · ") + if (src.regen) "  ⟲" else "", color = c(t.barTextDim), fontSize = 13.sp)
                Text("  ▾", color = c(t.barText), fontSize = 13.sp)
            }
            Spacer(Modifier.width(8.dp))
        }
        if (m.loadingPrograms) { Text("Lese Programme …", color = c(t.barTextDim), fontSize = 13.sp); Spacer(Modifier.width(12.dp)) }
        when {
            m.demo -> {
                BarButton(t, "Ladeprogramme …") { m.openPrograms() }
                BarButton(t, "Demo beenden") { m.stopDemo() }
            }
            m.connected -> {
                BarButton(t, "Ladeprogramme …") { m.openPrograms() }
                BarButton(t, "Info") { showInfo = true }
                BarButton(t, "Trennen") { m.disconnect() }
            }
            else -> {
                BarButton(t, if (m.viaWifi) "Verbinden (WLAN)" else "Verbinden (USB)") { m.connect() }
                BarButton(t, "Verbindung …") { showConn = true }
                BarButton(t, "Demo") { m.startDemo() }
            }
        }
    }
    if (showConn) ConnectionDialog(m) { showConn = false }
    if (showInfo) InfoDialog(m) { showInfo = false }
}

@Composable
private fun BarButton(t: AppTheme, text: String, onClick: () -> Unit) {
    Box(Modifier.padding(start = 8.dp).clip(RoundedCornerShape(8.dp)).background(c(t.barChip))
        .border(1.dp, c(t.barChipStroke), RoundedCornerShape(8.dp)).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, color = c(t.barText), fontSize = 13.sp)
    }
}

// ==================================================================== Kanal

@Composable
private fun ChannelCard(m: AppModel, t: AppTheme, k: Int, now: Long, modifier: Modifier) {
    val ch = m.channels.getOrNull(k)
    Column(modifier.clip(RoundedCornerShape(t.cardRadiusDp.dp)).background(c(t.card))
        .then(if (t.cardStroke != 0) Modifier.border(1.dp, c(t.cardStroke), RoundedCornerShape(t.cardRadiusDp.dp)) else Modifier)
        .padding(16.dp)) {
        // Kopf: Kanal + Status
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("KANAL ${k + 1}", color = c(t.heading), fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (ch != null) {
                val col = c(t.stateColor(ch.run))
                // Symbol nach Stromrichtung: Laden / Entladen / Halten / Stand
                val icon = when { ch.currentA > 0.005 -> "⚡"; ch.currentA < -0.005 -> "⇣"; ch.run == 1 -> "❚❚"; ch.isActive -> "●"; else -> "○" }
                Row(Modifier.alpha(if (ChannelLogic.blinkOn(ch, now)) 1f else 0.25f).clip(RoundedCornerShape(8.dp))
                    .background(col.copy(alpha = 0.12f)).border(1.5.dp, col, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(icon, color = col, fontSize = 22.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(ChannelLogic.badgeText(ch), color = col, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (ch == null) {
            Spacer(Modifier.height(24.dp))
            Text("Keine Daten", color = c(t.textMuted), fontSize = 16.sp)
            return@Column
        }
        ChannelLogic.errorLine(ch)?.let { Text(it, color = c(t.error), fontSize = 13.sp) }
        if (m.mismatch[k]) Text("Anzeige stimmt nicht: meldet „${ch.runText}“, liefert aber %+.2f A – am Lader prüfen".format(ch.currentA),
            color = c(t.error), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))

        // Hauptwerte
        Row(Modifier.fillMaxWidth()) {
            BigValue(t, "Spannung", "%.3f V".format(ch.uoutV), Modifier.weight(1f))
            BigValue(t, "Strom", "%+.2f A".format(ch.currentA), Modifier.weight(1f))
            BigValue(t, "Kapazität", "${ch.capMah} mAh", Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            SmallValue(t, "Leistung", "%.1f W".format(ch.powerW), Modifier.weight(1f))
            SmallValue(t, "Laufzeit", fmtClock(ch.ts), Modifier.weight(1f))
            SmallValue(t, "Temp. intern", "%.1f °C".format(ch.tInt), Modifier.weight(1f))
            SmallValue(t, "Eingang", "%.2f V".format(ch.uinV), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))

        // Zellen
        Text(ChannelLogic.cellsHeader(ch, null), color = c(t.textDim), fontSize = 13.sp)
        ChannelLogic.irHead(ch)?.let { h ->
            Row(Modifier.fillMaxWidth()) {
                Text(h.line, color = c(stateCol(t, h.lineState)), fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(h.total, color = c(stateCol(t, h.totalState)), fontSize = 12.sp)
            }
        }
        for (row in ChannelLogic.cellRows(ch)) CellRow(t, row)
        Spacer(Modifier.height(8.dp))

        // Verlauf
        Chart(t, m.history[k], m.cellChart[k], { m.cellChart[k] = it }, Modifier.fillMaxWidth().weight(1f).heightIn(min = 80.dp))
        Spacer(Modifier.height(10.dp))

        // Rueckfrage
        if (ch.run == 12) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c(t.warn).copy(alpha = 0.15f)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Rückfrage am Lader (z. B. „Regeneratives Entladen?“)", color = c(t.text), fontSize = 13.sp, modifier = Modifier.weight(1f))
                Button(onClick = { m.answer(k, true) }, enabled = !m.busy) { Text("Ja") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { m.answer(k, false) }, enabled = !m.busy) { Text("Nein") }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Knoepfe
        val act = ChannelLogic.mainAct(ch)
        val partnerBlock = act == MainAct.START && m.partnerCheck(k) == ChannelLogic.PartnerCheck.BLOCK
        val hidden = ChannelLogic.startHidden(act, m.programs.isNotEmpty(), m.demo)
        val dimMain = ChannelLogic.mainDimmed(ch) || partnerBlock || m.busy
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val (label, fill) = when (act) {
                MainAct.START -> "▶  Start" to t.startFill
                MainAct.CONTINUE -> "▶  Weiter" to t.startFill
                MainAct.HOLD -> "❚❚  Halten" to t.holdFill
            }
            Button(
                onClick = {
                    when (act) {
                        MainAct.START -> when {
                            partnerBlock -> m.notice = Notice("Kanal ${k + 1} nicht starten",
                                ChannelLogic.partnerWaitingReason(k, m.channels) ?: "")
                            m.partnerCheck(k) == ChannelLogic.PartnerCheck.ASK && !m.demo -> m.askForce = k
                            else -> m.startDialog = k to false
                        }
                        MainAct.CONTINUE -> m.resume(k)
                        MainAct.HOLD -> m.hold(k)
                    }
                },
                enabled = !hidden,
                colors = ButtonDefaults.buttonColors(containerColor = c(fill), contentColor = c(t.onFill)),
                modifier = Modifier.weight(1f).height(54.dp).alpha(if (dimMain) 0.45f else 1f)
            ) { Text(if (hidden) "Programme werden gelesen …" else label, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            val dimStop = ChannelLogic.stopDimmed(ch, m.prevTs(k)) || m.busy
            Button(
                onClick = { m.stop(k) },
                colors = ButtonDefaults.buttonColors(containerColor = c(t.error), contentColor = c(t.onFill)),
                modifier = Modifier.width(150.dp).height(54.dp).alpha(if (dimStop) 0.4f else 1f)
            ) { Text("■  Stopp", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

private fun stateCol(t: AppTheme, s: ChannelLogic.LineState) = when (s) {
    ChannelLogic.LineState.GOOD -> t.ok; ChannelLogic.LineState.WARN -> t.warn
    ChannelLogic.LineState.BAD -> t.error; ChannelLogic.LineState.NONE -> t.textDim
}

@Composable
private fun BigValue(t: AppTheme, label: String, value: String, modifier: Modifier) = Column(modifier) {
    Text(label, color = c(t.textDim), fontSize = 12.sp)
    Text(value, color = c(t.text), fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
}

@Composable
private fun SmallValue(t: AppTheme, label: String, value: String, modifier: Modifier) = Column(modifier) {
    Text(label, color = c(t.textDim), fontSize = 11.sp)
    Text(value, color = c(t.text), fontSize = 15.sp, fontFamily = FontFamily.Monospace)
}

@Composable
private fun CellRow(t: AppTheme, r: ChannelLogic.CellRow) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(r.label + " V", color = c(t.text), fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(96.dp))
        Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(c(t.gaugeTrack))) {
            Box(Modifier.fillMaxWidth(r.fill).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(c(t.ok)))
        }
        Text(if (r.balancing) "  ⇄" else "    ", color = c(t.warn), fontSize = 13.sp, modifier = Modifier.width(28.dp))
        Text(r.ir, color = c(stateCol(t, r.irState)), fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(90.dp))
    }
}

/**
 * Verlauf: Spannung (linke Skala) und Strom (rechte Skala) – oder umgeschaltet die Zellspannungen
 * (gemeinsame Skala, eine Farbe je Zelle).
 */
@Composable
private fun Chart(t: AppTheme, pts: List<Sample>, cellsMode: Boolean, onMode: (Boolean) -> Unit, modifier: Modifier) {
    val nCells = pts.maxOfOrNull { it.cells.size } ?: 0
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Verlauf  ", color = c(t.textDim), fontSize = 13.sp)
            Toggle(t, "Spannung / Strom", !cellsMode) { onMode(false) }
            Toggle(t, "Zellen", cellsMode) { onMode(true) }
            Spacer(Modifier.width(10.dp))
            if (!cellsMode) {
                Text("— Spannung  ", color = c(t.series[0]), fontSize = 13.sp)
                Text("— Strom", color = c(t.series[1]), fontSize = 13.sp)
            } else for (n in 0 until nCells) Text("— Z${n + 1} ", color = c(t.series[n % t.series.size]), fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            if (pts.size >= 2) {
                val txt = if (!cellsMode) "%.2f–%.2f V · %.2f–%.2f A".format(pts.minOf { it.u }, pts.maxOf { it.u }, pts.minOf { it.i }, pts.maxOf { it.i })
                else pts.flatMap { it.cells }.let { all -> if (all.isEmpty()) "" else "%.3f–%.3f V".format(all.min() / 1000.0, all.max() / 1000.0) }
                Text(txt, color = c(t.chartText), fontSize = 12.sp)
            }
        }
        Box(Modifier.fillMaxSize().padding(top = 4.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val grid = c(t.chartGrid)
                for (g in 0..4) { val y = size.height * g / 4; drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f) }
                if (pts.size < 2) return@Canvas
                val t0 = pts.first().t; val t1 = maxOf(pts.last().t, t0 + 1)
                fun line(vals: List<Pair<Long, Double>>, lo: Double, hi: Double, col: Color) {
                    if (vals.size < 2) return
                    val p = Path()
                    vals.forEachIndexed { i, (tt, v) ->
                        val x = (tt - t0).toFloat() / (t1 - t0) * size.width
                        val y = size.height - ((v - lo) / (hi - lo)).toFloat() * size.height * 0.9f - size.height * 0.05f
                        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                    }
                    drawPath(p, col, style = Stroke(width = 2.5f))
                }
                if (!cellsMode) {
                    for ((sel, col) in listOf<Pair<(Sample) -> Double, Int>>({ s: Sample -> s.u } to t.series[0], { s: Sample -> s.i } to t.series[1])) {
                        val v = pts.map { it.t to sel(it) }
                        val lo = v.minOf { it.second }; val hi = maxOf(v.maxOf { it.second }, lo + 0.01)
                        line(v, lo, hi, c(col))
                    }
                } else {
                    // gemeinsame Skala fuer alle Zellen, damit Abweichungen sichtbar werden
                    val all = pts.flatMap { it.cells }
                    if (all.isEmpty()) return@Canvas
                    val lo = all.min() / 1000.0; val hi = maxOf(all.max() / 1000.0, lo + 0.005)
                    for (n in 0 until nCells)
                        line(pts.mapNotNull { s -> s.cells.getOrNull(n)?.let { s.t to it / 1000.0 } }, lo, hi, c(t.series[n % t.series.size]))
                }
            }
            if (pts.size < 2 || (cellsMode && nCells == 0))
                Text(if (cellsMode && pts.size >= 2) "keine Zelldaten (ohne Balancerkabel)" else "noch keine Verlaufsdaten – Aufzeichnung startet mit dem Programm",
                    color = c(t.chartText), fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun Toggle(t: AppTheme, label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.padding(end = 4.dp).clip(RoundedCornerShape(12.dp)).background(c(if (on) t.accent else t.card))
        .border(1.dp, c(if (on) t.accent else t.chipStroke), RoundedCornerShape(12.dp)).clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(label, color = c(if (on) t.onFill else t.text), fontSize = 13.sp)
    }
}

// ==================================================================== Protokoll

@Composable
private fun LogBar(m: AppModel, t: AppTheme) {
    LazyColumn(Modifier.fillMaxWidth().height(96.dp).background(c(t.bar)).padding(horizontal = 16.dp, vertical = 6.dp)) {
        items(m.log) { Text(it, color = c(t.barTextDim), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
    }
}

// ==================================================================== Dialoge

@Composable
private fun Dialogs(m: AppModel, t: AppTheme) {
    m.notice?.let { n ->
        AlertDialog(onDismissRequest = { m.notice = null }, title = { Text(n.title) }, text = { Text(n.text) },
            confirmButton = { TextButton(onClick = { m.notice = null }) { Text("OK") } })
    }
    m.askForce?.let { k ->
        AlertDialog(onDismissRequest = { m.askForce = null },
            title = { Text("Kanal ${2 - k} wartet") },
            text = {
                Text("Kanal ${2 - k} steht auf „Warten“ und wurde am Ladegerät gestartet – die App kennt sein Programm nicht.\n\n" +
                        "Ist es Akku zu Akku, Kanal ${k + 1} bitte am Ladegerät starten: ein Start über die App stört die Kopplung.\n\n" +
                        "Sonst kann Kanal ${k + 1} normal über die App gestartet werden.")
            },
            confirmButton = { TextButton(onClick = { m.askForce = null; m.startDialog = k to true }) { Text("Trotzdem starten") } },
            dismissButton = { TextButton(onClick = { m.askForce = null }) { Text("Abbrechen") } })
    }
}

@Composable
private fun StartDialog(m: AppModel, t: AppTheme, k: Int, forced: Boolean, onClose: () -> Unit) {
    val PL = de.dx8.monitor.ProgramList
    val all = m.programs
    // zuletzt gestartetes Programm und Betriebsart vorauswaehlen
    var sel by remember { mutableStateOf(all.firstOrNull { it.slot == m.lastSlot[k] }) }
    var op by remember { mutableStateOf(m.lastOp[k]) }
    fun pick(p: Program) { sel = p; if (!p.opEnabled(op)) op = OPERATIONS.firstOrNull { p.opEnabled(it.second) }?.second ?: 0 }
    LaunchedEffect(Unit) { sel?.let { pick(it) } }
    var filter by remember { mutableStateOf<Int?>(null) }
    var showStd by remember { mutableStateOf(sel?.isBuiltIn == true) }
    val ch = m.channels.getOrNull(k)
    val akku = when { ch == null -> ""; ch.cells.isEmpty() -> "  ·  Akku %.2f V (ohne Balancer)".format(ch.uoutV)
        else -> "  ·  Akku ${ch.cells.size}S, %.2f V".format(ch.uoutV) }
    Panel(t, "Kanal ${k + 1} starten$akku", 1100.dp,
        header = {
            Spacer(Modifier.height(10.dp))
            // Gewaehltes Programm und Betriebsart – immer sichtbar
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c(t.page)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(sel?.let { "%02d  %s".format(it.pos, it.name) } ?: "Programm wählen …", color = c(if (sel == null) t.textDim else t.text),
                    fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                for ((i, pair) in OPERATIONS.withIndex()) {
                    val (name, code) = pair
                    val enabled = sel?.opEnabled(code) ?: false
                    val on = enabled && op == code
                    Row(Modifier.padding(start = 6.dp).alpha(if (enabled) 1f else 0.3f).clip(RoundedCornerShape(10.dp))
                        .background(c(if (on) t.accent else t.card)).border(1.dp, c(if (on) t.accent else t.chipStroke), RoundedCornerShape(10.dp))
                        .clickable(enabled = enabled) { op = code }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(de.dx8.desktop.OP_ICONS[i], color = c(if (on) t.onFill else t.accent), fontSize = 20.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(name, color = c(if (on) t.onFill else t.text), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            sel?.let { p ->
                if (ChannelLogic.isAkkuZuAkku(op, p.raw))
                    Text("Akku zu Akku: Partner danach am Ladegerät mit „Laden“ starten.", color = c(t.warn), fontSize = 14.sp,
                        modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                Chip(t, "Alle", filter == null) { filter = null }
                for ((ty, n) in PL.typeCounts(all)) Chip(t, "${de.dx8.monitor.BATTERY_TYPES.getOrElse(ty) { "Typ $ty" }} $n", filter == ty) { filter = ty }
            }
        },
        buttons = {
            TextButton(onClick = onClose) { Text("Abbrechen", fontSize = 15.sp) }
            Button(onClick = { sel?.let { m.start(it, op, k, forced) }; onClose() }, enabled = sel?.opEnabled(op) == true,
                colors = ButtonDefaults.buttonColors(containerColor = c(t.startFill), contentColor = c(t.onFill)),
                modifier = Modifier.height(44.dp)) { Text("▶  Starten", fontSize = 16.sp) }
        }) {
        val own = PL.custom(all, filter)
        Text("EIGENE PROGRAMME (${own.size})", color = c(t.heading), fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 3.dp, top = 4.dp, bottom = 4.dp))
        if (own.isEmpty()) Text("Keine eigenen Programme dieses Typs.", color = c(t.textDim), fontSize = 14.sp)
        else TileGrid(t, own, sel?.slot ?: -1, cols = 3) { pick(it) }
        val std = PL.builtIn(all, filter)
        if (std.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp).clip(RoundedCornerShape(12.dp))
                .border(1.dp, c(t.divider), RoundedCornerShape(12.dp)).clickable { showStd = !showStd }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Standardprogramme (${std.size}): " + std.joinToString(", ") { it.typeName }, color = c(t.text), fontSize = 14.sp,
                    modifier = Modifier.weight(1f))
                Text(if (showStd) "▾ ausblenden" else "▸ anzeigen", color = c(t.accent), fontSize = 14.sp)
            }
            if (showStd) TileGrid(t, std, sel?.slot ?: -1, cols = 3) { pick(it) }
        }
    }
}

/** Eingangsquelle waehlen: vier Karten, aktive hervorgehoben; antippen = umstellen (mit Rueckfrage). */
@Composable
private fun SourcePanel(m: AppModel, t: AppTheme) {
    val ci = m.info ?: return
    var ask by remember { mutableStateOf<Int?>(null) }
    Panel(t, "Eingangsquelle wählen", 720.dp, buttons = { TextButton(onClick = { m.showSources = false }) { Text("Schließen", fontSize = 15.sp) } }) {
        for (q in 0..3) {
            val src = ci.source(q)
            val active = q == ci.activeSource
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                .background(if (active) c(t.accent).copy(alpha = if (t.dark) 0.2f else 0.1f) else c(t.card))
                .border(if (active) 2.dp else 1.dp, c(if (active) t.accent else t.divider), RoundedCornerShape(14.dp))
                .clickable(enabled = !active) { ask = q }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(23.dp)).background(if (active) c(t.accent) else c(t.accent).copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center) {
                    Text("${q + 1}", color = c(if (active) t.onFill else t.accent), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Quelle ${q + 1}", color = c(t.text), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        if (active) Text("  AKTIV", color = c(t.accent), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(src.chips().joinToString("  ·  "), color = c(t.text), fontSize = 15.sp)
                    Text((if (src.regen) "⟲ " else "") + src.regenLine(), color = c(if (src.regen) t.warn else t.textDim), fontSize = 14.sp)
                }
            }
        }
        Text("Antippen = als Eingangsquelle verwenden. Werte der Quellen ändern: am Ladegerät oder in der App.",
            color = c(t.textDim), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
    ask?.let { q ->
        val src = ci.source(q)
        Panel(t, "Auf Quelle ${q + 1} umstellen?", 560.dp, buttons = {
            TextButton(onClick = { ask = null }) { Text("Abbrechen", fontSize = 15.sp) }
            Button(onClick = { ask = null; m.showSources = false; m.setSource(q) },
                colors = ButtonDefaults.buttonColors(containerColor = c(t.accent), contentColor = c(t.onFill))) { Text("Umstellen", fontSize = 15.sp) }
        }) {
            Text("Quelle ${ci.activeSource + 1} → Quelle ${q + 1}\n\n" + src.chips().joinToString(" · ") + "\n" + src.regenLine() +
                    "\n\nWird im Lader dauerhaft gespeichert. Ändern nur möglich, wenn kein Kanal arbeitet.", color = c(t.text), fontSize = 15.sp)
        }
    }
}

@Composable
private fun ConnectionDialog(m: AppModel, onClose: () -> Unit) {
    var wifi by remember { mutableStateOf(m.viaWifi) }
    var host by remember { mutableStateOf(m.host) }
    var port by remember { mutableStateOf(m.port.toString()) }
    var token by remember { mutableStateOf(m.token) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Verbindung") },
        text = {
            Column(Modifier.width(420.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(!wifi, { wifi = false }); Text("USB (Lader direkt am PC)") }
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(wifi, { wifi = true }); Text("WLAN-Brücke (Raspberry Pi)") }
                if (wifi) {
                    OutlinedTextField(host, { host = it }, label = { Text("Adresse") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(token, { token = it }, label = { Text("Passwort der Brücke") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                m.saveConnection(wifi, host, port.toIntOrNull() ?: de.dx8.monitor.TcpHidLink.DEFAULT_PORT, token)
                onClose(); m.connect()
            }) { Text("Speichern und verbinden") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } }
    )
}

@Composable
private fun InfoDialog(m: AppModel, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Ladegerät") },
        text = {
            LazyColumn(Modifier.width(640.dp).height(460.dp)) {
                m.fwText?.let { item { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp); Spacer(Modifier.height(12.dp)) } }
                item { Text(m.info?.format() ?: "Keine Daten", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Schließen") } },
        dismissButton = { TextButton(onClick = { m.checkFirmware() }) { Text("Firmware prüfen") } }
    )
}
