# 02 · Cómo instalar la app en tu iPhone sin tener Mac

## Lo que falta y por qué

| Componente | ¿Lo tienes? | ¿Es obligatorio? |
|---|---|---|
| iPhone con iOS ≥ 17.2 + Meta AI ≥ V292 | ✅ | Sí |
| Lentes con firmware ≥ V128 | ✅ (verifícalo) | Sí |
| **macOS + Xcode 26.4+ para compilar** | ❌ | **Sí, pero puede ser un Mac en la nube** (GitHub Actions o Codemagic). No necesitas comprar un Mac. |
| **Firma de código de Apple** | ❌ | **Sí.** iOS no ejecuta apps sin firmar. Gratis con Apple ID (7 días) o con Apple Developer Program (USD 99/año). |
| App de demostración oficial ya compilada | No existe | Meta no publica los samples en TestFlight/App Store. |

Conclusión: **no puedes probarlo solo con el iPhone y un navegador**. El mínimo es: una cuenta de GitHub (compila en un Mac de la nube) + tu PC con Windows (firma e instala) + tu Apple ID.

## Opciones evaluadas

| Opción | Costo | Validez de la instalación | Complejidad | Recomendación |
|---|---|---|---|---|
| **A. GitHub Actions (IPA sin firmar) + Sideloadly en Windows** | USD 0 | 7 días (reinstalas) | Baja | **Empieza aquí** |
| B. GitHub Actions o Codemagic + Apple Developer Program + TestFlight (tester interno) | USD 99/año | 90 días por build | Media | Para piloto con técnicos |
| C. Mac en la nube (MacinCloud, AWS EC2 Mac) con Xcode | USD 20–30/mes o más | Igual que A/B | Media | Útil si vas a depurar mucho; igual requiere A o B para instalar (el iPhone no se conecta por USB a un Mac remoto) |
| D. Ad-hoc (UDID registrado) + enlace OTA | USD 99/año | 1 año | Media | Alternativa a TestFlight |
| App Store | — | — | — | **Imposible hoy**: Meta indica que DAT no se puede publicar en App Store aún |

## Ruta A — paso a paso (gratis)

### A1. Compilar en la nube
1. Crea un repositorio en GitHub (puede ser privado) y sube el contenido de la carpeta `meta-dat-poc/` en la raíz del repo.
2. En GitHub → **Actions** → *Build unsigned IPAs* → **Run workflow**.
3. Al terminar (~10–20 min), descarga el artefacto **ipas-sin-firmar**. Contiene:
   - `CameraAccess-unsigned.ipa` — sample oficial de Meta, sin modificar.
   - `GlassesLab-unsigned.ipa` — la PoC con pruebas de aceptación y evidencia.
   - Si falla GlassesLab, descarga el artefacto `build-log` y pásamelo: el código no pudo compilarse aquí (no hay Mac en esta máquina).

Minutos de GitHub: los repos públicos son gratis; en privados los runners macOS consumen tu cuota con multiplicador ×10.

### A2. Firmar e instalar desde Windows (Sideloadly)
1. Instala **iTunes** y **iCloud** desde los instaladores de apple.com (no las versiones de Microsoft Store), luego **Sideloadly** (sideloadly.io).
2. Conecta el iPhone por USB y toca "Confiar en este equipo".
3. Arrastra el `.ipa` a Sideloadly, escribe tu Apple ID y pulsa *Start*. **La contraseña la ingresas tú** en Sideloadly; recomiendo usar un Apple ID secundario para esto.
4. En el iPhone: **Ajustes → Privacidad y seguridad → Modo de desarrollador → Activar** (reinicia).
5. **Ajustes → General → VPN y gestión de dispositivos →** tu Apple ID → **Confiar**.
6. Límites de la firma gratuita: la app caduca a los **7 días** (reinstalas con Sideloadly), máximo 3 apps activas.

Alternativa equivalente a Sideloadly: AltStore + AltServer para Windows.

### A3. Preparar Meta AI
1. Meta AI → **Ajustes → Información de la app → toca 5 veces el número de versión** → activa **Developer Mode** → *Enable*.
2. Verifica firmware ≥ V128 (si acabas de actualizar a V128, **reinicia los lentes**).
3. Carga los lentes a > 10 %.

Con Developer Mode, `MetaAppID` y `ClientToken` pueden quedar vacíos: el SDK omite la atestación. No necesitas registrar nada en Wearables Developer Center para la primera prueba.

### A4. Primera prueba (recomendado en este orden)
1. Instala **CameraAccess** (oficial) → *Connect* → aprueba en Meta AI → *Start Session* → *Preview* → captura una foto y graba un video. Si esto funciona, la cadena SDK + lentes + iPhone está validada sin código mío de por medio.
2. Instala **GlassesLab** y ejecuta las pruebas del documento `03-pruebas-aceptacion.md`.

## Ruta B — TestFlight (USD 99/año, sin Mac)

1. Inscríbete en **Apple Developer Program** (developer.apple.com/programs; se hace desde el navegador o la app Apple Developer del iPhone).
2. En **App Store Connect → Usuarios y acceso → Integraciones → App Store Connect API**: crea una key con rol **Admin**; anota *Key ID*, *Issuer ID* y descarga el `.p8`.
3. En **Certificates, IDs & Profiles** registra el Bundle ID (p. ej. `com.tuempresa.glasseslab`) y en **App Store Connect → Apps** crea la app con ese Bundle ID.
4. En GitHub → Settings → Secrets, carga `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8_BASE64` (el .p8 en base64), `APPLE_TEAM_ID`, `BUNDLE_ID`.
5. Ejecuta el workflow **TestFlight (internal)**. Firma en la nube y sube el build.
6. En App Store Connect → TestFlight agrega tu usuario como **tester interno** (sin Beta App Review). Instala desde la app **TestFlight** del iPhone.

Riesgos no verificados de la Ruta B: (1) el procesamiento automático de App Store Connect podría objetar `UISupportedExternalAccessoryProtocols` (Meta advierte rechazo en **App Review**; los testers internos no pasan por esa revisión); (2) los testers **externos** sí requieren Beta App Review y probablemente sean rechazados por el mismo motivo.

Para técnicos de otras personas, además de TestFlight necesitas los *release channels* de Wearables Developer Center: crear el proyecto, obtener `MetaAppID`/`ClientToken`, cargarlos en `project.yml` y poner tu `TeamID`, e invitar a cada técnico por el correo de su cuenta Meta.

## Permisos que la app pedirá

| Permiso | Dónde se concede | Para qué |
|---|---|---|
| Registro de la app | Meta AI (salta automáticamente) | Integración autorizada |
| Cámara de los lentes | Meta AI ("Permitir siempre / una vez") | Streaming, foto, video |
| Micrófono de los lentes (DAT) | Meta AI | Solo para A1 (PCM experimental) |
| Bluetooth / Red local | iOS (diálogo del sistema) | Transportes BLE, Bluetooth Classic, Wi-Fi |
| Micrófono de iOS | iOS (diálogo del sistema) | A2 (HFP); iOS lo exige también para audio Bluetooth |
