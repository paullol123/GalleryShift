package com.example.gallerycleaner

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.util.Locale

data class MediaItem(val id: Long, val uri: Uri, val isVideo: Boolean, val sizeInBytes: Long)

fun getAllFolders(context: Context): List<String> {
    val folderSet = mutableSetOf<String>()
    val queryUri = MediaStore.Files.getContentUri("external")
    val projection = arrayOf(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)

    try {
        context.contentResolver.query(queryUri, projection, null, null, null)?.use { cursor ->
            val folderColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(folderColumn ?: -1)
                if (!name.isNullOrEmpty()) folderSet.add(name)
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return folderSet.sortedWith(String.CASE_INSENSITIVE_ORDER)
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val group = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.2f %s", bytes / Math.pow(1024.0, group.toDouble()), units[group])
}