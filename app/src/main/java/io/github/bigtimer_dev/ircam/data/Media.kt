package io.github.bigtimer_dev.ircam.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import io.github.bigtimer_dev.ircam.thermal.RawFiles
import io.github.bigtimer_dev.ircam.thermal.RawImage
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A saved snapshot: the RAW PNG (temperatures) and the processed image next to it. */
data class SnapshotItem(
    val stamp: String,
    val rawUri: Uri,
    val imageUri: Uri?,
    val dateMillis: Long,
)

data class VideoItem(val uri: Uri, val name: String, val dateMillis: Long)

/**
 * Files live in the shared Pictures/IRCAM and Movies/IRCAM folders, so they show up in the
 * phone's gallery and can be copied to a PC and opened in ircam-linux or the Windows app.
 */
class Media(private val context: Context) {
    private val resolver get() = context.contentResolver

    companion object {
        const val PICTURES_DIR = "Pictures/IRCAM/"
        const val MOVIES_DIR = "Movies/IRCAM/"

        /** HHmmssfffddMMyyyy, the original program's file-name time stamp. */
        fun timestamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmssSSSddMMyyyy"))
    }

    private fun insert(collection: Uri, name: String, mime: String, dir: String): Uri {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(collection, v) ?: throw IOException("Could not create $name")
    }

    private fun publish(uri: Uri) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    private val images get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val videos get() = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun saveSnapshot(image: Bitmap, rawRgb: ByteArray, w: Int, h: Int): SnapshotItem {
        val stamp = timestamp()
        val imgUri = insert(images, "Snapshot_$stamp.png", "image/png", PICTURES_DIR)
        resolver.openOutputStream(imgUri)!!.use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        publish(imgUri)
        val rawUri = insert(images, "SnapShotRAW_$stamp.png", "image/png", PICTURES_DIR)
        resolver.openOutputStream(rawUri)!!.use { RawFiles.writeRgbPng(it, rawRgb, w, h) }
        publish(rawUri)
        return SnapshotItem(stamp, rawUri, imgUri, System.currentTimeMillis())
    }

    /** Overwrite the processed image of an existing snapshot (after editing it in analysis). */
    fun saveEditedImage(image: Bitmap): Uri {
        val uri = insert(images, "Snapshot_${timestamp()}.png", "image/png", PICTURES_DIR)
        resolver.openOutputStream(uri)!!.use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        publish(uri)
        return uri
    }

    fun newVideo(): Uri = insert(videos, "Recording_${timestamp()}.mp4", "video/mp4", MOVIES_DIR)
    fun finishVideo(uri: Uri) = publish(uri)
    fun discard(uri: Uri) { runCatching { resolver.delete(uri, null, null) } }

    fun listSnapshots(): List<SnapshotItem> {
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED)
        val sel = "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
        val raws = HashMap<String, Pair<Uri, Long>>()
        val imgs = HashMap<String, Uri>()
        resolver.query(images, proj, sel, arrayOf(PICTURES_DIR), null)?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nm = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dt = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val name = c.getString(nm) ?: continue
                val uri = ContentUris.withAppendedId(images, c.getLong(id))
                when {
                    name.startsWith("SnapShotRAW_") -> raws[name.removePrefix("SnapShotRAW_").removeSuffix(".png")] = uri to c.getLong(dt) * 1000
                    name.startsWith("Snapshot_") -> imgs[name.removePrefix("Snapshot_").removeSuffix(".png")] = uri
                }
            }
        }
        return raws.map { (stamp, v) -> SnapshotItem(stamp, v.first, imgs[stamp], v.second) }
            .sortedByDescending { it.dateMillis }
    }

    fun listVideos(): List<VideoItem> {
        val proj = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED)
        val out = ArrayList<VideoItem>()
        resolver.query(videos, proj, "${MediaStore.Video.Media.RELATIVE_PATH} = ?", arrayOf(MOVIES_DIR), null)?.use { c ->
            while (c.moveToNext()) {
                out += VideoItem(ContentUris.withAppendedId(videos, c.getLong(0)), c.getString(1) ?: "", c.getLong(2) * 1000)
            }
        }
        return out.sortedByDescending { it.dateMillis }
    }

    fun loadRaw(uri: Uri): RawImage? = resolver.openInputStream(uri)?.use { RawFiles.load(it) }

    fun delete(item: SnapshotItem) {
        runCatching { resolver.delete(item.rawUri, null, null) }
        item.imageUri?.let { runCatching { resolver.delete(it, null, null) } }
    }
}
