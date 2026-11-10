import AVFoundation
import CoreMedia
import CoreVideo
import Foundation
import QuartzCore

// MARK: - Utilidades de audio

enum AudioMath {
  /// Acumula suma de cuadrados y número de muestras del canal 0 (float32 o int16).
  static func accumulate(_ buffer: AVAudioPCMBuffer, sumSquares: inout Double, count: inout Int) {
    let n = Int(buffer.frameLength)
    guard n > 0 else { return }
    if let data = buffer.floatChannelData?[0] {
      for i in 0..<n {
        let v = Double(data[i])
        sumSquares += v * v
      }
      count += n
    } else if let data = buffer.int16ChannelData?[0] {
      for i in 0..<n {
        let v = Double(data[i]) / 32768.0
        sumSquares += v * v
      }
      count += n
    }
  }

  static func rms(sumSquares: Double, count: Int) -> Double {
    count > 0 ? (sumSquares / Double(count)).squareRoot() : 0
  }
}

struct AudioStats {
  var url: URL?
  var seconds: Double
  var rms: Double
  var buffersReceived: Int
  var format: String
  var error: String?
}

// MARK: - Escritor WAV para el PCM experimental de DAT (Stream.audioFramePublisher)

/// Thread-safe. `append` se llama desde el hilo del SDK.
final class PCMWavWriter: @unchecked Sendable {
  private let lock = NSLock()
  private var file: AVAudioFile?
  private var url: URL?
  private var recording = false
  private var buffersReceived = 0
  private var framesWritten: AVAudioFramePosition = 0
  private var sampleRate: Double = 0
  private var sumSquares: Double = 0
  private var sampleCount = 0
  private var formatText = "-"
  private var lastError: String?

  /// Buffers recibidos desde el último reset (aunque no se esté grabando).
  var receivedCount: Int { lock.withLock { buffersReceived } }
  var lastFormat: String { lock.withLock { formatText } }

  func resetCounters() {
    lock.withLock {
      buffersReceived = 0
      formatText = "-"
    }
  }

  func start(url: URL) {
    lock.withLock {
      self.url = url
      file = nil
      framesWritten = 0
      sampleRate = 0
      sumSquares = 0
      sampleCount = 0
      lastError = nil
      recording = true
    }
  }

  func append(_ buffer: AVAudioPCMBuffer) {
    lock.lock()
    defer { lock.unlock() }
    buffersReceived += 1
    formatText = "\(buffer.format)"
    guard recording, let url, buffer.frameLength > 0 else { return }
    do {
      if file == nil {
        file = try AVAudioFile(
          forWriting: url,
          settings: buffer.format.settings,
          commonFormat: buffer.format.commonFormat,
          interleaved: buffer.format.isInterleaved
        )
        sampleRate = buffer.format.sampleRate
      }
      try file?.write(from: buffer)
      framesWritten += AVAudioFramePosition(buffer.frameLength)
      AudioMath.accumulate(buffer, sumSquares: &sumSquares, count: &sampleCount)
    } catch {
      lastError = "\(error)"
    }
  }

  func stop() -> AudioStats {
    lock.withLock {
      recording = false
      file = nil  // al liberar AVAudioFile se cierra y finaliza el WAV
      let seconds = sampleRate > 0 ? Double(framesWritten) / sampleRate : 0
      return AudioStats(
        url: framesWritten > 0 ? url : nil,
        seconds: seconds,
        rms: AudioMath.rms(sumSquares: sumSquares, count: sampleCount),
        buffersReceived: buffersReceived,
        format: formatText,
        error: lastError
      )
    }
  }
}

// MARK: - Micrófono de los lentes por Bluetooth HFP (vía iOS, NO es API de DAT)

enum HFPError: LocalizedError {
  case noGlassesInput(String)
  case routeNotHFP(String)
  case badFormat

  var errorDescription: String? {
    switch self {
    case .noGlassesInput(let available):
      return "No hay entrada Bluetooth HFP disponible (¿lentes conectados al iPhone por Bluetooth?). Entradas: \(available)"
    case .routeNotHFP(let route):
      return "La ruta de entrada activa no es Bluetooth HFP: \(route)"
    case .badFormat:
      return "Formato de entrada inválido (¿permiso de micrófono denegado?)"
    }
  }
}

/// Graba el micrófono de los lentes cuando iOS lo expone como manos libres Bluetooth (HFP).
/// Nunca usa el micrófono del iPhone: si la ruta no es HFP, falla.
final class HFPMicRecorder: @unchecked Sendable {
  private let engine = AVAudioEngine()
  private let lock = NSLock()
  private var file: AVAudioFile?
  private var url: URL?
  private var framesWritten: AVAudioFramePosition = 0
  private var sampleRate: Double = 0
  private var sumSquares: Double = 0
  private var sampleCount = 0
  private var buffers = 0
  private var formatText = "-"
  private var lastError: String?

