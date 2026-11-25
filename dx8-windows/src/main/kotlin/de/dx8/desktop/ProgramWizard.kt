package de.dx8.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.dx8.monitor.AppTheme
import de.dx8.monitor.BATTERY_TYPES
import de.dx8.monitor.OPERATIONS
import de.dx8.monitor.Program
import de.dx8.monitor.ProgramList
import de.dx8.monitor.ProgramMemory
import kotlin.math.roundToInt

private fun c(argb: Int) = Color(argb)

// ==================================================================== Bausteine

/** Fenster ueber der App (abgedunkelter Hintergrund, Karte in der Mitte, Inhalt scrollt, Knoepfe bleiben stehen). */
@Composable
fun Panel(t: AppTheme, title: String, width: Dp, buttons: @Composable RowScope.() -> Unit,
          header: (@Composable ColumnScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center) {
        val maxH = maxHeight * 0.94f
        CompositionLocalProvider(LocalContentColor provides c(t.text)) {
            Column(Modifier.width(minOf(width, maxWidth * 0.96f)).heightIn(max = maxH).clip(RoundedCornerShape(16.dp))
                .background(c(t.card)).border(1.dp, c(if (t.cardStroke != 0) t.cardStroke else t.divider), RoundedCornerShape(16.dp))
                .padding(20.dp)) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c(t.text))
                header?.invoke(this)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) { buttons() }
            }
        }
    }
}

@Composable
private fun Label(t: AppTheme, s: String, color: Int = t.textDim, size: Int = 13) =
    Text(s, color = c(color), fontSize = size.sp, modifier = Modifier.padding(vertical = 3.dp))

@Composable
private fun Heading(t: AppTheme, s: String) =
    Text(s, color = c(t.text), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))

/** Auswahlliste (aufklappend). */
@Composable
private fun Choice(t: AppTheme, options: List<String>, sel: Int, enabled: Boolean, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.alpha(if (enabled) 1f else 0.45f).clip(RoundedCornerShape(8.dp)).border(1.dp, c(t.chipStroke), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { open = true }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(options.getOrElse(sel) { "?" }, color = c(t.text), fontSize = 14.sp)
            Text("  ▾", color = c(t.accent), fontSize = 14.sp)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onSelect(i) }) }
        }
    }
}

@Composable
private fun LabeledRow(t: AppTheme, label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c(t.text), fontSize = 14.sp, modifier = Modifier.width(190.dp))
        content()
    }
}

@Composable
private fun CheckRow(t: AppTheme, text: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled) { onChange(!checked) }.padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = if (enabled) onChange else null, enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = c(t.accent), checkmarkColor = c(t.onFill)))
        Text(text, color = c(t.text), fontSize = 14.sp, modifier = Modifier.alpha(if (enabled) 1f else 0.5f))
    }
}

@Composable
private fun LinkButton(t: AppTheme, text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(8.dp)).background(c(t.card))
        .border(1.dp, c(t.chipStroke), RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, color = c(t.accent), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** Schieberegler eines Programmfelds: Name mit min–max, Wert rechts, darunter [−] Regler [+] im Raster des Felds. */
@Composable
private fun FieldSlider(t: AppTheme, f: ProgramMemory.Field, raw: Long, enabled: Boolean, onChange: (Long) -> Unit) {
    val sp = remember(f) { f.slider() }
    val pos = sp.posOf(raw)
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).alpha(if (enabled) 1f else 0.4f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.label, color = c(t.text), fontSize = 14.sp)
                Text("min–max: ${f.range()}", color = c(t.textDim), fontSize = 12.sp)
            }
            Text("${f.fmt(raw)} ${f.unit}".trim(), color = c(t.accent), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(t, "−", enabled) { onChange(sp.rawAt(pos - 1)) }
            Slider(value = pos.toFloat(), onValueChange = { v -> onChange(sp.rawAt(v.roundToInt())) },
                valueRange = 0f..maxOf(1, sp.steps).toFloat(), enabled = enabled,
                colors = SliderDefaults.colors(thumbColor = c(t.accent), activeTrackColor = c(t.accent), inactiveTrackColor = c(t.gaugeTrack)),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            StepButton(t, "+", enabled) { onChange(sp.rawAt(pos + 1)) }
        }
    }
}

@Composable
private fun StepButton(t: AppTheme, sym: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(c(t.card)).border(1.dp, c(t.chipStroke), RoundedCornerShape(8.dp))
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(sym, color = c(t.accent), fontSize = 18.sp)
    }
}

