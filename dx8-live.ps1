<#
  Junsi iCharger DX8 - Live-Monitor ueber USB-HID (Modbus-HID, nur lesend)
  Aufruf:  .\dx8-live.ps1 [-Interval 1] [-Log dx8.csv] [-Eff 0.90] [-IdleW 3]
  Beenden: Strg+C
  Hinweis: Der DX8 meldet keinen Eingangsstrom. Er wird geschaetzt aus
           (Summe Ladeleistung / Wirkungsgrad + Grundverbrauch) / Eingangsspannung.
           Entladeleistung wird im Geraet verheizt und nicht aus dem Eingang bezogen.
#>
param([double]$Interval = 1, [string]$Log = '', [double]$Eff = 0.90, [double]$IdleW = 3)

Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
public static class Dx8Live {
  [DllImport("kernel32.dll", SetLastError=true, CharSet=CharSet.Unicode)]
  static extern SafeFileHandle CreateFile(string n, uint a, uint s, IntPtr sa, uint c, uint f, IntPtr t);
  [DllImport("hid.dll")] static extern bool HidD_FlushQueue(SafeFileHandle h);
  static FileStream fs; static SafeFileHandle hnd;
  // Jeder Prozess, der das Geraet offen hat, sieht ALLE HID-Antworten. Antworten tragen keine Adresse.
  // Deshalb: Abfragen prozessuebergreifend per Mutex serialisieren und vorher den eigenen Puffer leeren.
  static System.Threading.Mutex mtx = new System.Threading.Mutex(false, "Local\\JunsiDX8Hid");
  public static void Open(string path) {
    var h = CreateFile(path, 0xC0000000, 3, IntPtr.Zero, 3, 0, IntPtr.Zero);
    if (h.IsInvalid) throw new Exception("Oeffnen fehlgeschlagen (" + Marshal.GetLastWin32Error() + ")");
    hnd = h; fs = new FileStream(h, FileAccess.ReadWrite, 65);
  }
  public static void Close() { try { if (fs != null) fs.Close(); } catch {} fs = null; }
  public static bool IsOpen { get { return fs != null; } }
  public static int[] Read(int addr, int qty) {
    try { if (!mtx.WaitOne(5000)) throw new Exception("Geraet belegt"); }
    catch (System.Threading.AbandonedMutexException) { }
    try { HidD_FlushQueue(hnd); return ReadLocked(addr, qty); }
    finally { mtx.ReleaseMutex(); }
  }
  // Modbus 0x04 "Read Input Registers"; Antwort als big-endian U16
  static int[] ReadLocked(int addr, int qty) {
    var o = new byte[65];
    o[1] = 7; o[2] = 0x30; o[3] = 4; o[4] = (byte)(addr >> 8); o[5] = (byte)addr; o[6] = 0; o[7] = (byte)qty;
    fs.Write(o, 0, 65);
    var i = new byte[65];
    for (int tries = 0; ; tries++) {
      var t = fs.ReadAsync(i, 0, 65);
      if (!t.Wait(2000)) throw new Exception("Timeout");
      if (i[2] == 0x30 && i[3] == 4 && i[4] == qty * 2) break;          // passende Antwort
      if (i[2] == 0x30 && i[3] == 0x84) throw new Exception("Modbus-Fehler " + i[4]);
      if (tries > 50) throw new Exception("Keine passende Antwort");     // sonst: Push-Frames (Typ 0x10) ueberspringen
    }
    int n = i[4]; var r = new int[n / 2];
    for (int k = 0; k < n / 2; k++) r[k] = (i[5 + 2*k] << 8) | i[6 + 2*k];
    return r;
  }
}
'@

function S16($v) { if ($v -ge 0x8000) { $v - 0x10000 } else { $v } }
function U32($lo, $hi) { ([int64]$hi -shl 16) + $lo }                      # 32 Bit: niederwertiges Wort zuerst
function S32($lo, $hi) { $v = U32 $lo $hi; if ($v -ge 2147483648) { $v - 4294967296 } else { $v } }

$RunText = @{ 0 = 'Bereit'; 1 = 'Halten'; 2 = 'Start'; 5 = 'Startpruefung'; 6 = 'Vorbereitung'; 7 = 'Laden'; 10 = 'Nachladen'; 11 = 'Entladen-Start'; 12 = 'Entladen-Vorbereitung / Rueckfrage am Display'; 13 = 'Entladen'; 41 = 'FEHLER' }   # aus Beobachtung / Display abgeleitet (2, 5, 6 = vermutet)
# Fehlercode: High-Byte und Low-Byte dezimal hintereinander, wie am Display (0x0D05 -> "1305")
$ErrText = @{
  1302 = 'Verbindungsfehler (beim regenerativen Entladen gesehen)'
  1305 = 'Verbindungsfehler (kein Akku am Ausgang)'
  3100 = 'Zellenzahl-Autoerkennung fehlgeschlagen - bitte richtige Zellenzahl einstellen'
}
function Format-Err($e) {
  $code = [int]("{0}{1:D2}" -f ($e -shr 8), ($e -band 0xFF))
  $t = if ($ErrText.ContainsKey($code)) { $ErrText[$code] } else { 'unbekannt' }
  "Fehler {0} - {1}" -f $code, $t
}

