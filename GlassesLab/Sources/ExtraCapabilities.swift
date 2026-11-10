import Foundation
import MWDATCore
import MWDATInputs
import MWDATMotion
import MWDATSpeech
import Observation

/// Capacidades experimentales de DAT 1.0.x (solo dev/beta, no publicables en producción):
/// Inputs (botón de captura y panel táctil), Motion (IMU), Speech (transcripción en los lentes)
/// y Voice Invocations ("Hey Meta, start …").
/// Según la guía oficial, Inputs/Motion/Speech/Voice deben estar habilitadas para la app en
/// Wearables Developer Center; en Developer Mode puede que no lo exijan. Las pruebas lo dirán.
@Observable
@MainActor
final class ExtraCapabilities {
  // MARK: Estado visible

  var inputsActive = false
  var inputEvents: [String] = []
  var captureEvents = 0
  var touchEvents = 0
  var captureTriggersPhoto = true

  var motionActive = false
  var motionText = "-"
  var motionSamples = 0

  var speechActive = false
  var speechStateText = "-"
  var speechLocale = "-"
  var partialTranscript = ""
  var finalTranscripts: [String] = []

  var voiceListening = false
  var voiceText = "Inactivo"

  // MARK: Privado

  @ObservationIgnored private unowned let host: GlassesController

  @ObservationIgnored private var inputs: Inputs?
  @ObservationIgnored private var inputsTask: Task<Void, Never>?
  @ObservationIgnored private let inputTokens = ListenerTokenBag()
  @ObservationIgnored private var pendingShortPress = 0

  @ObservationIgnored private var motion: Motion?
  @ObservationIgnored private var motionTask: Task<Void, Never>?
  @ObservationIgnored private let motionTokens = ListenerTokenBag()
  @ObservationIgnored private var motionCSV: URL?
  @ObservationIgnored private var motionRows: [String] = []
  @ObservationIgnored private var accelMagnitudes: [Double] = []
  @ObservationIgnored private var maxGyro: Double = 0
  @ObservationIgnored private var motionEvaluated = false

  @ObservationIgnored private var speech: Speech?
  @ObservationIgnored private let speechTokens = ListenerTokenBag()
  @ObservationIgnored private var speechStarted = false
  @ObservationIgnored private var transcriptURL: URL?

  @ObservationIgnored private var voiceStream: VoiceInvocationsStream?
  @ObservationIgnored private let voiceTokens = ListenerTokenBag()
  @ObservationIgnored private static let voiceAutoStartKey = "GlassesLab.voiceAutoStart"

  init(host: GlassesController) {
    self.host = host
  }

  private func startedSession() -> DeviceSession? {
    guard let session = host.currentSession, session.state == .started else {
      host.log("Inicia la sesión con los lentes primero (estado 'started').")
      return nil
    }
    return session
  }

  // MARK: - Inputs (I1)

  func startInputs() {
    guard inputs == nil, let session = startedSession() else { return }
    do {
      // Agregar Inputs lo activa automáticamente (no tiene start()).
      guard let attached = try session.addInputs(configuration: InputsConfiguration(consumeBack: true)) else {
        host.setResult("I1", .fail, "addInputs devolvió nil: los lentes no reportan entradas")
        return
      }
      inputs = attached
      attached.statePublisher.listen { [weak self] state in
        Task { @MainActor in self?.host.log("Inputs: \(state)") }
      }.store(in: inputTokens)
      attached.errorPublisher.listen { [weak self] error in
        Task { @MainActor in self?.onInputsError("\(error)") }
      }.store(in: inputTokens)
      let events = attached.events
      inputsTask = Task { [weak self] in
        for await event in events {
          self?.onInput(event)
        }
        self?.host.log("Inputs: el stream de eventos terminó")
      }
      inputsActive = true
      host.log("Inputs activo: presiona el botón de captura y desliza/toca la patilla")
    } catch {
      host.setResult("I1", .fail, "addInputs: \(error)")
    }
  }

  func stopInputs(removeFromSession: Bool = true) {
    inputsTask?.cancel()
    inputsTask = nil
    inputTokens.clear()
    if removeFromSession { try? host.currentSession?.removeInputs() }
    inputs = nil
    inputsActive = false
  }

  private func onInputsError(_ error: String) {
    host.log("❌ Inputs: \(error)")
    if captureEvents == 0 && touchEvents == 0 {
      host.setResult("I1", .fail, "errorPublisher: \(error)")
    }
  }

