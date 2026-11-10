import java.net.URI
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

val localProperties =
    Properties().apply {
      val file = rootDir.resolve("local.properties")
      if (file.exists()) file.inputStream().use { load(it) }
    }

// Piezas multimedia del sample oficial CameraAccess (decodificador HEVC, grabador MP4 y micrófono
// HFP). Se descargan al compilar, fijadas al commit "Release 1.0.1", en lugar de copiarlas al repo.
val metaSampleCommit = "f4371bc9f90249d51eb07d7f8a61e8d829294f16"
val metaSampleFiles =
    listOf("AudioInputHandler.kt", "HevcDecoder.kt", "VideoCaptureHandler.kt", "VideoRecorder.kt")
val metaSampleDir = layout.buildDirectory.dir("generated/metaSample/kotlin")

val fetchMetaSampleMedia by
    tasks.registering {
      val outDir = metaSampleDir
      val commit = metaSampleCommit
      val files = metaSampleFiles
      outputs.dir(outDir)
      doLast {
        val pkgDir =
            outDir.get().asFile.resolve("com/meta/wearable/dat/externalsampleapps/cameraaccess/stream")
        pkgDir.mkdirs()
        files.forEach { name ->
          val target = pkgDir.resolve(name)
          if (!target.exists()) {
            val url =
                URI(
                        "https://raw.githubusercontent.com/meta/meta-wearables-dat-android/$commit/" +
                            "samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/" +
                            "externalsampleapps/cameraaccess/stream/$name")
                    .toURL()
            target.writeBytes(url.readBytes())
          }
        }
      }
    }

android {
  namespace = "com.glasseslab.android"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.glasseslab.android"
    // Android 12+: el sample oficial lo exige para enrutar el micrófono Bluetooth (setCommunicationDevice)
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"

    // Developer Mode de Meta AI: vacíos = sin atestación. Para release channels, ponlos en local.properties.
    manifestPlaceholders["mwdat_application_id"] =
        localProperties.getProperty("mwdat_application_id", "")
    manifestPlaceholders["mwdat_client_token"] =
        localProperties.getProperty("mwdat_client_token", "")
  }

  sourceSets { getByName("main") { java.srcDir(metaSampleDir) } }

  buildTypes { release { isMinifyEnabled = false } }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

tasks.named("preBuild") { dependsOn(fetchMetaSampleMedia) }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.material3)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.mwdat.inputs)
  implementation(libs.mwdat.motion)
  implementation(libs.mwdat.speech)
}
