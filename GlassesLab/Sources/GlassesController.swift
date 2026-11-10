import AVFoundation
import Foundation
import MWDATCamera
import MWDATCore
import Observation
import SwiftUI
import UIKit

/// Orquesta el ciclo de vida oficial de DAT 1.0.x:
/// configure → registro (Meta AI) → DeviceSession → addCamera → stream.start → captura/grabación → stop.
/// Cada prueba de aceptación queda registrada con evidencia en Documents/GlassesLab.
@Observable
@MainActor
final class GlassesController {
  // MARK: Estado visible

  var registrationText = "-"
  var isRegistered = false
  var deviceText = "Sin dispositivo"
  var sessionState: DeviceSessionState = .idle
  var streamStateText = "stopped"
  var isStreaming = false
  var streamHasPCM = false
  var includePCM = false
  var previewImage: UIImage?
  var framesReceived = 0
  var fps: Double = 0
  var frameSizeText = "-"
  var isCapturingPhoto = false
  var isCapturingHQ = false
  var isRecordingVideo = false
  var isRecordingPCM = false
  var isRecordingHFP = false
  var lastPhoto: UIImage?
  var lastPhotoURL: URL?
  var aiPrompt = "¿Qué componente se ve y hay alguna falla visible?"
  var aiResponse = ""
  var aiBusy = false
  var apiKeyInput = ""
  var hasSavedKey = false
  var logLines: [String] = []
  var results: [TestResult]
  var evidenceZipURL: URL?

  @ObservationIgnored var extras: ExtraCapabilities!
  @ObservationIgnored private(set) var currentDeviceId: DeviceIdentifier?
  var currentSession: DeviceSession? { session }

  var hasSession: Bool {
    switch sessionState {
    case .starting, .started, .paused, .stopping: return true
    default: return false
    }
  }

  var sessionText: String { "\(sessionState)" }

  // MARK: Privado

  let evidence = EvidenceStore()
  @ObservationIgnored private let wearables: WearablesInterface
  @ObservationIgnored private let selector: AutoDeviceSelector
  @ObservationIgnored private var session: DeviceSession?
  @ObservationIgnored private var camera: MWDATCamera.Camera?
  @ObservationIgnored private var hqPhoto: MWDATCamera.Photo?
  @ObservationIgnored private var hqRequested = false
  @ObservationIgnored private let sessionTokens = ListenerTokenBag()
  @ObservationIgnored private let streamTokens = ListenerTokenBag()
  @ObservationIgnored private let photoTokens = ListenerTokenBag()
  @ObservationIgnored private var deviceTokens: [AnyListenerToken] = []
  @ObservationIgnored private var tasks: [Task<Void, Never>] = []
  @ObservationIgnored private var frameTimes: [CFTimeInterval] = []
  @ObservationIgnored private var streamStartTime: Date?
  @ObservationIgnored private let videoRecorder = VideoFileRecorder()
  @ObservationIgnored private let pcmWriter = PCMWavWriter()
  @ObservationIgnored private let hfpRecorder = HFPMicRecorder()
  @ObservationIgnored private var currentDeviceType = "-"

  // MARK: Init

  init(configureError: String?) {
    wearables = Wearables.shared
    selector = AutoDeviceSelector(wearables: Wearables.shared)
    results = TestCatalog.initial
    if let saved = evidence.loadResults() {
      // Conserva resultados de ejecuciones anteriores.
      results = TestCatalog.initial.map { base in saved.first(where: { $0.id == base.id }) ?? base }
    }
    hasSavedKey = KeychainStore.load() != nil
    if let configureError { log("❌ \(configureError)") }
    log("App iniciada. iOS \(UIDevice.current.systemVersion), \(UIDevice.current.model)")
    evidence.append(event: "app_start", fields: environmentFields())

    registrationText = "\(wearables.registrationState)"
    isRegistered = wearables.registrationState == .registered

    extras = ExtraCapabilities(host: self)

    tasks.append(Task { [weak self] in
      guard let self else { return }
      for await state in self.wearables.registrationStateStream() {
        self.onRegistration(state)
      }
    })
    tasks.append(Task { [weak self] in
      guard let self else { return }
      for await devices in self.wearables.devicesStream() {
        self.onDevices(devices)
      }
    })
  }

  // MARK: Registro (R0)

