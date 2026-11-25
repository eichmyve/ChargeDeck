# Sicherung der Pi-Einstellungen auf den PC (Windows PowerShell).
# Aufruf:  powershell -ExecutionPolicy Bypass -File pi-sicherung.ps1 [-PiHost planedeck-pi.local] [-Ziel <Ordner>]
#
# Gesichert werden alle Einstellungen, nicht das ganze System:
#   DX8-Bruecke (Konfiguration mit Passwoertern, Programm, Dienst), WLAN-Netze, Netzwerk-Grundeinstellung,
#   Jeti-Dienst (PlaneDeck), Boot-Einstellungen, SSH-Schluessel, Hostname, SD-Schonung.
# ACHTUNG: Die Sicherung enthaelt Passwoerter (Bruecke, MQTT, WLAN) – nur auf dem eigenen PC aufbewahren,
# nicht ins Repository und nicht weitergeben. Es bleiben die letzten 10 Sicherungen.
param(
    [string]$PiHost = "planedeck-pi.local",
    [string]$User = "pi",
    [string]$Ziel = (Join-Path ([Environment]::GetFolderPath("MyDocuments")) "Pi-Sicherung")
)
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force $Ziel | Out-Null
$stamp = Get-Date -Format "yyyy-MM-dd_HHmm"
$name = "$($PiHost.Split('.')[0])-$stamp.tar.gz"
$remote = "/tmp/$name"          # /tmp liegt im RAM – die SD-Karte wird nicht beschrieben

$paths = @(
    "/etc/dx8bridge.json", "/opt/dx8bridge", "/etc/systemd/system/dx8bridge.service",
    "/etc/NetworkManager/system-connections", "/etc/netplan",
    "/opt/jeti-ble", "/etc/systemd/system/jeti-ble.service",
    "/boot/firmware/config.txt", "/boot/firmware/cmdline.txt",
    "/home/pi/.ssh/authorized_keys", "/etc/hostname",
    "/etc/rpi/swap.conf.d", "/etc/systemd/journald.conf.d"
) -join " "

Write-Host "Sichere $PiHost ..."
ssh -o BatchMode=yes "$User@$PiHost" "sudo tar czf $remote --ignore-failed-read $paths 2>/dev/null; sudo chown $User $remote"
if ($LASTEXITCODE -ne 0) { throw "Sicherung auf dem Pi fehlgeschlagen (Code $LASTEXITCODE)" }
scp -q -o BatchMode=yes "${User}@${PiHost}:$remote" (Join-Path $Ziel $name)
$code = $LASTEXITCODE
ssh -o BatchMode=yes "$User@$PiHost" "rm -f $remote" | Out-Null
if ($code -ne 0) { throw "Kopieren auf den PC fehlgeschlagen (Code $code)" }

# nur die letzten 10 behalten
Get-ChildItem $Ziel -Filter "$($PiHost.Split('.')[0])-*.tar.gz" | Sort-Object LastWriteTime -Descending |
    Select-Object -Skip 10 | Remove-Item -Force

$f = Get-Item (Join-Path $Ziel $name)
Write-Host ("Gesichert: {0} ({1:N0} KB)" -f $f.FullName, ($f.Length / 1KB))
