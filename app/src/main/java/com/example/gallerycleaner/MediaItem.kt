package com.gallerysift.app

import android.net.Uri

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val size: Long,
    val dateAdded: Long // Hier hinzugefügt
)