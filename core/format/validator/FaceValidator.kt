package com.galaxyfit3.core.format.validator

import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.format.model.*
import com.galaxyfit3.core.format.parser.WatchFaceParser

/** Result of structural validation — a reparse that must be clean before install. */
data class ValidationResult(
    val ok: Boolean,
    val errors: List<String>,
    val warnings: List<String>,
    val reparsed: WatchFaceContainer?,
    val sizeBytes: Int
) {
    companion object {
        fun ok(container: WatchFaceContainer?, sizeBytes: Int, warnings: List<String> = emptyList()) =
            ValidationResult(true, emptyList(), warnings, container, sizeBytes)

        fun fail(errors: List<String>, sizeBytes: Int = 0, warnings: List<String> = emptyList()) =
            ValidationResult(false, errors, warnings, null, sizeBytes)
    }
}

object FaceValidator {

    /**
     * Validate a freshly-serialized byte array by reparsing and re-walking it.
     *
     * @param retainReparsed keep the reparsed container in the result. Only the Preview
     *        page reads it; the editor shows ok/errors/size only, and a reparsed real face
     *        is ~10 MB of heap, so it asks for it to be dropped.
     */
    fun validate(bytes: ByteArray, retainReparsed: Boolean = true): ValidationResult {
        if (bytes.size > WatchFaceFormat.WATCH_CONTAINER_BYTE_CEILING) {
            return ValidationResult.fail(
                listOf("Container is ${bytes.size} bytes, over the 4 MiB firmware ceiling.")
            )
        }
        return try {
            val parsed = WatchFaceParser.parse(bytes)
            validateParsed(parsed, bytes.size, retainReparsed)
        } catch (e: Exception) {
            ValidationResult.fail(listOf(e.message ?: e.javaClass.simpleName), bytes.size)
        }
    }

    /**
     * Validate an already-parsed container.
     *
     * Preview parses the output to render it anyway; revalidating through [validate]
     * would parse the same ~2.6 MB container a second time — another ~10 MB of heap on a
     * path that runs while the editor's state is still alive, right where the GC storm in
     * the logcat hang was worst.
     */
    fun validateParsed(
        parsed: WatchFaceContainer,
        sizeBytes: Int,
        retainParsed: Boolean = false
    ): ValidationResult {
        if (sizeBytes > WatchFaceFormat.WATCH_CONTAINER_BYTE_CEILING) {
            return ValidationResult.fail(
                listOf("Container is $sizeBytes bytes, over the 4 MiB firmware ceiling.")
            )
        }
        val structural = structuralChecks(parsed, sizeBytes)
        if (structural.isEmpty()) {
            return ValidationResult.ok(if (retainParsed) parsed else null, sizeBytes)
        }
        return ValidationResult.fail(structural, sizeBytes)
    }

    /** Non-fatal notes surfaced to the user. */
    fun warnings(container: WatchFaceContainer): List<String> {
        val out = ArrayList<String>()
        container.styleEntries.forEach { entry ->
            val style = (entry.payload as? ContainerPayload.Style)?.data ?: return@forEach
            if (style.rasters.isEmpty()) out.add("${entry.name} carries no background raster.")
            if (style.widgets.none { it.type == WidgetType.PAIR }) out.add("${entry.name} has no value widgets.")
        }
        if (container.aodEntry == null) out.add("No AOD (always-on) style present.")
        return out
    }

    private fun structuralChecks(container: WatchFaceContainer, size: Int): List<String> {
        val errors = ArrayList<String>()
        container.entries.forEach { entry ->
            val payload = entry.payload
            if (payload is ContainerPayload.Style) {
                val style = payload.data
                if (style.imageSectionOffset != 0x18 + style.widgetBytes) {
                    errors += "${entry.name}: image offset ${style.imageSectionOffset} != 24 + ${style.widgetBytes}"
                }
                if (style.entrySize != style.imageSectionOffset + style.imageBytes) {
                    errors += "${entry.name}: entry size ${style.entrySize} != ${style.imageSectionOffset} + ${style.imageBytes}"
                }
                if (style.widgets.size != style.widgetCount) {
                    errors += "${entry.name}: widget records ${style.widgets.size} != count ${style.widgetCount}"
                }

                // Every raster must be referenced by at least one widget offset word.
                val referenced = HashSet<Int>()
                style.widgets.forEach { w ->
                    if (w.isPointerHolder) {
                        val offsets = pointerOffsets(w)
                        offsets.forEach { off ->
                            if (off < 0 || off >= style.imageBytes) {
                                errors += "${entry.name}: widget ${w.sequenceId} refs image offset $off out of range"
                            } else referenced += off
                        }
                    }
                }
            }
        }
        return errors
    }

    private fun pointerOffsets(w: WidgetRecord): List<Int> = when (w.type) {
        WidgetType.STATIC -> {
            val off = w.wordA.toInt()
            if (off > 0) listOf(off) else emptyList()
        }
        WidgetType.HAND -> w.words.drop(1).take(1)
        WidgetType.SPRITE -> w.words
        else -> emptyList()
    }
}