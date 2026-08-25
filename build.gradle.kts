plugins {
    id("com.android.application") version "7.4.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

// VERSION is the single source of truth for both APKs, the release tag, and the
// Releases artifacts. tools/scripts/release_version.py enforces that; do not
// write a version literal into a module build script.
val versionText = file("VERSION").readText().trim()
val versionParts = Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$""").matchEntire(versionText)
    ?: throw GradleException("VERSION must hold a semantic version such as 1.2.3, found \"$versionText\"")
val (major, minor, patch) = versionParts.destructured

extra["appVersionName"] = versionText
extra["appVersionCode"] = major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
