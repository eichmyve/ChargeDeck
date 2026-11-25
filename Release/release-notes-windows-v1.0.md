ChargeDeck für Windows – Version 1.0 (Vorabversion)

## Datei
- `ChargeDeck-1.0.0.msi` – Installer für Windows 10/11 (64 Bit). Java ist enthalten, nichts weiter nötig.

## Funktionen
- Verbindung über USB (Lader direkt am PC) oder über die WLAN-Brücke (Raspberry Pi)
- Live-Anzeige beider Kanäle: Status mit Symbol, Spannung, Strom, Kapazität, Leistung, Laufzeit, Zellen mit Innenwiderstand
- Verlauf umschaltbar: Spannung/Strom oder einzelne Zellen
- Start mit Programmkacheln und Betriebsart-Knöpfen, Halten, Weiter, Stopp, Rückfragen Ja/Nein
- Ladeprogramme: Liste mit Filter, Programm-Assistent zum Ändern und Neuanlegen aus einer Vorlage
- Eingangsquelle direkt im Hauptfenster wählbar
- Akku-zu-Akku-Sperre, Warnung „Anzeige stimmt nicht“, Windows-Benachrichtigung bei Fertig, Fehler und Schutz-Stopp
- Info mit Firmware-Prüfung, helles und dunkles Design, Größe 100–150 %, Demo ohne Lader

## Installation
1. `ChargeDeck-1.0.0.msi` herunterladen und öffnen.
2. Windows zeigt eventuell „Der Computer wurde durch Windows geschützt“ – der Installer ist nicht signiert.
   „Weitere Informationen“ → „Trotzdem ausführen“.
3. Nach der Installation steht ChargeDeck im Startmenü.

## Hinweise
- Vorabversion: Anzeige und Verbindung sind mit dem DX8 geprüft. Programm speichern und Eingangsquelle umstellen
  sind bisher nur im Demo-Modus getestet.
- Die Junsi-Software und andere Programme, die auf den Lader zugreifen, vorher schließen.
- Noch nicht enthalten: Bearbeiten der Eingangsquellen, Systemeinstellungen, Schutz der Eingangsbatterie, CSV-Export.
