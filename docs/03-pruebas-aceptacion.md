# 03 · Pruebas de aceptación con evidencia verificable

**Estado actual de todas las pruebas: NO EJECUTADA.** Ninguna función se ha probado sobre los lentes reales. La app GlassesLab registra automáticamente cada resultado (PASÓ / FALLÓ / NO EJECUTADA), con criterio, detalle técnico y archivo de evidencia.

## Dónde queda la evidencia
- En el iPhone: app **Archivos → En mi iPhone → GlassesLab → GlassesLab/**
  - `results.json` — estado de cada prueba.
  - `evidence.jsonl` — bitácora con fecha/hora, versión de iOS, SDK, tipo de dispositivo, rutas de audio y errores.
  - `captures/` — fotos, MP4, WAV, frames y respuestas de IA.
- Botón **"Preparar paquete de evidencia (.zip)"** → compartir por AirDrop/correo/WhatsApp.

## Precondiciones (registrar antes de empezar)
| Dato | Valor |
|---|---|
| Modelo iPhone / versión iOS | |
| Versión Meta AI (≥ V292) | |
| Firmware lentes (≥ V128) | |
| Developer Mode activo en Meta AI | Sí / No |
| Batería lentes (> 10 %) | |
| App y build instalados (CameraAccess / GlassesLab) | |

## Casos de prueba

| ID | Función | Pasos | Criterio automático (app) | Verificación humana | Evidencia |
|---|---|---|---|---|---|
| **R0** | Registro | "Conectar app con Meta AI" → aprobar en Meta AI → volver | `registrationState == registered` | La app aparece en Meta AI → Ajustes → App connections → Developer mode apps | `results.json`, captura de pantalla de Meta AI |
| **D0** | Modelo | Con los lentes encendidos y conectados | `deviceType() == metaGlasses` y sin `deviceUpdateRequired` | El nombre mostrado corresponde a tus Adventurer | `evidence.jsonl` evento `device_detected` |
| **S1** | Streaming en vivo | "Iniciar sesión" → "Iniciar preview" → aprobar cámara | `StreamState == streaming` y ≥ 150 frames | El LED de privacidad de los lentes se enciende; mover la mano frente a los lentes se ve en el iPhone con < 1 s de retraso | `S1_frame_*.jpg`, fps y resolución en el detalle |
| **F1** | Foto (en streaming) | Con S1 activo: "Foto durante streaming" | JPEG recibido, decodificable y guardado | La foto muestra lo que veías con los lentes (no la cámara del iPhone) | `F1_foto_*.jpg` |
| **F2** | Foto HQ (experimental) | Detener preview → "Foto HQ sin streaming" | `PhotoCaptureData` decodificable y guardado | Resolución mayor que F1 | `F2_fotoHQ_*.jpg` |
| **V1** | Video | Con S1 activo: "Grabar video" 10 s → "Detener" | MP4 con pista de video y duración ≥ 3 s | Reproducir en Archivos: muestra la vista de los lentes | `V1_video_*.mp4` |
| **A1** | Audio PCM DAT (experimental) | Detener preview → activar "Incluir audio PCM" → "Iniciar preview" (aprobar micrófono) → "Grabar PCM" 10 s hablando → "Detener" | WAV ≥ 3 s y RMS > 0.003 | Al reproducir se escucha tu voz; tapar el iPhone no la atenúa (viene de los lentes) | `A1_pcm_dat_*.wav`, formato (16 kHz mono Float32) |
| **A2** | Audio mic lentes por HFP | Lentes vinculados por Bluetooth al iPhone → "Grabar mic Bluetooth" 10 s hablando → "Detener" | Ruta = `bluetoothHFP`, WAV ≥ 3 s, RMS > 0.003 | El puerto registrado tiene el nombre de tus lentes | `A2_hfp_*.wav`, evento `hfp_route` |
| **I1** | Inputs (experimental) | Sesión iniciada → "Activar Inputs" → presionar el botón de captura y deslizar/tocar la patilla | ≥ 1 evento `.capture` y ≥ 1 táctil | Cada gesto aparece en la lista; con preview activo, el botón de captura dispara F1. Anotar si además los lentes guardan una foto propia | `evidence.jsonl` eventos `input_event` |
| **M1** | Motion (experimental) | Sesión iniciada → "Iniciar Motion" → mover la cabeza 5 s | ≥ 90 muestras; aceleración media 7–12.5 m/s² | Al girar la cabeza cambia el giroscopio; quieto, la aceleración ≈ 9.8 | `M1_motion_*.csv` |
| **SP1** | Speech (experimental) | Sesión iniciada → "Iniciar Speech" (aprobar micrófono) → decir "revisar presión de neumáticos" | ≥ 1 transcripción final no vacía | El texto coincide con lo dicho; anotar el idioma (`locale`) | `SP1_transcripcion_*.txt` |
| **VI1** | Voice Invocations (experimental) | Configurar nombre hablado en Wearables Developer Center → "Escuchar invocaciones" → cerrar la app → decir "Hey Meta, start <nombre>" | `LaunchApp` recibido y `sendSuccess` entregado | La app se abre sola en el iPhone | `evidence.jsonl` evento `voice_invocation` |
| **IA1** | IA | Guardar API key → "Enviar última foto a Claude" | HTTP 200, `stop_reason ≠ refusal`, texto no vacío | La respuesta describe el contenido real de la foto | `IA1_respuesta_*.txt` (incluye nombre de la foto) |

Una prueba solo cuenta como **PASÓ** si el criterio automático **y** la verificación humana se cumplen. Si la app marca PASÓ pero la verificación humana falla, anótala como FALLÓ en la tabla de abajo.

## Prueba de control (antes de GlassesLab)
| ID | Prueba | Resultado |
|---|---|---|
| C0 | Sample oficial CameraAccess: Connect → Start Session → Preview → foto → video | NO EJECUTADA |

## Registro consolidado

| ID | Resultado | Fecha | Evidencia | Observaciones |
|---|---|---|---|---|
| C0 | NO EJECUTADA | | | |
| R0 | NO EJECUTADA | | | |
| D0 | NO EJECUTADA | | | |
| S1 | NO EJECUTADA | | | |
| F1 | NO EJECUTADA | | | |
| F2 | NO EJECUTADA | | | |
| V1 | NO EJECUTADA | | | |
| A1 | NO EJECUTADA | | | |
| A2 | NO EJECUTADA | | | |
| I1 | NO EJECUTADA | | | |
| M1 | NO EJECUTADA | | | |
| SP1 | NO EJECUTADA | | | |
| VI1 | NO EJECUTADA | | | |
| IA1 | NO EJECUTADA | | | |

## Diagnóstico rápido si algo falla
| Síntoma | Causa probable (fuente oficial) | Acción |
|---|---|---|
| Registro no avanza | Developer Mode apagado o sin internet | Activar Developer Mode; registro requiere conexión |
| `deviceUpdateRequired` | Firmware < V128 | Actualizar firmware desde la app |
| `datAppOnTheGlassesUpdateRequired` / `dwaUnavailable` | App DAT de los lentes desactualizada o no instalada | Meta AI → App connections → actualizar |
| Stream no arranca tras actualizar a V128 | Known issue oficial | Reiniciar los lentes |
| Instalación de la app DAT en lentes falla | Batería < 10 % (known issue) | Cargar |
| `capabilityDenied` | Capacidad habilitada por servidor por usuario | Reportar en el foro de Meta; no es error de código |
| A2 sin audio en iPhone 17 / iOS 26 | Known issue HFP | Actualizar a iOS 27 |
| `hingesClosed` | Patillas cerradas o lentes quitados | Ponerse los lentes |
