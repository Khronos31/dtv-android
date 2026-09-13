import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersionText = file("VERSION").readText().trim()
val appVersionParts = Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$""").matchEntire(appVersionText)
    ?: throw GradleException("mirakc/VERSION must hold a semantic version such as 1.2.3, found \"$appVersionText\"")
val (appMajor, appMinor, appPatch) = appVersionParts.destructured
val appVersionCode = appMajor.toInt() * 10000 + appMinor.toInt() * 100 + appPatch.toInt()

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
val mirakcAribBinaries = listOf(
    nativeOutputDir.file("arm64-v8a/libmirakc-arib.so"),
    nativeOutputDir.file("armeabi-v7a/libmirakc-arib.so")
)

val sianoUserlandDir = providers.gradleProperty("sianoUserlandDir")
    .orElse("/config/GitHub/siano-userland")
val sianoBuildScript = sianoUserlandDir.map { file(it).resolve("scripts/build-android.sh") }
val sianoPinnedRef = "d4f8930ab56d13c479037f2e242461062d96c127"

val mirakcAribSourceDir = providers.gradleProperty("mirakcAribSourceDir")
    .orElse(layout.projectDirectory.dir("../.work/mirakc-arib-0.24.37").asFile.absolutePath)
val mirakcAribBuildScript = layout.projectDirectory.file("../tools/mirakc-arib/build-android.sh")
val mirakcAribBootstrapScript = layout.projectDirectory.file("../tools/mirakc-arib/bootstrap-autotools.sh")
val mirakcAribToolchainFile = layout.projectDirectory.file("../tools/mirakc-arib/android.toolchain.cmake")
val mirakcAribVerifierScript = layout.projectDirectory.file("../tools/mirakc-arib/verify-android-elf.sh")
val mirakcAribAndroidPatch = layout.projectDirectory.file("../tools/mirakc-arib/patches/tsduck-android.patch")
val sianoAdapterSource = layout.projectDirectory.file("src/main/cpp/siano_adapter.cpp")
val sianoAdapterCmake = layout.projectDirectory.file("src/main/cpp/CMakeLists.txt")
val sianoAdapterVerifier = layout.projectDirectory.file("../tools/mirakc/verify-android-elf.sh")
val androidNdkRoot = providers.environmentVariable("ANDROID_NDK_HOME")
    .orElse(providers.environmentVariable("ANDROID_NDK_ROOT"))
    .orElse("/config/.tools/android-sdk/ndk/$configuredNdkVersion")

val mirakcBinaries = listOf(
    nativeOutputDir.file("arm64-v8a/libmirakc.so"),
    nativeOutputDir.file("armeabi-v7a/libmirakc.so")
)
val mirakcPinnedRef = "b7a20d75d95595e0bca83dfb1b5473cfa5be6a93"
val mirakcSourceDir = providers.gradleProperty("mirakcSourceDir")
    .orElse(layout.projectDirectory.dir("../.work/mirakc-3.4.85").asFile.absolutePath)
val mirakcBuildScript = layout.projectDirectory.file("../tools/mirakc/build-android.sh")
val mirakcVerifierScript = layout.projectDirectory.file("../tools/mirakc/verify-android-elf.sh")

val prepareMirakcAribBinaries = tasks.register("prepareMirakcAribBinaries") {
    inputs.property("mirakcAribSourceDir", mirakcAribSourceDir)
    inputs.files(
        mirakcAribBuildScript,
        mirakcAribBootstrapScript,
        mirakcAribToolchainFile,
        mirakcAribVerifierScript,
        mirakcAribAndroidPatch
    )
    outputs.files(mirakcAribBinaries)

    doLast {
        val script = mirakcAribBuildScript.asFile
        if (!script.isFile) {
            throw GradleException("mirakc-arib build harness not found at $script")
        }
        val sourceDir = file(mirakcAribSourceDir.get())
        fun buildAbi(abi: String, outputName: String) {
            val destination = nativeOutputDir.dir(outputName.substringBefore('/'))
                .file(outputName.substringAfter('/')).asFile
            project.exec {
                workingDir(project.rootDir)
                commandLine("/bin/sh", script.absolutePath)
                environment("MIRAKC_ARIB_ABI", abi)
                environment("MIRAKC_ARIB_SOURCE_DIR", sourceDir.absolutePath)
                environment("MIRAKC_ARIB_BUILD_DIR", project.rootDir.resolve(".work/build-mirakc-arib-$abi").absolutePath)
                environment("MIRAKC_ARIB_OUTPUT", destination.absolutePath)
            }
        }
        buildAbi("arm64-v8a", "arm64-v8a/libmirakc-arib.so")
        buildAbi("armeabi-v7a", "armeabi-v7a/libmirakc-arib.so")
    }
}

val prepareSianoAdapterBinaries = tasks.register("prepareSianoAdapterBinaries") {
    inputs.files(sianoAdapterSource, sianoAdapterCmake, sianoAdapterVerifier)
    outputs.files(
        nativeOutputDir.file("arm64-v8a/libmirakc-siano-adapter.so"),
        nativeOutputDir.file("armeabi-v7a/libmirakc-siano-adapter.so")
    )

    doLast {
        val ndk = file(androidNdkRoot.get())
        if (!ndk.isDirectory) throw GradleException("Android NDK not found at $ndk")
        fun buildAbi(abi: String) {
            val buildDir = project.rootDir.resolve(".work/build-siano-adapter-$abi")
            val destination = nativeOutputDir.dir(abi).file("libmirakc-siano-adapter.so").asFile
            project.exec {
                workingDir(project.rootDir)
                commandLine(
                    "cmake", "-S", sianoAdapterSource.asFile.parent,
                    "-B", buildDir.absolutePath, "-G", "Ninja",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DCMAKE_TOOLCHAIN_FILE=${ndk.resolve("build/cmake/android.toolchain.cmake")}",
                    "-DANDROID_ABI=$abi", "-DANDROID_PLATFORM=android-24",
                    "-DANDROID_STL=c++_static",
                    "-DMIRAKC_BUILD_SIANO_ADAPTER=ON"
                )
            }
            project.exec {
                workingDir(project.rootDir)
                commandLine("ninja", "-C", buildDir.absolutePath, "siano_adapter")
            }
            val built = buildDir.resolve("libmirakc-siano-adapter.so")
            if (!built.isFile) throw GradleException("Siano adapter build produced no $built")
            destination.parentFile.mkdirs()
            built.copyTo(destination, overwrite = true)
            destination.setExecutable(true, false)
            project.exec {
                commandLine(ndk.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip").absolutePath,
                    "--strip-unneeded", destination.absolutePath)
            }
            project.exec {
                commandLine("/bin/sh", sianoAdapterVerifier.asFile.absolutePath, destination.absolutePath, abi)
            }
        }
        buildAbi("arm64-v8a")
        buildAbi("armeabi-v7a")
    }
}

val mirakcSourceState = mirakcSourceDir.map { directoryName ->
    val directory = file(directoryName)
    if (!directory.isDirectory) {
        "missing"
    } else {
        val result = gitOutput(directory, "rev-parse", "HEAD")
        if (result.first != 0 || result.second.isBlank()) {
            "unavailable"
        } else {
            val worktree = if (gitOutput(directory, "diff", "--quiet").first == 0) "clean" else "dirty"
            val staged = if (gitOutput(directory, "diff", "--cached", "--quiet").first == 0) "clean" else "dirty"
            "${result.second}:worktree=$worktree:staged=$staged"
        }
    }
}
val mirakcTrackedSourceFiles = mirakcSourceDir.map { directoryName ->
    val directory = file(directoryName)
    if (!directory.isDirectory) {
        project.files()
    } else {
        val result = gitOutput(
            directory,
            "ls-files",
            "-z",
            "--",
            "Cargo.lock",
            "Cargo.toml",
            "actlet/**",
            "actlet-derive/**",
            "axum-extract/**",
            "chrono-jst/**",
            "mirakc/**",
            "mirakc-core/**",
            "mirakc-timeshift-fs/**"
        )
        if (result.first != 0) {
            project.files()
        } else {
            project.files(
                result.second.split('\u0000')
                    .filter { it.isNotEmpty() }
                    .map { directory.resolve(it) }
            )
        }
    }
}

val prepareMirakcBinary = tasks.register("prepareMirakcBinary") {
    inputs.property("mirakcSourceDir", mirakcSourceDir)
    inputs.property("mirakcPinnedRef", mirakcPinnedRef)
    inputs.property("mirakcSourceState", mirakcSourceState)
    inputs.files(mirakcBuildScript, mirakcVerifierScript)
    inputs.files(mirakcTrackedSourceFiles)
    outputs.files(mirakcBinaries)

    doLast {
        val sourceDir = file(mirakcSourceDir.get())
        val script = mirakcBuildScript.asFile
        if (!script.isFile) {
            throw GradleException("mirakc Android build harness not found at $script")
        }

        fun buildAbi(abi: String) {
            val outputDir = project.rootDir.resolve(".work/mirakc-output-$abi")
            outputDir.mkdirs()
            project.exec {
                workingDir(project.rootDir)
                commandLine("/bin/sh", script.absolutePath)
                environment("MIRAKC_SOURCE_URL", "https://github.com/mirakc/mirakc.git")
                environment("MIRAKC_SOURCE_REF", mirakcPinnedRef)
                environment("MIRAKC_SOURCE_DIR", sourceDir.absolutePath)
                environment("MIRAKC_BUILD_DIR", project.rootDir.resolve(".work/build-mirakc-$abi").absolutePath)
                environment("MIRAKC_OUTPUT_DIR", outputDir.absolutePath)
                environment("ANDROID_ABI", abi)
            }
            val built = outputDir.resolve("mirakc-$abi")
            if (!built.isFile) {
                throw GradleException("mirakc build completed without producing $built")
            }
            val destination = nativeOutputDir.dir(abi).file("libmirakc.so").asFile
            destination.parentFile.mkdirs()
            built.copyTo(destination, overwrite = true)
            destination.setExecutable(true, false)
        }

        buildAbi("arm64-v8a")
        buildAbi("armeabi-v7a")
    }
}

val px4Binaries = listOf(
    nativeOutputDir.file("arm64-v8a/libpx4d.so"),
    nativeOutputDir.file("arm64-v8a/libpx4-ts.so"),
    nativeOutputDir.file("arm64-v8a/libpx4ctl.so"),
    nativeOutputDir.file("armeabi-v7a/libpx4d.so"),
    nativeOutputDir.file("armeabi-v7a/libpx4-ts.so"),
    nativeOutputDir.file("armeabi-v7a/libpx4ctl.so")
)
val px4PinnedRef = "639e65feee7c9f503d44023edd9ab9bba12d5d74"
val px4UserlandDir = providers.gradleProperty("px4UserlandDir")
    .orElse("/config/GitHub/px4-userland")
val px4BuildScript = px4UserlandDir.map { file(it).resolve("scripts/build-android.sh") }

fun gitOutput(directory: java.io.File, vararg args: String): Pair<Int, String> {
    val command = mutableListOf("git", "-C", directory.absolutePath)
    command.addAll(args)
    return try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor() to output
    } catch (error: Exception) {
        1 to (error.message ?: error.javaClass.simpleName)
    }
}

// These providers are evaluated only when Gradle snapshots task inputs.  A
// missing or invalid checkout therefore produces the task's friendly
// diagnostic instead of failing during project configuration.
val px4SourceState = px4UserlandDir.map { directoryName ->
    val directory = file(directoryName)
    if (!directory.isDirectory) {
        "missing"
    } else {
        val result = gitOutput(directory, "rev-parse", "HEAD")
        if (result.first != 0 || result.second.isBlank()) {
            "unavailable"
        } else {
            val worktree = if (gitOutput(directory, "diff", "--quiet").first == 0) {
                "clean"
            } else {
                "dirty"
            }
            val staged = if (gitOutput(directory, "diff", "--cached", "--quiet").first == 0) {
                "clean"
            } else {
                "dirty"
            }
            "${result.second}:worktree=$worktree:staged=$staged"
        }
    }
}
val px4TrackedSourceFiles = px4UserlandDir.map { directoryName ->
    val directory = file(directoryName)
    if (!directory.isDirectory) {
        project.files()
    } else {
        // Pathspecs keep documentation, .git metadata, and any build output
        // out of the Gradle input snapshot while covering all compiled code,
        // build configuration, and helper scripts.
        val result = gitOutput(
            directory,
            "ls-files",
            "-z",
            "--",
            "CMakeLists.txt",
            "VERSION",
            "cmake/**",
            "scripts/**",
            "userland/**"
        )
        if (result.first != 0) {
            project.files()
        } else {
            project.files(
                result.second.split('\u0000')
                    .filter { it.isNotEmpty() }
                    .map { directory.resolve(it) }
            )
        }
    }
}

val preparePx4Binaries = tasks.register("preparePx4Binaries") {
    inputs.property("px4UserlandDir", px4UserlandDir)
    inputs.property("px4PinnedRef", px4PinnedRef)
    inputs.property("px4SourceState", px4SourceState)
    inputs.files(px4BuildScript)
    inputs.files(px4TrackedSourceFiles)
    outputs.files(px4Binaries)

    doLast {
        val userlandDir = file(px4UserlandDir.get())
        if (!userlandDir.isDirectory) {
            throw GradleException(
                "px4-userland directory not found at $userlandDir. " +
                    "Set -Ppx4UserlandDir=/path/to/px4-userland."
            )
        }
        val script = userlandDir.resolve("scripts/build-android.sh")
        if (!script.isFile) {
            throw GradleException(
                "px4-userland build script not found at $script. " +
                    "Set -Ppx4UserlandDir=/path/to/px4-userland."
            )
        }

        val head = gitOutput(userlandDir, "rev-parse", "HEAD")
        if (head.first != 0) {
            throw GradleException(
                "px4-userland is not a Git checkout at $userlandDir: ${head.second}"
            )
        }
        if (head.second != px4PinnedRef) {
            throw GradleException(
                "px4-userland HEAD mismatch at $userlandDir: expected $px4PinnedRef, " +
                    "found ${head.second}"
            )
        }
        if (gitOutput(userlandDir, "diff", "--quiet").first != 0 ||
            gitOutput(userlandDir, "diff", "--cached", "--quiet").first != 0
        ) {
            throw GradleException(
                "px4-userland has tracked or staged changes at $userlandDir; " +
                    "commit or discard them before building (untracked build output is allowed)."
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
                "Android NDK r26+ is required to build px4-userland. " +
                    "Install it or set ANDROID_NDK_HOME=/path/to/ndk."
            )
        }

        fun buildAbi(abi: String) {
            val outputDir = project.rootDir.resolve(".work/px4-userland-android-$abi")
            outputDir.mkdirs()
            project.exec {
                workingDir(userlandDir)
                commandLine("/bin/sh", script.absolutePath, "--abi", abi, "--output", outputDir.absolutePath)
                environment("ANDROID_NDK_HOME", ndkRoot)
                environment("ANDROID_ABI", abi)
            }

            for (binary in listOf("px4d", "px4-ts", "px4ctl")) {
                val built = outputDir.resolve("$binary-$abi")
                if (!built.isFile) {
                    throw GradleException(
                        "px4-userland build completed without producing $built"
                    )
                }
                val destination = nativeOutputDir.dir(abi).file("lib$binary.so").asFile
                destination.parentFile.mkdirs()
                built.copyTo(destination, overwrite = true)
                destination.setExecutable(true, false)
            }
        }

        buildAbi("arm64-v8a")
        buildAbi("armeabi-v7a")
    }
}

val sianoSourceState = sianoUserlandDir.map { directoryName ->
    val directory = file(directoryName)
    if (!directory.isDirectory) {
        "missing"
    } else {
        val result = gitOutput(directory, "rev-parse", "HEAD")
        if (result.first != 0 || result.second.isBlank()) {
            "unavailable"
        } else {
            val worktree = if (gitOutput(directory, "diff", "--quiet").first == 0) {
                "clean"
            } else {
                "dirty"
            }
            val staged = if (gitOutput(directory, "diff", "--cached", "--quiet").first == 0) {
                "clean"
            } else {
                "dirty"
            }
            "${result.second}:worktree=$worktree:staged=$staged"
        }
    }
}
val sianoTrackedSourceFiles = sianoUserlandDir.map { directoryName ->
    val directory = file(directoryName)
    if (!directory.isDirectory) {
        project.files()
    } else {
        // Include only tracked files used to compile/install siano-ts.  This
        // deliberately excludes .git metadata and ignored build output.
        val result = gitOutput(
            directory,
            "ls-files",
            "-z",
            "--",
            "Makefile",
            "VERSION",
            "*.c",
            "*.h",
            "scripts/build-android.sh",
            "scripts/verify-android-elf.sh"
        )
        if (result.first != 0) {
            project.files()
        } else {
            project.files(
                result.second.split('\u0000')
                    .filter { it.isNotEmpty() }
                    .map { directory.resolve(it) }
            )
        }
    }
}

val prepareSianoBinaries = tasks.register("prepareSianoBinaries") {
    inputs.property("sianoUserlandDir", sianoUserlandDir)
    inputs.property("sianoPinnedRef", sianoPinnedRef)
    inputs.property("sianoSourceState", sianoSourceState)
    // inputs.files, not inputs.file: the path may be absent, and the friendlier
    // diagnostic below should be what the user sees.
    inputs.files(sianoBuildScript)
    inputs.files(sianoTrackedSourceFiles)
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

        val head = gitOutput(userlandDir, "rev-parse", "HEAD")
        if (head.first != 0) {
            throw GradleException(
                "siano-userland is not a Git checkout at $userlandDir: ${head.second}"
            )
        }
        if (head.second != sianoPinnedRef) {
            throw GradleException(
                "siano-userland HEAD mismatch at $userlandDir: expected $sianoPinnedRef, " +
                    "found ${head.second}"
            )
        }
        if (gitOutput(userlandDir, "diff", "--quiet").first != 0 ||
            gitOutput(userlandDir, "diff", "--cached", "--quiet").first != 0
        ) {
            throw GradleException(
                "siano-userland has tracked or staged changes at $userlandDir; " +
                    "commit or discard them before building (untracked build output is allowed)."
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
// git.kernel.org answers 403 to some hosts, GitHub Actions runners among them,
// so the GitLab mirror that upstream linux-firmware now publishes comes first.
val firmwareSources = listOf(
    "https://gitlab.com/kernel-firmware/linux-firmware/-/raw/main/isdbt_rio.inp",
    "https://git.kernel.org/pub/scm/linux/kernel/git/firmware/linux-firmware.git/plain/isdbt_rio.inp"
)
val firmwareMd5 = "9b762c1808fd8da81bbec3e24ddb04a3"

val prepareFirmware = tasks.register("prepareFirmware") {
    outputs.file(firmwareAsset)
    doLast {
        val dest = firmwareAsset.asFile
        dest.parentFile.mkdirs()
        if (dest.isFile && dest.length() == 85840L) {
            return@doLast
        }
        val failures = mutableListOf<String>()
        var payload: ByteArray? = null
        for (source in firmwareSources) {
            try {
                val connection = URI(source).toURL().openConnection()
                // The default Java agent is one of the things git.kernel.org rejects.
                connection.setRequestProperty("User-Agent", "dtv-android-build")
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                val bytes = connection.getInputStream().use { it.readBytes() }
                val digest = MessageDigest.getInstance("MD5").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                if (digest != firmwareMd5) {
                    failures += "$source: checksum $digest"
                    continue
                }
                payload = bytes
                break
            } catch (error: Exception) {
                failures += "$source: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        if (payload == null) {
            throw GradleException("Unable to fetch isdbt_rio.inp:\n" + failures.joinToString("\n"))
        }
        dest.writeBytes(payload)
    }
}

plugins.withId("com.android.application") {
    tasks.matching { it.name == "preBuild" || it.name.endsWith("JniLibFolders") }
        .configureEach {
            dependsOn(prepareSianoBinaries)
            dependsOn(prepareSianoAdapterBinaries)
            dependsOn(prepareMirakcAribBinaries)
            dependsOn(preparePx4Binaries)
            dependsOn(prepareMirakcBinary)
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
        versionCode = appVersionCode
        versionName = appVersionText

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
    implementation(project(":updater"))
}
