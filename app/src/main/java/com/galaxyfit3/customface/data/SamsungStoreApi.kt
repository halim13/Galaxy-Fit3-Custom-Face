package com.galaxyfit3.customface.data

import android.net.Uri
import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Resolved face from the SM-R390 store catalogue. */
data class CatalogFace(
    val id: String,
    val appId: String,
    val name: String,
    val versionCode: String,
    val sizeBytes: Long,
    val previewUrl: String = ""
)

/**
 * Replicates the stock "Galaxy Fit 3" plugin's calls to the Samsung Galaxy Store
 * to list downloadable faces and fetch their OPPO container seed (the .bin file
 * lives inside an APK package under assets/).
 */
@Singleton
class SamsungStoreApi @Inject constructor(
    private val client: OkHttpClient
) {

    companion object {
        private const val USER_AGENT = "Mozilla/5.0"
    }

    private fun qs(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "${Uri.encode(k)}=${Uri.encode(v)}" }

    private fun get(url: String): okhttp3.Response? =
        client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute()

    fun listFaces(): List<CatalogFace> {
        val xml = getXml(catalogUrl())
        return parseCatalog(xml)
    }

    /** Download the face package and extract the SM-R390_*_256x402.bin seed. */
    fun downloadSeed(face: CatalogFace): ByteArray {
        val uri = resolveDownloadUri(face.appId)
            ?: throw IllegalStateException("Store refused to resolve ${face.appId}")
        get(uri)?.use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Download failed: HTTP ${resp.code}")
            val apk = resp.body?.bytes() ?: throw IllegalStateException("Empty package for ${face.appId}")
            return extractContainerMember(apk)
                ?: throw IllegalStateException("No SM-R390_*_256x402.bin found in ${face.appId} package")
        } ?: throw IllegalStateException("No response for download URI")
    }

    private fun catalogUrl(): String =
        "https://vas.samsungapps.com/vas/product/getContentCategoryProductList.as?" + qs(
            "imgWidth" to "216", "imgHeight" to "432",
            "startNum" to "1", "endNum" to "100",
            "status" to "1", "cc" to "KOR", "extraInfo" to "screenshot",
            "callerId" to "com.samsung.wearable.fit3plugin", "locale" to "en_US",
            "alignOrder" to "recent", "contentCategoryID" to "0000004252",
            "mcc" to "450", "mnc" to "10", "csc" to "NONE",
            "deviceId" to "SM-R390", "sdkVer" to "34", "pd" to "0"
        )

    private fun resolveDownloadUrl(appId: String): String =
        "https://vas.samsungapps.com/vas/stub/gearAppDownload.as?" + qs(
            "callerId" to "com.samsung.wearable.fit3plugin",
            "versionCode" to "126071051", "extuk" to "83a1c4f7", "systemId" to "1730000000000",
            "abiType" to "64", "cc" to "KOR", "deviceId" to "SM-R390", "locale" to "en_US",
            "mcc" to "450", "mnc" to "10", "csc" to "NONE", "loginType" to "N", "pd" to "0",
            "oneUiVersion" to "0", "contentCategoryID" to "0000004252", "sdkVer" to "34",
            "appInfo" to appId,
            "hashValue" to storeHash(appId)
        )

    private fun resolveDownloadUri(appId: String): String? {
        val xml = getXml(resolveDownloadUrl(appId))
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var uri: String? = null
        var resultCode = -1
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "downloadURI") {
                uri = parser.nextText().trim().ifBlank { null }
            } else if (event == XmlPullParser.START_TAG && parser.name == "resultCode") {
                resultCode = parser.nextText().trim().toIntOrNull() ?: -1
            }
            event = parser.next()
        }
        return uri?.takeIf { resultCode == 1 }
    }

    private fun storeHash(appId: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest((appId + "GALAXYAPPSAPI").toByteArray(Charsets.ISO_8859_1))
        return android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
    }

    private fun getXml(url: String): String {
        get(url)?.use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Store HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        } ?: throw IllegalStateException("No response")
    }

    internal fun parseCatalog(xml: String): List<CatalogFace> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val out = ArrayList<CatalogFace>()
        var event = parser.eventType
        val appInfo = HashMap<String, String>()
        var key = ""
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    key = parser.name
                    if (key == "appInfo") appInfo.clear()
                    else appInfo[key] = ""
                }
                XmlPullParser.TEXT -> {
                    if (key.isNotEmpty()) appInfo[key] = (appInfo[key] ?: "") + parser.text
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "appInfo") {
                        val appId = appInfo["appId"]?.trim().orEmpty()
                        val m = Regex("sm_r390_(\\d{4,5})\$", RegexOption.IGNORE_CASE).find(appId)
if (m != null) {
                        out += CatalogFace(
                            id = m.groupValues[1].padStart(5, '0'),
                            appId = appId,
                            name = appInfo["productName"]?.trim().orEmpty(),
                            versionCode = appInfo["versionCode"]?.trim().orEmpty(),
                            sizeBytes = appInfo["realContentSize"]?.trim()?.toLongOrNull() ?: 0L,
                            previewUrl = screenshotUrl(appInfo) ?: appInfo["iconImgURL"]?.trim().orEmpty()
                        )
                    }
                    }
                    key = ""
                }
            }
            event = parser.next()
        }
        return out
    }

    /**
     * The catalogue lists one base shot URL per face; each screenshot is that base
     * with `_<w>_<h>_<n>.png` appended. Only the 256x402 shots match the watch's
     * own panel, so those are the real face previews — the icon field is a generic
     * default and is only a fallback.
     */
    private fun screenshotUrl(appInfo: Map<String, String>): String? {
        val base = appInfo["screenShotImgURL"]?.trim().orEmpty()
        if (!base.startsWith("https://")) return null
        val resolutions = appInfo["screenShotResolution"]?.trim().orEmpty()
            .split('|').map { it.trim() }
        val shots = appInfo["screenShotIndex"]?.trim().orEmpty()
            .split('|').map { it.trim().toIntOrNull() }
        val pos = resolutions.indexOfFirst { it.equals("256x402", ignoreCase = true) }
        if (pos < 0) return null
        val shot = shots.getOrNull(pos) ?: (pos + 1)
        return "${base.removeSuffix(".png")}_256_402_$shot.png"
    }

    internal fun extractContainerMember(apk: ByteArray): ByteArray? {
        val pattern = Regex("SM-R390_\\d{5}_256x402\\.bin$")
        ZipInputStream(apk.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (pattern.containsMatchIn(entry.name)) {
                    return zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        return null
    }
}