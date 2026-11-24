package ai.opencode.feature.system.updates

import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

object AppUpdateChannel {
    const val packageName = "ai.opencode.rocmobilecode"
    const val repository = "roc198419/opencode-android"
    const val certificate = "176a82beabb79af4abcb73765a1fc1c962a6b8bb964b505455874907323b7172"
    const val releases = "https://github.com/$repository/releases/download"
}

@Serializable
data class AppUpdateRelease(
    val schemaVersion: Int,
    val versionName: String,
    val versionCode: Long,
    val packageName: String,
    val minSdk: Int,
    val apkSize: Long,
    val sha256: String,
    val certificateSha256: String,
    val apkUrl: String,
) {
    fun validate(installed: InstalledAppUpdate) {
        requireUpdate(installed.supported && schemaVersion == 1)
        requireUpdate(versionName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
        requireUpdate(versionCode > 0 && minSdk in 1..installed.sdk)
        requireUpdate(packageName == installed.packageName && certificateSha256 == AppUpdateChannel.certificate)
        requireUpdate(apkSize in 1..MAX_APK_BYTES && sha256.matches(Regex("[a-f0-9]{64}")))
        requireUpdate(apkUrl == "${AppUpdateChannel.releases}/v$versionName/opencode-android-$versionName.apk")
    }

    companion object {
        const val MAX_APK_BYTES = 512L * 1024 * 1024
    }
}

data class InstalledAppUpdate(
    val packageName: String = "",
    val versionName: String = "",
    val versionCode: Long = 0,
    val certificate: String = "",
    val debuggable: Boolean = true,
    val sdk: Int = 0,
) {
    val supported: Boolean get() = packageName == AppUpdateChannel.packageName &&
        certificate == AppUpdateChannel.certificate && !debuggable
}

enum class AppUpdateError { Check, Download, Verification, Install }

internal class AppUpdateException(val reason: AppUpdateError) : Exception()

internal fun requireUpdate(valid: Boolean) {
    if (!valid) throw AppUpdateException(AppUpdateError.Verification)
}

internal suspend fun latestAppUpdate(installed: InstalledAppUpdate, readJson: suspend (String) -> String): AppUpdateRelease? {
    val json = Json { ignoreUnknownKeys = true }
    if (!installed.supported) return null
    val listing = json.decodeFromString<GitHubRelease>(readJson("https://api.github.com/repos/${AppUpdateChannel.repository}/releases/latest"))
    requireUpdate(!listing.draft && !listing.prerelease)
    requireUpdate(listing.tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")))
    val base = "${AppUpdateChannel.releases}/${listing.tag}"
    val metadata = listing.assets.singleOrNull { it.name == "release.json" }
    requireUpdate(metadata?.url == "$base/release.json" && metadata.size in 1..(64 * 1024).toLong())
    val release = json.decodeFromString<AppUpdateRelease>(readJson("$base/release.json"))
    release.validate(installed)
    requireUpdate(listing.tag == "v${release.versionName}")
    val apk = listing.assets.singleOrNull { it.url == release.apkUrl }
    requireUpdate(apk?.size == release.apkSize)
    requireUpdate(apk?.digest == null || apk.digest == "sha256:${release.sha256}")
    return release.takeIf { it.versionCode > installed.versionCode }
}

@Serializable private data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<Asset>,
)
@Serializable private data class Asset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long,
    val digest: String? = null,
)
