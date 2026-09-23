package com.galaxyfit3.core.format.parser

import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.format.model.ContainerPayload
import com.galaxyfit3.core.format.model.DirectoryEntry
import com.galaxyfit3.core.format.model.WatchFaceContainer

/**
 * The face identity a container carries, read back from its own bytes.
 *
 * Three independent places must agree for the watch to accept an install request:
 * the `faceId` in `setting.bin` (16-byte ASCII at 0x10), the directory paths
 * (`./SM-R390_<faceId>_256x402/…`), and the filename sent in the install command
 * (`SM-R390_<faceId>_256x402.bin`). A container whose own id is outside 1..255
 * can still parse, validate and transfer cleanly — the mismatch only surfaces at
 * the install command, where the id must fit one protocol byte and the watch then
 * registers a slot that names a file the container is not. That mismatch is what
 * corrupts the watch's face list, so the identity has to be checked — or rewritten
 * to a consistent one — before anything is sent.
 */
data class FaceIdentity(
    /** The id exactly as stored in setting.bin, e.g. "90001" or "00031". */
    val settingFaceId: String,
    /** The `SM-R390_<id>_256x402` prefix every directory path carries, or null
     *  when the paths do not share one shape. */
    val directoryPrefix: String?,
    /** The name string setting.bin carries in its two 64-byte slots. */
    val settingName: String,
) {
    /** Numeric view of [settingFaceId], or null when it is not plain digits. */
    val faceIdNumber: Int? get() = settingFaceId.toIntOrNull()

    /**
     * Whether an install request can address this face at all: the id must fit the
     * protocol byte and every directory path must carry the matching prefix, so the
     * filename the watch registers is the filename the container's entries live under.
     */
    val installable: Boolean
        get() = faceIdNumber in 1..255 &&
            directoryPrefix == "SM-R390_${settingFaceId.padStart(5, '0')}_256x402"

    companion object {
        fun of(container: WatchFaceContainer): FaceIdentity {
            val setting = (container.settingEntry?.payload as? ContainerPayload.Setting)?.data
            return FaceIdentity(
                settingFaceId = setting?.faceId.orEmpty().trim(),
                directoryPrefix = directoryPrefix(container),
                settingName = setting?.name.orEmpty(),
            )
        }

        /** The shared `SM-R390_<id>_256x402` prefix of every directory path, or null
         *  when the entries disagree — which no parseable face has been seen to do. */
        private fun directoryPrefix(container: WatchFaceContainer): String? {
            val prefixes = container.entries.mapNotNull { entry ->
                val path = entry.directory.path
                val dir = path.removePrefix("./").substringBeforeLast('/')
                Regex("""^(SM-R390_\d{5}_256x402)""").find(dir)?.groupValues?.get(1)
            }.toSet()
            return prefixes.singleOrNull()
        }
    }
}

/**
 * Rewrites a container's face identity — setting.bin's id and name slots plus every
 * directory path — and re-serializes with fresh CRCs, so the whole file stays
 * internally consistent under one new id.
 *
 * This is the mechanism for installing onto a face slot the watch already has: the
 * watch-face list only shows registered faces, and a one-shot install can overwrite
 * a registered id but cannot introduce a new one. Editing in place keeps the watch's
 * slot registry intact and retargets the container instead.
 */
object FaceIdRemapper {

    /** A rewrite target: the five-digit id to give the container, 00001..00255. */
    data class Target(val faceId: String) {
        init {
            require(faceId.matches(Regex("""\d{5}"""))) { "Face id must be five digits: $faceId" }
            val n = faceId.toInt()
            require(n in 1..255) { "Face id $n outside the 1..255 install range" }
        }

        val prefix: String get() = "SM-R390_${faceId}_256x402"

        companion object {
            fun of(faceIdNumber: Int): Target = Target(faceIdNumber.toString().padStart(5, '0'))
        }
    }

    /**
     * Re-serialize [container] under [target]'s identity.
     *
     * Only the identity surfaces change: setting.bin's faceId and both name slots,
     * and the directory of every entry. Payloads keep their bytes, so rasters stay
     * in their parsed in-place form and serialize without copying the image data —
     * the same cost profile as an editor rebuild.
     */
    fun remap(container: WatchFaceContainer, target: Target): ByteArray {
        val oldPrefix = FaceIdentity.of(container).directoryPrefix
        val entries = container.entries.map { entry ->
            val path = entry.directory.path
            val remappedPath = if (oldPrefix != null && path.contains(oldPrefix)) {
                path.replace(oldPrefix, target.prefix)
            } else {
                "./${target.prefix}/${entry.name}"
            }
            entry.copy(directory = DirectoryEntry(remappedPath, 0, entry.directory.size, 0))
        }

        val setting = (container.settingEntry?.payload as? ContainerPayload.Setting)?.data
        val remappedEntries = if (setting == null) {
            entries
        } else {
            entries.map { entry ->
                val payload = entry.payload
                if (payload is ContainerPayload.Setting) {
                    val renamed = setting.copy(
                        faceId = target.faceId,
                        name = target.prefix,
                    )
                    entry.copy(payload = ContainerPayload.Setting(renamed))
                } else {
                    entry
                }
            }
        }

        return WatchFaceSerializer.serializeEntries(WatchFaceFormat.VERSION, remappedEntries)
    }
}
