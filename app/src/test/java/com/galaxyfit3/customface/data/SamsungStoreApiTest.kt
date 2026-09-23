package com.galaxyfit3.customface.data

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SamsungStoreApiTest {

    private val api = SamsungStoreApi(OkHttpClient())

    @Test
    fun `catalog xml yields only sm_r390 faces`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <response>
              <resultCode>0</resultCode>
              <appInfos>
                <appInfo>
                  <appId>sm_r390_00031</appId>
                  <versionCode>126071051</versionCode>
                  <productName>Casual Minimalist</productName>
                  <realContentSize>3123456</realContentSize>
                  <screenShotImgURL><![CDATA[https://img.samsungapps.com/00031/preview.png]]></screenShotImgURL>
                  <screenShotResolution>256x402|512x512</screenShotResolution>
                  <screenShotIndex>1|2</screenShotIndex>
                </appInfo>
                <appInfo>
                  <appId>sm_r390_00094</appId>
                  <productName>Passing note</productName>
                  <realContentSize>442000</realContentSize>
                </appInfo>
                <appInfo>
                  <appId>com.samsung.other.app</appId>
                  <productName>Not a face</productName>
                </appInfo>
              </appInfos>
            </response>
        """.trimIndent()

        val faces = api.parseCatalog(xml)
        assertEquals(2, faces.size)
        assertEquals("00031", faces[0].id)
        assertEquals("Casual Minimalist", faces[0].name)
        assertEquals(3_123_456L, faces[0].sizeBytes)
        assertEquals("https://img.samsungapps.com/00031/preview_256_402_1.png", faces[0].previewUrl)
        assertEquals("00094", faces[1].id)
    }

    @Test
    fun `extractContainerMember finds the face bin inside an apk zip`() {
        val zipBytes = buildZip {
            put("META-INF/MANIFEST.MF", "ok")
            put("assets/SM-R390_00031_256x402.bin", ByteArray(42) { 0x7B })
            put("assets/preview.png", ByteArray(8))
        }
        val member = api.extractContainerMember(zipBytes)!!
        assertEquals(42, member.size)
        assertEquals(0x7B, member[0].toInt() and 0xFF)
    }

    @Test
    fun `extractContainerMember returns null when no face bin present`() {
        val zipBytes = buildZip {
            put("assets/SM-R390_00031_128x128.bin", ByteArray(4))
        }
        assertNull(api.extractContainerMember(zipBytes))
    }

    private fun buildZip(block: ZipBuilder.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            ZipBuilder(zos).block()
        }
        return out.toByteArray()
    }

    private class ZipBuilder(private val zos: ZipOutputStream) {
        fun put(name: String, data: ByteArray) {
            zos.putNextEntry(ZipEntry(name))
            zos.write(data)
            zos.closeEntry()
        }

        fun put(name: String, data: String) = put(name, data.toByteArray())
    }
}