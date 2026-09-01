plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersionText = file("VERSION").readText().trim()
val appVersionParts = Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$""").matchEntire(appVersionText)
    ?: throw GradleException("epgstation-server/VERSION must hold a semantic version such as 1.2.3, found \"$appVersionText\"")
val (appMajor, appMinor, appPatch) = appVersionParts.destructured
val appVersionCode = appMajor.toInt() * 10000 + appMinor.toInt() * 100 + appPatch.toInt()

// Set by the release workflow. Without it the release build stays unsigned, so
// a local `assembleRelease` never silently produces something installable.
val releaseKeystore = providers.environmentVariable("KEYSTORE_FILE").orNull

android {
    namespace = "dev.khronos31.epgstation.server"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.khronos31.epgstation.server"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionText
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
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

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("epgstation-assets"))
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("epgstation-jniLibs"))

    packagingOptions {
        doNotStrip("**/*.so")
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

val prepareEpgStationPayload = tasks.register<Exec>("prepareEpgStationPayload") {
    workingDir(rootProject.projectDir)
    commandLine("bash", "tools/prepare-epgstation-payload.sh")
}

val stageEpgStationPayload = tasks.register<Sync>("stageEpgStationPayload") {
    dependsOn(prepareEpgStationPayload)
    from(rootProject.file("epgstation-server/.generated/epgstation-payload")) {
        exclude("runtime/**")
        exclude("native/**")
        exclude("**/*.node")
        exclude("**/*.so")
    }
    into(layout.buildDirectory.dir("epgstation-assets"))
}

val stageEpgStationJniLibs = tasks.register<Sync>("stageEpgStationJniLibs") {
    dependsOn(prepareEpgStationPayload)
    from(rootProject.file("epgstation-server/.generated/jniLibs"))
    into(layout.buildDirectory.dir("epgstation-jniLibs"))
}

tasks.matching {
    it.name == "preBuild" || it.name.endsWith("JniLibFolders")
}.configureEach {
    dependsOn(stageEpgStationPayload)
    dependsOn(stageEpgStationJniLibs)
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    implementation("com.google.zxing:core:3.5.3")
    implementation(project(":updater"))
}
