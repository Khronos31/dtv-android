plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.khronos31.epgstation.server"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.khronos31.epgstation.server"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}
