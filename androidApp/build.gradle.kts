plugins {
    alias(libs.plugins.android.application)
    // Collects the licenses of Gradle dependencies (from their POMs) for the in-app licenses screen.
    alias(libs.plugins.oss.licenses)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.facet.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    // Same NDK as native/android, so release builds can strip native debug symbols.
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        // PLACEHOLDER — final applicationId is still to be decided (see DEV_TODO.md).
        applicationId = "app.facet"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        // Same ABIs as the C++ core (native/android). Every ARCore Depth phone is arm64;
        // x86_64 is for the emulator. Drops the 32-bit libs that ARCore/AndroidX would add.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // ContrastTest reads the shared design tokens.
    testOptions {
        unitTests.all { it.systemProperty("facet.tokens", rootProject.file("design/tokens/tokens.json").path) }
    }

    // MediaPipe memory-maps its model: keep it uncompressed in the APK.
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ARCore: installed/updated through the Play Store (ArCoreApk) — the app itself has no network access.
    implementation(libs.arcore)
    // High detail runs as expedited / foreground work (survives leaving the app).
    implementation(libs.androidx.work.runtime)

    // On-device object outlines for silhouette carving (model bundled in assets; no network).
    implementation(libs.mediapipe.tasks.vision)

    // Settings › About › Open-source licences (Gradle dependencies, collected by the plugin above).
    implementation(libs.play.services.oss.licenses)

    // JVM unit tests (WCAG contrast of the colour tokens).
    testImplementation(libs.junit)
}

// ---------------------------------------------------------------------------------------------
// The oss-licenses plugin only sees Gradle dependencies. The C++ core's libraries are listed by
// hand: every facet_fetch(<name> …) and every "# facet-license: <names>" line in native/CMakeLists.txt
// needs src/main/assets/licenses/<name>.txt, and the Native libraries screen must show it.
// ---------------------------------------------------------------------------------------------
val verifyNativeLicenses by tasks.registering {
    group = "verification"
    description = "Fails if a native library in native/CMakeLists.txt has no license file in assets/licenses/."
    val cmakeFile = rootProject.layout.projectDirectory.file("native/CMakeLists.txt")
    val licenseDir = layout.projectDirectory.dir("src/main/assets/licenses")
    val screenFile = layout.projectDirectory.file("src/main/kotlin/app/facet/android/ui/settings/NativeLibraries.kt")
    val stamp = layout.buildDirectory.file("verifyNativeLicenses/ok.txt")
    inputs.file(cmakeFile)
    inputs.dir(licenseDir)
    inputs.file(screenFile)
    outputs.file(stamp)
    doLast {
        val cmake = cmakeFile.asFile.readText()
        val fetched = Regex("""^\s*facet_fetch\(\s*([A-Za-z0-9_.+-]+)""", RegexOption.MULTILINE)
            .findAll(cmake).map { it.groupValues[1] }
        val declared = Regex("""^\s*#\s*facet-license:\s*(.+)$""", RegexOption.MULTILINE)
            .findAll(cmake).flatMap { it.groupValues[1].trim().split(Regex("\\s+")) }
        val names = (fetched + declared).toSortedSet()
        if (names.isEmpty()) throw GradleException("verifyNativeLicenses: no native libraries found in ${cmakeFile.asFile}")
        val screen = screenFile.asFile.readText()
        val problems = names.flatMap { name ->
            listOfNotNull(
                "$name: missing ${licenseDir.asFile.name}/$name.txt".takeUnless { licenseDir.file("$name.txt").asFile.isFile },
                "$name: not listed on the Native libraries screen (\"licenses/$name.txt\" in ${screenFile.asFile.name})"
                    .takeUnless { "\"licenses/$name.txt\"" in screen },
            )
        }
        if (problems.isNotEmpty()) {
            throw GradleException("verifyNativeLicenses failed:\n" + problems.joinToString("\n") { "  - $it" })
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText(names.joinToString("\n") + "\n")
    }
}
tasks.named("preBuild") { dependsOn(verifyNativeLicenses) }
