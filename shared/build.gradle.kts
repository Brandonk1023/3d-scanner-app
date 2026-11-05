import java.util.Properties
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    // Room's generated FacetDatabaseConstructor is an expect/actual object.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    android {
        namespace = "app.facet.shared"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
        // Runs commonTest on the JVM too (fast feedback; `:shared:allTests` includes it).
        withHostTestBuilder {}
    }

    // Apple Silicon only: device + arm64 Simulator.
    val appleTargets = listOf(iosArm64(), iosSimulatorArm64())
    appleTargets.forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
        configureFacetCoreInterop(target)
    }

    sourceSets {
        all { languageSettings.optIn("kotlin.time.ExperimentalTime") }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.okio)
            api(libs.androidx.lifecycle.viewmodel)
            api(libs.androidx.datastore.preferences.core)
            // Gallery: scan records in Room (KMP) on the bundled SQLite, so Android and iOS behave the same.
            api(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okio.fakefilesystem)
        }
        androidMain.dependencies {
            implementation(project(":nativeAndroid"))
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
        }
    }
}

// Room's generated code (DAO implementations, FacetDatabaseConstructor) for every target.
dependencies {
    listOf("kspAndroid", "kspIosArm64", "kspIosSimulatorArm64").forEach { add(it, libs.androidx.room.compiler) }
}

// Exported schemas are committed so future versions can write and test migrations.
room {
    schemaDirectory("$projectDir/schemas")
}

// ---------------------------------------------------------------------------------------------
// C++ core for Apple targets: build native/ as a static library per target with CMake, then
// bind it with cinterop (src/nativeInterop/cinterop/facet_mesh.def). Uses the CMake + Ninja that
// ship with the Android SDK so no extra tools are needed.
// ---------------------------------------------------------------------------------------------

fun androidSdkDir(): File {
    val props = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(props::load)
    val path = props.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
        ?: error("Android SDK not found: set sdk.dir in local.properties or ANDROID_HOME")
    return File(path)
}

fun configureFacetCoreInterop(target: KotlinNativeTarget) {
    val nativeDir = rootProject.layout.projectDirectory.dir("native")
    val cmakeBin = File(androidSdkDir(), "cmake/${libs.versions.cmake.get()}/bin")
    val sysroot = when (target.name) {
        "iosArm64" -> "iphoneos"
        "iosSimulatorArm64" -> "iphonesimulator"
        else -> error("Unsupported Apple target ${target.name}")
    }
    val outDir = layout.buildDirectory.dir("facet-core/${target.name}")
    val suffix = target.name.replaceFirstChar { it.uppercase() }

    val buildTask = tasks.register<Exec>("buildFacetCore$suffix") {
        group = "native"
        description = "Builds libfacet_core_all.a (core + pinned third-party libraries) for ${target.name}"
        inputs.dir(nativeDir.dir("src"))
        inputs.dir(nativeDir.dir("include"))
        inputs.dir(nativeDir.dir("fusion"))
        inputs.dir(nativeDir.dir("cmake"))
        inputs.file(nativeDir.file("CMakeLists.txt"))
        outputs.file(outDir.map { it.file("libfacet_core_all.a") })
        // Xcode build phases export SDK/arch variables that would confuse a nested CMake build.
        listOf("SDKROOT", "ARCHS", "PLATFORM_NAME", "IPHONEOS_DEPLOYMENT_TARGET", "CC", "CXX", "LD")
            .forEach { environment.remove(it) }
        val out = outDir.get().asFile.absolutePath
        val cmake = File(cmakeBin, "cmake").absolutePath
        commandLine(
            "sh", "-c",
            listOf(
                "'$cmake' -S '${nativeDir.asFile.absolutePath}' -B '$out' -G Ninja",
                "-DCMAKE_MAKE_PROGRAM='${File(cmakeBin, "ninja").absolutePath}'",
                "-DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT=$sysroot -DCMAKE_OSX_ARCHITECTURES=arm64",
                "-DCMAKE_OSX_DEPLOYMENT_TARGET=16.0 -DCMAKE_BUILD_TYPE=Release -DFACET_BUILD_TESTS=OFF",
                "-DCMAKE_ARCHIVE_OUTPUT_DIRECTORY='$out' > /dev/null",
                "&& '$cmake' --build '$out' --target facet_core_bundle",
            ).joinToString(" "),
        )
    }

    target.compilations.getByName("main").cinterops.create("facet_mesh") {
        definitionFile.set(project.file("src/nativeInterop/cinterop/facet_mesh.def"))
        includeDirs(nativeDir.dir("include"))
        extraOpts("-libraryPath", outDir.get().asFile.absolutePath)
        tasks.named(interopProcessingTaskName) { dependsOn(buildTask) }
    }
}
