import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

/**
 * Collects an optional greeting audio file dropped in the project root and exposes it
 * (plus the bundled localized fallbacks) as generated raw resources.
 *
 * Supported root filenames: answering_machine_greeting.* / answering_machine.* /
 * greeting.* / answer_machine.* (or a single audio file at the project root).
 */
abstract class GenerateAnsweringMachineGreetingTask : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NAME_ONLY)
  abstract val candidateAudioFiles: ConfigurableFileCollection

  @get:InputFile
  abstract val fallbackEnglish: RegularFileProperty

  @get:InputFile
  abstract val fallbackChinese: RegularFileProperty

  @get:OutputDirectory
  abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun generate() {
    val supported = setOf("wav", "mp3", "m4a", "aac", "ogg", "flac", "opus")
    val preferredNames = setOf(
      "answering_machine_greeting", "answering_machine", "greeting", "answer_machine"
    )
    val candidates = candidateAudioFiles.files
      .filter { it.isFile && it.extension.lowercase() in supported }
      .sortedBy { it.name.lowercase() }
    val custom = candidates.firstOrNull { it.nameWithoutExtension.lowercase() in preferredNames }
      ?: candidates.singleOrNull()

    val rawDir = outputDirectory.get().asFile.resolve("raw")
    rawDir.deleteRecursively()
    rawDir.mkdirs()

    fallbackEnglish.get().asFile.copyTo(
      rawDir.resolve("answering_machine_greeting_default_en.wav"), overwrite = true
    )
    fallbackChinese.get().asFile.copyTo(
      rawDir.resolve("answering_machine_greeting_default_zh.wav"), overwrite = true
    )

    val main = custom ?: fallbackEnglish.get().asFile
    val extension = main.extension.lowercase().ifBlank { "wav" }
    main.copyTo(rawDir.resolve("answering_machine_greeting.$extension"), overwrite = true)
    // A tiny marker resource lets the app distinguish a user-provided greeting
    // from the built-in English fallback without relying on BuildConfig (which
    // can be stale when the configuration cache is warm).
    rawDir.resolve("answering_machine_greeting_custom.txt").writeText(
      if (custom != null) "1" else "0"
    )
    logger.lifecycle(
      "Answering-machine greeting packaged: ${if (custom != null) custom.name else "built-in default"}"
    )
  }
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "io.github.securephoneapps.securedialer"
    minSdk = 24
    targetSdk = 36
    versionCode = 16
    versionName = "1.8.1"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  val isDebugBuild = gradle.startParameter.taskNames.any {
    it.contains("Debug", ignoreCase = true) || it.contains("test", ignoreCase = true)
  }

  splits {
    abi {
      isEnable = !isDebugBuild
      reset()
      include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
      isUniversalApk = true
    }
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      val keystoreFile = file(keystorePath)
      val pass = System.getenv("KEYSTORE_PASSWORD")
      val alias = System.getenv("KEY_ALIAS")
      val keyPass = System.getenv("KEY_PASSWORD")

      if (keystoreFile.exists() && !pass.isNullOrEmpty() && !alias.isNullOrEmpty() && !keyPass.isNullOrEmpty()) {
        storeFile = keystoreFile
        storePassword = pass
        keyAlias = alias
        keyPassword = keyPass
      } else {
        storeFile = file("${rootDir}/debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = true
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  lint {
    checkReleaseBuilds = false
    abortOnError = false
    disable += "ExtraTranslation"
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

val generateAnsweringMachineGreeting =
  tasks.register<GenerateAnsweringMachineGreetingTask>("generateAnsweringMachineGreeting") {
    candidateAudioFiles.from(
      fileTree(rootProject.projectDir) {
        include("*.wav", "*.mp3", "*.m4a", "*.aac", "*.ogg", "*.flac", "*.opus")
      }
    )
    fallbackEnglish.set(
      layout.projectDirectory.file("answering_machine_greeting_default_en.wav")
    )
    fallbackChinese.set(
      layout.projectDirectory.file("answering_machine_greeting_default_zh.wav")
    )
    outputDirectory.set(
      layout.buildDirectory.dir("generated/answeringMachineGreetingRes")
    )
  }

androidComponents {
  onVariants { variant ->
    variant.sources.res?.addGeneratedSourceDirectory(
      generateAnsweringMachineGreeting,
      GenerateAnsweringMachineGreetingTask::outputDirectory
    )
  }
}

configurations.all {
  resolutionStrategy {
    force("org.bouncycastle:bcprov-jdk18on:1.84")
    force("io.netty:netty-codec-http2:4.1.118.Final")
    force("io.netty:netty-codec-http:4.1.118.Final")
    force("io.netty:netty-handler:4.1.118.Final")
    force("io.netty:netty-codec:4.1.118.Final")
    force("io.netty:netty-common:4.1.118.Final")
    force("io.netty:netty-buffer:4.1.118.Final")
    force("io.netty:netty-transport:4.1.118.Final")
    force("org.apache.httpcomponents:httpclient:4.5.14")
    force("org.apache.commons:commons-lang3:3.14.0")
    force("org.jdom:jdom2:2.0.6.1")
    force("org.bitbucket.b_c:jose4j:0.9.6")
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}


// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.paging.runtime)
  implementation(libs.androidx.paging.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.paging)
  implementation(libs.sqlcipher)
  implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  // implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  // implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  ksp(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}
