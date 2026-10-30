import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.testing.Test
import org.gradle.api.provider.Provider

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersionText = file("VERSION").readText().trim()
val appVersionParts = Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$""").matchEntire(appVersionText)
    ?: throw GradleException("mirakc/VERSION must hold a semantic version such as 1.2.3, found \"$appVersionText\"")
val (appMajor, appMinor, appPatch) = appVersionParts.destructured
val appVersionCode = appMajor.toInt() * 10000 + appMinor.toInt() * 100 + appPatch.toInt()
val ciNativeCacheEnabled = providers.gradleProperty("ciNativeCache").orNull == "true"
val nativeCacheFixtureOutputDir = providers.gradleProperty("mirakcNativeCacheFixtureOutputDir").orNull?.let(::file)

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
    "arm64-v8a" to listOf("px4d", "px4-ts", "px4ctl", "sianod", "sianoctl", "siano-ts", "asicend", "asicenctl", "asicen-ts", "pixd", "pixctl", "pix-ts"),
    "armeabi-v7a" to listOf("px4d", "px4-ts", "px4ctl", "sianod", "sianoctl", "siano-ts", "asicend", "asicenctl", "asicen-ts", "pixd", "pixctl", "pix-ts")
).flatMap { (abi, programs) -> programs.map { program -> nativeOutputDir.file("$abi/lib$program.so") } }
val userlandCoreDir = providers.gradleProperty("userlandCoreDir")
    .orElse(layout.projectDirectory.dir("../.work/pinned-dtv-userland-core").asFile.absolutePath)
val asicenUserlandDir = providers.gradleProperty("asicenUserlandDir")
    .orElse(layout.projectDirectory.dir("../.work/pinned-asicen-userland").asFile.absolutePath)
val pixUserlandDir = providers.gradleProperty("pixUserlandDir")
    .orElse(layout.projectDirectory.dir("../.work/pinned-pix-userland").asFile.absolutePath)
val sianoPx4UserlandDir = providers.gradleProperty("sianoPx4UserlandDir")
    .orElse(layout.projectDirectory.dir("../.work/pinned-siano-px4-userland").asFile.absolutePath)
val pixBuildScript = pixUserlandDir.map { file(it).resolve("tools/build-portable.sh") }
val pixAdapterBinaries = listOf(
    nativeOutputDir.file("arm64-v8a/libmirakc-pix-adapter.so"),
    nativeOutputDir.file("armeabi-v7a/libmirakc-pix-adapter.so")
)
val px4PinnedRef = "5c91e8c57b288c0f490061789829e42dbbf61b3d"
val sianoPinnedRef = "33c3468e7e10781ae6c7e9a5fd57a0c1b0825288"
val asicenPinnedRef = "e2a86ca194b60f1111ecfa0a020cd0944c351c76"
val pixPinnedRef = "66f5a731784d205d1d306edda9cc57071c087f8e"
val corePinnedRef = "67420d1c909e9205841612a186ae4c7fbf4dd092"
val sianoPx4PinnedRef = "90b08dfe79b0b40e393f015975c761863830866a"
val asicenBuildScript = asicenUserlandDir.map { file(it).resolve("scripts/build-android.sh") }
val libusbSourceDir = providers.gradleProperty("libusbSourceDir")
    .orElse(layout.projectDirectory.dir("../.work/libusb-1.0.30").asFile.absolutePath)
val libusbArchive = providers.gradleProperty("libusbArchive")
    .orElse(layout.projectDirectory.dir("../.work/libusb-1.0.30.tar.bz2").asFile.absolutePath)
val asicenFirmwareAsset = layout.projectDirectory.file("src/main/assets/asicen-loader.bin")
val pixFirmwareAssets = pixUserlandDir.map { directoryName ->
    listOf("firmware/dt181.bin", "firmware/dt295.bin").map { file(directoryName).resolve(it) }
}
val mirakcAribBinaries = listOf(
    nativeOutputDir.file("arm64-v8a/libmirakc-arib.so"),
    nativeOutputDir.file("armeabi-v7a/libmirakc-arib.so")
)

val sianoUserlandDir = providers.gradleProperty("sianoUserlandDir")
    .orElse(layout.projectDirectory.dir("../.work/pinned-siano-userland").asFile.absolutePath)
val sianoBuildScript = sianoUserlandDir.map { file(it).resolve("scripts/build-android.sh") }

val mirakcAribSourceDir = providers.gradleProperty("mirakcAribSourceDir")
    .orElse(layout.projectDirectory.dir("../.work/mirakc-arib-0.24.40").asFile.absolutePath)
val mirakcAribBuildScript = layout.projectDirectory.file("../tools/mirakc-arib/build-android.sh")
val mirakcAribSourceStageScript = layout.projectDirectory.file("../tools/mirakc-arib/prepare-build-source.sh")
val mirakcAribBootstrapScript = layout.projectDirectory.file("../tools/mirakc-arib/bootstrap-autotools.sh")
val mirakcAribToolchainFile = layout.projectDirectory.file("../tools/mirakc-arib/android.toolchain.cmake")
val mirakcAribVerifierScript = layout.projectDirectory.file("../tools/mirakc-arib/verify-android-elf.sh")
val mirakcAribAndroidPatch = layout.projectDirectory.file("../tools/mirakc-arib/patches/tsduck-android.patch")
val mirakcAribPinnedRef = "312168e85b30941a1fc0344900fd543a036ea341"
val mirakcAribVendorVersions = mirakcAribSourceDir.map { directoryName ->
    val source = file(directoryName)
    val versionScript = source.resolve("scripts/tag-or-hash")
    val vendors = listOf("docopt", "fmt", "spdlog", "rapidjson", "cppcodec", "aribb24", "tsduck-arib", "libisdb", "googletest", "google-benchmark")
    val versions = vendors.joinToString("\n") { vendor ->
        val process = ProcessBuilder("/bin/sh", versionScript.absolutePath)
            .directory(source.resolve("vendor/$vendor"))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.waitFor() != 0) throw GradleException("unable to read pinned ARIB vendor version: $vendor")
        "$vendor=$output"
    }
    val benchmark = ProcessBuilder(
        "git", "describe", "--tags", "--match", "v[0-9]*.[0-9]*.[0-9]*", "--abbrev=8"
    ).directory(source.resolve("vendor/google-benchmark")).redirectErrorStream(true).start()
    val benchmarkVersion = benchmark.inputStream.bufferedReader().use { it.readText() }.trim()
    if (benchmark.waitFor() != 0) throw GradleException("unable to read pinned Google Benchmark Git version")
    "$versions\ngoogle-benchmark-cmake=$benchmarkVersion"
}
val sianoAdapterSource = layout.projectDirectory.file("src/main/cpp/siano_adapter.cpp")
val sianoAdapterCmake = layout.projectDirectory.file("src/main/cpp/CMakeLists.txt")
val sianoAdapterVerifier = layout.projectDirectory.file("../tools/mirakc/verify-android-elf.sh")
val b25FilterSource = layout.projectDirectory.file("src/main/cpp/b25_filter_main.cpp")
val b25FilterCmake = layout.projectDirectory.file("src/main/cpp/CMakeLists.txt")
val b25FilterVerifier = layout.projectDirectory.file("../tools/mirakc/verify-android-elf.sh")
val sianoStreamProtocolHeader = layout.projectDirectory.file("src/main/cpp/siano_stream_protocol.h")
val px4AdapterSource = layout.projectDirectory.file("src/main/cpp/px4_adapter.cpp")
val pixAdapterSource = layout.projectDirectory.file("src/main/cpp/pix_adapter.cpp")
val adapterCliHeader = layout.projectDirectory.file("src/main/cpp/adapter_cli.h")
val coreConcurrencyPatch = layout.projectDirectory.file(
    "../tools/mirakc/patches/dtv-core-receiver-control-concurrency.patch"
)
val px4BuildJobsPatch = layout.projectDirectory.file(
    "../tools/mirakc/patches/px4-userland-build-jobs.patch"
)
val pixAndroid16kPatch = layout.projectDirectory.file(
    "../tools/mirakc/patches/pix-userland-android-16k.patch"
)
val corePatchedSourceDir = project.rootDir.resolve(".work/dtv-userland-core-patched")
val asicenCoreBundleDir = project.rootDir.resolve(".work/third_party/dtv-userland-core")
val px4BuildSourceDir = project.rootDir.resolve(".work/px4-userland-build-source")
val px4TunePlanSource = layout.projectDirectory.file("src/main/cpp/px4_tune_plan.cpp")
val px4TunePlanHeader = layout.projectDirectory.file("src/main/cpp/px4_tune_plan.h")
val px4TunePlanTests = layout.projectDirectory.file("src/main/cpp/tests/px4_tune_plan_test.cpp")
val px4ReceiverRetryHeader = layout.projectDirectory.file("src/main/cpp/px4_receiver_retry.h")
val px4ReceiverRetryTests = layout.projectDirectory.file("src/main/cpp/tests/px4_receiver_retry_test.cpp")
val px4CardRetryHeader = layout.projectDirectory.file("src/main/cpp/px4_card_retry.h")
val px4CardRetryTests = layout.projectDirectory.file("src/main/cpp/tests/px4_card_retry_test.cpp")
val px4TunePlanTestScript = layout.projectDirectory.file("../tools/mirakc/test-px4-tune-plan.sh")
val px4RuntimeSocketPathTest =
    layout.projectDirectory.file("src/main/cpp/tests/px4_runtime_socket_path_test.cpp")
val px4RuntimeSocketPathTestScript =
    layout.projectDirectory.file("../tools/mirakc/test-px4-runtime-socket-path.sh")
val px4AdapterCmake = layout.projectDirectory.file("src/main/cpp/CMakeLists.txt")
val px4AdapterVerifier = layout.projectDirectory.file("../tools/mirakc/verify-android-elf.sh")
val px4UserlandDir = providers.gradleProperty("px4UserlandDir")
    .orElse(layout.projectDirectory.dir("../.work/pinned-px4-userland").asFile.absolutePath)

val runPx4RuntimeSocketPathHostTests = tasks.register<Exec>("runPx4RuntimeSocketPathHostTests") {
    group = "verification"
    description = "Checks Android PX4 socket paths against pinned dtv-userland-core"
    workingDir(project.rootDir)
    commandLine("/bin/sh", px4RuntimeSocketPathTestScript.asFile.absolutePath, userlandCoreDir.get())
    inputs.property("corePinnedRef", corePinnedRef)
    inputs.property("coreHead", userlandCoreDir.map { gitOutput(file(it), "rev-parse", "HEAD").second })
    inputs.files(px4RuntimeSocketPathTest, px4RuntimeSocketPathTestScript)
    inputs.files(coreTrackedSourceFiles)
}

val androidNdkRoot = providers.environmentVariable("ANDROID_NDK_HOME")
    .orElse(providers.environmentVariable("ANDROID_NDK_ROOT"))
    .orElse("/config/.tools/android-sdk/ndk/$configuredNdkVersion")
fun toolFromPath(name: String): File {
    return System.getenv("PATH").split(File.pathSeparator)
        .map { File(it, name) }
        .firstOrNull { it.isFile && it.canExecute() }
        ?: throw GradleException("required cache tool is missing from PATH: $name")
}
val nativeToolchainExecutables = providers.provider {
    val toolchain = file(androidNdkRoot.get()).resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")
    listOf("clang", "clang++", "llvm-ar", "llvm-strip").map { toolchain.resolve(it) }
}
val rustToolchainExecutables = providers.provider {
    listOf("rustc", "cargo").map { name ->
        val process = ProcessBuilder("rustup", "which", name).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.waitFor() != 0) throw GradleException("unable to resolve Rust toolchain executable: $name")
        file(output)
    }
}
val cmakeToolchainExecutables = providers.provider { listOf(toolFromPath("cmake"), toolFromPath("ninja")) }

val t1StateMachineSource = layout.projectDirectory.file("src/main/cpp/t1_state_machine.c")
val t1StateMachineHeader = layout.projectDirectory.file("src/main/cpp/t1_state_machine.h")
val t1StateMachineTests = layout.projectDirectory.file("src/main/cpp/tests/t1_state_machine_test.cpp")
val t1StateMachineTestScript = layout.projectDirectory.file("../tools/mirakc/test-t1-state-machine.sh")
val b25IoTestScript = layout.projectDirectory.file("../tools/mirakc/test-b25-io.sh")
val b25IoSources = listOf(
    "b25_filter.cpp",
    "b25_filter_main.cpp",
    "siano_adapter.cpp",
    "siano_stream_protocol.h",
    "tests/b25_filter_io_test.cpp",
    "tests/b25_filter_main_exit_test.cpp",
    "tests/b25_filter_sigpipe_test.cpp",
    "tests/siano_adapter_io_test.cpp",
    "tests/siano_adapter_terminal_host.cpp",
    "b25_filter_contract.h",
    "ccid_reader.h",
    "arib25/arib_std_b25.h",
    "arib25/b_cas_card.h",
    "arib25/b_cas_card_error_code.h",
    "tests/stubs/android/log.h"
).map { layout.projectDirectory.file("src/main/cpp/$it") }
val sianoTerminalFixtures = listOf("exit0.hex", "exit7.hex", "signal9.hex")
    .map { layout.projectDirectory.file("src/test/resources/siano-terminal/$it") }
val ccidReaderSource = layout.projectDirectory.file("src/main/cpp/ccid_reader.c")
val ccidReaderHeader = layout.projectDirectory.file("src/main/cpp/ccid_reader.h")
val ccidReaderDescriptorTests =
    layout.projectDirectory.file("src/main/cpp/tests/ccid_reader_descriptor_test.c")
val ccidReaderDescriptorTestScript =
    layout.projectDirectory.file("../tools/mirakc/test-ccid-reader-descriptor.sh")

val runPx4TunePlanHostTests = tasks.register<Exec>("runPx4TunePlanHostTests") {
    workingDir(project.rootDir)
    commandLine("/bin/sh", px4TunePlanTestScript.asFile.absolutePath)
    inputs.files(
        px4TunePlanSource,
        px4TunePlanHeader,
        px4TunePlanTests,
        px4ReceiverRetryHeader,
        px4ReceiverRetryTests,
        px4CardRetryHeader,
        px4CardRetryTests,
        px4TunePlanTestScript
    )
}

val runT1StateMachineHostTests = tasks.register<Exec>("runT1StateMachineHostTests") {
    group = "verification"
    description = "Runs deterministic host tests for the T=1 state machine"
    workingDir(project.rootDir)
    commandLine("/bin/sh", t1StateMachineTestScript.asFile.absolutePath)
    inputs.files(
        t1StateMachineSource,
        t1StateMachineHeader,
        t1StateMachineTests,
        t1StateMachineTestScript
    )
}

val runB25IoHostTests = tasks.register<Exec>("runB25IoHostTests") {
    group = "verification"
    description = "Runs deterministic host tests for B25 and Siano adapter I/O"
    workingDir(project.rootDir)
    commandLine("/bin/sh", b25IoTestScript.asFile.absolutePath)
    inputs.files(b25IoSources, sianoTerminalFixtures, b25IoTestScript)
}

tasks.withType<Test>().configureEach {
    systemProperty(
        "mirakc.siano.terminal.host",
        project.rootDir.resolve(".work/host-b25-io/siano_adapter_terminal_host").absolutePath
    )
}

val runCcidReaderDescriptorHostTests = tasks.register<Exec>("runCcidReaderDescriptorHostTests") {
    group = "verification"
    description = "Runs deterministic host tests for CCID descriptor endpoint selection"
    workingDir(project.rootDir)
    commandLine("/bin/sh", ccidReaderDescriptorTestScript.asFile.absolutePath)
    inputs.files(
        ccidReaderSource,
        ccidReaderHeader,
        t1StateMachineSource,
        t1StateMachineHeader,
        ccidReaderDescriptorTests,
        ccidReaderDescriptorTestScript
    )
}

tasks.matching {
    it.name == "check" || it.name == "test" || it.name.endsWith("UnitTest")
}.configureEach {
    dependsOn(runT1StateMachineHostTests)
    dependsOn(runCcidReaderDescriptorHostTests)
    dependsOn(runPx4RuntimeSocketPathHostTests)
    dependsOn(runB25IoHostTests)
}

val mirakcBinaries = listOf(
    nativeOutputDir.file("arm64-v8a/libmirakc.so"),
    nativeOutputDir.file("armeabi-v7a/libmirakc.so")
)
val mirakcPinnedRef = "4f0767e87b109cabd17ed1ded0311355244fa018"
val mirakcSourceDir = providers.gradleProperty("mirakcSourceDir")
    .orElse(layout.projectDirectory.dir("../.work/mirakc-3.4.91").asFile.absolutePath)
val mirakcBuildScript = layout.projectDirectory.file("../tools/mirakc/build-android.sh")
val mirakcVerifierScript = layout.projectDirectory.file("../tools/mirakc/verify-android-elf.sh")
val mirakcAndroidPatch = layout.projectDirectory.file("../tools/mirakc/patches/mirakc-android-web-resilience.patch")

val prepareMirakcAribBinaries = tasks.register("prepareMirakcAribBinaries") {
    inputs.property("mirakcAribSourceDir", mirakcAribSourceDir)
    inputs.files(
        mirakcAribBuildScript,
        mirakcAribSourceStageScript,
        mirakcAribBootstrapScript,
        mirakcAribToolchainFile,
        mirakcAribVerifierScript,
        mirakcAribAndroidPatch
    )
    inputs.files(mirakcAribSourceDir.map { directoryName ->
        val source = file(directoryName)
        val tracked = gitOutput(source, "ls-files", "-z", "--recurse-submodules")
        if (tracked.first != 0) project.files() else project.files(
            tracked.second.split('\u0000').filter { it.isNotEmpty() }.map { source.resolve(it) }
        )
    })
    inputs.property("mirakcAribPinnedRef", mirakcAribPinnedRef)
    inputs.property("mirakcAribVendorVersions", mirakcAribVendorVersions)
    inputs.property("mirakcAribAbis", "arm64-v8a,armeabi-v7a")
    inputs.property("mirakcAribApi", 24)
    inputs.property("androidNdkVersion", configuredNdkVersion)
    inputs.property("androidCmakeVersion", "3.22.1")
    inputs.property("androidNinjaVersion", "1.10.2")
    inputs.property("androidNdkRevision", providers.provider {
        file(androidNdkRoot.get()).resolve("source.properties").readLines()
            .firstOrNull { it.startsWith("Pkg.Revision = ") }?.substringAfter("= ") ?: "missing"
    })
    inputs.property("aribHostToolchain", providers.provider {
        listOf(listOf("cc", "--version"), listOf("make", "--version"), listOf("pkg-config", "--version"))
            .joinToString("\n") { command ->
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                if (process.waitFor() != 0) throw GradleException("unable to fingerprint ARIB host tool: ${command.first()}")
                output
            }
    })
    inputs.files(nativeToolchainExecutables, cmakeToolchainExecutables)
    inputs.property("mirakcAribRecursiveGitlinks", providers.provider {
        gitOutput(file(mirakcAribSourceDir.get()), "submodule", "status", "--recursive").second
    })
    outputs.files(nativeCacheFixtureOutputDir?.resolve("prepareMirakcAribBinaries.so") ?: mirakcAribBinaries)
    outputs.cacheIf("CI native cache is enabled") { ciNativeCacheEnabled }

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
                environment("ANDROID_ABI", abi)
                environment("MIRAKC_ARIB_ABI", abi)
                environment("MIRAKC_ARIB_SOURCE_DIR", sourceDir.absolutePath)
                environment("MIRAKC_ARIB_BUILD_DIR", project.rootDir.resolve(".work/build-mirakc-arib-gradle-$abi").absolutePath)
                environment("MIRAKC_ARIB_OUTPUT", destination.absolutePath)
            }
        }
        buildAbi("arm64-v8a", "arm64-v8a/libmirakc-arib.so")
        buildAbi("armeabi-v7a", "armeabi-v7a/libmirakc-arib.so")
    }
}

val prepareSianoAdapterBinaries = tasks.register("prepareSianoAdapterBinaries") {
    dependsOn(preparePatchedCoreSource)
    inputs.files(sianoAdapterSource, sianoStreamProtocolHeader, sianoAdapterCmake, sianoAdapterVerifier, coreTrackedSourceFiles, coreConcurrencyPatch)
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
                    "-DDTV_USERLAND_CORE_DIR=${corePatchedSourceDir.absolutePath}",
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

val prepareB25FilterBinaries = tasks.register("prepareB25FilterBinaries") {
    inputs.files(
        b25FilterSource,
        b25FilterCmake,
        b25FilterVerifier,
        layout.projectDirectory.file("src/main/cpp/b25_filter.cpp"),
        layout.projectDirectory.file("src/main/cpp/b25_filter_contract.h"),
        layout.projectDirectory.file("src/main/cpp/ccid_reader.c"),
        layout.projectDirectory.file("src/main/cpp/ccid_reader.h"),
        layout.projectDirectory.file("src/main/cpp/t1_state_machine.c"),
        layout.projectDirectory.file("src/main/cpp/t1_state_machine.h"),
        layout.projectDirectory.file("src/main/cpp/b_cas_card_ccid.c"),
        layout.projectDirectory.file("src/main/cpp/arib25/b_cas_card.h"),
        fileTree(layout.projectDirectory.dir("src/main/cpp/arib25"))
    )
    outputs.files(
        nativeOutputDir.file("arm64-v8a/libmirakc-b25-filter.so"),
        nativeOutputDir.file("armeabi-v7a/libmirakc-b25-filter.so")
    )

    doLast {
        val ndk = file(androidNdkRoot.get())
        if (!ndk.isDirectory) throw GradleException("Android NDK not found at $ndk")
        fun buildAbi(abi: String) {
            val buildDir = project.rootDir.resolve(".work/build-b25-filter-$abi")
            val destination = nativeOutputDir.dir(abi).file("libmirakc-b25-filter.so").asFile
            project.exec {
                workingDir(project.rootDir)
                commandLine(
                    "cmake", "-S", b25FilterSource.asFile.parent,
                    "-B", buildDir.absolutePath, "-G", "Ninja",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DCMAKE_TOOLCHAIN_FILE=${ndk.resolve("build/cmake/android.toolchain.cmake")}",
                    "-DANDROID_ABI=$abi", "-DANDROID_PLATFORM=android-24",
                    "-DANDROID_STL=c++_static",
                    "-DMIRAKC_BUILD_B25_FILTER=ON"
                )
            }
            project.exec {
                workingDir(project.rootDir)
                commandLine("ninja", "-C", buildDir.absolutePath, "b25_filter")
            }
            val built = buildDir.resolve("libmirakc-b25-filter.so")
            if (!built.isFile) throw GradleException("B25 filter build produced no $built")
            destination.parentFile.mkdirs()
            built.copyTo(destination, overwrite = true)
            destination.setExecutable(true, false)
            project.exec {
                commandLine(
                    ndk.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip").absolutePath,
                    "--strip-unneeded", destination.absolutePath
                )
            }
            project.exec {
                commandLine("/bin/sh", b25FilterVerifier.asFile.absolutePath, destination.absolutePath, abi)
            }
        }
        buildAbi("arm64-v8a")
        buildAbi("armeabi-v7a")
    }
}

val preparePx4AdapterBinaries = tasks.register("preparePx4AdapterBinaries") {
    dependsOn(preparePatchedCoreSource)
    dependsOn(runPx4TunePlanHostTests)
    inputs.property("px4UserlandDir", px4UserlandDir)
    inputs.files(coreTrackedSourceFiles)
    inputs.files(
        px4AdapterSource,
        adapterCliHeader,
        px4ReceiverRetryHeader,
        px4TunePlanSource,
        px4TunePlanHeader,
        px4TunePlanTests,
        px4CardRetryHeader,
        px4CardRetryTests,
        px4TunePlanTestScript,
        px4AdapterCmake,
        px4AdapterVerifier,
        layout.projectDirectory.file("src/main/cpp/b25_filter.cpp"),
        layout.projectDirectory.file("src/main/cpp/b25_filter_contract.h"),
        layout.projectDirectory.file("src/main/cpp/ccid_reader.c"),
        layout.projectDirectory.file("src/main/cpp/ccid_reader.h"),
        layout.projectDirectory.file("src/main/cpp/t1_state_machine.c"),
        layout.projectDirectory.file("src/main/cpp/t1_state_machine.h"),
        layout.projectDirectory.file("src/main/cpp/b_cas_card_ccid.c"),
        layout.projectDirectory.file("src/main/cpp/arib25/b_cas_card.h"),
        fileTree(layout.projectDirectory.dir("src/main/cpp/arib25"))
    )
    inputs.files(
        px4UserlandDir.map { directoryName ->
            val sourceRoot = file(directoryName).resolve("userland/src")
            listOf(
                sourceRoot.resolve("control_client.cpp"),
                sourceRoot.resolve("control_server.cpp"),
                sourceRoot.resolve("control_workers.cpp"),
                sourceRoot.resolve("error.cpp"),
                sourceRoot.resolve("ipc.cpp"),
                sourceRoot.resolve("pcsc_ifd_adapter.cpp"),
                sourceRoot.resolve("posix_ipc.cpp"),
                sourceRoot.resolve("q3u4_frontend.cpp")
            )
        }
    )
    inputs.dir(px4UserlandDir.map { file(it).resolve("userland/include/px4") })
    inputs.files(px4BuildJobsPatch, coreConcurrencyPatch, coreTrackedSourceFiles)
    outputs.files(
        nativeOutputDir.file("arm64-v8a/libmirakc-px4-adapter.so"),
        nativeOutputDir.file("armeabi-v7a/libmirakc-px4-adapter.so")
    )

    doLast {
        val ndk = file(androidNdkRoot.get())
        if (!ndk.isDirectory) throw GradleException("Android NDK not found at $ndk")
        val stagedCore = corePatchedSourceDir
        fun buildAbi(abi: String) {
            val buildDir = project.rootDir.resolve(".work/build-px4-adapter-$abi")
            val destination = nativeOutputDir.dir(abi).file("libmirakc-px4-adapter.so").asFile
            // Normalize DWARF paths before link-time build-id generation so a
            // clean-room build from a different checkout path stays byte-equal.
            val sourcePrefixMapFlags = listOf(
                "-ffile-prefix-map=${px4AdapterSource.asFile.parent}=/src/dtv",
                "-ffile-prefix-map=${file(px4UserlandDir.get()).absolutePath}=/src/px4",
                "-ffile-prefix-map=${stagedCore.absolutePath}=/src/core",
                "-ffile-prefix-map=${buildDir.absolutePath}=/src/build"
            ).joinToString(" ")
            project.exec {
                workingDir(project.rootDir)
                commandLine(
                    "cmake", "-S", px4AdapterSource.asFile.parent,
                    "-B", buildDir.absolutePath, "-G", "Ninja",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DCMAKE_TOOLCHAIN_FILE=${ndk.resolve("build/cmake/android.toolchain.cmake")}",
                    "-DANDROID_ABI=$abi", "-DANDROID_PLATFORM=android-24",
                    "-DANDROID_STL=c++_static",
                    "-DMIRAKC_BUILD_PX4_ADAPTER=ON",
                    "-DPX4_USERLAND_DIR=${file(px4UserlandDir.get()).absolutePath}",
                    "-DDTV_USERLAND_CORE_DIR=${stagedCore.absolutePath}",
                    "-DCMAKE_C_FLAGS=$sourcePrefixMapFlags",
                    "-DCMAKE_CXX_FLAGS=$sourcePrefixMapFlags"
                )
            }
            project.exec {
                workingDir(project.rootDir)
                commandLine("ninja", "-C", buildDir.absolutePath, "px4_adapter")
            }
            val built = buildDir.resolve("libmirakc-px4-adapter.so")
            if (!built.isFile) throw GradleException("PX4 adapter build produced no $built")
            destination.parentFile.mkdirs()
            built.copyTo(destination, overwrite = true)
            destination.setExecutable(true, false)
            project.exec {
                commandLine(ndk.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip").absolutePath,
                    "--strip-unneeded", destination.absolutePath)
            }
            project.exec {
                commandLine("/bin/sh", px4AdapterVerifier.asFile.absolutePath, destination.absolutePath, abi)
            }
        }
        buildAbi("arm64-v8a")
        buildAbi("armeabi-v7a")
    }
}
tasks.named("preparePx4AdapterBinaries") {
    dependsOn(preparePatchedCoreSource)
}

val prepareAsicenAdapterBinaries = tasks.register("prepareAsicenAdapterBinaries") {
    dependsOn(preparePatchedCoreSource)
    inputs.files(px4AdapterSource, px4AdapterCmake, px4AdapterVerifier, asicenTrackedSourceFiles, coreTrackedSourceFiles, coreConcurrencyPatch)
    inputs.files(
        adapterCliHeader,
        px4TunePlanSource,
        px4TunePlanHeader,
        px4ReceiverRetryHeader,
        px4CardRetryHeader,
        layout.projectDirectory.file("src/main/cpp/b25_filter.cpp"),
        layout.projectDirectory.file("src/main/cpp/b25_filter_contract.h"),
        layout.projectDirectory.file("src/main/cpp/ccid_reader.c"),
        layout.projectDirectory.file("src/main/cpp/ccid_reader.h"),
        layout.projectDirectory.file("src/main/cpp/t1_state_machine.c"),
        layout.projectDirectory.file("src/main/cpp/t1_state_machine.h"),
        layout.projectDirectory.file("src/main/cpp/b_cas_card_ccid.c"),
        fileTree(layout.projectDirectory.dir("src/main/cpp/arib25"))
    )
    outputs.files(
        nativeOutputDir.file("arm64-v8a/libmirakc-asicen-adapter.so"),
        nativeOutputDir.file("armeabi-v7a/libmirakc-asicen-adapter.so")
    )
    doLast {
        val ndk = file(androidNdkRoot.get())
        if (!ndk.isDirectory) throw GradleException("Android NDK not found at $ndk")
        val asicen = file(asicenUserlandDir.get())
        stagePinnedSource(asicen, asicenPinnedRef, project.rootDir.resolve(".work/staged-asicen-adapter-source"))
        val stagedCore = corePatchedSourceDir
        val stagedAsicen = project.rootDir.resolve(".work/staged-asicen-adapter-source")
        fun buildAbi(abi: String) {
            val buildDir = project.rootDir.resolve(".work/build-asicen-adapter-$abi")
            val destination = nativeOutputDir.dir(abi).file("libmirakc-asicen-adapter.so").asFile
            val prefixMapFlags = listOf(
                "-ffile-prefix-map=${px4AdapterSource.asFile.parent}=/src/dtv",
                "-ffile-prefix-map=${stagedAsicen.absolutePath}=/src/asicen",
                "-ffile-prefix-map=${stagedCore.absolutePath}=/src/core",
                "-ffile-prefix-map=${buildDir.absolutePath}=/src/build"
            ).joinToString(" ")
            project.exec {
                workingDir(project.rootDir)
                commandLine("cmake", "-S", px4AdapterSource.asFile.parent, "-B", buildDir.absolutePath, "-G", "Ninja",
                    "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_TOOLCHAIN_FILE=${ndk.resolve("build/cmake/android.toolchain.cmake")}",
                    "-DANDROID_ABI=$abi", "-DANDROID_PLATFORM=android-24", "-DANDROID_STL=c++_static",
                    "-DMIRAKC_BUILD_ASICEN_ADAPTER=ON", "-DASICEN_USERLAND_DIR=${stagedAsicen.absolutePath}",
                    "-DDTV_USERLAND_CORE_DIR=${stagedCore.absolutePath}",
                    "-DCMAKE_C_FLAGS=$prefixMapFlags", "-DCMAKE_CXX_FLAGS=$prefixMapFlags")
            }
            project.exec { workingDir(project.rootDir); commandLine("ninja", "-C", buildDir.absolutePath, "asicen_adapter") }
            val built = buildDir.resolve("libmirakc-asicen-adapter.so")
            if (!built.isFile) throw GradleException("ASICEN adapter build produced no $built")
            destination.parentFile.mkdirs()
            built.copyTo(destination, overwrite = true)
            destination.setExecutable(true, false)
            project.exec { commandLine(ndk.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip").absolutePath, "--strip-unneeded", destination.absolutePath) }
            project.exec { commandLine("/bin/sh", px4AdapterVerifier.asFile.absolutePath, destination.absolutePath, abi) }
        }
        buildAbi("arm64-v8a")
        buildAbi("armeabi-v7a")
    }
}

val preparePixAdapterBinaries = tasks.register("preparePixAdapterBinaries") {
    dependsOn(preparePatchedCoreSource)
    inputs.files(pixAdapterSource, adapterCliHeader, px4AdapterCmake, px4AdapterVerifier, coreTrackedSourceFiles, coreConcurrencyPatch)
    outputs.files(pixAdapterBinaries)
    doLast {
        val ndk = file(androidNdkRoot.get())
        if (!ndk.isDirectory) throw GradleException("Android NDK not found at $ndk")
        fun buildAbi(abi: String) {
            val buildDir = project.rootDir.resolve(".work/build-pix-adapter-$abi")
            val destination = nativeOutputDir.dir(abi).file("libmirakc-pix-adapter.so").asFile
            val prefixMapFlags = listOf(
                "-ffile-prefix-map=${pixAdapterSource.asFile.parent}=/src/dtv",
                "-ffile-prefix-map=${corePatchedSourceDir.absolutePath}=/src/core",
                "-ffile-prefix-map=${buildDir.absolutePath}=/src/build"
            ).joinToString(" ")
            project.exec {
                workingDir(project.rootDir)
                commandLine("cmake", "-S", pixAdapterSource.asFile.parent, "-B", buildDir.absolutePath, "-G", "Ninja",
                    "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_TOOLCHAIN_FILE=${ndk.resolve("build/cmake/android.toolchain.cmake")}",
                    "-DANDROID_ABI=$abi", "-DANDROID_PLATFORM=android-24", "-DANDROID_STL=c++_static",
                    "-DMIRAKC_BUILD_PIX_ADAPTER=ON", "-DDTV_USERLAND_CORE_DIR=${corePatchedSourceDir.absolutePath}",
                    "-DCMAKE_C_FLAGS=$prefixMapFlags", "-DCMAKE_CXX_FLAGS=$prefixMapFlags")
            }
            project.exec { workingDir(project.rootDir); commandLine("ninja", "-C", buildDir.absolutePath, "pix_adapter") }
            val built = buildDir.resolve("libmirakc-pix-adapter.so")
            if (!built.isFile) throw GradleException("PIX adapter build produced no $built")
            destination.parentFile.mkdirs()
            built.copyTo(destination, overwrite = true)
            destination.setExecutable(true, false)
            project.exec { commandLine(ndk.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip").absolutePath, "--strip-unneeded", destination.absolutePath) }
            project.exec { commandLine("/bin/sh", px4AdapterVerifier.asFile.absolutePath, destination.absolutePath, abi) }
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
    inputs.files(mirakcBuildScript, mirakcVerifierScript, mirakcAndroidPatch)
    inputs.files(mirakcTrackedSourceFiles)
    inputs.files(nativeToolchainExecutables, rustToolchainExecutables)
    inputs.property("androidNdkVersion", configuredNdkVersion)
    inputs.property("androidApi", 24)
    inputs.property("androidAbis", "arm64-v8a,armeabi-v7a")
    inputs.property("cargoBuildJobs", providers.environmentVariable("CARGO_BUILD_JOBS").orElse("1"))
    inputs.property("rustcVersion", providers.provider { providers.exec { commandLine("rustc", "--version", "--verbose") }.standardOutput.asText.get() })
    inputs.property("cargoVersion", providers.provider { providers.exec { commandLine("cargo", "--version") }.standardOutput.asText.get() })
    inputs.property("rustLinkFlags", "16k-page-size;relro;now;thin-lto")
    outputs.files(nativeCacheFixtureOutputDir?.resolve("prepareMirakcBinary.so") ?: mirakcBinaries)
    outputs.cacheIf("CI native cache is enabled") { ciNativeCacheEnabled }

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

val validateMirakcNativeCacheInputs = tasks.register("validateMirakcNativeCacheInputs") {
    doLast {
        val source = file(mirakcSourceDir.get())
        val cargoHome = System.getenv("CARGO_HOME")?.let(::file)
            ?: if (file("/config/.tools").isDirectory) file("/config/.tools/cargo")
            else file(System.getProperty("user.home")).resolve(".cargo")
        val cargoConfigPaths = mutableListOf<File>()
        var ancestor: File? = source.absoluteFile
        while (ancestor != null) {
            cargoConfigPaths.add(ancestor.resolve(".cargo/config"))
            cargoConfigPaths.add(ancestor.resolve(".cargo/config.toml"))
            ancestor = ancestor.parentFile
        }
        cargoConfigPaths.add(cargoHome.resolve("config"))
        cargoConfigPaths.add(cargoHome.resolve("config.toml"))
        if (cargoConfigPaths.any { it.exists() }) {
            throw GradleException("Cargo configuration overrides are unsupported for mirakc native cache")
        }
        val environment = System.getenv()
        val overrideNames = setOf(
            "RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "CARGO_BUILD_RUSTFLAGS", "CARGO_BUILD_TARGET",
            "RUSTC", "RUSTC_WRAPPER", "RUSTC_WORKSPACE_WRAPPER", "RUSTUP_TOOLCHAIN", "RUSTC_BOOTSTRAP",
            "MIRAKC_SOURCE_URL", "MIRAKC_SOURCE_REF", "MIRAKC_CLEAN_ROOM", "MIRAKC_GIT_BIN"
        )
        val overrides = environment.keys.filter {
            it in overrideNames || it.startsWith("CARGO_PROFILE_") || it.startsWith("CARGO_TARGET_")
        }
        if (overrides.isNotEmpty()) throw GradleException("unsupported Rust environment overrides: ${overrides.joinToString()}")
        val head = gitOutput(source, "rev-parse", "HEAD")
        if (head.first != 0 || head.second != mirakcPinnedRef) {
            throw GradleException("mirakc cache source pin mismatch: expected $mirakcPinnedRef, found ${head.second}")
        }
        if (gitStatusOutput(source).let { it.first != 0 || it.second.isNotEmpty() }) {
            throw GradleException("mirakc cache source checkout must be clean: $source")
        }
        val lockSha = MessageDigest.getInstance("SHA-256").digest(source.resolve("Cargo.lock").readBytes())
            .joinToString("") { byte: Byte -> "%02x".format(byte.toInt() and 0xff) }
        if (lockSha != "005a492cfdd0f6d44dd78585359caa0aa28b3c78eb46aaa6c55b18854f78a8b8") {
            throw GradleException("mirakc cache Cargo.lock digest mismatch: $lockSha")
        }
        val patchSha = MessageDigest.getInstance("SHA-256").digest(mirakcAndroidPatch.asFile.readBytes())
            .joinToString("") { byte: Byte -> "%02x".format(byte.toInt() and 0xff) }
        if (patchSha != "7b0de81b73a3e8f1da35ca4a3f6ad70865a97b9c057c4b35dd22fc1af67fe0af") {
            throw GradleException("mirakc cache patch digest mismatch: $patchSha")
        }
        val ndk = file(androidNdkRoot.get())
        val ndkRevision = ndk.resolve("source.properties").readLines()
            .firstOrNull { it.startsWith("Pkg.Revision = ") }?.substringAfter("= ")
        val rustc = providers.exec { commandLine("rustc", "--version", "--verbose") }.standardOutput.asText.get()
        val cargo = providers.exec { commandLine("cargo", "--version") }.standardOutput.asText.get()
        if (ndkRevision != "27.0.12077973" || !rustc.startsWith("rustc 1.98.1 ") || !cargo.startsWith("cargo 1.98.1 ")) {
            throw GradleException("mirakc cache requires NDK 27.0.12077973 and Rust/Cargo 1.98.1")
        }
        if (System.getenv("CARGO_BUILD_JOBS") != "1") throw GradleException("mirakc native cache requires CARGO_BUILD_JOBS=1")
    }
}

val validateMirakcAribCacheInputs = tasks.register("validateMirakcAribCacheInputs") {
    doLast {
        val source = file(mirakcAribSourceDir.get())
        val head = gitOutput(source, "rev-parse", "HEAD")
        if (head.first != 0 || head.second != mirakcAribPinnedRef) {
            throw GradleException("mirakc-arib cache source pin mismatch: expected $mirakcAribPinnedRef, found ${head.second}")
        }
        val patchSha = MessageDigest.getInstance("SHA-256").digest(mirakcAribAndroidPatch.asFile.readBytes())
            .joinToString("") { byte: Byte -> "%02x".format(byte.toInt() and 0xff) }
        if (patchSha != "8ce7be5fc624ff1b456948c0dc015ca883c1c6a9635b73106f6a7532f8da5b88") {
            throw GradleException("mirakc-arib cache patch digest mismatch: $patchSha")
        }
        val status = gitStatusOutput(source)
        if (status.first != 0 || status.second.isNotEmpty()) {
            throw GradleException("mirakc-arib cache source checkout must be clean: $source")
        }
        val submodules = gitOutput(source, "submodule", "status", "--recursive")
        if (submodules.first != 0 || submodules.second.lineSequence().any { it.firstOrNull() in listOf('+', '-', 'U') }) {
            throw GradleException("mirakc-arib recursive submodules do not match pinned gitlinks")
        }
        val nestedStatus = gitOutput(source, "submodule", "foreach", "--quiet", "--recursive", "test -z \"$(git status --porcelain=v1 --untracked-files=all)\"")
        if (nestedStatus.first != 0) throw GradleException("mirakc-arib recursive source checkout must be clean")
        val ndk = file(androidNdkRoot.get())
        val ndkRevision = ndk.resolve("source.properties").readLines()
            .firstOrNull { it.startsWith("Pkg.Revision = ") }?.substringAfter("= ")
        val cmake = providers.exec { commandLine("cmake", "--version") }.standardOutput.asText.get()
            .lineSequence().first().substringAfter("cmake version ").substringBefore('-')
        val ninja = providers.exec { commandLine("ninja", "--version") }.standardOutput.asText.get().trim()
        if (ndkRevision != "27.0.12077973" || cmake != "3.22.1" || ninja != "1.10.2") {
            throw GradleException("mirakc-arib cache requires NDK 27.0.12077973, CMake 3.22.1, and Ninja 1.10.2")
        }
        val overrides = listOf("CMAKE", "NINJA", "CMAKE_PREFIX_PATH", "CMAKE_GENERATOR", "CMAKE_TOOLCHAIN_FILE", "CFLAGS", "CXXFLAGS", "CPPFLAGS", "LDFLAGS", "PKG_CONFIG_PATH", "PKG_CONFIG", "CC", "CXX", "AR", "RANLIB", "STRIP", "ANDROID_ABI", "MIRAKC_ARIB_ABI", "MIRAKC_ARIB_API", "MIRAKC_ARIB_SOURCE_REF", "MIRAKC_ARIB_SOURCE_URL", "MIRAKC_ARIB_AUTOTOOLS_PREFIX", "MIRAKC_ARIB_AUTOTOOLS_WORK", "MIRAKC_ARIB_AUTOTOOLS_JOBS", "MIRAKC_CLEAN_ROOM")
        val present = overrides.filter { System.getenv(it) != null }
        if (present.isNotEmpty()) throw GradleException("unsupported mirakc-arib cache environment overrides: ${present.joinToString()}")
    }
}

if (ciNativeCacheEnabled) {
    prepareMirakcBinary.configure { dependsOn(validateMirakcNativeCacheInputs) }
    prepareMirakcAribBinaries.configure { dependsOn(validateMirakcAribCacheInputs) }
}

val verifyCachedMirakcNativeBinaries = tasks.register("verifyCachedMirakcNativeBinaries") {
    dependsOn(prepareMirakcBinary, prepareMirakcAribBinaries)
    doLast {
        listOf("arm64-v8a", "armeabi-v7a").forEach { abi ->
            project.exec {
                commandLine("/bin/sh", mirakcVerifierScript.asFile.absolutePath,
                    nativeOutputDir.file("$abi/libmirakc.so").asFile.absolutePath, abi)
            }
            project.exec {
                commandLine("/bin/sh", mirakcAribVerifierScript.asFile.absolutePath,
                    nativeOutputDir.file("$abi/libmirakc-arib.so").asFile.absolutePath, abi)
            }
        }
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
fun gitStatusOutput(directory: java.io.File): Pair<Int, ByteArray> {
    return try {
        val process = ProcessBuilder(
            "git", "-C", directory.absolutePath, "status", "--porcelain=v1", "-z", "--untracked-files=all"
        ).start()
        val output = process.inputStream.use { it.readBytes() }
        process.waitFor() to output
    } catch (error: Exception) {
        1 to (error.message ?: error.javaClass.simpleName).toByteArray(Charsets.UTF_8)
    }
}

fun stagePinnedSource(root: java.io.File, pin: String, destination: java.io.File) {
    val head = gitOutput(root, "rev-parse", "HEAD")
    if (head.first != 0 || head.second != pin) {
        throw GradleException("source HEAD mismatch at $root: expected $pin, found ${head.second}")
    }
    if (gitOutput(root, "diff", "--quiet").first != 0 || gitOutput(root, "diff", "--cached", "--quiet").first != 0) {
        throw GradleException("pinned source checkout must have no tracked changes: $root")
    }
    if (destination.exists() && !destination.deleteRecursively()) {
        throw GradleException("cannot clear generated source stage at $destination")
    }
    destination.mkdirs()
    val archive = destination.parentFile.resolve("${destination.name}.tar")
    try {
        project.exec {
            commandLine("git", "-C", root.absolutePath, "archive", "--format=tar", "--output", archive.absolutePath, pin)
        }
        project.exec { commandLine("tar", "-xf", archive.absolutePath, "-C", destination.absolutePath) }
    } finally {
        archive.delete()
    }
}

fun trackedFiles(directory: Provider<String>, vararg paths: String) = directory.map { directoryName ->
    val root = file(directoryName)
    if (!root.isDirectory) {
        project.files()
    } else {
        val result = gitOutput(root, "ls-files", "-z", "--", *paths)
        if (result.first != 0) project.files() else project.files(
            result.second.split('\u0000').filter { it.isNotEmpty() }.map { root.resolve(it) }
        )
    }
}

val coreTrackedSourceFiles = trackedFiles(userlandCoreDir, "CMakeLists.txt", "VERSION", "LICENSE", "cmake/**", "include/**", "src/**", "adapters/**")
val asicenTrackedSourceFiles = trackedFiles(asicenUserlandDir, "CMakeLists.txt", "VERSION", "cmake/**", "include/**", "userland/**", "third_party/px4-userland/**", "scripts/build-android.sh", "scripts/verify-android-elf.sh", "firmware/README.txt", "LICENSE", "COPYING.gpl2", "THIRD_PARTY_NOTICES.md")
val pixTrackedSourceFiles = trackedFiles(pixUserlandDir, "CMakeLists.txt", "VERSION", "include/**", "core-integration/**", "tools/build-portable.sh", "tools/core-notices.py", "firmware/**", "LICENSE", "licenses/**")
val sianoPx4TrackedSourceFiles = trackedFiles(sianoPx4UserlandDir, "CMakeLists.txt", "VERSION", "cmake/**", "userland/**")

val preparePatchedCoreSource = tasks.register("preparePatchedCoreSource") {
    inputs.property("corePinnedRef", corePinnedRef)
    inputs.files(coreTrackedSourceFiles, coreConcurrencyPatch)
    outputs.dir(corePatchedSourceDir)
    doLast {
        val core = file(userlandCoreDir.get())
        val patch = coreConcurrencyPatch.asFile
        val expectedPatchSha256 = "9da0cf46a7727a5c7e20de165babd0bc2d78e47c119cad38c1f2c00abeb10521"
        val actualPatchSha256 = MessageDigest.getInstance("SHA-256").digest(patch.readBytes())
            .joinToString("") { byte: Byte -> "%02x".format(byte.toInt() and 0xff) }
        if (actualPatchSha256 != expectedPatchSha256) {
            throw GradleException("Core concurrency patch checksum mismatch: expected $expectedPatchSha256, found $actualPatchSha256")
        }
        stagePinnedSource(core, corePinnedRef, corePatchedSourceDir)
        val check = ProcessBuilder(
            "patch", "--dry-run", "--batch", "--fuzz=0", "--forward", "-p1", "-i", patch.absolutePath
        ).directory(corePatchedSourceDir).redirectErrorStream(true).start()
        val checkOutput = check.inputStream.bufferedReader().use { it.readText() }.trim()
        if (check.waitFor() != 0) {
            throw GradleException("Pinned Core concurrency patch does not apply cleanly: $checkOutput")
        }
        project.exec {
            workingDir(corePatchedSourceDir)
            commandLine("patch", "--batch", "--fuzz=0", "--forward", "-p1", "-i", patch.absolutePath)
        }
        val patchedWorkers = corePatchedSourceDir.resolve("adapters/host_daemon/src/control_workers.cpp")
        val patchedWorkersSha256 = MessageDigest.getInstance("SHA-256").digest(patchedWorkers.readBytes())
            .joinToString("") { byte: Byte -> "%02x".format(byte.toInt() and 0xff) }
        corePatchedSourceDir.resolve("DTV-PATCH-METADATA.json").writeText(
            "{\n" +
                "  \"base_commit\": \"$corePinnedRef\",\n" +
                "  \"patch_path\": \"tools/mirakc/patches/dtv-core-receiver-control-concurrency.patch\",\n" +
                "  \"patch_sha256\": \"$actualPatchSha256\",\n" +
                "  \"patched_control_workers_sha256\": \"$patchedWorkersSha256\"\n" +
                "}\n"
        )
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
            "userland/**",
            "third_party/dtv-userland-core.revision"
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

val preparePx4BuildSource = tasks.register("preparePx4BuildSource") {
    inputs.property("px4UserlandDir", px4UserlandDir)
    inputs.property("px4PinnedRef", px4PinnedRef)
    inputs.files(px4TrackedSourceFiles, px4BuildJobsPatch)
    outputs.dir(px4BuildSourceDir)

    doLast {
        val userlandDir = file(px4UserlandDir.get())
        if (!userlandDir.isDirectory) {
            throw GradleException("px4-userland directory not found at $userlandDir")
        }
        stagePinnedSource(userlandDir, px4PinnedRef, px4BuildSourceDir)
        val patch = px4BuildJobsPatch.asFile
        val actualPatchHash = MessageDigest.getInstance("SHA-256")
            .digest(patch.readBytes())
            .joinToString("") { byte: Byte -> "%02x".format(byte.toInt() and 0xff) }
        val expectedPatchHash = "ba9c4607e3411e706e537a38cc879990f46db04977ca717e714e0004553a4a42"
        if (actualPatchHash != expectedPatchHash) {
            throw GradleException(
                "PX4 bounded-job patch checksum mismatch: expected $expectedPatchHash, " +
                    "found $actualPatchHash"
            )
        }
        val check = ProcessBuilder(
            "patch", "--dry-run", "--batch", "--fuzz=0", "--forward", "-p1", "-i", patch.absolutePath
        ).directory(px4BuildSourceDir).redirectErrorStream(true).start()
        val checkOutput = check.inputStream.bufferedReader().use { it.readText() }.trim()
        if (check.waitFor() != 0) {
            throw GradleException("PX4 bounded-job patch does not apply cleanly: $checkOutput")
        }
        project.exec {
            workingDir(px4BuildSourceDir)
            commandLine("patch", "--batch", "--fuzz=0", "--forward", "-p1", "-i", patch.absolutePath)
        }
    }
}

val preparePx4Binaries = tasks.register("preparePx4Binaries") {
    dependsOn(preparePx4BuildSource, preparePatchedCoreSource)
    inputs.property("px4UserlandDir", px4UserlandDir)
    inputs.property("px4PinnedRef", px4PinnedRef)
    inputs.property("px4SourceState", px4SourceState)
    inputs.files(px4BuildScript)
    inputs.file(px4BuildJobsPatch)
    inputs.files(coreTrackedSourceFiles, coreConcurrencyPatch)
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
        val script = px4BuildSourceDir.resolve("scripts/build-android.sh")
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
                workingDir(px4BuildSourceDir)
                commandLine("/bin/sh", script.absolutePath, "--abi", abi, "--output", outputDir.absolutePath)
                environment("ANDROID_NDK_HOME", ndkRoot)
                environment("ANDROID_ABI", abi)
                environment("PX4_ANDROID_BUILD_JOBS", "1")
                environment("DTV_USERLAND_CORE_SOURCE_DIR", corePatchedSourceDir.absolutePath)
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
            "CMakeLists.txt",
            "cmake/**",
            "**/*.cpp",
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
    dependsOn(preparePatchedCoreSource)
    inputs.property("sianoUserlandDir", sianoUserlandDir)
    inputs.property("sianoPinnedRef", sianoPinnedRef)
    inputs.property("sianoSourceState", sianoSourceState)
    // inputs.files, not inputs.file: the path may be absent, and the friendlier
    // diagnostic below should be what the user sees.
    inputs.files(sianoBuildScript)
    inputs.files(sianoTrackedSourceFiles)
    inputs.files(coreTrackedSourceFiles, coreConcurrencyPatch, sianoPx4TrackedSourceFiles)
    inputs.files(userlandCoreDir.map { file(it).resolve(".git/HEAD") }, sianoPx4UserlandDir.map { file(it).resolve(".git/HEAD") })
    outputs.files(nativeBinaries.filter { it.asFile.name in setOf("libsianod.so", "libsianoctl.so", "libsiano-ts.so") })

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
        val nestedPx4Dir = file(sianoPx4UserlandDir.get())

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

        val stagedSiano = project.rootDir.resolve(".work/staged-siano-userland")
        val stagedCore = corePatchedSourceDir
        val stagedNestedPx4 = project.rootDir.resolve(".work/staged-siano-px4-userland")
        stagePinnedSource(userlandDir, sianoPinnedRef, stagedSiano)
        if (!stagedCore.resolve("DTV-PATCH-METADATA.json").isFile) {
            throw GradleException("patched Core source stage is missing: $stagedCore")
        }
        stagePinnedSource(nestedPx4Dir, sianoPx4PinnedRef, stagedNestedPx4)

        fun buildAbi(abi: String, sourceAbi: String) {
            project.exec {
                workingDir(stagedSiano)
                commandLine("/bin/sh", stagedSiano.resolve("scripts/build-android.sh").absolutePath)
                environment("ANDROID_NDK_HOME", ndkRoot)
                environment("ANDROID_ABI", sourceAbi)
                environment("DTV_USERLAND_CORE_SOURCE_DIR", stagedCore.absolutePath)
                environment("SIANO_PX4_USERLAND_SOURCE_DIR", stagedNestedPx4.absolutePath)
            }
            val buildDir = stagedSiano.resolve("build/android-$sourceAbi")
            for (program in listOf("sianod", "sianoctl", "siano-ts")) {
                val built = buildDir.resolve(program)
                if (!built.isFile) throw GradleException("Siano build completed without producing $built")
                val destination = nativeOutputDir.dir(abi).file("lib$program.so").asFile
                destination.parentFile.mkdirs()
                built.copyTo(destination, overwrite = true)
                destination.setExecutable(true, false)
            }
        }

        buildAbi("arm64-v8a", "aarch64")
        buildAbi("armeabi-v7a", "armv7a")
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
val firmwareSha256 = "054520642d5d09cb7ab7d08dbd6fd9ba9365de56adf2e7d7d06927f9845ff818"

val prepareFirmware = tasks.register("prepareFirmware") {
    outputs.file(firmwareAsset)
    doLast {
        val dest = firmwareAsset.asFile
        fun valid(bytes: ByteArray): Boolean {
            val md5 = MessageDigest.getInstance("MD5").digest(bytes)
                .joinToString("") { byte: Byte -> "%02x".format(byte.toInt() and 0xff) }
            val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { byte: Byte -> "%02x".format(byte.toInt() and 0xff) }
            return bytes.size == 85840 && md5 == firmwareMd5 && sha256 == firmwareSha256
        }
        dest.parentFile.mkdirs()
        if (dest.isFile && valid(dest.readBytes())) return@doLast
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
                if (!valid(bytes)) {
                    failures += "$source: firmware size or digest mismatch"
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

val prepareAsicenFirmware = tasks.register("prepareAsicenFirmware") {
    inputs.files(asicenFirmwareAsset, layout.projectDirectory.file("src/main/assets/ASICEN-LOADER-NOTICE.txt"))
    doLast {
        val source = asicenFirmwareAsset.asFile
        val expected = "5db39facda140bdcb3a238dad10da5aac752a1ba3aaf17e2239f8d79a0ad6a2a"
        if (!source.isFile) throw GradleException("ASICEN loader firmware is missing: $source")
        val actual = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
            .joinToString("") { byte: Byte -> "%02x".format(byte.toInt() and 0xff) }
        if (actual != expected) throw GradleException("ASICEN loader firmware digest mismatch: $actual")
    }
}

val prepareLibusbSource = tasks.register("prepareLibusbSource") {
    outputs.file(libusbArchive)
    outputs.dir(libusbSourceDir)
    doLast {
        val archive = file(libusbArchive.get())
        val expected = "fea36f34f9156400209595e300840767ab1a385ede1dc7ee893015aea9c6dbaf"
        if (!archive.isFile) {
            archive.parentFile.mkdirs()
            URI("https://github.com/libusb/libusb/releases/download/v1.0.30/libusb-1.0.30.tar.bz2")
                .toURL().openStream().use { input -> archive.outputStream().use(input::copyTo) }
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(archive.readBytes())
            .joinToString("") { byte: Byte -> "%02x".format(byte.toInt() and 0xff) }
        if (actual != expected) throw GradleException("libusb 1.0.30 archive digest mismatch: $actual")
        val source = file(libusbSourceDir.get())
        if (!source.resolve("configure").isFile || !source.resolve("COPYING").isFile) {
            if (source.exists() && source.listFiles()?.isEmpty() != true) {
                throw GradleException("libusb source directory is incomplete: $source")
            }
            source.parentFile.mkdirs()
            val extractionRoot = Files.createTempDirectory(source.parentFile.toPath(), ".libusb-source-").toFile()
            try {
                project.exec { commandLine("tar", "-xjf", archive.absolutePath, "-C", extractionRoot.absolutePath) }
                val extracted = extractionRoot.resolve("libusb-1.0.30")
                if (!extracted.resolve("configure").isFile || !extracted.resolve("COPYING").isFile) {
                    throw GradleException("libusb archive did not contain a complete source tree")
                }
                if (source.exists() && !source.delete()) {
                    throw GradleException("cannot replace empty libusb source directory: $source")
                }
                if (!extracted.renameTo(source)) throw GradleException("cannot stage libusb source at $source")
            } finally {
                extractionRoot.deleteRecursively()
            }
        }
    }
}

val prepareAsicenBinaries = tasks.register("prepareAsicenBinaries") {
    dependsOn(preparePatchedCoreSource)
    inputs.property("asicenUserlandDir", asicenUserlandDir)
    inputs.property("userlandCoreDir", userlandCoreDir)
    inputs.property("asicenCoreBundlePath", asicenCoreBundleDir.absolutePath)
    inputs.files(asicenBuildScript, asicenTrackedSourceFiles, coreTrackedSourceFiles, coreConcurrencyPatch, asicenUserlandDir.map { file(it).resolve("third_party/px4-userland") })
    inputs.files(libusbArchive, libusbSourceDir.map { file(it).resolve("configure") }, libusbSourceDir.map { file(it).resolve("COPYING") })
    outputs.files(nativeBinaries.filter { it.asFile.name in setOf("libasicend.so", "libasicenctl.so", "libasicen-ts.so") })
    dependsOn(prepareLibusbSource)
    dependsOn(prepareAsicenFirmware)
    doLast {
        val source = file(asicenUserlandDir.get())
        val staged = project.rootDir.resolve(".work/staged-asicen-userland")
        val stagedCore = corePatchedSourceDir
        stagePinnedSource(source, asicenPinnedRef, staged)
        if (!stagedCore.resolve("DTV-PATCH-METADATA.json").isFile) {
            throw GradleException("patched Core source stage is missing: $stagedCore")
        }
        val asicenCoreRevision = staged.resolve("cmake/dtv-userland-core.revision").readText().trim()
        val coreMetadata = stagedCore.resolve("DTV-PATCH-METADATA.json").readText()
        val coreBaseCommit = Regex("\\\"base_commit\\\"\\s*:\\s*\\\"([0-9a-f]{40})\\\"")
            .find(coreMetadata)?.groupValues?.get(1)
        if (!Regex("[0-9a-f]{40}").matches(asicenCoreRevision) ||
            asicenCoreRevision != corePinnedRef || coreBaseCommit != asicenCoreRevision
        ) {
            throw GradleException(
                "ASICEN Core revision pin does not match the staged patched Core: " +
                    "ASICEN=$asicenCoreRevision, Core=${coreBaseCommit ?: "missing"}"
            )
        }
        val coreBundle = asicenCoreBundleDir
        coreBundle.resolve("repository").mkdirs()
        project.copy { from(stagedCore); into(coreBundle.resolve("repository")) }
        coreBundle.resolve("SOURCE-REVISION").writeText("$asicenCoreRevision\n")
        val ndk = file(androidNdkRoot.get())
        val libusb = file(libusbSourceDir.get())
        if (!libusb.resolve("configure").isFile || !libusb.resolve("COPYING").isFile) {
            throw GradleException("Pinned libusb source directory is incomplete: $libusb")
        }
        fun buildAbi(abi: String, sourceAbi: String) {
            val output = project.rootDir.resolve(".work/asicen-output-$abi")
            project.exec {
                workingDir(staged)
                commandLine("/bin/sh", staged.resolve("scripts/build-android.sh").absolutePath,
                    "--abi", sourceAbi, "--output", output.absolutePath, "--libusb-source", libusb.absolutePath)
                environment("ANDROID_NDK_HOME", ndk.absolutePath)
            }
            for (program in listOf("asicend", "asicenctl", "asicen-ts")) {
                val built = output.resolve("$program-$sourceAbi")
                if (!built.isFile) throw GradleException("ASICEN build completed without producing $built")
                val destination = nativeOutputDir.dir(abi).file("lib$program.so").asFile
                destination.parentFile.mkdirs()
                built.copyTo(destination, overwrite = true)
                destination.setExecutable(true, false)
            }
        }
        buildAbi("arm64-v8a", "arm64-v8a")
        buildAbi("armeabi-v7a", "armeabi-v7a")
    }
}

val preparePixBinaries = tasks.register("preparePixBinaries") {
    dependsOn(preparePatchedCoreSource)
    inputs.property("pixUserlandDir", pixUserlandDir)
    inputs.files(pixBuildScript, pixTrackedSourceFiles, coreTrackedSourceFiles, coreConcurrencyPatch, pixAndroid16kPatch)
    inputs.files(pixFirmwareAssets)
    inputs.file(libusbArchive)
    outputs.files(nativeBinaries.filter { it.asFile.name in setOf("libpixd.so", "libpixctl.so", "libpix-ts.so") })
    dependsOn(prepareLibusbSource)
    doLast {
        val source = file(pixUserlandDir.get())
        val staged = project.rootDir.resolve(".work/staged-pix-userland")
        val stagedCore = corePatchedSourceDir
        stagePinnedSource(source, pixPinnedRef, staged)
        val patch = pixAndroid16kPatch.asFile
        val actualPatchHash = MessageDigest.getInstance("SHA-256")
            .digest(patch.readBytes())
            .joinToString("") { byte: Byte -> "%02x".format(byte.toInt() and 0xff) }
        val expectedPatchHash = "a1155cb6400c59222abc5348d3e31a89def378e24c5540e62aed272fb02590d2"
        if (actualPatchHash != expectedPatchHash) {
            throw GradleException(
                "PIX Android 16 KiB patch checksum mismatch: expected $expectedPatchHash, " +
                    "found $actualPatchHash"
            )
        }
        val patchCheck = ProcessBuilder(
            "patch", "--dry-run", "--batch", "--fuzz=0", "--forward", "-p1", "-i", patch.absolutePath
        ).directory(staged).redirectErrorStream(true).start()
        val patchCheckOutput = patchCheck.inputStream.bufferedReader().use { it.readText() }.trim()
        if (patchCheck.waitFor() != 0) {
            throw GradleException("Pinned PIX Android 16 KiB patch does not apply cleanly: $patchCheckOutput")
        }
        project.exec {
            workingDir(staged)
            commandLine("patch", "--batch", "--fuzz=0", "--forward", "-p1", "-i", patch.absolutePath)
        }
        if (!stagedCore.resolve("DTV-PATCH-METADATA.json").isFile) {
            throw GradleException("patched Core source stage is missing: $stagedCore")
        }
        val ndk = file(androidNdkRoot.get())
        fun buildAbi(abi: String, sourceAbi: String) {
            project.exec {
                workingDir(staged)
                commandLine("/bin/sh", staged.resolve("tools/build-portable.sh").absolutePath, "android")
                environment("ANDROID_NDK_HOME", ndk.absolutePath)
                environment("ANDROID_ABI", sourceAbi)
                environment("DTV_USERLAND_CORE_SOURCE_DIR", stagedCore.absolutePath)
                environment("PIX_SOURCE_REVISION_OVERRIDE", pixPinnedRef)
                environment("PIX_EMBEDDED_ASSETS", "ON")
                environment("JOBS", "1")
                environment("LIBUSB_SOURCE_TARBALL", file(libusbArchive.get()).absolutePath)
            }
            val output = staged.resolve("build/android-$sourceAbi")
            for (program in listOf("pixd", "pixctl", "pix-ts")) {
                val built = output.resolve(program)
                if (!built.isFile) throw GradleException("PIX build completed without producing $built")
                val destination = nativeOutputDir.dir(abi).file("lib$program.so").asFile
                destination.parentFile.mkdirs()
                built.copyTo(destination, overwrite = true)
                destination.setExecutable(true, false)
            }
        }
        buildAbi("arm64-v8a", "aarch64")
        buildAbi("armeabi-v7a", "armv7a")
    }
}

val apkSourceMetadataDir = layout.buildDirectory.dir("generated/source-metadata")
val debugApkSourceMetadataDir = layout.buildDirectory.dir("generated/debug-source-metadata")
val apkSourceMetadataScript = layout.projectDirectory.file("../tools/mirakc/generate-apk-metadata.py")
val apkSourceMetadataAuditScript = layout.projectDirectory.file("../tools/mirakc/audit-apk-metadata.py")
val apkSourceMetadataDtvRef = providers.gradleProperty("dtvRef")
    .orElse(providers.environmentVariable("DTV_REF"))
    .orElse("HEAD")
val apkSourceMetadataDtvRoot = providers.gradleProperty("dtvRoot")
    .orElse(project.rootDir.absolutePath)
val apkSourceMetadataDtvHead = apkSourceMetadataDtvRoot.map {
    gitOutput(file(it), "rev-parse", "HEAD").second
}
val apkDevelopmentDtvFiles = apkSourceMetadataDtvRoot.map { directoryName ->
    val root = file(directoryName)
    val status = gitStatusOutput(root)
    if (status.first != 0) throw GradleException("cannot inspect DTV development source inputs: $root")
    String(status.second, Charsets.UTF_8).split('\u0000').filter { it.length >= 4 }.mapNotNull { record ->
        val relative = record.substring(3)
        val basename = relative.substringAfterLast('/').toLowerCase()
        if (basename.contains("secret") || basename.contains("credential") || basename.contains(".env") ||
            relative.split('/').any { it == ".storage" || it == ".ssh" }) {
            throw GradleException("DTV development source overlay contains a sensitive path")
        }
        root.resolve(relative).takeIf { it.isFile && !Files.isSymbolicLink(it.toPath()) }
    }
}
val apkDevelopmentDtvStatus = apkSourceMetadataDtvRoot.map { directoryName ->
    val root = file(directoryName)
    val status = gitStatusOutput(root)
    if (status.first != 0) throw GradleException("cannot inspect DTV development source status: $root")
    Base64.getEncoder().encodeToString(status.second)
}
val apkSourceMetadataLibusb = providers.gradleProperty("libusbArchive")
    .orElse(libusbArchive)

fun apkMetadataLicenseInputs(directory: Provider<String>) = directory.map { directoryName ->
    project.fileTree(file(directoryName)) {
        include(
            "**/LICENSE", "**/LICENSE.*", "**/LICENCE", "**/LICENCE.*",
            "**/COPYING", "**/COPYING.*", "**/NOTICE", "**/NOTICE.*",
            "**/THIRD_PARTY_NOTICES*", "**/DEPENDENCY-NOTICE*"
        )
        exclude(".git/**", "**/.git/**", "**/build/**", "**/.cxx/**", "**/CMakeFiles/**")
    }
}
val apkSourceMetadataMirakcLicenses = apkMetadataLicenseInputs(mirakcSourceDir)
val apkSourceMetadataMirakcAribLicenses = apkMetadataLicenseInputs(mirakcAribSourceDir)
val apkSourceMetadataSianoLicenses = apkMetadataLicenseInputs(sianoUserlandDir)
val apkSourceMetadataPx4Licenses = apkMetadataLicenseInputs(px4UserlandDir)
val apkSourceMetadataAsicenLicenses = apkMetadataLicenseInputs(asicenUserlandDir)
val apkSourceMetadataPixLicenses = apkMetadataLicenseInputs(pixUserlandDir)
val apkSourceMetadataCoreLicenses = apkMetadataLicenseInputs(userlandCoreDir)
val apkSourceMetadataDtvFiles = apkSourceMetadataDtvRoot.map { directoryName ->
    listOf(
        "LICENSE", "mirakc/VERSION", "mirakc/build.gradle.kts",
        "tools/mirakc/generate-apk-metadata.py", "tools/mirakc/audit-apk-metadata.py",
        "tools/mirakc/audit-source.py", "tools/mirakc/audit-apk-native.sh", "tools/mirakc/build-android.sh",
        "tools/mirakc/fetch-pinned-userland-sources.sh",
        "tools/mirakc/package-source.py", "tools/mirakc/test-source-package.sh", "tools/mirakc/test-source-native-rebuild.sh",
        "tools/mirakc/patches/dtv-core-receiver-control-concurrency.patch",
        "tools/mirakc/patches/px4-userland-build-jobs.patch",
        "tools/mirakc/patches/pix-userland-android-16k.patch",
        "tools/mirakc-arib/build-android.sh", "tools/mirakc-arib/bootstrap-autotools.sh",
        "tools/mirakc-arib/android.toolchain.cmake", "tools/mirakc-arib/patches/tsduck-android.patch"
    ).map { relative -> file(directoryName).resolve(relative) }
}
tasks.register("writeDebugDtvStatusInputFixture") {
    group = "verification"
    description = "Writes the encoded DTV worktree status input for an isolated Gradle fixture"
    inputs.property("dtvRef", apkSourceMetadataDtvRef)
    inputs.property("dtvRoot", apkSourceMetadataDtvRoot)
    inputs.property("dtvWorktreeStatus", apkDevelopmentDtvStatus)
    inputs.property("dtvHead", apkSourceMetadataDtvHead)
    inputs.files(apkDevelopmentDtvFiles)
    val fixtureOutput = layout.buildDirectory.file("tmp/debug-dtv-status-input-fixture.txt")
    outputs.file(fixtureOutput)
    doLast {
        fixtureOutput.get().asFile.apply {
            parentFile.mkdirs()
            writeText(apkDevelopmentDtvStatus.get())
        }
    }
}
val generateApkSourceMetadata = tasks.register("generateApkSourceMetadata") {
    group = "verification"
    description = "Generates deterministic APK source and license provenance assets"
    dependsOn(prepareMirakcBinary, prepareMirakcAribBinaries, prepareSianoBinaries, preparePx4Binaries, prepareAsicenBinaries, preparePixBinaries, prepareLibusbSource)
    inputs.property("dtvRef", apkSourceMetadataDtvRef)
    inputs.property("dtvRoot", apkSourceMetadataDtvRoot)
    inputs.property("dtvWorktreeStatus", apkDevelopmentDtvStatus)
    inputs.files(apkDevelopmentDtvFiles)
    inputs.property("dtvHead", apkSourceMetadataDtvHead)
    inputs.files(
        apkSourceMetadataScript,
        apkSourceMetadataAuditScript,
        layout.projectDirectory.file("../tools/mirakc/audit-source.py"),
        layout.projectDirectory.file("LICENSE"),
        layout.projectDirectory.file("VERSION"),
        layout.projectDirectory.dir("src/main/cpp/arib25")
    )
    inputs.files(apkSourceMetadataDtvFiles)
    inputs.files(mirakcTrackedSourceFiles, sianoTrackedSourceFiles, px4TrackedSourceFiles,
        asicenTrackedSourceFiles, pixTrackedSourceFiles, coreTrackedSourceFiles, sianoPx4TrackedSourceFiles)
    inputs.files(
        apkSourceMetadataMirakcLicenses,
        apkSourceMetadataMirakcAribLicenses,
        apkSourceMetadataSianoLicenses,
        apkSourceMetadataPx4Licenses,
        apkSourceMetadataAsicenLicenses,
        apkSourceMetadataPixLicenses,
        apkSourceMetadataCoreLicenses
    )
    inputs.files(apkSourceMetadataLibusb)
    inputs.file(asicenFirmwareAsset)
    inputs.file(layout.projectDirectory.file("src/main/assets/ASICEN-LOADER-NOTICE.txt"))
    inputs.files(pixFirmwareAssets)
    outputs.dir(apkSourceMetadataDir)
    doLast {
        project.exec {
            workingDir(project.rootDir)
            commandLine(
                "python3", apkSourceMetadataScript.asFile.absolutePath,
                "--output", apkSourceMetadataDir.get().asFile.absolutePath,
                "--dtv-root", file(apkSourceMetadataDtvRoot.get()).absolutePath,
                "--dtv-ref", apkSourceMetadataDtvRef.get(),
                "--mirakc-root", file(mirakcSourceDir.get()).absolutePath,
                "--arib-root", file(mirakcAribSourceDir.get()).absolutePath,
                "--siano-root", file(sianoUserlandDir.get()).absolutePath,
                "--px4-root", file(px4UserlandDir.get()).absolutePath,
                "--asicen-root", file(asicenUserlandDir.get()).absolutePath,
                "--pix-root", file(pixUserlandDir.get()).absolutePath,
                "--core-root", file(userlandCoreDir.get()).absolutePath,
                "--siano-px4-root", file(sianoPx4UserlandDir.get()).absolutePath,
                "--arib25-root", layout.projectDirectory.dir("src/main/cpp/arib25").asFile.absolutePath,
                "--libusb-archive", file(apkSourceMetadataLibusb.get()).absolutePath
            )
        }
    }
}

val generateDebugApkSourceMetadata = tasks.register("generateDebugApkSourceMetadata") {
    group = "verification"
    description = "Generates APK source provenance with an explicit DTV worktree overlay for Debug"
    dependsOn(prepareMirakcBinary, prepareMirakcAribBinaries, prepareSianoBinaries, preparePx4Binaries, prepareAsicenBinaries, preparePixBinaries, prepareLibusbSource)
    inputs.property("dtvRef", apkSourceMetadataDtvRef)
    inputs.property("dtvRoot", apkSourceMetadataDtvRoot)
    inputs.property("dtvWorktreeStatus", apkDevelopmentDtvStatus)
    inputs.property("dtvHead", apkSourceMetadataDtvHead)
    inputs.files(apkDevelopmentDtvFiles)
    inputs.files(apkSourceMetadataScript, apkSourceMetadataAuditScript, layout.projectDirectory.file("../tools/mirakc/audit-source.py"))
    inputs.files(apkSourceMetadataDtvFiles)
    inputs.files(mirakcTrackedSourceFiles, sianoTrackedSourceFiles, px4TrackedSourceFiles,
        asicenTrackedSourceFiles, pixTrackedSourceFiles, coreTrackedSourceFiles, sianoPx4TrackedSourceFiles)
    inputs.files(apkSourceMetadataMirakcLicenses, apkSourceMetadataMirakcAribLicenses,
        apkSourceMetadataSianoLicenses, apkSourceMetadataPx4Licenses, apkSourceMetadataAsicenLicenses,
        apkSourceMetadataPixLicenses, apkSourceMetadataCoreLicenses, apkSourceMetadataLibusb)
    inputs.files(asicenFirmwareAsset, layout.projectDirectory.file("src/main/assets/ASICEN-LOADER-NOTICE.txt"), pixFirmwareAssets)
    outputs.dir(debugApkSourceMetadataDir)
    doLast {
        project.exec {
            workingDir(project.rootDir)
            commandLine(
                "python3", apkSourceMetadataScript.asFile.absolutePath,
                "--output", debugApkSourceMetadataDir.get().asFile.absolutePath,
                "--dtv-root", file(apkSourceMetadataDtvRoot.get()).absolutePath,
                "--dtv-ref", apkSourceMetadataDtvRef.get(),
                "--allow-development-worktree",
                "--mirakc-root", file(mirakcSourceDir.get()).absolutePath,
                "--arib-root", file(mirakcAribSourceDir.get()).absolutePath,
                "--siano-root", file(sianoUserlandDir.get()).absolutePath,
                "--px4-root", file(px4UserlandDir.get()).absolutePath,
                "--asicen-root", file(asicenUserlandDir.get()).absolutePath,
                "--pix-root", file(pixUserlandDir.get()).absolutePath,
                "--core-root", file(userlandCoreDir.get()).absolutePath,
                "--siano-px4-root", file(sianoPx4UserlandDir.get()).absolutePath,
                "--arib25-root", layout.projectDirectory.dir("src/main/cpp/arib25").asFile.absolutePath,
                "--libusb-archive", file(apkSourceMetadataLibusb.get()).absolutePath
            )
        }
    }
}

tasks.named("writeDebugDtvStatusInputFixture") {
    doLast {
        val expectedHead = apkSourceMetadataDtvHead.get()
        val expectedRoot = apkSourceMetadataDtvRoot.get()
        val expectedStatus = apkDevelopmentDtvStatus.get()
        val expectedDirtyFiles = apkDevelopmentDtvFiles.get().map { it.canonicalFile }.toSet()
        listOf(generateApkSourceMetadata, generateDebugApkSourceMetadata).forEach { metadataTask ->
            val task = metadataTask.get()
            val properties = task.inputs.properties
            if (properties["dtvHead"] != expectedHead || properties["dtvRoot"] != expectedRoot ||
                properties["dtvWorktreeStatus"] != expectedStatus) {
                throw GradleException("${task.name} is missing current DTV root, HEAD, or status inputs")
            }
            val taskFiles = task.inputs.files.files.map { it.canonicalFile }.toSet()
            expectedDirtyFiles.forEach { dirtyFile ->
                if (!taskFiles.contains(dirtyFile)) {
                    throw GradleException("${task.name} is missing dirty DTV byte input $dirtyFile")
                }
            }
        }
    }
}

plugins.withId("com.android.application") {
    tasks.matching { it.name == "preBuild" || it.name.endsWith("JniLibFolders") }
        .configureEach {
            if (ciNativeCacheEnabled) dependsOn(verifyCachedMirakcNativeBinaries)
            dependsOn(prepareSianoBinaries)
            dependsOn(prepareAsicenBinaries)
            dependsOn(preparePixBinaries)
            dependsOn(prepareAsicenFirmware)
            dependsOn(prepareSianoAdapterBinaries)
            dependsOn(prepareAsicenAdapterBinaries)
            dependsOn(prepareB25FilterBinaries)
            dependsOn(preparePx4AdapterBinaries)
            dependsOn(preparePixAdapterBinaries)
            dependsOn(prepareMirakcAribBinaries)
            dependsOn(preparePx4Binaries)
            dependsOn(prepareMirakcBinary)
            dependsOn(prepareFirmware)
        }
    tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(generateDebugApkSourceMetadata) }
    tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(generateApkSourceMetadata) }
}

// Set by the release workflow. Without it the release build stays unsigned, so
// a local `assembleRelease` never silently produces something installable.
val releaseKeystore = providers.environmentVariable("KEYSTORE_FILE").orNull

android {
    namespace = "dev.khronos31.mirakc"
    compileSdk = 34
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
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

    sourceSets.getByName("debug").assets.srcDir(debugApkSourceMetadataDir)
    sourceSets.getByName("release").assets.srcDir(apkSourceMetadataDir)

    packagingOptions {
        doNotStrip("**/*.so")
        jniLibs {
            useLegacyPackaging = true
            excludes += "**/libmirakc-px4-fwtool.so"
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    implementation(project(":updater"))
    implementation(project(":mirakc-ui-contract"))
    implementation(project(":mirakc-tv-ui"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.runtime:runtime:1.6.8")
    implementation("androidx.compose.ui:ui:1.6.8")
    testImplementation("junit:junit:4.13.2")
}
