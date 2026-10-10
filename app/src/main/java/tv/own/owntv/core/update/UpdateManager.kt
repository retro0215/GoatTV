package tv.own.owntv.core.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import tv.own.owntv.BuildConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * In-app updates straight from GitHub Releases: checks the repo's latest release, compares its tag
 * with the installed version, downloads the release APK, and hands it to the system installer.
 * No server of our own Ã¢â‚¬â€ the releases CI already publishes `OwnTV-vX.Y.Z.apk` (arm) and
 * `OwnTV-x86_64-vX.Y.Z.apk` per tag; the asset matching this device's ABI is chosen, so updates
 * also work on an x86_64 emulator.
 */
class UpdateManager(
    private val context: Context,
    sharedClient: OkHttpClient,
) {
    /**
     * The shared client (proxy, DNS, user agent) with an updater-only connection pool. GitHub closes an
     * idle HTTP/1.1 connection after ~30 s, but OkHttp keeps pooled connections for 5 min and the shared
     * client disables OkHttp's retry, so a check 30 s - 5 min after the previous one reused the dead
     * socket and failed with "unexpected end of stream". Idle connections here expire before GitHub
     * drops them, and OkHttp may transparently retry a GET on a fresh connection.
     */
    private val client: OkHttpClient = updateClient(sharedClient)

    data class UpdateInfo(val version: String, val notes: String, val apkUrl: String)

    sealed interface Failure {
        data class CheckHttp(val code: Int) : Failure
        data object NoCompatibleApk : Failure
        data object InvalidReleaseResponse : Failure
        data object CheckNetwork : Failure
        data class DownloadHttp(val code: Int) : Failure
        data object EmptyDownload : Failure
        data object DownloadNetwork : Failure
        data object Install : Failure
    }

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val info: UpdateInfo) : State
        data class Downloading(val percent: Int) : State
        data class Failed(val failure: Failure, val retryInfo: UpdateInfo? = null) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()


    private fun failureFor(error: Throwable, checking: Boolean): Failure = when (error) {
        is CheckHttpException -> Failure.CheckHttp(error.code)
        is DownloadHttpException -> Failure.DownloadHttp(error.code)
        is NoCompatibleApkException -> Failure.NoCompatibleApk
        is InvalidReleaseResponseException -> Failure.InvalidReleaseResponse
        is EmptyDownloadException -> Failure.EmptyDownload
        else -> if (checking) Failure.CheckNetwork else Failure.DownloadNetwork
    }

    val currentVersion: String = BuildConfig.VERSION_NAME

    /** Queries GitHub's latest release for THIS brand; moves to Available / UpToDate / a semantic failure. */
    fun check() {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        _state.value = State.Checking
        scope.launch {
            runCatching {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/${BuildConfig.REPO_PATH}/releases")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", BuildConfig.BRAND_UA)
                    .build()
                // One bounded retry for a dropped connection or timeout. HTTP errors and unusable
                // responses are answers, not network blips, and are never retried.
                val body = withNetworkRetry {
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) throw CheckHttpException(resp.code)
                        resp.body.string()
                    }
                }
                val info = ReleaseSelection.select(
                    body = body,
                    tagPrefix = BuildConfig.RELEASE_TAG_PREFIX,
                    // Production builds ignore pre-releases.
                    includePrereleases = BuildConfig.DEBUG || BuildConfig.DIAGNOSTIC_BUILD,
                    apkPrefix = BuildConfig.UPDATE_APK_PREFIX,
                    // Releases carry one APK per ABI flavor (arm = generic, x86_64 suffixed). Never
                    // silently install an APK for the wrong ABI.
                    wantX86 = android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64",
                )
                if (ReleaseSelection.isNewer(info.version, currentVersion)) _state.value = State.Available(info)
                else _state.value = State.UpToDate
            }.onFailure { error ->
                Log.w(TAG, "update check failed: ${error.message}", error)
                _state.value = State.Failed(failureFor(error, checking = true))
            }
        }
    }

    /** Downloads the release APK with progress, then opens the system installer. */
    fun downloadAndInstall() {
        val info = (_state.value as? State.Available)?.info ?: return
        _state.value = State.Downloading(0)
        scope.launch {
            runCatching {
                val dir = File(context.filesDir, "updates").apply { mkdirs() }
                val out = File(dir, "update.apk")
                val request = Request.Builder().url(info.apkUrl).header("User-Agent", BuildConfig.BRAND_UA).build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw DownloadHttpException(resp.code)
                    val body = resp.body
                    val total = body.contentLength()
                    var copied = 0L
                    body.byteStream().use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                copied += n
                                if (total > 0) _state.value = State.Downloading((copied * 100 / total).toInt())
                            }
                        }
                    }
                    if (copied == 0L) throw EmptyDownloadException()
                }
                runCatching { install(out) }.getOrElse { throw InstallException(it) }
                _state.value = State.Available(info) // dialog stays sane if the user cancels install
            }.onFailure { error ->
                Log.w(TAG, "update download failed: ${error.message}", error)
                val failure = if (error is InstallException) Failure.Install else failureFor(error, checking = false)
                _state.value = State.Failed(failure, retryInfo = info)
            }
        }
    }

    private fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Retries the failed phase without losing a successfully resolved release asset. */
    fun retry() {
        val failed = _state.value as? State.Failed ?: return
        val info = failed.retryInfo
        if (info != null && failed.failure !is Failure.CheckHttp && failed.failure !is Failure.NoCompatibleApk &&
            failed.failure !is Failure.InvalidReleaseResponse && failed.failure !is Failure.CheckNetwork
        ) {
            _state.value = State.Available(info)
            downloadAndInstall()
        } else {
            check()
        }
    }

    fun reset() {
        if (_state.value !is State.Downloading) _state.value = State.Idle
    }

    private class InstallException(cause: Throwable) : IOException(cause)

    companion object {
        private const val TAG = "UpdateManager"

        /** Below GitHub's ~30 s idle timeout, so a pooled connection is never one GitHub already closed. */
        internal const val KEEP_ALIVE_SECONDS = 20L
        private const val MAX_IDLE_CONNECTIONS = 2

        /** Total attempts for an update check: the first plus one retry. */
        internal const val CHECK_ATTEMPTS = 2
        internal const val RETRY_BACKOFF_MS = 1_500L

        internal fun updateClient(shared: OkHttpClient): OkHttpClient = shared.newBuilder()
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            .build()

        /** Network failures (dropped connection, timeout, DNS), not HTTP errors or unusable responses. */
        internal fun isTransient(error: Throwable): Boolean = error is IOException &&
            error !is CheckHttpException && error !is DownloadHttpException &&
            error !is NoCompatibleApkException && error !is InvalidReleaseResponseException &&
            error !is EmptyDownloadException

        /** Runs [block] up to [attempts] times while it fails with a transient network error. */
        internal suspend fun <T> withNetworkRetry(
            attempts: Int = CHECK_ATTEMPTS,
            backoffMs: Long = RETRY_BACKOFF_MS,
            block: () -> T,
        ): T {
            var attempt = 1
            while (true) {
                try {
                    return block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    if (!isTransient(e) || attempt >= attempts) throw e
                    attempt++
                    delay(backoffMs)
                }
            }
        }
    }
}

