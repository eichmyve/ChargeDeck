# ChargeDeck Promo-Video: Standbilder je Szene -> Ken-Burns-Clip -> Ueberblendungen
$ErrorActionPreference = "Stop"
$ff = "C:\Users\eichm\AppData\Local\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-9.1-full_build\bin\ffmpeg.exe"
$dir = $PSScriptRoot
$shots = "C:\Users\eichm\Documents\ChargeDeck\screenshots"
Set-Location $dir
Copy-Item C:\Windows\Fonts\segoeuib.ttf, C:\Windows\Fonts\segoeui.ttf, C:\Windows\Fonts\segoeuisl.ttf $dir -Force
$utf8 = New-Object Text.UTF8Encoding $false
function T($name, $text) { [IO.File]::WriteAllText("$dir\$name", $text, $utf8) }
function Run($fargs) { & $ff -hide_banner -loglevel error -y @fargs; if ($LASTEXITCODE -ne 0) { throw "ffmpeg fehlgeschlagen: $fargs" } }

$BG = "0x0e1319"
# Ueberschrift + Unterzeile oben (fuer Bildschirm-Szenen)
function Head($n) {
    "drawtext=expansion=none:fontfile=segoeuib.ttf:textfile=$n-h.txt:fontsize=60:fontcolor=white:x=(w-text_w)/2:y=44," +
    "drawtext=expansion=none:fontfile=segoeui.ttf:textfile=$n-s.txt:fontsize=31:fontcolor=0x9fb0c2:x=(w-text_w)/2:y=124"
}

# Szene mit einem Windows-Bild (1920x1080) verkleinert unter der Ueberschrift
function WinScene($n, $img, $h, $s) {
    T "$n-h.txt" $h; T "$n-s.txt" $s
    Run @("-f","lavfi","-i","color=c=${BG}:s=1920x1080:d=1","-i",$img,"-filter_complex",
        "[1:v]scale=1520:-1,pad=iw+4:ih+4:2:2:color=0x34414f[sc];[0:v][sc]overlay=(W-w)/2:200,$(Head $n)",
        "-frames:v","1","$n.png")
}

# Szene mit zwei Tablet-Bildern nebeneinander (Statusleiste/Titel und Navigationsleiste abgeschnitten)
function TabScene($n, $a, $b, $h, $s) {
    T "$n-h.txt" $h; T "$n-s.txt" $s
    $prep = "crop=1200:1600:0:140,scale=-1:820,pad=iw+4:ih+4:2:2:color=0x34414f"
    Run @("-f","lavfi","-i","color=c=${BG}:s=1920x1080:d=1","-i",$a,"-i",$b,"-filter_complex",
        "[1:v]$prep[a];[2:v]$prep[b];[0:v][a]overlay=W/2-w-30:205[t];[t][b]overlay=W/2+30:205,$(Head $n)",
        "-frames:v","1","$n.png")
}

# Text-Szenen (Titel, Sicherheit, Abschluss)
function TitleScene($n, $title, $sub, $iconY) {
    T "$n-h.txt" $title; T "$n-s.txt" $sub
    $f = "[1:v]scale=240:240[ic];[0:v][ic]overlay=(W-w)/2:$iconY," +
        "drawtext=expansion=none:fontfile=segoeuib.ttf:textfile=$n-h.txt:fontsize=120:fontcolor=white:x=(w-text_w)/2:y=$($iconY+280)," +
        "drawtext=expansion=none:fontfile=segoeui.ttf:textfile=$n-s.txt:fontsize=40:fontcolor=0x9fb0c2:x=(w-text_w)/2:y=$($iconY+440)"
    Run @("-f","lavfi","-i","color=c=${BG}:s=1920x1080:d=1","-i","icon.png","-filter_complex",$f,"-frames:v","1","$n.png")
}

function ListScene($n, $h, $lines) {
    T "$n-h.txt" $h
    $f = "drawtext=expansion=none:fontfile=segoeuib.ttf:textfile=$n-h.txt:fontsize=72:fontcolor=white:x=(w-text_w)/2:y=250"
    for ($i = 0; $i -lt $lines.Count; $i++) {
        T "$n-$i.txt" $lines[$i]
        $y = 440 + $i * 110
        $f += ",drawbox=x=520:y=$($y+8):w=26:h=26:color=0x4fc3f7:t=fill"
        $f += ",drawtext=expansion=none:fontfile=segoeui.ttf:textfile=$n-$i.txt:fontsize=44:fontcolor=0xdde6ee:x=580:y=$y"
    }
    Run @("-f","lavfi","-i","color=c=${BG}:s=1920x1080:d=1","-vf",$f,"-frames:v","1","$n.png")
}

