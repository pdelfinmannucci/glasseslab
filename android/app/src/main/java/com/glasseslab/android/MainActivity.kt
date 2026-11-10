package com.glasseslab.android

import android.Manifest.permission.BLUETOOTH
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.CAMERA
import android.Manifest.permission.INTERNET
import android.Manifest.permission.RECORD_AUDIO
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.voiceinvocations.isVoiceInvocationsIntent
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {
  private val vm: LabViewModel by viewModels()
  private var sdkInitialized = false

  // 1) Permisos Android necesarios antes de inicializar el SDK (igual que el sample oficial).
  private val androidPermissions =
      registerForActivityResult(RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
          if (!sdkInitialized) {
            Wearables.initialize(this)
                .onSuccess { sdkInitialized = true }
                .onFailure { error, _ -> vm.log("❌ Wearables.initialize: ${error.description}") }
          }
          if (sdkInitialized) {
            handleMetaAIIntent(intent)
            vm.startMonitoring()
          }
        } else {
          vm.log("❌ Faltan permisos de Bluetooth: concédelos en Ajustes > Apps > GlassesLab")
        }
      }

  // 2) Permisos de los lentes (cámara / micrófono) se conceden en la app Meta AI.
  private var wearablesContinuation: CancellableContinuation<PermissionStatus>? = null
  private val permissionMutex = Mutex()
  private val wearablesPermission =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        wearablesContinuation?.resume(result.getOrDefault(PermissionStatus.Denied))
        wearablesContinuation = null
      }

  suspend fun requestWearablesPermission(permission: Permission): PermissionStatus =
      permissionMutex.withLock {
        suspendCancellableCoroutine { continuation ->
          wearablesContinuation = continuation
          continuation.invokeOnCancellation { wearablesContinuation = null }
          wearablesPermission.launch(permission)
        }
      }

  // 3) Micrófono del teléfono (Android lo exige para capturar audio Bluetooth HFP/SCO).
  private var audioContinuation: CancellableContinuation<Boolean>? = null
  private val recordAudioPermission =
      registerForActivityResult(RequestPermission()) { granted ->
        audioContinuation?.resume(granted)
        audioContinuation = null
      }

  suspend fun requestRecordAudio(): Boolean {
    if (ContextCompat.checkSelfPermission(this, RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      return true
    }
    return permissionMutex.withLock {
      suspendCancellableCoroutine { continuation ->
        audioContinuation = continuation
        continuation.invokeOnCancellation { audioContinuation = null }
        recordAudioPermission.launch(RECORD_AUDIO)
      }
    }
  }

  // 4) Cámara del teléfono/emulador: solo para el modo simulado (alimenta el video de los lentes virtuales).
  private var cameraContinuation: CancellableContinuation<Boolean>? = null
  private val cameraPermission =
      registerForActivityResult(RequestPermission()) { granted ->
        cameraContinuation?.resume(granted)
        cameraContinuation = null
      }

  suspend fun requestPhoneCamera(): Boolean {
    if (ContextCompat.checkSelfPermission(this, CAMERA) == PackageManager.PERMISSION_GRANTED) return true
    return permissionMutex.withLock {
      suspendCancellableCoroutine { continuation ->
        cameraContinuation = continuation
        continuation.invokeOnCancellation { cameraContinuation = null }
        cameraPermission.launch(CAMERA)
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    vm.requestWearablesPermission = ::requestWearablesPermission
    vm.requestRecordAudio = ::requestRecordAudio
    vm.requestPhoneCamera = ::requestPhoneCamera
    checkVoiceLaunch(intent)
    setContent { MaterialTheme { LabScreen(vm, this) } }
  }

  override fun onStart() {
    super.onStart()
    androidPermissions.launch(arrayOf(BLUETOOTH, BLUETOOTH_CONNECT, INTERNET))
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    checkVoiceLaunch(intent)
    if (sdkInitialized) handleMetaAIIntent(intent)
  }

  override fun onDestroy() {
    if (isFinishing) {
      vm.requestWearablesPermission = null
      vm.requestRecordAudio = null
      vm.requestPhoneCamera = null
    }
    super.onDestroy()
  }

  /** Registro iniciado desde la app Meta AI (SDK 1.0.0+). */
  private fun handleMetaAIIntent(intent: Intent?) {
    intent ?: return
    Wearables.handleIntent(intent) { request -> request.continueRegistration(this) }
        .onFailure { error, _ -> vm.log("handleIntent: ${error.description}") }
  }

  private fun checkVoiceLaunch(intent: Intent?) {
    if (intent != null && isVoiceInvocationsIntent(intent)) vm.onVoiceLaunchIntent()
  }
}
