# Ciclo de desarrollo desde Windows:
#   1) sube tus cambios a GitHub
#   2) espera la compilación en la Mac de la nube (GitHub Actions)
#   3) descarga los IPA a .\salida\ para instalarlos con Sideloadly
#
# Requisitos (una sola vez):
#   winget install Git.Git
#   winget install GitHub.cli
#   gh auth login            (inicias sesión tú en el navegador)
#   y el repo ya creado y enlazado:  git remote -v  debe mostrar tu repo de GitHub
#
# Uso:  powershell -ExecutionPolicy Bypass -File .\scripts\compilar-en-la-nube.ps1 -Mensaje "agrego X"

param(
  [string]$Mensaje = "cambios de prueba"
)

$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

Write-Host "== 1/3 Subiendo cambios ==" -ForegroundColor Cyan
git add -A
git diff --cached --quiet
if ($LASTEXITCODE -ne 0) {
  git commit -m $Mensaje
}
git push

Write-Host "== 2/3 Esperando la compilación en GitHub Actions (10-20 min) ==" -ForegroundColor Cyan
$commit = (git rev-parse HEAD).Trim()
$runId = $null
for ($i = 0; $i -lt 20 -and -not $runId; $i++) {
  Start-Sleep -Seconds 6
  $runId = gh run list --workflow "build-unsigned-ipa.yml" --commit $commit --limit 1 --json databaseId --jq ".[0].databaseId"
}
if (-not $runId) { throw "No encontré la ejecución del workflow. ¿Existe .github/workflows/build-unsigned-ipa.yml en el repo?" }
gh run watch $runId --exit-status
$ok = $LASTEXITCODE -eq 0

Write-Host "== 3/3 Descargando resultados ==" -ForegroundColor Cyan
$salida = Join-Path (Get-Location) "salida"
if (Test-Path $salida) { Remove-Item -Recurse -Force $salida }
New-Item -ItemType Directory -Force $salida | Out-Null

if ($ok) {
  gh run download $runId --name "ipas-sin-firmar" --dir $salida
  Write-Host "Listo. Arrastra el .ipa de $salida a Sideloadly:" -ForegroundColor Green
  Get-ChildItem $salida -Filter *.ipa | ForEach-Object { Write-Host "  $($_.FullName)" }
} else {
  gh run download $runId --name "build-log" --dir $salida
  Write-Host "La compilación FALLÓ. Errores:" -ForegroundColor Red
  Select-String -Path (Join-Path $salida "glasseslab-build.log") -Pattern "error:" | Select-Object -First 30 | ForEach-Object { $_.Line }
  Write-Host "Log completo en $salida\glasseslab-build.log (pásaselo a Claude para corregir)." -ForegroundColor Yellow
  exit 1
}
