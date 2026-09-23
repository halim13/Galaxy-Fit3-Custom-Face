package com.galaxyfit3.customface.data

import android.content.Context
import android.net.Uri
import com.galaxyfit3.core.format.model.StyleEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** The edited state of ONE face style. A style with no entry ships stock. */
data class StyleEdit(
    val styleIndex: Int,
    val placed: List<PlacedDesign> = emptyList(),
    val frameOverrides: List<FrameOverride> = emptyList(),
    val backgroundUri: String? = null,
    val backgroundFit: String = "cover",
    val backgroundColor: String? = null
)

/** A design session: seed face + one placed/edit set per style. */
data class Project(
    val id: String,
    val name: String,
    val seedName: String,
    val createdAt: Long,
    /** styleIndex -> the edits made to that style; missing = untouched (stock look). */
    val styleEdits: Map<Int, StyleEdit> = emptyMap(),
    /** The style selected in the editor; Preview renders it so what the user edited
     *  is what the install page shows. */
    val styleIndex: Int = 0
) {
    val lastModified: Long get() = styleEdits.values.flatMap { it.placed }.maxOfOrNull { it.ts } ?: createdAt

    // Flat mirrors of the active style's edit, for callers that read the current
    // style (Home widget count, install's active style, preview).
    val placed: List<PlacedDesign> get() = styleEdits[styleIndex]?.placed ?: emptyList()
    val frameOverrides: List<FrameOverride> get() = styleEdits[styleIndex]?.frameOverrides ?: emptyList()
    val backgroundUri: String? get() = styleEdits[styleIndex]?.backgroundUri
    /** "cover"/"fit"/"stretch"/"center" or a "manual@…" placement string (pinch/pan). */
    val backgroundFit: String get() = styleEdits[styleIndex]?.backgroundFit ?: "cover"
    /** Solid background color when no image is used, as #RRGGBB; null otherwise. */
    val backgroundColor: String? get() = styleEdits[styleIndex]?.backgroundColor
}

/** A widget frame whose image the user replaced. */
data class FrameOverride(
    val donorIndex: Int,
    val frameIndex: Int,
    /** "cover" (centre-crop) or "fit" (letterbox, transparent padding). */
    val fit: String
)

/** One widget placed on the canvas, referencing a donor record in the seed. */
data class PlacedDesign(
    val donorIndex: Int,
    val x: Int,
    val y: Int,
    val ts: Long,
    val meaning: String,
    /** Custom text ARGB as "#RRGGBB"; null = use the record's firmware colour. */
    val color: String? = null
)

/** Persists projects as JSON in app-private storage. */
class ProjectStore(root: File, private val context: Context? = null) {

    private val root: File = root.apply { mkdirs() }

    constructor(context: Context) : this(File(context.filesDir, "projects"), context)

    fun create(seedBytes: ByteArray, seedName: String, name: String): Project {
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val dir = File(root, id).apply { mkdirs() }
        File(dir, "seed.bin").writeBytes(seedBytes)
        val project = Project(id, name, seedName, System.currentTimeMillis())
        save(project)
        return project
    }

    fun save(project: Project) {
        val dir = File(root, project.id).apply { mkdirs() }
        // Write to a temp file then rename: a reader has no window where it parses a
        // half-written design.json (persist fan-out is heavy, so reads do race saves).
        val tmp = File(dir, "design.json.tmp.${System.nanoTime()}")
        val target = File(dir, "design.json")
        tmp.writeText(projectToJson(project).toString())
        if (!tmp.renameTo(target)) {
            target.writeBytes(tmp.readBytes())
            tmp.delete()
        }
    }