function Find-Dx8Path {
  $d = Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue |
       Where-Object { $_.InstanceId -like 'HID\VID_0483&PID_5751&MI_00*' } | Select-Object -First 1
  if (-not $d) { return $null }
  '\\?\' + ($d.InstanceId -replace '\\', '#') + '#{4d1e55b2-f16f-11cf-88cb-001111000030}'
}

function Read-Channel([int]$ch) {
  $b = $ch * 0x100
  $g = [Dx8Live]::Read($b, 30) + [Dx8Live]::Read($b + 30, 30)
  $cells = @(); $bal = @(); $ir = @()
  # Balance-Status: U8 pro Zelle, gepackt in Register 27..30
  $balBytes = @(); foreach ($r in $g[27..30]) { $balBytes += ($r -shr 8); $balBytes += ($r -band 0xFF) }
  for ($k = 0; $k -lt 8; $k++) {
    $v = $g[11 + $k]
    if ($v -gt 0 -and $v -lt 5000) { $cells += $v; $bal += $balBytes[$k]; $ir += $g[35 + $k] }
  }
  [pscustomobject]@{
    Kanal = $ch; Ts = U32 $g[0] $g[1]; P = (S32 $g[2] $g[3]) / 100; I = (S16 $g[4]) / 100
    Uin = $g[5] / 1000; Uout = $g[6] / 1000; Cap = S32 $g[7] $g[8]
    Tint = (S16 $g[9]) / 10; Text = (S16 $g[10]) / 10
    Cells = $cells; Bal = $bal; IR = $ir; IRtot = $g[51]; IRline = $g[52]
    Ctrl = $g[54]; Run = $g[55]; Err = $g[56]; Dlg = $g[57]
  }
}

$E = [char]27
function C($code, $s) { "$E[${code}m$s$E[0m" }
function Bar($mv) {
  $min = 3000; $max = 4200; $w = 20
  $f = [math]::Max(0, [math]::Min($w, [int](($mv - $min) / ($max - $min) * $w)))
  ('#' * $f) + ('.' * ($w - $f))
}

function Format-Channel($c) {
  $state = if ($RunText.ContainsKey($c.Run)) { $RunText[$c.Run] } else { "Code $($c.Run)" }
  # Leerlaufspannung > 36 V (ueber 8S LiHV) ohne Zellwerte = kein Akku am Ausgang
  $noBatt = ($c.Cells.Count -eq 0) -and ($c.Uout -gt 36) -and ($c.I -eq 0)
  if ($noBatt -and ($c.Run -eq 0 -or $c.Run -eq 1)) { $state = 'Akku n. verbunden' }
  $col = switch ($c.Run) { 7 { '32' } 10 { '32' } 13 { '33' } 0 { '90' } 1 { '90' } 41 { '1;31' } default { '36' } }
  $t = [TimeSpan]::FromMilliseconds($c.Ts)
  $L = @()
  $L += (C '1' ("  KANAL {0}   " -f $c.Kanal)) + (C $col $state) + ("   Laufzeit {0:hh\:mm\:ss}" -f $t)
  # Fehlercode bleibt nach dem Quittieren im Register stehen; der Lader zeigt ihn dann nicht mehr -> nur bei Run 41 anzeigen
  if ($c.Err -and $c.Run -eq 41) { $L += C '1;31' ("  " + (Format-Err $c.Err) + ("   (roh 0x{0:X4})" -f $c.Err)) }
  $L += "  Spannung {0,7:N3} V   Strom {1,7:N2} A   Leistung {2,7:N2} W" -f $c.Uout, $c.I, $c.P
  $L += "  Kapazitaet {0,6} mAh   Temp intern {1,5:N1} C   extern {2,5:N1} C" -f $c.Cap, $c.Tint, $c.Text
  if ($c.Cells.Count) {
    $diff = ($c.Cells | Measure-Object -Maximum -Minimum)
    $L += "  {0}S   Delta {1} mV   IR gesamt {2:N1} mOhm   Leitung {3:N1} mOhm" -f $c.Cells.Count, ($diff.Maximum - $diff.Minimum), ($c.IRtot / 10), ($c.IRline / 10)
    for ($k = 0; $k -lt $c.Cells.Count; $k++) {
      $b = if ($c.Bal[$k]) { C '35' (" bal {0,3}" -f $c.Bal[$k]) } else { '        ' }
      $L += "   Zelle {0}  {1:N3} V  [{2}]  IR {3,5:N1} mOhm{4}" -f ($k + 1), ($c.Cells[$k] / 1000), (Bar $c.Cells[$k]), ($c.IR[$k] / 10), $b
    }
  } else { $L += C '90' '  kein Akku / keine Balancer-Daten' }
  $L += C '90' ("  Ctrl 0x{0:X4}   Run {1}   Dialog 0x{2:X4}" -f $c.Ctrl, $c.Run, $c.Dlg)
  $L
}

