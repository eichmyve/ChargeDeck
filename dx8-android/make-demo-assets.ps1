# Erzeugt die Demo-Abspieldaten (app/src/main/assets) aus den echten Messungen vom 21.11.2026
$base = Split-Path $PSScriptRoot
$scratch = 'C:\Users\eichm\AppData\Local\Temp\claude\c9e0b60e0bab83fc\50268e3d-d2b8-405c-b47d-7e456d7ea713\scratchpad'
$assets = Join-Path $PSScriptRoot 'app\src\main\assets'
New-Item -ItemType Directory -Force (Join-Path $assets 'programs') | Out-Null
$hdr = 'ts_ms;run;err;uin_mV;uout_mV;i_A;p_W;cap_mAh;tint_C;cells_mV;ir_01mOhm'

# Kanal 1: regeneratives Entladen Pos 07 (10 A)
$r1 = Import-Csv (Join-Path $base 'dx8-regen-20261121-191547.csv') -Delimiter ';'
$l1 = foreach ($r in $r1) { $r.Ts, $r.Run, $r.Err, $r.Uin, $r.U, $r.I, $r.P, $r.Cap, $r.Tint, $r.Zellen, '106/118/127' -join ';' }
@($hdr) + $l1 | Set-Content (Join-Path $assets 'demo_k1.csv') -Encoding utf8

# Kanal 2: Laden Pos 05 (2 A)
$r2 = Import-Csv (Join-Path $base 'dx8-test-20261121-172003.csv') -Delimiter ';'
$l2 = foreach ($r in $r2) { $r.Ts, $r.Run, $r.Err, $r.Uin_mV, $r.U_mV, $r.I_A, $r.P_W, $r.Cap, $r.Tint, $r.Zellen, $(if ($r.IR) { $r.IR } else { '106/118/127' }) -join ';' }
@($hdr) + $l2 | Set-Content (Join-Path $assets 'demo_k2.csv') -Encoding utf8

# Programme (Rohspeicher aus dem Lader)
Copy-Item (Join-Path $scratch 'mem_order.txt') (Join-Path $assets 'programs\order.txt') -Force
Get-ChildItem $scratch -Filter 'mem_??.bin' | Copy-Item -Destination (Join-Path $assets 'programs') -Force
"K1: $($l1.Count) Zeilen, K2: $($l2.Count) Zeilen, Programme: " + (Get-ChildItem (Join-Path $assets 'programs') -Filter *.bin).Count