    private fun projectToJson(project: Project): JSONObject = JSONObject().apply {
        put("id", project.id)
        put("name", project.name)
        put("seedName", project.seedName)
        put("createdAt", project.createdAt)
        put("styleIndex", project.styleIndex)
        put("styleEdits", JSONArray().apply {
            project.styleEdits.values.sortedBy { it.styleIndex }.forEach { e ->
                put(JSONObject().apply {
                    put("styleIndex", e.styleIndex)
                    put("backgroundUri", e.backgroundUri ?: JSONObject.NULL)
                    put("backgroundFit", e.backgroundFit)
                    put("backgroundColor", e.backgroundColor ?: JSONObject.NULL)
                    put("frameOverrides", JSONArray().apply {
                        e.frameOverrides.forEach { fo ->
                            put(JSONObject().apply {
                                put("donorIndex", fo.donorIndex)
                                put("frameIndex", fo.frameIndex)
                                put("fit", fo.fit)
                            })
                        }
                    })
                    put("placed", JSONArray().apply {
                        e.placed.forEach { p ->
                            put(JSONObject().apply {
                                put("donorIndex", p.donorIndex)
                                put("x", p.x)
                                put("y", p.y)
                                put("ts", p.ts)
                                put("meaning", p.meaning)
                                put("color", p.color ?: JSONObject.NULL)
                            })
                        }
                    })
                })
            }
        })
    }

    /** Rename a project; its folder and id stay the same. */
    fun rename(projectId: String, newName: String) {
        val p = get(projectId) ?: return
        val trimmed = newName.trim().take(60)
        if (trimmed.isEmpty()) return
        save(p.copy(name = trimmed))
    }

    fun projects(): List<Project> {
        return root.listFiles()?.mapNotNull { dir ->
            val f = File(dir, "design.json")
            if (!f.exists()) null else read(f.readText())
        }?.sortedByDescending { it.lastModified } ?: emptyList()
    }

    fun get(id: String): Project? {
        val f = File(File(root, id), "design.json")
        return if (f.exists()) read(f.readText()) else null
    }

    fun seedBytes(id: String): ByteArray? {
        val f = File(File(root, id), "seed.bin")
        return if (f.exists()) f.readBytes() else null
    }

    /** The organiser stores an imported donor face's raw container so widgets borrowed
     *  from it can be reconstructed on reopen (records + rasters live in that container). */
    fun saveDonorFace(projectId: String, bytes: ByteArray): String {
        val f = donorFaceFile(projectId)
        f.writeBytes(bytes)
        return f.absolutePath
    }

    fun donorFaceBytes(projectId: String): ByteArray? {
        val f = donorFaceFile(projectId)
        return if (f.exists()) f.readBytes() else null
    }

    fun removeDonorFace(projectId: String) {
        donorFaceFile(projectId).delete()
    }

    private fun donorFaceFile(projectId: String): File = File(File(root, projectId), "donor_face.bin")