  private func onInput(_ event: InputEvent) {
    let text = String(describing: event)
    inputEvents.insert(text, at: 0)
    if inputEvents.count > 20 { inputEvents.removeLast(inputEvents.count - 20) }
    host.evidence.append(event: "input_event", fields: ["event": text])

    switch event {
    case .capture(let pressType, _, _):
      captureEvents += 1
      if pressType == .shortPress {
        scheduleCapturePhoto()
      } else if pressType == .doublePress {
        // Known issue oficial: un doble toque llega como SHORT_PRESS + DOUBLE_PRESS.
        pendingShortPress += 1  // invalida el disparo pendiente del short press
      }
    case .nav, .select, .back, .drag, .button:
      touchEvents += 1
    @unknown default:
      touchEvents += 1
    }

    if captureEvents >= 1 && touchEvents >= 1 {
      let status = host.results.first(where: { $0.id == "I1" })?.status
      if status != .pass {
        host.setResult("I1", .pass, "eventos captura=\(captureEvents) táctiles=\(touchEvents). Último: \(text)")
      }
    }
  }

  /// Espera 400 ms antes de disparar la foto para descartar el SHORT_PRESS de un doble toque.
  private func scheduleCapturePhoto() {
    guard captureTriggersPhoto else { return }
    pendingShortPress += 1
    let ticket = pendingShortPress
    Task { [weak self] in
      try? await Task.sleep(nanoseconds: 400_000_000)
      guard let self, self.pendingShortPress == ticket else { return }
      if self.host.isStreaming {
        self.host.log("Botón de captura → foto (F1)")
        self.host.capturePhoto()
      } else {
        self.host.log("Botón de captura recibido; inicia el preview para que dispare una foto")
      }
    }
  }

  // MARK: - Motion (M1)

  func startMotion() {
    guard motion == nil, let session = startedSession() else { return }
    do {
      guard let attached = try session.addMotion(configuration: MotionConfiguration(samplingRate: .hz30)) else {
        host.setResult("M1", .fail, "addMotion devolvió nil: los lentes no reportan sensores")
        return
      }
      motion = attached
      motionSamples = 0
      accelMagnitudes = []
      maxGyro = 0
      motionEvaluated = false
      let url = host.evidence.newCaptureURL(prefix: "M1_motion", ext: "csv")
      motionCSV = url
      motionRows = ["timestamp_ns,source,ax,ay,az,gx,gy,gz,mx,my,mz,qx,qy,qz,qw"]

      attached.statePublisher.listen { [weak self] state in
        let text = "\(state)"
        Task { @MainActor in self?.onMotionState(text) }
      }.store(in: motionTokens)
      attached.errorPublisher.listen { [weak self] error in
        Task { @MainActor in
          self?.host.log("❌ Motion: \(error)")
          if self?.motionSamples == 0 { self?.host.setResult("M1", .fail, "errorPublisher: \(error)") }
        }
      }.store(in: motionTokens)

      let samples = attached.samples
      motionTask = Task { [weak self] in
        for await sample in samples {
          self?.onMotion(sample)
        }
      }
      // A diferencia de Inputs, Motion se entrega detenido: hay que llamar start().
      attached.start()
      motionActive = true
      host.log("Motion iniciado a 30 Hz: mueve la cabeza")
    } catch {
      host.setResult("M1", .fail, "addMotion: \(error)")
    }
  }

  func stopMotion(removeFromSession: Bool = true) {
    motion?.stop()
    motionTask?.cancel()
    motionTask = nil
    motionTokens.clear()
    if removeFromSession { try? host.currentSession?.removeMotion() }
    motion = nil
    motionActive = false
    flushMotionCSV()
  }

  private func onMotionState(_ state: String) {
    host.log("Motion: \(state)")
    // Observado en el sample oficial BirdSpotter: una captura de foto detiene Motion.
    if state.contains("stopped"), motionActive, let motion {
      host.log("Motion se detuvo (¿por una foto?). Reiniciando…")
      motion.start()
    }
  }

