package com.glasseslab.android

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cliente HTTP mínimo de la Messages API de Anthropic.
 *
 * AVISO: guardar la API key en el teléfono solo es aceptable para esta PoC personal. En producción
 * la app debe llamar a un backend propio que guarde la key.
 */
object ClaudeClient {
  const val MODEL = "claude-opus-5-5"

  private const val SYSTEM_PROMPT =
      "Eres un asistente técnico para mecánicos de taller automotriz. Recibes imágenes capturadas " +
          "desde lentes inteligentes del técnico. Describe lo que se ve (componente, estado, posibles " +
          "fallas visibles, lecturas de instrumentos o etiquetas legibles) y sugiere próximos pasos de " +
          "diagnóstico seguros. Si la imagen no permite concluir algo, dilo explícitamente. Responde en español."

  data class Result(
      val text: String,
      val model: String,
      val stopReason: String,
      val inputTokens: Int,
      val outputTokens: Int,
  )

  /** Bloqueante: llamar desde Dispatchers.IO. */
  fun analyze(image: Bitmap, prompt: String, apiKey: String): Result {
    val jpeg = downscaledJpeg(image, 1568, 80)
    val body =
        JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 16000)
            .put("system", SYSTEM_PROMPT)
            .put("output_config", JSONObject().put("effort", "medium"))
            // Si los clasificadores de seguridad rechazan, el servidor reintenta con el modelo recomendado.
            .put("fallbacks", "default")
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("type", "image")
                                            .put(
                                                "source",
                                                JSONObject()
                                                    .put("type", "base64")
                                                    .put("media_type", "image/jpeg")
                                                    .put(
                                                        "data",
                                                        Base64.encodeToString(jpeg, Base64.NO_WRAP))))
                                    .put(JSONObject().put("type", "text").put("text", prompt)))))

    val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.connectTimeout = 30_000
    conn.readTimeout = 180_000
    conn.doOutput = true
    conn.setRequestProperty("x-api-key", apiKey)
    conn.setRequestProperty("anthropic-version", "2023-06-01")
    conn.setRequestProperty("anthropic-beta", "server-side-fallback-2026-07-01")
    conn.setRequestProperty("content-type", "application/json")
    conn.outputStream.use { it.write(body.toString().toByteArray()) }

    val code = conn.responseCode
    val raw =
        (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() } ?: ""
    conn.disconnect()
    if (code != 200) error("HTTP $code: ${raw.take(400)}")

    val json = JSONObject(raw)
    val stopReason = json.optString("stop_reason", "?")
    if (stopReason == "refusal") error("El modelo rechazó la solicitud: ${json.opt("stop_details")}")
    val blocks = json.optJSONArray("content") ?: JSONArray()
    val text =
        (0 until blocks.length())
            .map { blocks.getJSONObject(it) }
            .filter { it.optString("type") == "text" }
            .joinToString("\n") { it.optString("text") }
    if (text.isBlank()) error("Respuesta sin texto: ${raw.take(400)}")
    val usage = json.optJSONObject("usage") ?: JSONObject()
    return Result(
        text = text,
        model = json.optString("model", MODEL),
        stopReason = stopReason,
        inputTokens = usage.optInt("input_tokens"),
        outputTokens = usage.optInt("output_tokens"),
    )
  }

  private fun downscaledJpeg(image: Bitmap, maxEdge: Int, quality: Int): ByteArray {
    val longest = maxOf(image.width, image.height)
    val scaled =
        if (longest > maxEdge) {
          val scale = maxEdge.toFloat() / longest
          Bitmap.createScaledBitmap(
              image, (image.width * scale).toInt(), (image.height * scale).toInt(), true)
        } else image
    return ByteArrayOutputStream().use { out ->
      scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
      out.toByteArray()
    }
  }
}