  func handleMetaAICallback(_ url: URL) async {
    log("Callback de Meta AI: \(url.absoluteString)")
    do {
      _ = try await wearables.handleUrl(url)
    } catch {
      log("❌ handleUrl: \(error)")
    }
  }

  func register() {
    Task {
      do {
        log("Iniciando registro: se abrirá Meta AI…")
        try await wearables.startRegistration()
      } catch {
        log("❌ startRegistration: \(error)")
        setResult("R0", .fail, "startRegistration: \(error)")
      }
    }
  }

  func unregister() {
    Task {
      do {
        try await wearables.startUnregistration()
      } catch {
        log("❌ startUnregistration: \(error)")
      }
    }
  }

  private func onRegistration(_ state: RegistrationState) {
    registrationText = "\(state)"
    isRegistered = state == .registered
    log("Registro: \(state)")
    if state == .registered {
      setResult("R0", .pass, "registrationState=registered")
    }
  }

  // MARK: Dispositivos (D0)

  private func onDevices(_ ids: [DeviceIdentifier]) {
    deviceTokens.removeAll()
    currentDeviceId = ids.first
    guard let id = ids.first, let device = wearables.deviceForIdentifier(id) else {
      deviceText = ids.isEmpty ? "Sin dispositivo" : "Dispositivo sin detalle"
      return
    }
    refreshDeviceText(device, total: ids.count)
    extras.deviceAvailable()
    let token = device.addDeviceStateListener { [weak self] _ in
      Task { @MainActor in self?.refreshDeviceText(device, total: ids.count) }
    }
    deviceTokens.append(token)

    let type = device.deviceType()
    let compatibility = device.compatibility()
    currentDeviceType = "\(type)"
    let detail = "nombre=\(device.nameOrId()) tipo=\(type) compat=\(compatibility) link=\(device.linkState)"
    log("Dispositivo: \(detail)")
    evidence.append(event: "device_detected", fields: [
      "name": device.nameOrId(), "deviceType": "\(type)",
      "compatibility": "\(compatibility)", "linkState": "\(device.linkState)",
      "battery": device.batteryLevel.map { "\($0)" } ?? "nil",
    ])
    if compatibility == .deviceUpdateRequired {
      setResult("D0", .fail, "Requiere actualizar firmware. \(detail)")
    } else if type == .metaGlasses {
      setResult("D0", .pass, detail)
    } else {
      setResult("D0", .fail, "Tipo detectado distinto de metaGlasses. \(detail)")
    }
  }

  private func refreshDeviceText(_ device: Device, total: Int) {
    let battery = device.batteryLevel.map { "\($0)%" } ?? "?"
    deviceText = "\(device.nameOrId()) · \(device.deviceType()) · \(device.linkState) · batería \(battery) · puesto: \(device.donState) · \(total) vinculado(s)"
  }

  func openFirmwareUpdate() {
    Task {
      do { try await wearables.openFirmwareUpdate() } catch { log("❌ openFirmwareUpdate: \(error)") }
    }
  }

  // MARK: Sesión

  func startSession() {
    guard !hasSession else { return }
    do {
      let newSession = try wearables.createSession(deviceSelector: selector)
      session = newSession
      newSession.statePublisher.listen { [weak self] state in
        Task { @MainActor in self?.onSessionState(state) }
      }.store(in: sessionTokens)
      newSession.errorPublisher.listen { [weak self] error in
        Task { @MainActor in self?.log("❌ Error de sesión: \(error)") }
      }.store(in: sessionTokens)
      sessionState = .starting
      try newSession.start()
      log("Sesión iniciando…")
    } catch {
      log("❌ createSession/start: \(error)")
      sessionState = .idle
      session = nil
      sessionTokens.clear()
    }
  }

  func endSession() {
    session?.stop()
  }

  private func onSessionState(_ state: DeviceSessionState) {
    sessionState = state
    log("Sesión: \(state)")
    if state == .stopped {
      sessionTokens.clear()
      session = nil
      clearStream()
      extras.sessionEnded()
    }
  }

  // MARK: Permisos

  func ensurePermission(_ permission: Permission) async -> Bool {
    do {
      if try await wearables.checkPermissionStatus(permission) == .granted { return true }
      log("Solicitando permiso \(permission): se abrirá Meta AI…")
      let status = try await wearables.requestPermission(permission)
      log("Permiso \(permission): \(status)")
      return status == .granted
    } catch {
      log("❌ Permiso \(permission): \(error)")
      return false
    }
  }