  /// Devuelve la descripción de la ruta usada (evidencia: nombre del puerto Bluetooth).
  func start(url: URL) throws -> String {
    let session = AVAudioSession.sharedInstance()
    try session.setCategory(.playAndRecord, mode: .default, options: [.allowBluetoothHFP])
    try session.setActive(true)

    let available = (session.availableInputs ?? [])
      .map { "\($0.portName) [\($0.portType.rawValue)]" }
      .joined(separator: ", ")
    guard let hfp = session.availableInputs?.first(where: { $0.portType == .bluetoothHFP }) else {
      throw HFPError.noGlassesInput(available)
    }
    try session.setPreferredInput(hfp)

    let route = session.currentRoute.inputs
      .map { "\($0.portName) [\($0.portType.rawValue)]" }
      .joined(separator: ", ")
    guard session.currentRoute.inputs.contains(where: { $0.portType == .bluetoothHFP }) else {
      throw HFPError.routeNotHFP(route)
    }

    let input = engine.inputNode
    let format = input.inputFormat(forBus: 0)
    guard format.sampleRate > 0, format.channelCount > 0 else { throw HFPError.badFormat }

    try lock.withLock {
      self.url = url
      framesWritten = 0
      sumSquares = 0
      sampleCount = 0
      buffers = 0
      lastError = nil
      sampleRate = format.sampleRate
      formatText = "\(format)"
      file = try AVAudioFile(
        forWriting: url,
        settings: format.settings,
        commonFormat: format.commonFormat,
        interleaved: format.isInterleaved
      )
    }

    input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
      self?.write(buffer)
    }
    engine.prepare()
    try engine.start()
    return "\(route) @ \(Int(format.sampleRate)) Hz, \(format.channelCount) canal(es)"
  }

  private func write(_ buffer: AVAudioPCMBuffer) {
    lock.lock()
    defer { lock.unlock() }
    buffers += 1
    guard let file else { return }
    do {
      try file.write(from: buffer)
      framesWritten += AVAudioFramePosition(buffer.frameLength)
      AudioMath.accumulate(buffer, sumSquares: &sumSquares, count: &sampleCount)
    } catch {
      lastError = "\(error)"
    }
  }

  func stop() -> AudioStats {
    engine.stop()
    engine.inputNode.removeTap(onBus: 0)
    try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    return lock.withLock {
      file = nil
      let seconds = sampleRate > 0 ? Double(framesWritten) / sampleRate : 0
      return AudioStats(
        url: framesWritten > 0 ? url : nil,
        seconds: seconds,
        rms: AudioMath.rms(sumSquares: sumSquares, count: sampleCount),
        buffersReceived: buffers,
        format: formatText,
        error: lastError
      )
    }
  }
}

// MARK: - Grabación de video a MP4 a partir de los frames del stream de DAT

/// DAT no tiene una API "grabar video en los lentes y devolver el archivo".
/// El video se graba en la app escribiendo los frames del stream (VideoFrame.sampleBuffer)
/// en un MP4 con AVAssetWriter, igual que hace el sample oficial CameraAccess.
final class VideoFileRecorder: @unchecked Sendable {
  enum Outcome {
    case completed(URL, frames: Int, size: String)
    case noFrames(String)
    case failed(String)
  }

  private let lock = NSLock()
  private var targetURL: URL?
  private var armed = false
  private var writer: AVAssetWriter?
  private var input: AVAssetWriterInput?
  private var adaptor: AVAssetWriterInputPixelBufferAdaptor?
  private var t0: CFTimeInterval = 0
  private var frames = 0
  private var sizeText = "-"
  private var lastError: String?

  func start(url: URL) {
    lock.withLock {
      targetURL = url
      writer = nil
      input = nil
      adaptor = nil
      frames = 0
      sizeText = "-"
      lastError = nil
      armed = true
    }
  }

  /// Llamado por cada frame del stream (hilo del SDK).
  func append(_ sampleBuffer: CMSampleBuffer) {
    lock.lock()
    defer { lock.unlock() }
    guard armed, let url = targetURL else { return }
    guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
      lastError = "El frame no trae CVPixelBuffer (¿codec hvc1?). Use videoCodec .raw para esta grabación."
      return
    }
    if writer == nil {
      let width = CVPixelBufferGetWidth(pixelBuffer)
      let height = CVPixelBufferGetHeight(pixelBuffer)
      do {
        try? FileManager.default.removeItem(at: url)
        let w = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let settings: [String: Any] = [
          AVVideoCodecKey: AVVideoCodecType.h264,
          AVVideoWidthKey: width,
          AVVideoHeightKey: height,
        ]
        let i = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
        i.expectsMediaDataInRealTime = true
        guard w.canAdd(i) else {
          lastError = "AVAssetWriter no acepta la entrada de video"
          armed = false
          return
        }
        w.add(i)
        let a = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: i, sourcePixelBufferAttributes: nil)
        guard w.startWriting() else {
          lastError = "startWriting falló: \(String(describing: w.error))"
          armed = false
          return
        }
        w.startSession(atSourceTime: .zero)
        writer = w
        input = i
        adaptor = a
        t0 = CACurrentMediaTime()
        sizeText = "\(width)x\(height)"
      } catch {
        lastError = "No se pudo crear el MP4: \(error)"
        armed = false
        return
      }
    }
    guard let input, let adaptor, input.isReadyForMoreMediaData else { return }
    let pts = CMTime(seconds: CACurrentMediaTime() - t0, preferredTimescale: 600)
    if adaptor.append(pixelBuffer, withPresentationTime: pts) {
      frames += 1
    }
  }

  func finish() async -> Outcome {
    let (w, i, url, count, size, err): (AVAssetWriter?, AVAssetWriterInput?, URL?, Int, String, String?) =
      lock.withLock {
        armed = false
        let snapshot = (writer, input, targetURL, frames, sizeText, lastError)
        writer = nil
        input = nil
        adaptor = nil
        return snapshot
      }
    guard let w, let i, let url else {
      return .noFrames(err ?? "No llegó ningún frame mientras se grababa")
    }
    i.markAsFinished()
    await w.finishWriting()
    if w.status == .completed {
      return .completed(url, frames: count, size: size)
    }
    return .failed("Estado del writer: \(w.status.rawValue) error: \(String(describing: w.error))")
  }
}