  private func onMotion(_ sample: MotionSample) {
    motionSamples += 1
    func f(_ v: Double) -> String { String(format: "%.3f", v) }

    var accelText = "-"
    var row: [String] = ["\(sample.timestampNs)", "\(sample.source)"]
    if let a = sample.accelerometer {
      let (x, y, z) = (Double(a.x), Double(a.y), Double(a.z))
      let magnitude = (x * x + y * y + z * z).squareRoot()
      accelMagnitudes.append(magnitude)
      accelText = "acc (\(f(x)), \(f(y)), \(f(z))) |a|=\(String(format: "%.2f", magnitude)) m/s²"
      row += [f(x), f(y), f(z)]
    } else {
      row += ["", "", ""]
    }
    var gyroText = ""
    if let g = sample.gyroscope {
      let (x, y, z) = (Double(g.x), Double(g.y), Double(g.z))
      maxGyro = max(maxGyro, (x * x + y * y + z * z).squareRoot())
      gyroText = " · gyro (\(f(x)), \(f(y)), \(f(z))) rad/s"
      row += [f(x), f(y), f(z)]
    } else {
      row += ["", "", ""]
    }
    if let m = sample.magnetometer {
      row += [f(Double(m.x)), f(Double(m.y)), f(Double(m.z))]
    } else {
      row += ["", "", ""]
    }
    if let q = sample.orientation {
      row += [f(Double(q.x)), f(Double(q.y)), f(Double(q.z)), f(Double(q.w))]
    } else {
      row += ["", "", "", ""]
    }
    motionRows.append(row.joined(separator: ","))
    if motionRows.count >= 60 { flushMotionCSV() }
    motionText = accelText + gyroText

    if motionSamples >= 90, !motionEvaluated {
      motionEvaluated = true
      evaluateMotion()
    }
  }

  private func evaluateMotion() {
    flushMotionCSV()
    let file = motionCSV?.lastPathComponent
    guard !accelMagnitudes.isEmpty else {
      host.setResult("M1", .fail, "\(motionSamples) muestras sin acelerómetro", file: file)
      return
    }
    let mean = accelMagnitudes.reduce(0, +) / Double(accelMagnitudes.count)
    let gravityOK = mean > 7 && mean < 12.5
    let detail = "muestras=\(motionSamples) con acelerómetro=\(accelMagnitudes.count) |a| medio=\(String(format: "%.2f", mean)) m/s² (gravedad≈9.81) gyro máx=\(String(format: "%.2f", maxGyro)) rad/s"
    host.setResult("M1", gravityOK ? .pass : .fail, detail, file: file)
  }

  private func flushMotionCSV() {
    guard let url = motionCSV, !motionRows.isEmpty else { return }
    let chunk = motionRows.joined(separator: "\n") + "\n"
    motionRows = []
    if let handle = try? FileHandle(forWritingTo: url) {
      handle.seekToEndOfFile()
      handle.write(Data(chunk.utf8))
      try? handle.close()
    } else {
      try? Data(chunk.utf8).write(to: url)
    }
  }

  // MARK: - Speech (SP1)

  func startSpeech() {
    guard speech == nil, let session = startedSession() else { return }
    Task {
      guard await host.ensurePermission(.microphone) else {
        host.setResult("SP1", .fail, "Permiso de micrófono (DAT) no concedido")
        return
      }
      do {
        guard let attached = try session.addSpeech() else {
          host.setResult("SP1", .fail, "addSpeech devolvió nil: los lentes no ofrecen speech")
          return
        }
        speech = attached
        speechStarted = false
        transcriptURL = host.evidence.newCaptureURL(prefix: "SP1_transcripcion", ext: "txt")

        attached.transcriptionPublisher.listen { [weak self] result in
          let text = result.text
          let isFinal = result.isFinal
          let confidence = "\(result.confidence)"
          Task { @MainActor in self?.onTranscript(text, isFinal: isFinal, confidence: confidence) }
        }.store(in: speechTokens)
        attached.statePublisher.listen { [weak self] state in
          let text = "\(state)"
          Task { @MainActor in self?.onSpeechState(text) }
        }.store(in: speechTokens)
        attached.localePublisher.listen { [weak self] locale in
          let text = "\(locale)"
          Task { @MainActor in
            self?.speechLocale = text
            self?.host.log("Speech locale: \(text)")
          }
        }.store(in: speechTokens)
        attached.errorPublisher.listen { [weak self] error in
          Task { @MainActor in self?.onSpeechError("\(error)") }
        }.store(in: speechTokens)

        attached.start()
        speechActive = true
        host.log("Speech iniciado: di una frase clara cerca de los lentes")
      } catch {
        host.setResult("SP1", .fail, "addSpeech: \(error)")
      }
    }
  }

  func stopSpeech(removeFromSession: Bool = true) {
    speechActive = false
    speech?.stop()
    speechTokens.clear()
    if removeFromSession { try? host.currentSession?.removeSpeech() }
    speech = nil
    partialTranscript = ""
  }

