package com.galaxyfit3.customface.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Latest GitHub release that carries an APK to update the app with. */
data class LatestRelease(
    val tag: String,
    val apkUrl: String,
    val sizeBytes: Long
)

/**
 * Reads the newest release from GitHub Releases (no auth: public repos only, rate
 * limited to 60 req/h which is plenty for a manual "check for updates" tap).
 */
@Singleton
class GithubUpdateApi @Inject constructor(
    private val client: OkHttpClient
) {
    companion object {
        const val REPO = "halim13/Galaxy-Fit3-Custom-Face"
        private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    }

    /** The latest release's APK asset, or null when the repo has no release yet. */
    fun latestApk(): LatestRelease? {
        val resp = client.newCall(Request.Builder().url(LATEST_URL).build()).execute()
        resp.use {
            if (it.code == 404) return null
            if (!it.isSuccessful) throw IllegalStateException("GitHub HTTP ${it.code}")
            val json = JSONObject(it.body?.string().orEmpty())
            val apk = json.getJSONArray("assets").takeIf { l -> l.length() > 0 }
                ?.let { l -> (0 until l.length()).map { j -> l.getJSONObject(j) }
                    .firstOrNull { a -> a.optString("name").endsWith(".apk") } }
                ?: return null
            return LatestRelease(
                tag = json.optString("tag_name", "latest"),
                apkUrl = apk.getString("browser_download_url"),
                sizeBytes = apk.optLong("size", 0L)
            )
        }
    }
}