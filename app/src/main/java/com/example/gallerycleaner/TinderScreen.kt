package com.gallerysift.app

import android.content.ContentUris
import android.content.Intent
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun TinderScreen(
    includeImages: Boolean,
    includeVideos: Boolean,
    folder: String,
    sortOption: String,
    navController: NavController,
    onNavigateToSummary: (List<MediaItem>) -> Unit
) {
    val context = LocalContext.current
    val prefs = PreferenceHelper.getPrefs(context)
    var mediaList by remember { mutableStateOf(listOf<MediaItem>()) }
    var currentIndex by remember { mutableIntStateOf(0) }
    val toDeleteList = remember { mutableStateListOf<MediaItem>() }
    val startTime = remember { System.currentTimeMillis() }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (currentIndex > 0) {
                val durationSeconds = (System.currentTimeMillis() - startTime) / 1000f
                if (durationSeconds > 0) {
                    val currentSpeed = currentIndex.toFloat() / durationSeconds
                    val totalSum = prefs.getFloat("total_speed_sum", 0f)
                    val sessionCount = prefs.getInt("session_count", 0)
                    prefs.edit()
                        .putFloat("total_speed_sum", totalSum + currentSpeed)
                        .putInt("session_count", sessionCount + 1)
                        .commit()
                }
            }
            exoPlayer.release()
        }
    }

    LaunchedEffect(Unit) {
        val list = mutableListOf<MediaItem>()
        val seenIds = prefs.getStringSet("seen_media_ids", emptySet()) ?: emptySet()
        val query = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME
        )
        val selection = if (folder != "Alle Ordner") "${MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME} = ?" else null
        val selectionArgs = if (folder != "Alle Ordner") arrayOf(folder) else null

        context.contentResolver.query(query, projection, selection, selectionArgs, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val type = cursor.getInt(typeCol)
                val size = cursor.getLong(sizeCol)
                val date = cursor.getLong(dateCol)
                val isVideo = (type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)

                if (((includeVideos && isVideo) || (includeImages && type == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)) && !seenIds.contains(id.toString())) {
                    val uri = ContentUris.withAppendedId(if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    list.add(MediaItem(id, uri, isVideo, size, date))
                }
            }
        }

        // --- DEBUG: Sortierung ---
        Log.d("SortDebug", "Sortierung aktiv: $sortOption")

        mediaList = when (sortOption) {
            "Neuste" -> list.sortedByDescending { it.dateAdded }
            "Älteste" -> list.sortedBy { it.dateAdded }
            "Größte" -> list.sortedByDescending { it.size }
            "Kleinste" -> list.sortedBy { it.size }
            else -> list.shuffled()
        }

        if (mediaList.isNotEmpty()) {
            val first = mediaList.first()
            Log.d("SortDebug", "Erstes Element nach Sortierung: ID=${first.id}, Size=${first.size}, Date=${first.dateAdded}")
        } else {
            Log.d("SortDebug", "Keine Medien gefunden!")
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { navController.navigate("main_screen") { popUpTo(0) } }) { Icon(Icons.Default.Home, null) }
            if (currentIndex < mediaList.size) {
                IconButton(onClick = {
                    val item = mediaList[currentIndex]
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(item.uri, if (item.isVideo) "video/*" else "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                }) { Icon(Icons.Default.Visibility, "Öffnen") }
            }
        }

        if (currentIndex < mediaList.size) {
            val item = mediaList[currentIndex]
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (item.isVideo) {
                    DisposableEffect(item.uri) {
                        exoPlayer.setMediaItem(ExoMediaItem.fromUri(item.uri))
                        exoPlayer.prepare()
                        exoPlayer.play()
                        onDispose { exoPlayer.stop() }
                    }
                    AndroidView({ PlayerView(it).apply { player = exoPlayer; useController = false } }, Modifier.fillMaxSize())
                } else {
                    AsyncImage(item.uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }

            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = {
                        toDeleteList.add(item)
                        val savedBytes = prefs.getLong("saved_bytes", 0L)
                        prefs.edit().putLong("saved_bytes", savedBytes + item.size).apply()
                        PreferenceHelper.markAsSeen(context, item.id.toString())
                        currentIndex++
                    }, Modifier.weight(1f)) { Text("Löschen") }

                    Button(onClick = {
                        PreferenceHelper.markAsSeen(context, item.id.toString())
                        currentIndex++
                    }, Modifier.weight(1f)) { Text("Behalten") }
                }
                OutlinedButton(onClick = {
                    if (toDeleteList.isEmpty()) navController.navigate("main_screen") { popUpTo(0) { inclusive = true } }
                    else onNavigateToSummary(toDeleteList)
                }, Modifier.fillMaxWidth()) {
                    Text(if (toDeleteList.isEmpty()) "Überprüfung beenden" else "Abschließen (${toDeleteList.size})")
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = {
                    if (toDeleteList.isEmpty()) navController.navigate("main_screen") { popUpTo(0) { inclusive = true } }
                    else onNavigateToSummary(toDeleteList)
                }, Modifier.fillMaxWidth()) { Text("Fertig!") }
            }
        }
    }
}