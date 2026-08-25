import java.io.ByteArrayOutputStream
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val nativeOutputDir = layout.projectDirectory.dir("src/main/jniLibs")
val configuredNdkVersion = providers.environmentVariable("ANDROID_NDK_HOME").orNull
    ?.let { file(it) }
    ?.takeIf { it.isDirectory }
    ?.name
    ?: file("/config/.tools/android-sdk/ndk").listFiles()
        ?.filter { it.isDirectory && it.name.matches(Regex("\\d+\\.\\d+\\.\\d+")) }
        ?.maxByOrNull { it.name }
        ?.name
    ?: "27.0.12077973"
val nativeBinaries = listOf(
    nativeOutputDir.file("arm64-v8a/libsiano-ts.so"),
    nativeOutputDir.file("armeabi-v7a/libsiano-ts.so")
)

val sianoUserlandDir = providers.gradleProperty("sianoUserlandDir")
    .orElse("/config/GitHub/siano-userland")

val prepareSianoBinaries = tasks.register("prepareSianoBinaries") {
    inputs.property("sianoUserlandDir", sianoUserlandDir)
    // inputs.files, not inputs.file: the path may be absent, and the friendlier
    // diagnostic below should be what the user sees.
    inputs.files(sianoUserlandDir.map { file("$it/scripts/build-android.sh") })
    outputs.files(nativeBinaries)

    doLast {
        val userlandDir = file(sianoUserlandDir.get())
        val script = userlandDir.resolve("scripts/build-android.sh")
        if (!script.isFile) {
            throw GradleException(
                "siano-userland build script not found at $script. " +
                    "Set -PsianoUserlandDir=/path/to/siano-userland."
            )
        }

        val ndkRoot = providers.environmentVariable("ANDROID_NDK_HOME").orNull
            ?: providers.environmentVariable("ANDROID_NDK_ROOT").orNull
            ?: providers.environmentVariable("NDK").orNull
            ?: file("/config/.tools/android-sdk/ndk").listFiles()
                ?.filter { it.isDirectory && it.name.matches(Regex("\\d+\\.\\d+\\.\\d+")) }
                ?.maxByOrNull { it.name }
                ?.absolutePath
        if (ndkRoot == null || !file(ndkRoot).isDirectory) {
            throw GradleException(
                "Android NDK r26+ is required to build siano-ts. " +
                    "Install it or set ANDROID_NDK_HOME=/path/to/ndk."
            )
        }

        fun buildAbi(abi: String, outputName: String) {
            project.exec {
                workingDir(userlandDir)
                commandLine("/bin/sh", script.absolutePath)
                environment("ANDROID_NDK_HOME", ndkRoot)
                environment("ANDROID_ABI", abi)
            }
            val built = userlandDir.resolve("build/android-$abi/siano-ts")
            if (!built.isFile) {
                throw GradleException("siano-ts build completed without producing $built")
            }
            val destination = nativeOutputDir.dir(outputName.substringBefore('/'))
                .file(outputName.substringAfter('/')).asFile
            destination.parentFile.mkdirs()
            built.copyTo(destination, overwrite = true)
            destination.setExecutable(true, false)
        }

        buildAbi("aarch64", "arm64-v8a/libsiano-ts.so")
        buildAbi("armv7a", "armeabi-v7a/libsiano-ts.so")
    }
}

val firmwareAsset = layout.projectDirectory.file("src/main/assets/isdbt_rio.inp")
val prepareFirmware = tasks.register("prepareFirmware") {
    outputs.file(firmwareAsset)
    doLast {
        val dest = firmwareAsset.asFile
        dest.parentFile.mkdirs()
        if (dest.isFile && dest.length() == 85840L) {
            return@doLast
        }
        dest.outputStream().use { out ->
            URI("https://git.kernel.org/pub/scm/linux/kernel/git/firmware/linux-firmware.git/plain/isdbt_rio.inp")
                .toURL()
                .openStream()
                .use { stream -> stream.copyTo(out) }
        }
        val checksum = ByteArrayOutputStream()
        exec {
            commandLine("md5sum", dest.absolutePath)
            standardOutput = checksum
        }
        val md5 = checksum.toString().trim().substringBefore(' ')
        if (md5 != "9b762c1808fd8da81bbec3e24ddb04a3") {
            dest.delete()
            throw GradleException("isdbt_rio.inp checksum mismatch: $md5")
        }
    }
}

plugins.withId("com.android.application") {
    tasks.matching { it.name == "preBuild" || it.name.endsWith("JniLibFolders") }
        .configureEach {
            dependsOn(prepareSianoBinaries)
            dependsOn(prepareFirmware)
        }
}

// Set by the release workflow. Without it the release build stays unsigned, so
// a local `assembleRelease` never silently produces something installable.
val releaseKeystore = providers.environmentVariable("KEYSTORE_FILE").orNull

android {
    namespace = "dev.khronos31.mirakc"
    compileSdk = 34
    ndkVersion = configuredNdkVersion

    defaultConfig {
        applicationId = "dev.khronos31.mirakc"
        minSdk = 24
        targetSdk = 34
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        externalNativeBuild {
            cmake { cppFlags += "-std=c++17" }
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }

    packagingOptions {
        doNotStrip("**/*.so")
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}
