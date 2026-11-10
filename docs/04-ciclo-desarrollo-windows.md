# 04 · Cómo programar en la laptop (Windows), generar la app y probar con los lentes

```
 LAPTOP WINDOWS                 GITHUB (Mac en la nube)            iPHONE + LENTES
 ┌────────────────────┐  push   ┌──────────────────────────┐  IPA   ┌───────────────────────┐
 │ VS Code: editas    │ ──────▶ │ Xcode 26.x compila la app │ ─────▶ │ Sideloadly (USB) firma │
 │ los .swift         │         │ con el SDK de Meta (SPM)  │        │ e instala GlassesLab   │
 └────────────────────┘         └──────────────────────────┘        │ Meta AI ⇄ lentes (BT)  │
          ▲                                                          └──────────┬────────────┘
          └──────────── .zip de evidencia / log de errores ◀────────────────────┘
```

Lo que **no** puedes hacer en Windows: compilar Swift para iOS, ejecutar el simulador o depurar paso a paso con Xcode. Por eso la compilación ocurre en la Mac de GitHub y la depuración se hace con el **registro de eventos** de la app y el **.zip de evidencia**.

## Preparación (una sola vez, ~30 min)

| Paso | Comando / acción |
|---|---|
| Editor | Instala **VS Code** y la extensión **Swift** (resaltado de sintaxis; no tendrá autocompletado del SDK de Meta porque el SDK de iOS no existe en Windows) |
| Git y GitHub CLI | `winget install Git.Git` y `winget install GitHub.cli`, luego `gh auth login` |
| Repositorio | En la carpeta `meta-dat-poc`: `git init`, `git add -A`, `git commit -m "inicio"`, `gh repo create glasseslab --public --source . --push` |
| Instalador | iTunes + iCloud (versiones de apple.com) y **Sideloadly** |
| iPhone | Modo de desarrollador activado; confiar en tu Apple ID (ver `02-instalar-sin-mac.md`) |
| Meta AI | Developer Mode activado; firmware de lentes ≥ V128 |

Un repo **público** da minutos de macOS gratis en GitHub Actions. Si lo haces privado, consumes cuota (los minutos de macOS cuentan ×10).

## El ciclo diario (cada cambio)

1. **Programas** en VS Code (por ejemplo, editas `GlassesLab/Sources/GlassesController.swift`).
2. **Compilas en la nube** con un solo comando desde PowerShell:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\compilar-en-la-nube.ps1 -Mensaje "mi cambio"
   ```
   El script sube el código, espera a que termine la compilación y descarga los `.ipa` en `.\salida\`. Si hay errores de compilación, te muestra las líneas `error:` y guarda el log.
3. **Instalas**: conectas el iPhone por USB y arrastras `salida\GlassesLab-unsigned.ipa` a Sideloadly → *Start*. (Reinstalar encima conserva los datos de la app.)
4. **Pruebas con los lentes**: abre GlassesLab, sigue los pasos 1→10 en pantalla. Cada prueba se marca PASÓ/FALLÓ sola.
5. **Traes la evidencia a la laptop**: botón *Preparar paquete de evidencia (.zip)* → *Compartir* → AirDrop no existe en Windows, así que usa correo, WhatsApp Web, OneDrive o iCloud Drive.
6. **Corriges** con esa información y vuelves al paso 1.

Tiempo típico por iteración: 15–25 min (casi todo es la compilación en la nube).

## Cómo depurar sin Xcode

| Necesitas ver… | Dónde |
|---|---|
| Qué hizo la app paso a paso | Sección *Registro de eventos* en la app (últimas 60 líneas) |
| Historial completo con fecha/hora | `evidence.jsonl` dentro del .zip |
| Errores de compilación | `salida\glasseslab-build.log` (el script lo descarga si falla) |
| Fotos, videos, audios, CSV de sensores | carpeta `captures/` del .zip |

Cuando algo falle, pásale a Claude el log de compilación o el .zip de evidencia: con eso se puede diagnosticar sin tener la Mac.

## Atajo para acelerar el ciclo (opcional, de pago)

Si vas a programar mucho, alquila una Mac en la nube (MacinCloud, AWS EC2 Mac) y conéctate por Escritorio Remoto desde la laptop: tendrás Xcode con autocompletado y errores al instante. Igual tendrás que instalar en el iPhone con un IPA (Sideloadly o TestFlight), porque el iPhone no se puede conectar por USB a una Mac remota.
