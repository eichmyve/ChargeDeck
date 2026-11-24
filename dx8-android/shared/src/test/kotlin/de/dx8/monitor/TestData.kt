package de.dx8.monitor

import java.io.File

/** Echte Daten vom DX8 (Programme, Systemwerte) – liegen bei den Assets der Android-App. */
object TestData {
    val ASSETS: File = File(System.getProperty("dx8.assets") ?: "../app/src/main/assets")
}