internal class CheckHttpException(val code: Int) : IOException()
internal class DownloadHttpException(val code: Int) : IOException()
internal class NoCompatibleApkException : IOException()
internal class InvalidReleaseResponseException : IOException()
internal class EmptyDownloadException : IOException()

/**
 * Which GitHub release is this brand's update. The rules are unchanged, only extracted so they can be
 * tested: the newest non-draft release (pre-releases only when includePrereleases) whose tag is the
 * brand prefix immediately followed by an X.Y.Z version, with an APK named for the brand and ABI.
 */
internal object ReleaseSelection {
    private val VERSION = Regex("""\d+\.\d+\.\d+""")

    fun select(
        body: String,
        tagPrefix: String,
        includePrereleases: Boolean,
        apkPrefix: String,
        wantX86: Boolean,
    ): UpdateManager.UpdateInfo {
        if (body.isBlank()) throw InvalidReleaseResponseException()
        val releases = runCatching { org.json.JSONArray(body) }.getOrElse { throw InvalidReleaseResponseException() }

        var targetRelease: JSONObject? = null
        for (i in 0 until releases.length()) {
            val rel = releases.optJSONObject(i) ?: continue
            val tag = rel.optString("tag_name")
            if (rel.optBoolean("draft")) continue
            if (rel.optBoolean("prerelease") && !includePrereleases) continue
            val brandVersion = tag.removePrefix(tagPrefix)
            // Strict brand isolation: tag must start with our prefix AND the very next
            // character must be a digit (prevents 'v' from matching '5star-v').
            if (tag.startsWith(tagPrefix) && brandVersion.firstOrNull()?.isDigit() == true &&
                brandVersion.matches(VERSION)
            ) {
                targetRelease = rel
                break
            }
        }

        val o = targetRelease ?: throw NoCompatibleApkException()
        val version = o.optString("tag_name").removePrefix(tagPrefix).removePrefix("v").takeIf { it.isNotBlank() }
            ?: throw InvalidReleaseResponseException()
        val notes = o.optString("body").take(16_000)
        val assets = o.optJSONArray("assets") ?: throw InvalidReleaseResponseException()
        val apkUrl = (0 until assets.length())
            .asSequence()
            .mapNotNull { assets.optJSONObject(it) }
            .mapNotNull { asset ->
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (!name.endsWith(".apk") || url.isBlank()) return@mapNotNull null
                if (!name.startsWith(apkPrefix, ignoreCase = true)) return@mapNotNull null
                val isX86 = name.contains("x86_64", ignoreCase = true)
                if (isX86 == wantX86) url else null
            }
            .firstOrNull()
            ?: throw NoCompatibleApkException()
        return UpdateManager.UpdateInfo(version, notes, apkUrl)
    }

    /** Numeric segment-wise compare: "1.10.0" > "1.9.3"; non-numeric junk compares as 0. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
