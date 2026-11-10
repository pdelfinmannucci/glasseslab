# 05 · Versión Android: programar en la laptop y probar con los lentes

## Por qué Android es más simple que iPhone en tu caso
| | iPhone | Android |
|---|---|---|
| Compilar | Mac en la nube (GitHub, macOS) | En la laptop con Android Studio **o** en la nube (Linux, gratis) |
| Instalar | Sideloadly + Apple ID, caduca a los 7 días | Copiar el `.apk` al teléfono o botón *Run* de Android Studio; no caduca |
| Cuenta | Apple ID / USD 99 año | Ninguna. El SDK está en Maven Central sin token |
| Depurar | Registro de eventos de la app | Depuración en vivo y Logcat en Android Studio |

**El emulador de Android no sirve con los lentes reales**: no usa el Bluetooth físico de la PC y la app Meta AI necesita estar vinculada a los lentes en un teléfono real. Solo sirve con MockDeviceKit (lentes simulados).

## Requisitos
- Teléfono Android **12 o superior** (el sample oficial usa `minSdk 31` para enrutar el micrófono Bluetooth).
- App **Meta AI** (V292 o superior para SDK 1.0.1) con los lentes vinculados **a ese teléfono**. Los lentes se vinculan a un teléfono a la vez: si hoy están en el iPhone, pásalos al Android desde Meta AI.
- Firmware de los lentes **V128** o superior (tras actualizar a V128, reiniciar los lentes: known issue oficial).
- **Developer Mode** en Meta AI: Ajustes → Información de la app → tocar 5 veces la versión → activar.

## Opción 1 · Instalar el APK compilado en la nube (sin instalar nada en la laptop)
1. En GitHub → *Actions* → **Build Android APKs** → última ejecución → artefacto **apks-android**. (O pide a Claude que lo descargue a `salida\`.)
2. Contiene:
   - `CameraAccess-android-debug.apk` — sample oficial de Meta, sin modificar (prueba de control).
   - `GlassesLab-android-debug.apk` — la PoC con las 13 pruebas y evidencia.
3. Copia el `.apk` al teléfono (cable USB en modo *Transferencia de archivos*, Google Drive o WhatsApp a ti mismo).
4. Ábrelo en el teléfono. Android pedirá **permitir instalar apps de este origen** (Archivos / Drive / Chrome): actívalo solo para esa app y vuelve.
5. Instala primero **CameraAccess**: *Connect* → aprobar en Meta AI → *Start Session* → *Preview*. Si ves la imagen de los lentes, la cadena funciona.
6. Instala **GlassesLab** y ejecuta las pruebas en pantalla (secciones 1 a 10).

## Opción 2 · Programar en la laptop con Android Studio (ciclo diario recomendado)
1. Instala **Android Studio** (developer.android.com/studio). El instalador te mostrará las licencias del SDK de Android: acéptalas tú.
2. *File → Open* → carpeta `D:\Simulacion Lentes IA\meta-dat-poc\android`. Espera el *Gradle Sync* (la primera vez descarga dependencias y las piezas multimedia del sample oficial).
3. En el teléfono: **Ajustes → Acerca del teléfono → toca 7 veces "Número de compilación"** → vuelve a Ajustes → **Opciones de desarrollador → Depuración USB** activada.
4. Conecta el teléfono por USB, acepta "¿Permitir depuración USB?" y pulsa **Run ▶** en Android Studio. La app se instala y abre; los logs aparecen en **Logcat**.
5. Cada cambio en el código: **Run ▶** de nuevo (segundos, no minutos).

## Opción 3 · Sin teléfono: emulador + lentes simulados (modo simulado)
Sirve para ver y probar la app y su flujo **hoy**, sin hardware. No prueba los lentes reales: cada resultado queda marcado **[SIMULADO]**.

1. Instala **Android Studio** (developer.android.com/studio) y acepta tú las licencias del SDK en el asistente.
2. *More Actions → Virtual Device Manager → Create device* → **Pixel 8** → imagen de sistema **API 35 o 36 (Google APIs, x86_64)** → *Finish*. Arráncalo con ▶.
   - Si Android Studio avisa que falta virtualización: activa *Intel VT-x / AMD-V* en la BIOS o la función de Windows *Plataforma del hipervisor de Windows*.
3. Instala la app en el emulador: arrastra `salida\GlassesLab-android-debug.apk` a la ventana del emulador, **o** abre la carpeta `android` en Android Studio y pulsa **Run ▶**.
4. En la app: acepta los permisos → sección **🧪 Modo simulado** → **Activar lentes simulados** (acepta el permiso de cámara: la cámara virtual del emulador será el "video de los lentes").
5. Sigue los pasos 2 → 10. Para gestos y voz usa los botones *Botón captura*, *Deslizar*, *Tocar*, *Decir frase* y *Hey Meta* del modo simulado.

| Prueba | ¿Se puede simular? |
|---|---|
| R0, D0, S1, F1, F2, V1, I1, M1, SP1, VI1, IA1 | Sí (con datos simulados) |
| A1 (PCM DAT), A2 (micrófono Bluetooth) | **No**: MockDeviceKit no inyecta audio |

## Pruebas
Las mismas 13 pruebas que en iPhone (ver `03-pruebas-aceptacion.md`), con dos diferencias:
- **D0** además registra la versión de firmware de los lentes (`Device.firmwareInfo`).
- **A2** usa el perfil Bluetooth SCO de Android (44.1 kHz mono, 16 bits).

La evidencia queda dentro de la app; usa **Preparar paquete de evidencia (.zip)** → **Compartir** (Gmail, Drive, WhatsApp).

## Piezas reutilizadas del sample oficial
`HevcDecoder`, `VideoCaptureHandler`/`HevcParameterSetCollector`, `VideoRecorder` y `AudioInputHandler` se descargan al compilar desde el repositorio oficial `meta/meta-wearables-dat-android` en el commit *Release 1.0.1* (`f4371bc`). No se copian a este repositorio.