  // MARK: Streaming (S1)

  func startPreview() {
    guard let session, session.state == .started else {
      log("Primero inicia la sesión y espera el estado 'started'.")
      return
    }
    guard camera == nil else { return }
    let withPCM = includePCM
    Task {
      guard await ensurePermission(.camera) else {
        setResult("S1", .fail, "Permiso de cámara no concedido")
        return
      }
      if withPCM {
        guard await ensurePermission(.microphone) else {
          setResult("A1", .fail, "Permiso de micrófono (DAT) no concedido")
          return
        }
      }
      beginStream(on: session, withPCM: withPCM)
    }
  }

  private func beginStream(on session: DeviceSession, withPCM: Bool) {
    // .raw: frames decodificados → preview con makeUIImage() y grabación MP4 directa.
    let config: StreamConfiguration
    if withPCM {
      config = StreamConfiguration(
        videoCodec: .raw,
        audioCodec: .pcm(sampleRate: .rate16000, numberOfChannels: 1),
        resolution: .medium,
        frameRate: 24
      )
    } else {
      config = StreamConfiguration(videoCodec: .raw, resolution: .medium, frameRate: 24)
    }
    do {
      guard let newCamera = try session.addCamera(config: config) else {
        log("❌ addCamera devolvió nil (¿sesión no iniciada?)")
        return
      }
      camera = newCamera
      streamHasPCM = withPCM
      framesReceived = 0
      frameTimes = []
      streamStartTime = Date()
      pcmWriter.resetCounters()
      attachStreamListeners(newCamera.stream, withPCM: withPCM)
      streamStateText = "starting"
      newCamera.stream.start()
      log("Stream solicitado: raw 504x896 @24fps, PCM=\(withPCM)")
      evidence.append(event: "stream_requested", fields: [
        "codec": "raw", "resolution": "medium", "fps": "24", "pcm": "\(withPCM)",
      ])
    } catch {
      log("❌ addCamera: \(error)")
    }
  }

  private func attachStreamListeners(_ stream: MWDATCamera.Stream, withPCM: Bool) {
    let recorder = videoRecorder
    let pcm = pcmWriter

    stream.statePublisher.listen { [weak self] state in
      Task { @MainActor in self?.onStreamState(state) }
    }.store(in: streamTokens)

    stream.videoFramePublisher.listen { [weak self] frame in
      recorder.append(frame.sampleBuffer)
      let image = frame.makeUIImage()
      Task { @MainActor in self?.onFrame(image) }
    }.store(in: streamTokens)

    stream.photoDataPublisher.listen { [weak self] photo in
      let data = photo.data
      let format = "\(photo.format)"
      Task { @MainActor in self?.onStreamPhoto(data, format: format) }
    }.store(in: streamTokens)

    stream.errorPublisher.listen { [weak self] error in
      Task { @MainActor in self?.onStreamError(error) }
    }.store(in: streamTokens)

    if withPCM {
      // Experimental: registrar antes de stream.start(). No mutar el buffer entregado.
      stream.audioFramePublisher.listen { frame in
        pcm.append(frame.pcmBuffer)
      }.store(in: streamTokens)
    }
  }

  private func onStreamState(_ state: StreamState) {
    streamStateText = "\(state)"
    isStreaming = state == .streaming
    log("Stream: \(state)")
    if state == .stopped {
      if results.first(where: { $0.id == "S1" })?.status == .notRun, framesReceived > 0 {
        setResult("S1", .fail, "El stream se detuvo tras \(framesReceived) frames (<150)")
      }
      clearStream()
    }
  }

  private func onFrame(_ image: UIImage?) {
    framesReceived += 1
    let now = CACurrentMediaTime()
    frameTimes.append(now)
    frameTimes.removeAll { now - $0 > 2 }
    if frameTimes.count > 1, let first = frameTimes.first {
      fps = Double(frameTimes.count - 1) / max(now - first, 0.001)
    }
    if let image {
      previewImage = image
      frameSizeText = "\(Int(image.size.width * image.scale))x\(Int(image.size.height * image.scale))"
    }
    if framesReceived == 150 {
      let elapsed = Date().timeIntervalSince(streamStartTime ?? Date())
      let detail = "150 frames en \(String(format: "%.1f", elapsed)) s, fps≈\(String(format: "%.1f", fps)), frame=\(frameSizeText), dispositivo=\(currentDeviceType)"
      var fileName: String?
      if let image, let jpeg = image.jpegData(compressionQuality: 0.85) {
        let url = evidence.newCaptureURL(prefix: "S1_frame", ext: "jpg")
        try? jpeg.write(to: url)
        fileName = url.lastPathComponent
      }
      setResult("S1", .pass, detail, file: fileName)
    }
  }

