import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// One version for the whole repository: the root VERSION file, which
// scripts/set-version.mjs stamps from the release tag. The code is maj*1e6 + min*1e3 +
// pat, the scheme the Tauri Android build uses, so an APK built from a later release
// always carries a higher versionCode and Android installs it over the previous one
// instead of refusing it as a downgrade.
val appVersionName: String = rootProject.file("../VERSION").readText().trim()
val appVersionCode: Int = run {
    val parts = appVersionName.split('.')
    require(parts.size == 3 && parts.all { part -> part.isNotEmpty() && part.all { it.isDigit() } }) {
        "VERSION is not x.y.z: " + appVersionName
    }
    parts[0].toInt() * 1_000_000 + parts[1].toInt() * 1_000 + parts[2].toInt()
}

// Production signing is configured outside Git, as for the Tauri Android build:
// android-native/keystore.properties (gitignored) names the store and its passwords, and
// CI writes it from secrets. Without it a release build is test-signed and its published
// file name carries _debug, so a test-signed APK is never passed off as a production one.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) keystorePropertiesFile.inputStream().use { load(it) }
}
val hasReleaseSigning = keystorePropertiesFile.isFile &&
    !keystoreProperties.getProperty("storeFile").isNullOrBlank()

// The debug key is otherwise generated per checkout, and a generated key has a random
// certificate: two CI runs, or a CI run and a local build, would each sign with a
// different key, and Android refuses to install one over the other. A CI secret can
// supply one stable test key instead (android-native/test-keystore.properties, written
// by the workflow from ANDROID_TEST_KEYSTORE_BASE64). It is a test key either way: it
// is never a release key, and an APK it signs is still named _debug.
val testKeystorePropertiesFile = rootProject.file("test-keystore.properties")
val testKeystoreProperties = Properties().apply {
    if (testKeystorePropertiesFile.isFile) testKeystorePropertiesFile.inputStream().use { load(it) }
}
val hasTestSigning = testKeystorePropertiesFile.isFile &&
    !testKeystoreProperties.getProperty("storeFile").isNullOrBlank()

android {
    namespace = "lol.mony.harmony"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.wwpol.brapmony"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        fun literal(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        buildConfigField("String", "DEFAULT_INSTANCE_URL", literal(providers.gradleProperty("harmonyInstanceUrl").getOrElse("")))
        buildConfigField("String", "DEFAULT_INSTANCE_ANON_KEY", literal(providers.gradleProperty("harmonyAnonKey").getOrElse("")))
    }

    // A test key, never a release key: the stable one when the build has it, otherwise
    // the conventional debug key generated into ignored project state. Either way an APK
    // it signs is named _debug.
    signingConfigs.getByName("debug") {
        if (hasTestSigning) {
            storeFile = rootProject.file(testKeystoreProperties.getProperty("storeFile"))
            storePassword = testKeystoreProperties.getProperty("storePassword")
            keyAlias = testKeystoreProperties.getProperty("keyAlias")
            keyPassword = testKeystoreProperties.getProperty("keyPassword")
        } else {
            storeFile = rootProject.file(".gradle/debug.keystore")
        }
    }

    // Configured only when keystore.properties exists, so a checkout without production
    // signing still builds (test-signed) instead of failing on a missing key.
    if (hasReleaseSigning) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Falls back to the test key rather than leaving the APK unsigned: an
            // unsigned release APK cannot be installed at all, and a file that cannot be
            // installed is not a build anybody can try. The name carries _debug.
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE.md", "META-INF/LICENSE-notice.md")
    }
}

// AGP creates only its default user-home debug store automatically. A confined build's
// user-home can be ephemeral, so create the conventional debug key in ignored project state.
val debugStore = rootProject.layout.projectDirectory.file(".gradle/debug.keystore")
val createLocalDebugKeystore = tasks.register<Exec>("createLocalDebugKeystore") {
    val destination = debugStore.asFile
    val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
    commandLine("${System.getProperty("java.home")}/bin/keytool$suffix", "-genkeypair", "-noprompt",
        "-keystore", destination.absolutePath, "-storepass", "android", "-keypass", "android",
        "-alias", "androiddebugkey", "-dname", "CN=Android Debug,O=Android,C=US",
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000")
    outputs.file(destination)
    onlyIf { !destination.exists() }
}
tasks.matching { it.name.startsWith("validateSigningDebug") }.configureEach {
    if (!hasTestSigning) dependsOn(createLocalDebugKeystore)
}

// A release build signed with the fallback test key still has to find that key.
tasks.matching { it.name.startsWith("validateSigningRelease") }.configureEach {
    if (!hasReleaseSigning && !hasTestSigning) dependsOn(createLocalDebugKeystore)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.coil.compose)
    // LiveKit is the transport Harmony's own voice stack speaks; the web client uses its JS
    // SDK against the same rooms, so a call placed here is a call the web client can hear.
    implementation(libs.livekit.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    // The headless Compose render harness draws real composables through Robolectric's native
    // graphics so a screen's pixels can be captured without an emulator.
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
