# 01 · Verificación del SDK Meta Wearables DAT contra fuentes oficiales

Fecha de verificación: 2026-11-09. Fuentes revisadas directamente:

- Repositorio oficial `meta-wearables-dat-ios` (clonado, rama `main`, último commit **"Release 1.0.1"**), incluido su `CHANGELOG.md`, los samples `CameraAccess` y `BirdSpotter` y las guías `plugins/mwdat-ios/skills/*`.
- Documentación oficial de Wearables Developer Center (versión de docs **v1.0**), páginas *Setup*, *Version Dependencies*, *Known issues*, *Integrate into your iOS app*, *Release channels* y la referencia de API iOS (`MWDATCore`, `MWDATCamera`).

## 1. ¿Los Meta Glasses Adventurer están soportados desde la 0.8.0?

**Sí, con un matiz.** Lo que dicen las fuentes oficiales:

| Afirmación | Fuente | Resultado |
|---|---|---|
| "Meta Glasses support" agregado en 0.8.0 | `CHANGELOG.md` → `[0.8.0] - 2026-06-25`: *"Meta Glasses support"* y *"`DeviceType.metaGlasses` value"* | ✅ Confirmado |
| El SDK soporta la línea "Meta glasses" | Docs → Setup → *Hardware requirements*: Ray-Ban Meta (Gen 1 y 2), Ray-Ban Meta Optics, **Meta glasses**, Meta Ray-Ban Display, Oakley Meta HSTN y Oakley Meta Vanguard | ✅ Confirmado |
| Firmware mínimo de "Meta glasses" | Docs → Version Dependencies: aparece por primera vez en 0.8.0 (firmware **V125**, Meta AI **V275**). Para 1.0.1: firmware **V128**, Meta AI **V292** | ✅ Confirmado |
| "Adventurer" es un modelo de la línea Meta glasses | meta.com publica el Adventurer en `meta.com/ai-glasses/meta-glasses/adventurer/` | ✅ Confirmado (fuente comercial de Meta) |
| El código "071S" corresponde al Adventurer | No encontré ninguna fuente pública que asocie "071S" al Adventurer | ⚠️ **No verificado** |

**Matiz:** ni el changelog ni la documentación nombran "Adventurer" ni "071S". El SDK habla de la *línea de producto* "Meta glasses" y la expone como `DeviceType.metaGlasses`. La prueba **D0** de la app verifica esto sobre tus lentes reales: si `device.deviceType()` devuelve `metaGlasses`, queda demostrado.

Revisa en Meta AI → Dispositivos → (tus lentes) → ⚙ → General → Acerca de → **Versión**: para el SDK 1.0.1 el firmware debe ser **≥ V128** y Meta AI **≥ V292**. Problema conocido oficial: *tras la OTA a V128 la captura de cámara no funciona hasta reiniciar los lentes.*

## 2. Versión actual y requisitos

| Elemento | Valor oficial |
|---|---|
| Versión actual del SDK | **1.0.1** (2026-10-09; sin cambios para desarrolladores respecto a 1.0.0) |
| Estado | *Developer preview* |
| iOS mínimo | **17.2** (subió desde 15.2 en 0.9.0) |
| Herramientas | **Xcode 26.4+**, Swift 6.3+ → **requiere macOS** para compilar |
| Distribución | Swift Package `https://github.com/facebook/meta-wearables-dat-ios` (las docs también usan `meta/meta-wearables-dat-ios`) |
| Módulos | `MWDATCore`, `MWDATCamera`, `MWDATDisplay`, `MWDATInputs`, `MWDATMotion`, `MWDATSpeech`, `MWDATMockDevice` |
| App Store | **No soportado todavía**. La guía iOS advierte que el uso de `ExternalAccessory` provoca rechazo en App Store por MFi. Distribución a testers vía *release channels* de Meta. |
| Conexión | La app **no** habla Bluetooth directamente con los lentes "por su cuenta": la app Meta AI media el registro y los permisos (*Developer Mode* para pruebas). |

## 3. Qué se puede hacer desde iOS (mapeo a tus 5 objetivos)

