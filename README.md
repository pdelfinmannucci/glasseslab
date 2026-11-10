# PoC · Meta Glasses Adventurer + Meta Wearables DAT (iOS)

Prueba de concepto para capturar foto, video, audio y streaming desde lentes Meta con el SDK oficial
**Meta Wearables Device Access Toolkit 1.0.1** y enviar las capturas a una API de IA (Claude).

**Estado: código escrito y sin compilar todavía (no hay Mac en este equipo). Ninguna prueba ejecutada sobre los lentes reales.**

## Contenido
| Ruta | Qué es |
|---|---|
| `docs/01-verificacion-sdk.md` | Verificación contra el repo y las docs oficiales: modelo, versiones, APIs, audio PCM |
| `docs/02-instalar-sin-mac.md` | Cómo compilar en la nube e instalar en el iPhone desde Windows (gratis) o vía TestFlight |
| `docs/03-pruebas-aceptacion.md` | Casos de prueba, criterios, evidencia y registro de resultados |
| `docs/04-ciclo-desarrollo-windows.md` | Cómo programar en la laptop, compilar en la nube, instalar y depurar |
| `scripts/compilar-en-la-nube.ps1` | Un comando: sube cambios, espera la compilación y descarga los IPA |
| `docs/05-android.md` | Versión Android: requisitos, instalación del APK y desarrollo con Android Studio |
| `android/` | GlassesLab para Android (Kotlin + Jetpack Compose), SDK `com.meta.wearable:mwdat-*` 1.0.1 |
| `.github/workflows/build-android.yml` | Compila en Linux el APK de GlassesLab y el sample oficial CameraAccess de Android |
| `GlassesLab/` | App SwiftUI (XcodeGen). Dependencias: `MWDATCore`, `MWDATCamera` 1.0.1 |
| `.github/workflows/build-unsigned-ipa.yml` | Compila en macOS de GitHub el sample oficial `CameraAccess` y `GlassesLab` como IPA sin firmar |
| `.github/workflows/testflight.yml` | Firma en la nube y sube a TestFlight (requiere Apple Developer Program) |

## Archivos de la app
| Archivo | Responsabilidad |
|---|---|
| `GlassesLabApp.swift` | `Wearables.configure()` y callbacks de Meta AI (`handleUrl`) |
| `GlassesController.swift` | Registro, detección de dispositivo, sesión, permisos, stream, foto, foto HQ, video, PCM, HFP, IA, evidencia |
| `MediaWriters.swift` | MP4 (`AVAssetWriter`), WAV del PCM de DAT, grabador del micrófono HFP |
| `Evidence.swift` | Catálogo de pruebas y persistencia `results.json` / `evidence.jsonl` / zip |
| `ClaudeClient.swift` | Messages API (`claude-opus-5-5`) con imagen base64 y Keychain para la API key |
| `ContentView.swift` | UI de pasos y panel de resultados |
| `ExtraCapabilities.swift` | Experimentales: Inputs (botón/táctil), Motion (IMU), Speech (voz a texto), Voice Invocations ("Hey Meta") |
| `ExtrasSection.swift` | UI de las capacidades experimentales |

## Orden recomendado
1. Subir esta carpeta a un repo de GitHub y ejecutar *Build unsigned IPAs*.
2. Instalar primero `CameraAccess` (sample oficial) con Sideloadly → prueba de control C0.
3. Instalar `GlassesLab` y ejecutar R0 → D0 → S1 → F1 → V1 → F2 → A2 → A1 → IA1.
4. Compartir el `.zip` de evidencia.
