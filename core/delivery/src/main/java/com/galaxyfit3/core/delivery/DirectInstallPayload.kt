package com.galaxyfit3.core.delivery

import java.security.MessageDigest

/**
 * The validated watch-face BIN, frozen at the moment the transfer starts.
 *
 * [copyBytes] hands out an independent copy so an edit cannot change the bytes
 * a transfer is mid-way through.
 */
class DirectInstallPayload(
    val faceId: Int,
    val samplerId: Int,
    val fileName: String,
    val sha256: String,
    bytes: ByteArray,
) {
    private val payload = bytes.copyOf()

    val size: Int
        get() = payload.size

    init {
        require(faceId in 0..255) { "Face ID must fit in the Fit3 protocol byte" }
        require(samplerId in 0..255) { "Sampler ID must fit in the Fit3 protocol byte" }
        require(
            fileName == "SM-R390_${faceId.toString().padStart(5, '0')}_256x402.bin",
        ) { "Binary filename does not match face ID $faceId" }
        require(payload.isNotEmpty()) { "Watch-face binary is empty" }
        require(payload.size <= MAX_DIRECT_INSTALL_BYTES) {
            "Watch-face binary exceeds the 16 MiB direct-install limit"
        }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) {
            "SHA-256 must be lowercase hexadecimal"
        }
        require(payload.sha256() == sha256) {
            "Watch-face binary does not match its frozen SHA-256"
        }
    }

    fun copyBytes(): ByteArray = payload.copyOf()

    companion object {
        const val MAX_DIRECT_INSTALL_BYTES: Int = 16 * 1024 * 1024

        fun create(
            faceId: Int,
            samplerId: Int,
            fileName: String,
            bytes: ByteArray,
        ): DirectInstallPayload = DirectInstallPayload(
            faceId = faceId,
            samplerId = samplerId,
            fileName = fileName,
            sha256 = bytes.sha256(),
            bytes = bytes,
        )
    }
}

internal fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { value -> "%02x".format(value.toInt() and 0xff) }