    fun projectDir(id: String): File = File(root, id)

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    private fun read(text: String): Project {
        val json = JSONObject(text)
        val styleEdits = json.optJSONArray("styleEdits")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> readStyleEdit(arr.optJSONObject(i)) }
                .associateBy { it.styleIndex }
        } ?: run {
            // Legacy flat schema: the whole-face edit is assigned to the recorded
            // style, matching the per-style model ("only the style you edited changes").
            val sel = json.optInt("styleIndex", 0)
            mapOf(sel to StyleEdit(
                styleIndex = sel,
                placed = readPlaced(json.optJSONArray("placed")),
                frameOverrides = readFrameOverrides(json.optJSONArray("frameOverrides")),
                backgroundUri = if (json.isNull("backgroundUri")) null else json.optString("backgroundUri"),
                backgroundFit = json.optString("backgroundFit", "cover"),
                backgroundColor = if (json.isNull("backgroundColor")) null else json.optString("backgroundColor")
            ))
        }
        return Project(
            id = json.getString("id"),
            name = json.optString("name", "Untitled"),
            seedName = json.optString("seedName", ""),
            createdAt = json.optLong("createdAt", 0),
            styleEdits = styleEdits,
            styleIndex = json.optInt("styleIndex", 0)
        )
    }

    private fun readStyleEdit(o: JSONObject): StyleEdit? = if (o == null) null else StyleEdit(
        styleIndex = o.optInt("styleIndex", 0),
        placed = readPlaced(o.optJSONArray("placed")),
        frameOverrides = readFrameOverrides(o.optJSONArray("frameOverrides")),
        backgroundUri = if (o.isNull("backgroundUri")) null else o.optString("backgroundUri"),
        backgroundFit = o.optString("backgroundFit", "cover"),
        backgroundColor = if (o.isNull("backgroundColor")) null else o.optString("backgroundColor")
    )

    private fun readPlaced(arr: JSONArray?): List<PlacedDesign> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            PlacedDesign(
                donorIndex = o.getInt("donorIndex"),
                x = o.getInt("x"),
                y = o.getInt("y"),
                ts = o.optLong("ts", System.currentTimeMillis()),
                meaning = o.optString("meaning", "UNKNOWN"),
                color = if (o.isNull("color")) null else o.optString("color")
            )
        }
    }

    private fun readFrameOverrides(arr: JSONArray?): List<FrameOverride> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            FrameOverride(o.getInt("donorIndex"), o.getInt("frameIndex"), o.optString("fit", "cover"))
        }
    }

    fun copyBackground(projectId: String, uri: Uri): String {
        val ctx = context ?: throw IllegalStateException("No content resolver available")
        val dir = File(root, projectId).apply { mkdirs() }
        val copy = File(dir, "background.png")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            copy.outputStream().use { output -> input.copyTo(output) }
        }
        return copy.absolutePath
    }

    /** Read a content uri fully; null when it cannot be opened. Used by the editor to
     *  decode a picked background on a worker thread instead of the UI thread. */
    fun readUriBytes(uri: Uri): ByteArray? = try {
        context?.contentResolver?.openInputStream(uri)?.use { it.readBytes() }
    } catch (_: Exception) {
        null
    }

    /** Store picked image bytes as the [styleIndex] style's background file; returns its path. */
    fun saveBackground(projectId: String, styleIndex: Int, bytes: ByteArray): String {
        val dir = File(root, projectId).apply { mkdirs() }
        val f = backgroundFile(projectId, styleIndex)
        f.writeBytes(bytes)
        return f.absolutePath
    }

    /** Drop a style's custom background file, if any. */
    fun removeBackground(projectId: String, styleIndex: Int) {
        backgroundFile(projectId, styleIndex).delete()
    }

    fun backgroundFile(projectId: String, styleIndex: Int): File =
        File(File(root, projectId), "background_$styleIndex.png")

    /** Directory holding per-frame replacement images (`frames/<style>_<donor>_<frame>.png`). */
    private fun framesDir(projectId: String): File = File(File(root, projectId), "frames")

    fun frameFile(projectId: String, styleIndex: Int, donorIndex: Int, frameIndex: Int): File =
        File(framesDir(projectId), "${styleIndex}_${donorIndex}_${frameIndex}.png")

    /** Persist a replacement frame image as PNG (path is deterministic per frame). */
    fun saveFrameImage(projectId: String, styleIndex: Int, donorIndex: Int, frameIndex: Int, bitmap: android.graphics.Bitmap) {
        val dir = framesDir(projectId).apply { mkdirs() }
        frameFile(projectId, styleIndex, donorIndex, frameIndex).outputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    /** Remove a replacement frame image, if any. */
    fun deleteFrameFile(projectId: String, styleIndex: Int, donorIndex: Int, frameIndex: Int) {
        frameFile(projectId, styleIndex, donorIndex, frameIndex).delete()
    }

    /** Cached list thumbnail; see HomeViewModel for why it is kept on disk. */
    fun thumbnailFile(projectId: String): File = File(File(root, projectId), "thumbnail.png")

    fun writeOutput(projectId: String, bytes: ByteArray): File {
        val dir = File(root, projectId).apply { mkdirs() }
        val f = File(dir, "output.bin")
        f.writeBytes(bytes)
        return f
    }

    /** The seed style (index 0) used as the widget donor library. */
    fun seedStyle(project: Project, container: com.galaxyfit3.core.format.model.WatchFaceContainer): StyleEntry? {
        container.entries
            .filter { it.name == "style0.bin" }
            .mapNotNull { it.payload as? com.galaxyfit3.core.format.model.ContainerPayload.Style }
            .map { it.data }
            .firstOrNull()
            ?.let { return it }
        return null
    }
}