$LogHeader = 'Zeit;Kanal;Laufzeit_ms;U_V;I_A;P_W;Cap_mAh;Tint_C;Run;Ctrl;Err;Zellen_mV;Uin_V;Iin_geschaetzt_A'
if ($Log -and (Test-Path $Log) -and ((Get-Content $Log -TotalCount 1) -replace '^﻿', '') -ne $LogHeader) {
  # altes Log mit anderem Spaltenformat beiseitelegen
  Move-Item $Log ($Log -replace '\.csv$', ('-alt-{0:yyyyMMdd-HHmmss}.csv' -f (Get-Date)))
}
if ($Log -and -not (Test-Path $Log)) { $LogHeader | Out-File $Log -Encoding utf8 }

[Console]::CursorVisible = $false
[Console]::Write("$E[2J$E[H")
try {
  while ($true) {
    $out = @()
    $out += C '1;36' ("  iCharger DX8 Live-Monitor   {0:HH:mm:ss}   (Strg+C beendet)" -f (Get-Date))
    try {
      if (-not [Dx8Live]::IsOpen) {
        $p = Find-Dx8Path
        if (-not $p) { throw 'DX8 nicht gefunden - USB pruefen' }
        [Dx8Live]::Open($p)
      }
      $chs = 1, 2 | ForEach-Object { Read-Channel $_ }
      $uin = $chs[0].Uin
      $pCharge = ($chs | Where-Object { $_.P -gt 0 } | Measure-Object -Property P -Sum).Sum
      if (-not $pCharge) { $pCharge = 0 }
      $pin = $pCharge / $Eff + $IdleW
      $iin = if ($uin -gt 1) { $pin / $uin } else { 0 }
      $tmax = ($chs | Measure-Object -Property Tint -Maximum).Maximum
      $tcol = if ($tmax -ge 60) { '31' } elseif ($tmax -ge 45) { '33' } else { '32' }
      $out += ''
      $out += (C '1' '  EINGANG / GERAET')
      $out += "  Eingangsspannung {0,7:N2} V" -f $uin
      $out += "  Eingangsstrom    {0,7:N2} A   " -f $iin + (C '90' ("(geschaetzt: Leistung ~{0:N1} W, Wirkungsgrad {1:P0}, Grundlast {2} W)" -f $pin, $Eff, $IdleW))
      $out += "  Temperatur Geraet " + (C $tcol ("{0,6:N1} C" -f $tmax)) + (C '90' ("   (K1 {0:N1} C / K2 {1:N1} C)" -f $chs[0].Tint, $chs[1].Tint))
      $script:LastIn = @($uin, [math]::Round($iin, 2), $tmax)
      foreach ($c in $chs) { $out += ''; $out += Format-Channel $c }
      if ($Log) {
        $now = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
        foreach ($c in $chs) {
          ($now, $c.Kanal, $c.Ts, $c.Uout, $c.I, $c.P, $c.Cap, $c.Tint, $c.Run, $c.Ctrl, $c.Err, ($c.Cells -join '/'), $script:LastIn[0], $script:LastIn[1]) -join ';' |
            Out-File $Log -Append -Encoding utf8
        }
        $out += ''; $out += C '90' "  Log: $Log"
      }
    } catch {
      [Dx8Live]::Close()
      $out += ''; $out += C '31' "  $($_.Exception.Message) - neuer Versuch ..."
    }
    # Cursor nach oben links, jede Zeile bis Zeilenende loeschen, Rest des Bildschirms loeschen
    [Console]::Write("$E[H" + (($out | ForEach-Object { "$_$E[K" }) -join "`n") + "$E[J")
    Start-Sleep -Milliseconds ($Interval * 1000)
  }
} finally {
  [Dx8Live]::Close()
  [Console]::CursorVisible = $true
}