  private func onSpeechState(_ state: String) {
    speechStateText = state
    host.log("Speech: \(state)")
    if state.contains("started") { speechStarted = true }
    // Known issue oficial: tras ~1 minuto deja de reportar texto. Si se detiene solo, se reinicia.
    if state.contains("stopped"), speechStarted, speechActive, let speech {
      host.log("Speech se detuvo solo; reiniciando…")
      speech.start()
    }
  }

  private func onSpeechError(_ error: String) {
    host.log("❌ Speech: \(error)")
    if finalTranscripts.isEmpty { host.setResult("SP1", .fail, "errorPublisher: \(error)") }
  }

  private func onTranscript(_ text: String, isFinal: Bool, confidence: String) {
    guard isFinal else {
      partialTranscript = text
      return
    }
    partialTranscript = ""
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return }
    finalTranscripts.insert(trimmed, at: 0)
    let line = "\(ISO8601DateFormatter().string(from: Date())) [conf=\(confidence)] \(trimmed)\n"
    if let url = transcriptURL {
      if let handle = try? FileHandle(forWritingTo: url) {
        handle.seekToEndOfFile()
        handle.write(Data(line.utf8))
        try? handle.close()
      } else {
        try? Data(line.utf8).write(to: url)
      }
    }
    host.evidence.append(event: "speech_final", fields: ["text": trimmed, "confidence": confidence, "locale": speechLocale])
    if host.results.first(where: { $0.id == "SP1" })?.status != .pass {
      host.setResult("SP1", .pass, "texto=\"\(trimmed)\" confianza=\(confidence) locale=\(speechLocale)", file: transcriptURL?.lastPathComponent)
    }
  }

  // MARK: - Voice Invocations (VI1)

  /// No requiere sesión de cámara. Requiere configurar en Wearables Developer Center el nombre hablado de la app.
  func startVoice() {
    guard let deviceId = host.currentDeviceId else {
      host.log("No hay lentes detectados para escuchar 'Hey Meta'.")
      return
    }
    do {
      let stream: VoiceInvocationsStream
      if let existing = voiceStream {
        stream = existing
      } else {
        stream = try VoiceInvocationsStream(wearables: Wearables.shared)
        stream.invocationsPublisher.listen { [weak self] invocation in
          Task { @MainActor in await self?.onInvocation(invocation) }
        }.store(in: voiceTokens)
        stream.errorPublisher.listen { [weak self] error in
          Task { @MainActor in
            self?.host.log("❌ Voice: \(error)")
            self?.voiceText = "Error: \(error)"
          }
        }.store(in: voiceTokens)
        voiceStream = stream
      }
      try stream.start(deviceIdentifier: deviceId)
      voiceListening = true
      voiceText = "Escuchando en \(deviceId)"
      UserDefaults.standard.set(true, forKey: Self.voiceAutoStartKey)
      host.log("Voice Invocations activo: di \"Hey Meta, start <nombre de la app>\"")
    } catch {
      voiceText = "No inició: \(error)"
      host.setResult("VI1", .fail, "VoiceInvocationsStream: \(error)")
    }
  }

  func stopVoice() {
    voiceStream?.stop()
    voiceListening = false
    voiceText = "Inactivo"
    UserDefaults.standard.set(false, forKey: Self.voiceAutoStartKey)
  }

  /// Llamado cuando aparecen los lentes: reanuda la escucha si el usuario la dejó activa,
  /// para poder recibir el LaunchApp aunque Meta AI haya abierto la app en frío.
  func deviceAvailable() {
    guard !voiceListening, UserDefaults.standard.bool(forKey: Self.voiceAutoStartKey) else { return }
    startVoice()
  }

  private func onInvocation(_ invocation: any VoiceInvocation) async {
    let text = String(describing: invocation)
    host.evidence.append(event: "voice_invocation", fields: ["invocation": text])
    guard let launch = invocation as? LaunchApp else {
      host.log("Voice: invocación no manejada \(text)")
      return
    }
    // Toda invocación debe confirmarse exactamente una vez.
    let delivered = await launch.responseHandle.sendSuccess(actionOutput: nil)
    host.log("Voice: LaunchApp recibido, confirmación entregada=\(delivered)")
    host.setResult("VI1", delivered ? .pass : .fail, "LaunchApp recibido; sendSuccess entregado=\(delivered)")
  }

  // MARK: - Fin de sesión

  /// La sesión terminó: sus capacidades ya no existen (Voice no depende de la sesión).
  func sessionEnded() {
    if inputsActive || inputs != nil { stopInputs(removeFromSession: false) }
    if motionActive || motion != nil { stopMotion(removeFromSession: false) }
    if speechActive || speech != nil { stopSpeech(removeFromSession: false) }
  }
}