// ==================================================================== Programmliste

@Composable
fun ProgramManager(m: AppModel, t: AppTheme, mode: AppModel.ManagerMode) {
    val PL = ProgramList
    val all = m.programs
    val pick = mode == AppModel.ManagerMode.TEMPLATE
    var filter by remember { mutableStateOf<Int?>(null) }
    var showStd by remember(mode) { mutableStateOf(pick) }
    val onPick: (Program) -> Unit = { p -> m.editProgram(p, pick) }
    Panel(t, if (pick) "Vorlage für das neue Programm" else "Ladeprogramme", 1000.dp, buttons = {
        if (!pick) TextButton(onClick = { m.managerMode = null; m.programs = emptyList(); m.openPrograms() }, enabled = !m.demo) { Text("Neu lesen") }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { m.managerMode = null }) { Text(if (pick) "Abbrechen" else "Schließen") }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Chip(t, "Alle ${PL.custom(all).size}", filter == null) { filter = null }
                for ((ty, n) in PL.typeCounts(all)) Chip(t, "${BATTERY_TYPES.getOrElse(ty) { "Typ $ty" }} $n", filter == ty) { filter = ty }
            }
            if (!pick) Chip(t, "+ Neues Programm", true) { m.newProgram() }
        }
        val own = PL.custom(all, filter)
        Text("EIGENE PROGRAMME (${own.size})", color = c(t.heading), fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 3.dp, top = 12.dp, bottom = 4.dp))
        if (own.isEmpty()) Label(t, "Keine eigenen Programme dieses Typs.") else TileGrid(t, own, onPick = onPick)
        val std = PL.builtIn(all, filter)
        if (std.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp).clip(RoundedCornerShape(12.dp))
                .border(1.dp, c(t.divider), RoundedCornerShape(12.dp)).clickable { showStd = !showStd }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Standardprogramme (${std.size})\n" + std.joinToString(", ") { it.typeName } + if (pick) "" else " – als Vorlage",
                    color = c(t.text), fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(if (showStd) "▾ ausblenden" else "▸ anzeigen", color = c(t.accent), fontSize = 14.sp)
            }
            if (showStd) TileGrid(t, std, onPick = onPick)
        }
        Label(t, PL.LEGEND, size = 12)
    }
}

@Composable
fun Chip(t: AppTheme, label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.padding(end = 6.dp).clip(RoundedCornerShape(14.dp)).background(c(if (on) t.accent else t.card))
        .border(1.dp, c(if (on) t.accent else t.chipStroke), RoundedCornerShape(14.dp)).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text(label, color = c(if (on) t.onFill else t.text), fontSize = 13.sp)
    }
}

@Composable
fun TileGrid(t: AppTheme, list: List<Program>, selectedSlot: Int = -1, cols: Int = 2, onPick: (Program) -> Unit) {
    for (row in list.chunked(cols)) Row(Modifier.fillMaxWidth()) {
        for (i in 0 until cols) {
            val p = row.getOrNull(i)
            Box(Modifier.weight(1f).padding(3.dp)) { if (p != null) ProgramTile(t, p, p.slot == selectedSlot) { onPick(p) } }
        }
    }
}

/** Symbol je Betriebsart (Laden, Storage, Entladen, Zyklus, Nur Balancen). */
val OP_ICONS = listOf("⚡", "▣", "⇣", "↻", "⇄")

