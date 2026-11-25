package de.dx8.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import de.dx8.monitor.AppTheme
import java.io.File
import java.util.prefs.Preferences
import javax.swing.SwingUtilities

fun main(args: Array<String>) {
    // Bild der Oberflaeche ohne Fenster erzeugen (Pruefung/Anleitung): --screenshot datei.png [--light]
    val shot = args.indexOf("--screenshot")
    if (shot >= 0) {
        val open = args.indexOf("--open").let { if (it >= 0) args.getOrNull(it + 1) else null }
        val zoom = args.indexOf("--zoom").let { if (it >= 0) args.getOrNull(it + 1)?.toFloatOrNull() else null } ?: 1.3f
        val w = args.indexOf("--size").let { if (it >= 0) args.getOrNull(it + 1) else null }?.split('x')?.mapNotNull { it.toIntOrNull() }
        screenshot(File(args[shot + 1]), if ("--light" in args) AppTheme.LIGHT else AppTheme.DARK, open, zoom,
            w?.getOrNull(0) ?: 1600, w?.getOrNull(1) ?: 1000); return
    }

    val prefs = Preferences.userRoot().node("de/dx8/chargedeck")
    val model = AppModel()
    // Fehlersuche: unerwartetes Beenden im Protokoll sichtbar machen
    Thread.setDefaultUncaughtExceptionHandler { th, e -> System.err.println("Unbehandelter Fehler in ${th.name}: $e"); e.printStackTrace() }
    Runtime.getRuntime().addShutdownHook(Thread { println("ChargeDeck beendet") })
    application {
        var theme by remember { mutableStateOf(AppTheme.byId(prefs.get("theme", "dark"))) }
        var zoom by remember { mutableStateOf(prefs.getFloat("zoom", 1.3f)) }
        val tray = rememberTrayState()
        // Ereignisse (fertig, Fehler, Rueckfrage, Schutz-Stopp) als Windows-Benachrichtigung
        LaunchedEffect(model.events.size) {
            while (model.events.isNotEmpty()) {
                val ev = model.events.removeAt(0)
                tray.sendNotification(Notification(ev.title, ev.text,
                    if (ev.kind == de.dx8.monitor.ChannelEvent.Kind.ERROR) Notification.Type.Error else Notification.Type.Info))
            }
        }
        Tray(icon = ColorPainter(Color(theme.accent)), state = tray, tooltip = "ChargeDeck")
        Window(
            onCloseRequest = { println("Fenster geschlossen (X)"); model.disconnect(); exitApplication() },
            title = "ChargeDeck – iCharger DX8",
            state = rememberWindowState(placement = androidx.compose.ui.window.WindowPlacement.Maximized, size = DpSize(1360.dp, 900.dp))
        ) {
            MenuBar {
                Menu("Ansicht") {
                    for (t in AppTheme.ALL) RadioButtonItem(t.title, selected = theme.id == t.id) { theme = t; prefs.put("theme", t.id) }
                    Separator()
                    for (z in ZOOMS) RadioButtonItem("Größe ${(z * 100).toInt()} %", selected = zoom == z) { zoom = z; prefs.putFloat("zoom", z) }
                }
                Menu("Lader") {
                    Item("Verbinden", enabled = !model.connected && !model.demo) { model.connect() }
                    Item("Trennen", enabled = model.connected) { model.disconnect() }
                    Item("Ladeprogramme …", enabled = model.connected || model.demo) { model.openPrograms() }
                    Item("Neues Ladeprogramm …", enabled = (model.connected || model.demo) && model.programs.isNotEmpty()) { model.newProgram() }
                    Item("Programme neu lesen", enabled = model.connected) { model.loadPrograms() }
                    Separator()
                    Item(if (model.demo) "Demo beenden" else "Demo starten") { if (model.demo) model.stopDemo() else model.startDemo() }
                }
            }
            ChargeDeckApp(model, theme, zoom)
        }
    }
}

/**
 * Demo-Ansicht als PNG rendern (ohne sichtbares Fenster).
 * open: programs | template | start | wizard:SCHRITT[:PROGRAMMPOS][:pro]  (Fenster, die zusaetzlich offen sein sollen)
 */
private fun screenshot(out: File, theme: AppTheme, open: String?, zoom: Float, width: Int, height: Int) {
    val model = AppModel()
    SwingUtilities.invokeAndWait {
        model.startDemo()
        val o = open?.split(':') ?: emptyList()
        when (o.firstOrNull()) {
            "programs" -> model.openPrograms()
            "template" -> model.newProgram()
            "start" -> model.startDialog = 1 to false
            "sources" -> model.showSources = true
            "cells" -> model.cellChart[0] = true
            "wizard" -> {
                val pos = o.getOrNull(2)?.toIntOrNull() ?: 0
                model.programs.getOrNull(pos)?.let { model.editProgram(it, false, o.getOrNull(1)?.toIntOrNull() ?: 0) }
                if ("pro" in o) model.wizard?.let { w -> for (i in w.proOpen.indices) w.proOpen[i] = true }
            }
        }
    }
    SwingUtilities.invokeAndWait { }            // ausstehende UI-Updates (Protokoll) abarbeiten
    val scene = ImageComposeScene(width, height, Density(1f)) { ChargeDeckApp(model, theme, zoom) }
    scene.render(0)
    val img = scene.render(500_000_000)
    out.writeBytes(img.encodeToData()!!.bytes)
    scene.close()
    println("Bild: ${out.absolutePath}")
}