| Objetivo | ¿Posible? | API oficial | Estado |
|---|---|---|---|
| **Foto** | Sí, dos vías | (a) `camera.stream.capturePhoto(format: .jpeg)` → `photoDataPublisher` (`PhotoData.data`, `.format`) con el streaming activo. (b) `camera.photo` (`Photo.start()`, `capturePhoto(resolution:quality:)`, `photoDataPublisher` → `PhotoCaptureData.imageData`, `transferProgressPublisher`) | (a) estable · (b) **experimental, solo dev/beta**; Stream y Photo compiten por la cámara (hay que detener el stream) |
| **Video (iniciar/detener y obtener archivo)** | Sí, pero **grabado en la app** | No existe API "graba en los lentes y devuélveme el archivo". Se graba escribiendo `VideoFrame.sampleBuffer` del stream a MP4 (`AVAssetWriter`), exactamente como hace el sample oficial `CameraAccess` | Estable. Resolución máx. por Bluetooth: **720×1280** (`.high`), 504×896 (`.medium`), 360×640 (`.low`); fps 2/7/15/24/30; el SDK baja calidad si falta ancho de banda |
| **Audio PCM en tiempo real** | Sí, **experimental** | `StreamConfiguration(videoCodec:audioCodec: .pcm(sampleRate: .rate16000/.rate44100/.rate48000, numberOfChannels:), ...)` + `stream.audioFramePublisher` → `AudioFrame.pcmBuffer` (`AVAudioPCMBuffer`, Float32) | **Experimental, solo dev/beta, no publicable en producción** |
| **Audio sin cámara** | Sí, por iOS (no DAT) | Los lentes se exponen a iOS como dispositivo **Bluetooth HFP**: `AVAudioSession(.playAndRecord, options: .allowBluetoothHFP)` + `AVAudioEngine`. Es lo que usa `CameraAccess` para el "sound-in-video" | Estable (es Bluetooth estándar). Calidad de voz de manos libres. Known issue: iPhone 17 + iOS 26 puede quedar sin audio HFP → actualizar a iOS 27 |
| **Streaming en vivo** | Sí | `wearables.createSession(deviceSelector:)` → `session.start()` → `session.addCamera(config:)` → `camera.stream.start()` → `videoFramePublisher` (`frame.makeUIImage()` / `frame.sampleBuffer`) | Estable. Codec `.raw` se pausa en background; `.hvc1` sigue en background |
| **Enviar a IA** | Sí (código de tu app) | No es parte del SDK: la app envía la foto/audio por HTTPS a la API que elijas | La PoC usa la Messages API de Anthropic |

## 4. Respuestas puntuales sobre audio PCM

1. **¿Está soportado el PCM independiente?** No como capacidad independiente. La skill oficial `audio-streaming` lo dice textual: *"Audio Streaming is an experimental extension of Camera Stream, not a separate capability."*
2. **¿Es experimental?** Sí. La referencia de API marca `audioCodec`, `AudioCodec`, `AudioSampleRate` y `audioFramePublisher` como **[dev and beta only]**: *"not supported in production release channels"*.
3. **¿Requiere sesión de cámara activa?** **Sí.** Se configura en el `StreamConfiguration` con el que se agrega la cámara y comparte estado y errores con el stream de video (`StreamError.audioStreamingError`). Requiere permisos `.camera` **y** `.microphone` (concedidos en Meta AI) y, según la guía, aprobación de *Audio Streaming* en Wearables Developer Center (en Developer Mode no está documentado si aplica; la prueba A1 lo dirá).
4. **Alternativa sin cámara:** micrófono por Bluetooth HFP desde iOS (prueba A2). No da acceso al arreglo de micrófonos crudo, sino al canal de voz manos libres que iOS entrega.
5. **Otra opción experimental:** `MWDATSpeech` (1.0.0) entrega transcripción en el dispositivo (`TranscriptionResult`), no PCM. Known issue: deja de reportar texto tras ~1 minuto.

Advertencia oficial adicional: *"Versions alone do not guarantee a capability"* — algunas capacidades se habilitan por usuario en el servidor y pueden devolver `DeviceSessionError.capabilityDenied` aunque las versiones sean correctas.

## 5. Lo que NO es posible (para no confundir rutas)

- Claude, ChatGPT o una página web **no** pueden conectarse por Bluetooth a los lentes. Safari en iOS no implementa Web Bluetooth, y el SDK es nativo (iOS/Android) con mediación de Meta AI. Las "Web Apps" de Meta solo corren en Meta Ray-Ban Display y no acceden a la cámara.
- Las fotos/videos que tomas con el botón de los lentes y se importan en Meta AI **no** son acceso programático: esos archivos no llegan a tu app por el SDK.
- No hay app de demostración oficial de Meta publicada en TestFlight ni en App Store (no encontré ninguna). Los samples se distribuyen solo como código fuente.