/** Kachel: Typ-Plakette | Name, Stroeme, Betriebsart-Symbole. */
@Composable
fun ProgramTile(t: AppTheme, p: Program, selected: Boolean = false, onClick: () -> Unit) {
    val PL = ProgramList
    val tc = c(t.series[PL.typeColorIndex(p.type)])
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) c(t.accent).copy(alpha = if (t.dark) 0.2f else 0.1f) else c(t.card))
        .border(if (selected) 2.dp else 1.dp, c(if (selected) t.accent else if (t.cardStroke != 0) t.cardStroke else t.divider), RoundedCornerShape(14.dp))
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(tc.copy(alpha = if (t.dark) 0.24f else 0.14f)),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(p.typeName, color = tc, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            val cl = PL.cellsLabel(p)
            if (cl.isNotEmpty()) Text(cl, color = tc, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, color = c(t.text), fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚡ ", color = c(t.ok), fontSize = 15.sp)
                Text(PL.chargeText(p), color = c(t.text), fontSize = 14.sp)
                Text("     ⇣ ", color = c(t.series[1]), fontSize = 15.sp)
                Text(PL.dischargeText(p), color = c(t.text), fontSize = 14.sp)
                PL.regenTag(p)?.let { Text("   $it", color = c(t.warn), fontSize = 12.sp) }
            }
            Row(Modifier.padding(top = 5.dp)) {
                OPERATIONS.forEachIndexed { i, (name, op) ->
                    val on = p.opEnabled(op)
                    Row(Modifier.padding(end = 5.dp).clip(RoundedCornerShape(6.dp)).background(c(if (on) t.accent else t.divider))
                        .padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(OP_ICONS[i] + " " + PL.OP_SHORT[i], color = c(if (on) t.onFill else t.textMuted), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ==================================================================== Assistent

@Composable
fun ProgramWizard(m: AppModel, t: AppTheme, d: ProgramDraft) {
    val PM = ProgramMemory
    val p = d.p
    val steps = ProgramDraft.STEPS
    val last = d.step == steps.size - 1
    var sub by remember { mutableStateOf<String?>(null) }          // offenes Untermenue
    var confirm by remember { mutableStateOf<ProgramDraft.Result.Ok?>(null) }
    var a2aHint by remember { mutableStateOf(false) }
    val ro = d.readOnly

    Panel(t, if (d.isNew) "Neues Ladeprogramm" else "%02d %s".format(p.pos, p.name), 860.dp,
        header = {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                for (i in steps.indices) Column(Modifier.weight(1f).padding(horizontal = 2.dp).clickable { d.step = i },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(c(if (i <= d.step) t.accent else t.divider)))
                    Text(steps[i], color = c(if (i == d.step) t.accent else t.textDim), fontSize = 12.sp,
                        fontWeight = if (i == d.step) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Schritt ${d.step + 1} von ${steps.size} · ${steps[d.step]}", color = c(t.text), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Label(t, ProgramDraft.HINTS[d.step])
        },
        buttons = {
            TextButton(onClick = { m.wizard = null }) { Text(if (ro) "Schließen" else "Abbrechen") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { d.step-- }, enabled = d.step > 0) { Text("Zurück") }
            Button(onClick = {
                when {
                    !last -> d.step++
                    ro -> { m.wizard = null; m.editProgram(p, true) }
                    else -> when (val r = d.collect()) {
                        is ProgramDraft.Result.Error -> m.notice = Notice("Bitte prüfen", r.msg)
                        is ProgramDraft.Result.Ok ->
                            if (!d.isNew && r.mem.contentEquals(p.raw)) m.notice = Notice("Ladeprogramm", "Keine Änderung")
                            else confirm = r
                    }
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = c(t.accent), contentColor = c(t.onFill))) {
                Text(when {
                    !last -> "Weiter: ${steps[d.step + 1]}"
                    ro -> "Als Kopie anlegen"
                    d.isNew -> "Anlegen …"
                    else -> "Speichern …"
                })
            }
        }) {
        // Zahlenfeld nach Offset (falls fuer diesen Akkutyp vorhanden)
        @Composable fun field(off: Int?, enabled: Boolean = !ro) {
            val i = if (off == null) -1 else d.indexOf(off)
            if (i >= 0) FieldSlider(t, d.fields[i], d.values[i], enabled) { d.setField(i, it) }
        }
        @Composable fun opBox(op: Int) {
            val i = OPERATIONS.indexOfFirst { it.second == op }
            CheckRow(t, "${OPERATIONS[i].first} am Lader anzeigen (aktiv)", d.ops[i], !ro) { d.ops[i] = it }
        }
        @Composable fun pro(i: Int, content: @Composable () -> Unit) {
            Text((if (d.proOpen[i]) "▾ " else "▸ ") + ProgramDraft.PRO[i], color = c(t.accent), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp).clickable { d.proOpen[i] = !d.proOpen[i] })
            if (d.proOpen[i]) content()
        }

        when (d.step) {
            0 -> {   // Akku
                OutlinedTextField(d.name, { d.name = it.take(PM.NAME_MAX) }, label = { Text("Name") }, singleLine = true, enabled = !ro,
                    modifier = Modifier.fillMaxWidth())
                Label(t, "Akkutyp: ${p.typeName}" + (if (d.isNew) "  ·  Vorlage: %02d %s".format(p.pos, p.name) else "  ·  Platz ${p.slot}") +
                        (if (p.isBuiltIn && !d.isNew) "  ·  Standardprogramm (nur als Kopie änderbar)" else ""))
                if (d.isLi) {
                    LabeledRow(t, "Zellenzahl") { Choice(t, PM.CELL_OPTIONS, d.cells, !ro) { d.cells = it } }
                    Label(t, d.packText(), color = t.accent)
                }
                field(0x28)
            }
            1 -> {   // Laden
                opBox(0); opBox(4)
                field(0x62)
                Label(t, d.rate(0x62)?.let { "Laderate: $it" } ?: "Laderate: Kapazität eingeben", color = t.accent)
                if (d.isLi) {
                    LabeledRow(t, "Ladeart") {
                        Choice(t, PM.chargeModeOptions(PM.chargeMode(p.raw)),
                            if (d.chargeMode in PM.CHARGE_MODES.indices) d.chargeMode else PM.CHARGE_MODES.size, !ro, Modifier.weight(1f)) {
                            d.chargeMode = PM.chargeModeValue(it, PM.chargeMode(p.raw))
                        }
                        Spacer(Modifier.width(8.dp))
                        LinkButton(t, "Ändern …", d.chargeMode != PM.MODE_NO_BAL) { sub = "bal" }
                    }
                    Label(t, d.chargeInfo())
                }
                field(d.vo?.get(0))
                pro(1) {
                    field(0x66, d.endCurrentEnabled())
                    if (d.isLi) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.endInfo(), color = c(t.textDim), fontSize = 13.sp, modifier = Modifier.weight(1f))
                            LinkButton(t, "Ändern …", PM.endModeEditable(d.chargeMode)) { sub = "end" }
                        }
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LinkButton(t, "Erweitert …", modifier = Modifier.weight(1f)) { sub = "adv" }
                            LinkButton(t, "Sicherheit …", modifier = Modifier.weight(1f)) { sub = "safe" }
                        }
                    }
                }
            }
            2 -> {   // Storage
                opBox(1)
                if (d.vo == null) Label(t, "Für diesen Akkutyp gibt es keine Storage-Spannung.")
                field(d.vo?.get(1))
                if (d.isLi) pro(2) {
                    FieldSlider(t, PM.STO_COMP, d.stoComp, !ro) { d.stoComp = it }
                    CheckRow(t, "Beschleunigte Storage", d.fastSto, !ro) { d.fastSto = it }
                }
            }
            3 -> {   // Entladen
                opBox(2)
                field(0x64)
                Label(t, d.rate(0x64)?.let { "Entladerate: $it" } ?: "Entladerate: Kapazität eingeben", color = t.accent)
                field(d.vo?.get(2))
                LabeledRow(t, "Regenerativer Modus") {
                    Choice(t, PM.regenOptions(d.regenOld), d.regenIdx, !ro, Modifier.weight(1f)) { d.regenIdx = it }
                }
                if (d.regen == 2) Column(Modifier.padding(start = 12.dp, top = 4.dp)) {
                    LabeledRow(t, "Ausgang verbinden") {
                        Choice(t, PM.outputLoadOptions(d.loadOld), d.loadIdx, !ro) {
                            if (it == PM.AKKU_ZU_AKKU && it != d.loadIdx) a2aHint = true
                            d.loadIdx = it
                        }
                    }
                    val lim = PM.outputLimitsEditable(d.load)
                    FieldSlider(t, PM.OUT_VOLT, d.outV, !ro && lim) { d.outV = it }
                    FieldSlider(t, PM.OUT_CURR, d.outA, !ro && lim) { d.outA = it }
                    if (!lim) Label(t, "Bei „Von Akku zu Akku“ legt der Lader die Grenzen selbst fest.\n\n" +
                            "Ablauf: diesen Kanal in der App mit „Entladen“ starten, dann den anderen Kanal am Ladegerät mit „Laden“.", color = t.warn)
                }
                Label(t, "Zu Eingang: nur wenn die aktive Eingangsquelle Rückspeisung erlaubt (Einstellungen am Lader → Eingangsquellen). " +
                        "Zu Ausgang: Verbraucher, Spannungs- und Stromgrenze einstellen. " +
                        "Beim Start fragt der Lader nach – Ja = regenerativ, Nein = normal.")
                pro(3) { field(0x68) }
            }
            4 -> {   // Zyklus
                opBox(3)
                LabeledRow(t, "Zyklus Modus") {
                    Choice(t, PM.cycleModeOptions(d.cycleOld), d.cycleIdx, !ro, Modifier.weight(1f)) { d.cycleIdx = it }
                }
                PM.CYCLE_FIELDS.forEachIndexed { i, f -> FieldSlider(t, f, d.cycleVals[i], !ro) { d.cycleVals[i] = it } }
            }
            else -> {   // Fertig
                Heading(t, if (d.isNew) "Abweichend von der Vorlage" else "Deine Änderungen")
                val res = if (ro) null else d.collect()
                Text(when {
                    ro -> "Standardprogramm – nur ansehen. Mit „Als Kopie anlegen“ als eigenes Programm übernehmen."
                    res is ProgramDraft.Result.Error -> "Bitte korrigieren: ${res.msg}"
                    res is ProgramDraft.Result.Ok && res.changes.isEmpty() ->
                        if (d.isNew) "Keine – gleiche Werte wie die Vorlage %02d %s.".format(p.pos, p.name) else "Keine Änderung."
                    res is ProgramDraft.Result.Ok -> res.changes.joinToString("\n") { "• $it" }
                    else -> ""
                }, color = c(if (res is ProgramDraft.Result.Error) t.error else t.text), fontSize = 14.sp)
                Heading(t, "Ausgang und Speicher Optionen")
                LabeledRow(t, "Ausgang Modus") {
                    Text(if (ro) PM.channelModeName(p.raw) else "Asynchron  (fest)", color = c(t.textDim), fontSize = 14.sp)
                }
                FieldSlider(t, PM.RUN_COUNT, d.runCount, !ro) { d.runCount = it }
                FieldSlider(t, PM.LOG_INTERVAL, d.logInt, !ro) { d.logInt = it }
                CheckRow(t, "Log nach Fertigstellung fortführen", d.autoSave, !ro) { d.autoSave = it }
                CheckRow(t, "Daten auf SD speichern", d.saveSd, !ro) { d.saveSd = it }
                Label(t, "Alle weiteren Werte (Balancer, Sicherheit, Ni/Pb-Optionen) werden unverändert übernommen.")
                if (!ro) Label(t, "Wird im Lader dauerhaft gespeichert, nur wenn beide Kanäle auf Bereit stehen.")
            }
        }
    }

    when (sub) {
        "bal" -> BalancerPanel(t, d) { sub = null }
        "end" -> EndModePanel(t, d) { sub = null }
        "adv" -> AdvancedPanel(t, d) { sub = null }
        "safe" -> FieldsPanel(t, d, "Lade Sicherheitseinstellungen", PM.SAFETY_FIELDS) { sub = null }
    }
    if (a2aHint) Panel(t, "Von Akku zu Akku", 560.dp, buttons = { TextButton(onClick = { a2aHint = false }) { Text("OK") } }) {
        Text(PM.AKKU_ZU_AKKU_HINT, color = c(t.text), fontSize = 14.sp)
    }
    confirm?.let { r ->
        val ask = if (d.isNew) "„${r.name}“ als neues ${p.typeName}-Programm anlegen?\n\nVorlage: %02d %s".format(p.pos, p.name) +
                (if (r.changes.isEmpty()) "" else "\n\nAbweichend von der Vorlage:\n" + r.changes.joinToString("\n"))
            else r.changes.joinToString("\n")
        Panel(t, if (d.isNew) "Programm anlegen?" else "%02d %s speichern?".format(p.pos, p.name), 620.dp, buttons = {
            TextButton(onClick = { confirm = null }) { Text("Zurück") }
            Button(onClick = { confirm = null; m.wizard = null; m.saveProgram(d, r.mem, r.name) },
                colors = ButtonDefaults.buttonColors(containerColor = c(t.accent), contentColor = c(t.onFill))) {
                Text(if (d.isNew) "Anlegen" else "Speichern")
            }
        }) { Text(ask, color = c(t.text), fontSize = 14.sp) }
    }
}

/** Untermenue mit Uebernehmen/Abbrechen (nur Schliessen bei Standardprogrammen). */
@Composable
private fun SubPanel(t: AppTheme, d: ProgramDraft, title: String, onClose: () -> Unit, apply: () -> Unit, content: @Composable ColumnScope.() -> Unit) =
    Panel(t, title, 620.dp, buttons = {
        if (d.readOnly) TextButton(onClick = onClose) { Text("Schließen") }
        else {
            TextButton(onClick = onClose) { Text("Abbrechen") }
            Button(onClick = { apply(); onClose() }, colors = ButtonDefaults.buttonColors(containerColor = c(t.accent), contentColor = c(t.onFill))) {
                Text("Übernehmen")
            }
        }
    }, content = content)

@Composable
private fun BalancerPanel(t: AppTheme, d: ProgramDraft, onClose: () -> Unit) {
    val PM = ProgramMemory
    val cur = PM.get(d.work, PM.OFF_BAL_START, 1).toInt()
    var start by remember { mutableStateOf(if (cur in PM.BAL_START.indices) cur else PM.BAL_START.size) }
    val vals = remember { mutableStateListOf(*PM.BAL_FIELDS.map { it.raw(d.work) }.toTypedArray()) }
    val editable = !d.readOnly && PM.balancerEditable(d.chargeMode)
    SubPanel(t, d, "LiPo Balancer Einstellungen", onClose, apply = {
        d.editWork { w ->
            PM.put(w, PM.OFF_BAL_START, 1, (if (start in PM.BAL_START.indices) start else cur).toLong())
            if (editable) PM.BAL_FIELDS.forEachIndexed { i, f -> PM.put(w, f.off, f.size, vals[i]) }
        }
    }) {
        LabeledRow(t, "Bal. Start") { Choice(t, PM.balStartOptions(cur), start, !d.readOnly) { start = it } }
        PM.BAL_FIELDS.forEachIndexed { i, f -> FieldSlider(t, f, vals[i], editable) { vals[i] = it } }
        if (!PM.balancerEditable(d.chargeMode)) Label(t, "Bei „${PM.chargeModeName(d.chargeMode)}“ legt der Lader Differenz, Sollwert, " +
                "Überladung und Verzögerung selbst fest (z. B. Bal. Langsam: 3 mV / 3 mV / 0 mV / 2 min). Änderbar nur bei Ladeart „Modifiziert“.")
    }
}

@Composable
private fun EndModePanel(t: AppTheme, d: ProgramDraft, onClose: () -> Unit) {
    val PM = ProgramMemory
    val cur = d.endMode
    var sel by remember { mutableStateOf(if (cur in PM.END_MODES.indices) cur else PM.END_MODES.size) }
    SubPanel(t, d, "Ladeendstrom – Abschaltung", onClose, apply = {
        d.editWork { w -> PM.put(w, PM.OFF_LI_BAL_END, 1, (if (sel in PM.END_MODES.indices) sel else cur).toLong()) }
    }) {
        PM.endModeOptions(cur).forEachIndexed { i, o ->
            Row(Modifier.fillMaxWidth().clickable(enabled = !d.readOnly) { sel = i }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(sel == i, onClick = { sel = i }, enabled = !d.readOnly)
                Text(o, color = c(t.text), fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun AdvancedPanel(t: AppTheme, d: ProgramDraft, onClose: () -> Unit) {
    val PM = ProgramMemory
    var keep by remember { mutableStateOf(PM.get(d.work, PM.OFF_KEEP_CHARGE, 1) != 0L) }
    val vals = remember { mutableStateListOf(*PM.RESTORE_FIELDS.map { it.raw(d.work) }.toTypedArray()) }
    SubPanel(t, d, "LiPo Erweiterte Einstellung", onClose, apply = {
        d.editWork { w ->
            PM.put(w, PM.OFF_KEEP_CHARGE, 1, if (keep) 1 else 0)
            PM.RESTORE_FIELDS.forEachIndexed { i, f -> PM.put(w, f.off, f.size, vals[i]) }
        }
    }) {
        CheckRow(t, "Verzögertes Nachladen", keep, !d.readOnly) { keep = it }
        Heading(t, "Reaktivierung bei Unterspannung")
        PM.RESTORE_FIELDS.forEachIndexed { i, f -> FieldSlider(t, f, vals[i], !d.readOnly) { vals[i] = it } }
    }
}

@Composable
private fun FieldsPanel(t: AppTheme, d: ProgramDraft, title: String, fields: List<ProgramMemory.Field>, onClose: () -> Unit) {
    val PM = ProgramMemory
    val vals = remember { mutableStateListOf(*fields.map { it.raw(d.work) }.toTypedArray()) }
    SubPanel(t, d, title, onClose, apply = {
        d.editWork { w -> fields.forEachIndexed { i, f -> PM.put(w, f.off, f.size, vals[i]) } }
    }) {
        fields.forEachIndexed { i, f -> FieldSlider(t, f, vals[i], !d.readOnly) { vals[i] = it } }
    }
}