  private func onStreamError(_ error: StreamError) {
    log("❌ Error de stream: \(error)")
    evidence.append(event: "stream_error", fields: ["error": "\(error)"])
    if isCapturingPhoto {
      isCapturingPhoto = false
      setResult("F1", .fail, "Error durante captura: \(error)")
    }
    if streamHasPCM, error == .audioStreamingError {
      setResult("A1", .fail, "audioStreamingError: \(error)")
    }
  }

  func stopPreview() {
    if isRecordingVideo { Task { await stopVideo() } }
    if isRecordingPCM { stopPCM() }
    streamStateText = "stopping"
    camera?.stop()  // detiene el stream hijo y libera la cámara de la sesión
  }

  private func clearStream() {
    streamTokens.clear()
    camera?.stop()
    camera = nil
    isStreaming = false
    streamHasPCM = false
    streamStateText = "stopped"
  }

  // MARK: Foto durante streaming (F1)

  func capturePhoto() {
    guard isStreaming, let camera, !isCapturingPhoto else {
      log("La foto requiere streaming activo.")
      return
    }
    isCapturingPhoto = true
    let started = camera.stream.capturePhoto(format: .jpeg)
    log("capturePhoto(.jpeg) → \(started)")
    if !started {
      isCapturingPhoto = false
      setResult("F1", .fail, "capturePhoto devolvió false (otra captura en curso o no disponible)")
    }
  }

  private func onStreamPhoto(_ data: Data, format: String) {
    isCapturingPhoto = false
    let ext = format.lowercased().contains("heic") ? "heic" : "jpg"
    let url = evidence.newCaptureURL(prefix: "F1_foto", ext: ext)
    do {
      try data.write(to: url)
    } catch {
      setResult("F1", .fail, "No se pudo guardar: \(error)")
      return
    }
    guard let image = UIImage(data: data) else {
      setResult("F1", .fail, "\(data.count) bytes recibidos pero no decodificables", file: url.lastPathComponent)
      return
    }
    lastPhoto = image
    lastPhotoURL = url
    let px = "\(Int(image.size.width * image.scale))x\(Int(image.size.height * image.scale))"
    setResult("F1", .pass, "formato=\(format) bytes=\(data.count) resolución=\(px)", file: url.lastPathComponent)
  }

  // MARK: Foto HQ independiente (F2, experimental)

  func captureHQPhoto() {
    guard let session, session.state == .started else {
      log("Inicia la sesión primero.")
      return
    }
    guard camera == nil else {
      log("Detén el streaming antes de la foto HQ: Stream y Photo compiten por la cámara.")
      return
    }
    isCapturingHQ = true
    Task {
      guard await ensurePermission(.camera) else {
        isCapturingHQ = false
        setResult("F2", .fail, "Permiso de cámara no concedido")
        return
      }
      do {
        guard let newCamera = try session.addCamera(config: StreamConfiguration()) else {
          isCapturingHQ = false
          log("❌ addCamera devolvió nil")
          return
        }
        camera = newCamera
        let photo = newCamera.photo
        hqPhoto = photo
        hqRequested = false
        photo.statePublisher.listen { [weak self] state in
          Task { @MainActor in self?.onHQState(state) }
        }.store(in: photoTokens)
        photo.photoDataPublisher.listen { [weak self] capture in
          let data = capture.imageData
          Task { @MainActor in self?.onHQPhoto(data) }
        }.store(in: photoTokens)
        photo.transferProgressPublisher.listen { [weak self] progress in
          Task { @MainActor in self?.log("Transferencia foto HQ: \(progress)") }
        }.store(in: photoTokens)
        photo.errorPublisher.listen { [weak self] error in
          Task { @MainActor in self?.onHQError("\(error)") }
        }.store(in: photoTokens)
        photo.start()
        log("Photo.start() solicitado; se capturará al llegar a 'started'")
      } catch {
        isCapturingHQ = false
        log("❌ addCamera (HQ): \(error)")
      }
    }
  }

