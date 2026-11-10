package com.glasseslab.android

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

enum class TestStatus(val label: String) {
  NOT_RUN("NO EJECUTADA"),
  PASS("PASÓ"),
  FAIL("FALLÓ"),
}

data class TestResult(
    val id: String,
    val title: String,
    val criterion: String,
    val status: TestStatus = TestStatus.NOT_RUN,
    val detail: String = "",
    val evidenceFile: String? = null,
    val timestamp: String? = null,
)

object TestCatalog {
  val initial: List<TestResult> =
      listOf(
          TestResult("R0", "Registro de la app en Meta AI", "registrationState == REGISTERED"),
          TestResult("D0", "Detección del modelo de lentes", "deviceType == META_GLASSES y sin DEVICE_UPDATE_REQUIRED"),
          TestResult("S1", "Streaming de video en vivo", "StreamState STREAMING y ≥150 frames"),
          TestResult("F1", "Foto durante streaming (stream.capturePhoto)", "Foto recibida, decodificable y guardada"),
          TestResult("F2", "Foto HQ independiente (camera.photo, experimental)", "PhotoCaptureData decodificable y guardado"),
          TestResult("V1", "Grabación de video (HEVC → MP4)", "MP4 con duración ≥ 3 s"),
          TestResult("A1", "Audio PCM por DAT (experimental, requiere streaming)", "WAV ≥ 3 s con RMS > 0.003"),
          TestResult("A2", "Micrófono de los lentes por Bluetooth (HFP/SCO)", "Ruta Bluetooth SCO, WAV ≥ 3 s, RMS > 0.003"),
          TestResult("I1", "Inputs: botón de captura y panel táctil (experimental)", "≥1 evento Capture y ≥1 táctil"),
          TestResult("M1", "Motion: IMU de los lentes (experimental)", "≥90 muestras; |aceleración| media 7–12.5 m/s²"),
          TestResult("SP1", "Speech: transcripción en los lentes (experimental)", "≥1 transcripción final no vacía"),
          TestResult("VI1", "Voice Invocations: \"Hey Meta, start …\" (experimental)", "LaunchApp recibido y sendSuccess entregado"),
          TestResult("IA1", "Envío de la foto capturada a la API de Claude", "HTTP 200, stop_reason ≠ refusal, texto no vacío"),
      )
}

/** Evidencia en filesDir/GlassesLab. Se comparte como .zip con el botón de la app. */
class EvidenceStore(private val context: Context) {
  val root: File = File(context.filesDir, "GlassesLab").apply { mkdirs() }
  val captures: File = File(root, "captures").apply { mkdirs() }
  private val logFile = File(root, "evidence.jsonl")
  private val resultsFile = File(root, "results.json")

  fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

  fun iso(): String =
      SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
          .apply { timeZone = TimeZone.getDefault() }
          .format(Date())

  fun newCaptureFile(prefix: String, ext: String): File = File(captures, "${prefix}_${stamp()}.$ext")

  @Synchronized
  fun append(event: String, fields: Map<String, String>) {
    val json = JSONObject()
    fields.forEach { (k, v) -> json.put(k, v) }
    json.put("event", event)
    json.put("time", iso())
    logFile.appendText(json.toString() + "\n")
  }

  @Synchronized
  fun save(results: List<TestResult>) {
    val array = JSONArray()
    results.forEach { r ->
      array.put(
          JSONObject()
              .put("id", r.id)
              .put("title", r.title)
              .put("criterion", r.criterion)
              .put("status", r.status.name)
              .put("detail", r.detail)
              .put("evidenceFile", r.evidenceFile ?: JSONObject.NULL)
              .put("timestamp", r.timestamp ?: JSONObject.NULL))
    }
    resultsFile.writeText(array.toString(2))
  }

  fun load(): List<TestResult>? {
    if (!resultsFile.exists()) return null
    return runCatching {
          val array = JSONArray(resultsFile.readText())
          val saved =
              (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                TestResult(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    criterion = o.getString("criterion"),
                    status = TestStatus.valueOf(o.getString("status")),
                    detail = o.optString("detail"),
                    evidenceFile = if (o.isNull("evidenceFile")) null else o.getString("evidenceFile"),
                    timestamp = if (o.isNull("timestamp")) null else o.getString("timestamp"),
                )
              }
          TestCatalog.initial.map { base -> saved.firstOrNull { it.id == base.id } ?: base }
        }
        .getOrNull()
  }

  /** Empaqueta la carpeta de evidencia en un .zip dentro de cacheDir/evidence (compartible vía FileProvider). */
  fun makeZip(): File {
    val outDir = File(context.cacheDir, "evidence").apply { mkdirs() }
    val zip = File(outDir, "GlassesLab-evidencia-${stamp()}.zip")
    ZipOutputStream(FileOutputStream(zip)).use { out ->
      root.walkTopDown()
          .filter { it.isFile }
          .forEach { file ->
            out.putNextEntry(ZipEntry("GlassesLab/" + file.relativeTo(root).path.replace('\\', '/')))
            file.inputStream().use { it.copyTo(out) }
            out.closeEntry()
          }
    }
    return zip
  }
}
