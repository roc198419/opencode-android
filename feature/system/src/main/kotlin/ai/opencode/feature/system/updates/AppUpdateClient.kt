package ai.opencode.feature.system.updates

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.server.OpenCodeTransport
import ai.opencode.feature.system.R
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class AppUpdateClient(
    val installed: () -> InstalledAppUpdate,
    val check: suspend () -> AppUpdateRelease?,
    val download: suspend (AppUpdateRelease, suspend (Long) -> Unit) -> Unit,
    val install: suspend (AppUpdateRelease) -> InstallResult,
    val canInstall: () -> Boolean,
) {
    enum class InstallResult { PermissionRequired, Opened }

    companion object Key : DependencyKey<AppUpdateClient> {
        override val liveValue: AppUpdateClient by lazy {
            val live = LiveAppUpdater(dependency(AppContext), dependency(OpenCodeTransport).request)
            AppUpdateClient(live::installed, live::check, live::download, live::install, live::canInstall)
        }
        override val testValue = AppUpdateClient(
            installed = { InstalledAppUpdate() },
            check = { null },
            download = { _, _ -> },
            install = { InstallResult.Opened },
            canInstall = { false },
        )
    }
}

internal class LiveAppUpdater(private val context: Context, private val http: OkHttpClient) {
    private val files get() = File(context.cacheDir, "app-updates").apply { mkdirs() }
    private val downloads = Mutex()
    private val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())

    fun installed(): InstalledAppUpdate {
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        return InstalledAppUpdate(
            context.packageName, info.versionName.orEmpty(), info.longVersionCode,
            certificate(info).orEmpty(), info.applicationInfo?.flags?.and(ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            Build.VERSION.SDK_INT,
        )
    }

    suspend fun check(): AppUpdateRelease? = latestAppUpdate(installed(), ::readJson)

    suspend fun download(release: AppUpdateRelease, progress: suspend (Long) -> Unit) = downloads.withLock {
        withContext(Dispatchers.IO) {
            release.validate(installed())
            requireUpdate(release.versionCode > installed().versionCode)
            val target = target(release)
            if (target.exists()) {
                try {
                    verify(target, release)
                    progress(release.apkSize)
                    return@withContext
                } catch (error: AppUpdateException) {
                    target.delete()
                }
            }
            files.listFiles()?.filter { it != target }?.forEach { it.delete() }
            val part = File(target.path + ".part")
            try {
                response(release.apkUrl) { response ->
                    requireUpdate(response.body.contentLength() == -1L || response.body.contentLength() == release.apkSize)
                    response.body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            var percent = -1L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                requireUpdate(total <= release.apkSize)
                                output.write(buffer, 0, count)
                                val next = total * 100 / release.apkSize
                                if (next != percent) { percent = next; progress(total) }
                            }
                            requireUpdate(total == release.apkSize)
                        }
                    }
                }
                verify(part, release)
                check(part.renameTo(target))
            } finally {
                part.delete()
            }
        }
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    suspend fun install(release: AppUpdateRelease): AppUpdateClient.InstallResult {
        withContext(Dispatchers.IO) { verify(target(release), release) }
        return withContext(Dispatchers.Main.immediate) {
            if (!canInstall()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                AppUpdateClient.InstallResult.PermissionRequired
            } else {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", target(release))
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    clipData = ClipData.newRawUri("", uri)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
                AppUpdateClient.InstallResult.Opened
            }
        }
    }

    private suspend fun verify(file: File, release: AppUpdateRelease) {
        val installed = installed()
        release.validate(installed)
        requireUpdate(release.versionCode > installed.versionCode && file.isFile && file.length() == release.apkSize)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        requireUpdate(digest.digest().hex() == release.sha256)
        val info = context.packageManager.getPackageArchiveInfo(file.path, flags)
            ?: throw AppUpdateException(AppUpdateError.Verification)
        requireUpdate(info.packageName == release.packageName && info.longVersionCode == release.versionCode && info.versionName == release.versionName)
        requireUpdate(info.applicationInfo?.let { it.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 && it.minSdkVersion <= installed.sdk } == true)
        requireUpdate(certificate(info) == installed.certificate)
    }

    private fun target(release: AppUpdateRelease): File = File(files, "${release.versionCode}-${release.sha256.take(16)}.apk")

    private suspend fun readJson(url: String): String = response(url) { response ->
        val bytes = response.body.byteStream().use { it.readNBytes(MAX_JSON_BYTES + 1) }
        requireUpdate(bytes.size <= MAX_JSON_BYTES)
        bytes.toString(Charsets.UTF_8)
    }

    private suspend fun <T> response(url: String, block: suspend (Response) -> T): T = withContext(Dispatchers.IO) {
        coroutineScope {
            val call = http.newCall(Request.Builder().url(url).header("Accept", "application/vnd.github+json")
                .header("User-Agent", "OpenCode-Android-Updater").build())
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    check(response.isSuccessful)
                    requireUpdate(response.request.url.isHttps)
                    block(response)
                }
            } finally {
                cancellation.cancel()
            }
        }
    }

    private fun certificate(info: PackageInfo): String? = info.signingInfo?.apkContentsSigners
        ?.singleOrNull()?.toByteArray()?.let { MessageDigest.getInstance("SHA-256").digest(it).hex() }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }


    companion object { private const val MAX_JSON_BYTES = 64 * 1024 }
}

class AppUpdateFileProvider : FileProvider(R.xml.app_update_paths)