  private func onHQState(_ state: PhotoState) {
    log("Photo: \(state)")
    if state == .started, !hqRequested, let hqPhoto {
      hqRequested = true
      hqPhoto.capturePhoto(resolution: .full, quality: .high)
      log("capturePhoto(resolution: .full, quality: .high)")
    }
  }

  private func onHQPhoto(_ data: Data) {
    let url = evidence.newCaptureURL(prefix: "F2_fotoHQ", ext: "jpg")
    try? data.write(to: url)
    if let image = UIImage(data: data) {
      lastPhoto = image
      lastPhotoURL = url
      let px = "\(Int(image.size.width * image.scale))x\(Int(image.size.height * image.scale))"
      setResult("F2", .pass, "bytes=\(data.count) resolución=\(px)", file: url.lastPathComponent)
    } else {
      setResult("F2", .fail, "\(data.count) bytes no decodificables", file: url.lastPathComponent)
    }
    finishHQ()
  }

  private func onHQError(_ error: String) {
    log("❌ Photo error: \(error)")
    setResult("F2", .fail, error)
    finishHQ()
  }

  private func finishHQ() {
    isCapturingHQ = false
    hqPhoto?.stop()
    photoTokens.clear()
    hqPhoto = nil
    camera?.stop()
    camera = nil
  }

  // MARK: Video (V1)

  func startVideo() {
    guard isStreaming, !isRecordingVideo else {
      log("La grabación requiere streaming activo.")
      return
    }
    let url = evidence.newCaptureURL(prefix: "V1_video", ext: "mp4")
    videoRecorder.start(url: url)
    isRecordingVideo = true
    log("Grabando video → \(url.lastPathComponent)")
  }

  func stopVideo() async {
    guard isRecordingVideo else { return }
    isRecordingVideo = false
    switch await videoRecorder.finish() {
    case .completed(let url, let frames, let size):
      let asset = AVURLAsset(url: url)
      let duration = (try? await asset.load(.duration))?.seconds ?? 0
      let tracks = (try? await asset.loadTracks(withMediaType: .video))?.count ?? 0
      let bytes = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0
      let detail = "duración=\(String(format: "%.1f", duration)) s, frames=\(frames), tamaño=\(size), pistas video=\(tracks), bytes=\(bytes)"
      setResult("V1", duration >= 3 && tracks > 0 ? .pass : .fail, detail, file: url.lastPathComponent)
    case .noFrames(let reason):
      setResult("V1", .fail, reason)
    case .failed(let reason):
      setResult("V1", .fail, reason)
    }
  }

  // MARK: Audio PCM por DAT (A1, experimental)

  func startPCM() {
    guard isStreaming, streamHasPCM else {
      log("Activa 'Incluir audio PCM' antes de iniciar el preview.")
      return
    }
    let url = evidence.newCaptureURL(prefix: "A1_pcm_dat", ext: "wav")
    pcmWriter.start(url: url)
    isRecordingPCM = true
    log("Grabando PCM DAT (habla cerca de los lentes) → \(url.lastPathComponent). Buffers recibidos hasta ahora: \(pcmWriter.receivedCount)")
  }

  func stopPCM() {
    guard isRecordingPCM else { return }
    isRecordingPCM = false
    let stats = pcmWriter.stop()
    let detail = "segundos=\(String(format: "%.1f", stats.seconds)) rms=\(String(format: "%.4f", stats.rms)) buffers=\(stats.buffersReceived) formato=\(stats.format)\(stats.error.map { " error=\($0)" } ?? "")"
    let ok = stats.seconds >= 3 && stats.rms > 0.003
    setResult("A1", ok ? .pass : .fail, detail, file: stats.url?.lastPathComponent)
  }

  // MARK: Audio por Bluetooth HFP (A2) — no requiere cámara ni sesión DAT

  func startHFP() {
    Task {
      let granted = await AVAudioApplication.requestRecordPermission()
      guard granted else {
        setResult("A2", .fail, "Permiso de micrófono de iOS denegado")
        return
      }
      let url = evidence.newCaptureURL(prefix: "A2_hfp", ext: "wav")
      do {
        let route = try hfpRecorder.start(url: url)
        isRecordingHFP = true
        log("Grabando HFP (habla cerca de los lentes): \(route)")
        evidence.append(event: "hfp_route", fields: ["route": route])
      } catch {
        log("❌ HFP: \(error.localizedDescription)")
        setResult("A2", .fail, error.localizedDescription)
      }
    }
  }

