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
}

val prepareEpgStationPayload = tasks.register<Exec>("prepareEpgStationPayload") {
    workingDir(rootProject.projectDir)
    commandLine("bash", "tools/prepare-epgstation-payload.sh")
}

val stageEpgStationPayload = tasks.register<Sync>("stageEpgStationPayload") {
    dependsOn(prepareEpgStationPayload)
    from(rootProject.file("epgstation-server/.generated/epgstation-payload"))
    into(layout.buildDirectory.dir("epgstation-assets"))
}

tasks.named("preBuild").configure { dependsOn(stageEpgStationPayload) }

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}