# ------------------------------------------------------------------ Szenen
TitleScene "s01" "ChargeDeck" "Den iCharger DX8 einfach bedienen – auf Android und Windows" 170
WinScene "s02" "w-main.png" "Alles auf einen Blick" "Beide Kanäle live: Spannung, Strom, Kapazität – jede Zelle mit Innenwiderstand"
WinScene "s03" "w-cells.png" "Ein Klick – jede Zelle im Verlauf" "Umschalten zwischen Spannung/Strom und Zellansicht"
WinScene "s04" "w-start.png" "Starten in zwei Klicks" "Programm-Kachel wählen, Betriebsart antippen – fertig"
WinScene "s05" "w-wizard.png" "Ladeprogramme ohne Menü-Labyrinth" "Der Assistent führt in sechs klaren Schritten durch jedes Programm"
WinScene "s06" "w-sources.png" "Eingangsquelle mit einem Klick" "Alle vier Quellen im Überblick – Umstellen mit Rückfrage und Kontrolle"
TabScene "s07" "$shots\anleitung\01b-laufend.png" "$shots\anleitung\07-programme.png" "Genauso einfach auf dem Tablet" "Gleiche Bedienung, gleiche Programme, gleiche Sicherheit"
TabScene "s08" "$shots\anleitung\11-assistent-laden.png" "$shots\anleitung\04-verlauf.png" "Assistent und Verlauf – auch in der Werkstatt" "Große Regler, klare Schritte, Zellkurven auf einen Tipp"
WinScene "s09" "w-light.png" "Hell oder dunkel – immer gut lesbar" "Helles und dunkles Design, Größe von 100 bis 150 %"
ListScene "s10" "Sicher bedient" @("Ändern nur, wenn kein Kanal arbeitet", "Schutz bei Akku-zu-Akku gegen Fehlstarts", "Jede Änderung wird zurückgelesen und geprüft")
TitleScene "s11" "ChargeDeck" "Für Android und Windows · iCharger DX8" 200

# ------------------------------------------------------------------ Clips (ruhiges Standbild, 30 fps)
$dur = [ordered]@{ s01=4.5; s02=6; s03=5; s04=6; s05=6; s06=5; s07=6; s08=6; s09=5; s10=5.5; s11=5 }
foreach ($n in $dur.Keys) {
    $fr = [int]($dur[$n] * 30)
    Run @("-loop","1","-i","$n.png","-vf",
        "fps=30,format=yuv420p",
        "-frames:v",$fr,"-c:v","libx264","-preset","medium","-crf","18","$n.mp4")
}

# ------------------------------------------------------------------ Ueberblendungen
$x = 0.7
$inputs = @(); foreach ($n in $dur.Keys) { $inputs += @("-i","$n.mp4") }
$names = @($dur.Keys)
$fc = ""; $prev = "[0:v]"; $off = 0.0
for ($i = 1; $i -lt $names.Count; $i++) {
    $off += $dur[$names[$i-1]] - $x
    $out = if ($i -eq $names.Count - 1) { "[xf]" } else { "[v$i]" }
    $fc += "$prev[${i}:v]xfade=transition=fade:duration=${x}:offset=$([string]::Format([Globalization.CultureInfo]::InvariantCulture,'{0:0.###}',$off))$out;"
    $prev = $out
}
$total = $off + $dur[$names[-1]]
$fc += "[xf]fade=t=in:st=0:d=0.8,fade=t=out:st=$([string]::Format([Globalization.CultureInfo]::InvariantCulture,'{0:0.###}',$total-1)):d=1,format=yuv420p[out]"
Run ($inputs + @("-filter_complex",$fc,"-map","[out]","-c:v","libx264","-preset","slow","-crf","18","-movflags","+faststart","ChargeDeck-Promo.mp4"))
"Fertig: $dir\ChargeDeck-Promo.mp4 ($total s)"