  func stopHFP() {
    guard isRecordingHFP else { return }
    isRecordingHFP = false
    let stats = hfpRecorder.stop()
    let detail = "segundos=\(String(format: "%.1f", stats.seconds)) rms=\(String(format: "%.4f", stats.rms)) formato=\(stats.format)\(stats.error.map { " error=\($0)" } ?? "")"
    let ok = stats.seconds >= 3 && stats.rms > 0.003
    setResult("A2", ok ? .pass : .fail, detail, file: stats.url?.lastPathComponent)
  }

  // MARK: IA (IA1)

  func saveAPIKey() {
    let key = apiKeyInput.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !key.isEmpty else { return }
    KeychainStore.save(key)
    apiKeyInput = ""
    hasSavedKey = true
    log("API key guardada en el Keychain del iPhone.")
  }

  func sendLastPhotoToAI() {
    guard let image = lastPhoto else {
      log("Primero captura una foto (F1 o F2).")
      return
    }
    guard let key = KeychainStore.load() else {
      log("Falta la API key de Anthropic.")
      return
    }
    aiBusy = true
    aiResponse = ""
    let prompt = aiPrompt
    let photoName = lastPhotoURL?.lastPathComponent
    Task {
      let started = Date()
      do {
        let result = try await ClaudeClient.analyze(image: image, prompt: prompt, apiKey: key)
        aiResponse = result.text
        let url = evidence.newCaptureURL(prefix: "IA1_respuesta", ext: "txt")
        try? "Foto: \(photoName ?? "-")\nPrompt: \(prompt)\nModelo: \(result.model)\n\n\(result.text)"
          .write(to: url, atomically: true, encoding: .utf8)
        let detail = "modelo=\(result.model) stop=\(result.stopReason) tokens in/out=\(result.inputTokens)/\(result.outputTokens) latencia=\(String(format: "%.1f", Date().timeIntervalSince(started))) s foto=\(photoName ?? "-")"
        setResult("IA1", .pass, detail, file: url.lastPathComponent)
      } catch {
        aiResponse = "Error: \(error.localizedDescription)"
        setResult("IA1", .fail, error.localizedDescription)
      }
      aiBusy = false
    }
  }

  // MARK: Evidencia

  func resetResults() {
    results = TestCatalog.initial
    evidence.save(results: results)
    log("Resultados reiniciados (los archivos se conservan).")
  }

  func prepareEvidenceZip() {
    do {
      evidenceZipURL = try evidence.makeZip()
      log("Paquete de evidencia listo: \(evidenceZipURL?.lastPathComponent ?? "")")
    } catch {
      log("❌ Zip: \(error)")
    }
  }

  func setResult(_ id: String, _ status: TestStatus, _ detail: String, file: String? = nil) {
    guard let index = results.firstIndex(where: { $0.id == id }) else { return }
    results[index].status = status
    results[index].detail = detail
    results[index].evidenceFile = file
    results[index].timestamp = Date()
    evidence.save(results: results)
    var fields = environmentFields()
    fields["test"] = id
    fields["status"] = status.rawValue
    fields["detail"] = detail
    fields["file"] = file ?? ""
    evidence.append(event: "test_result", fields: fields)
    log("[\(id)] \(status.rawValue): \(detail)")
  }

  private func environmentFields() -> [String: String] {
    let info = Bundle.main.infoDictionary ?? [:]
    return [
      "ios": UIDevice.current.systemVersion,
      "phone": UIDevice.current.model,
      "app_version": "\(info["CFBundleShortVersionString"] ?? "?") (\(info["CFBundleVersion"] ?? "?"))",
      "sdk": "meta-wearables-dat-ios 1.0.1",
      "deviceType": currentDeviceType,
    ]
  }

  func log(_ message: String) {
    let f = DateFormatter()
    f.dateFormat = "HH:mm:ss"
    logLines.append("\(f.string(from: Date())) \(message)")
    if logLines.count > 300 { logLines.removeFirst(logLines.count - 300) }
  }
}
