package nl.giejay.android.tv.immich.plus.update

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.BuildConfig
import nl.giejay.android.tv.immich.R
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

data class GithubRelease(
    @SerializedName("tag_name") val tagName: String,
    val name: String?,
    val body: String?,
    val assets: List<GithubReleaseAsset>
)

data class GithubReleaseAsset(
    val name: String,
    @SerializedName("browser_download_url") val downloadUrl: String
)

/**
 * Self-update from the GitHub releases of the fork (see .github/workflows/studio-release.yml).
 *
 * Releases are tagged "studio-<versionCode>" and carry the APK as [APK_NAME]. A newer versionCode
 * is downloaded and handed to the system installer, which asks the user for confirmation.
 */
object UpdateChecker {
    const val APK_NAME = "ImmichTVStudio.apk"
    private const val TAG_PREFIX = "studio-"
    private const val PREFS = "plus_updater"
    private const val KEY_LAST_CHECK = "last_check"
    private val CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(24)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var running = false

    fun checkIfDue(activity: FragmentActivity) {
        // debug builds have no matching release and must not replace themselves
        if (BuildConfig.DEBUG || running) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

        running = true
        activity.lifecycleScope.launch {
            try {
                val release = withContext(Dispatchers.IO) { fetchLatestRelease() } ?: return@launch
                val remoteVersionCode = release.tagName.removePrefix(TAG_PREFIX).toLongOrNull() ?: return@launch
                val apk = release.assets.firstOrNull { it.name == APK_NAME } ?: return@launch
                if (remoteVersionCode > BuildConfig.VERSION_CODE && !activity.isFinishing) {
                    askToInstall(activity, release, apk)
                }
            } catch (e: Exception) {
                Timber.w(e, "Update check failed")
            } finally {
                running = false
            }
        }
    }

    private fun fetchLatestRelease(): GithubRelease? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.PLUS_UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.w("Update check returned ${response.code}")
                return null
            }
            return Gson().fromJson(response.body?.string(), GithubRelease::class.java)
        }
    }

    private fun askToInstall(activity: FragmentActivity, release: GithubRelease, apk: GithubReleaseAsset) {
        val version = release.name ?: release.tagName
        val notes = release.body?.take(600).orEmpty()
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.plus_update_title)
            .setMessage(activity.getString(R.string.plus_update_message, version) + if (notes.isNotBlank()) "\n\n$notes" else "")
            .setPositiveButton(R.string.plus_update_install) { _, _ -> downloadAndInstall(activity, apk) }
            .setNegativeButton(R.string.plus_update_later, null)
            .show()
    }

    private fun downloadAndInstall(activity: FragmentActivity, apk: GithubReleaseAsset) {
        val context = activity.applicationContext
        Toast.makeText(context, R.string.plus_update_downloading, Toast.LENGTH_LONG).show()
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val file = download(apk.downloadUrl, File(context.cacheDir, "update/$APK_NAME"))
                    install(context, file)
                }
            } catch (e: Exception) {
                Timber.e(e, "Could not install update")
                Toast.makeText(context, context.getString(R.string.plus_update_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun download(url: String, target: File): File {
        target.parentFile?.mkdirs()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "Download failed (${response.code})" }
            val body = checkNotNull(response.body) { "Empty download" }
            target.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
        }
        return target
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // only honoured once this app is the installer of record, i.e. from the second update on
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite(APK_NAME, 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, InstallResultReceiver::class.java),
                flags
            )
            session.commit(callback.intentSender)
        }
    }
}
