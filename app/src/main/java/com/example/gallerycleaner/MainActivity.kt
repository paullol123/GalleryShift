package com.gallerysift.app

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.gallerycleaner.ui.theme.GalleryCleanerTheme
import java.util.Locale

// ============================================================================
// 1. DATENMODELL & MEDIEN-LOADER (MIT ORDNER-FILTER & ORDNER-ERKENNUNG)
// ============================================================================

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val sizeInBytes: Long
)

fun getAllFolders(context: Context): List<String> {
    val folderSet = mutableSetOf<String>()
    val projection = arrayOf(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
    val queryUri = MediaStore.Files.getContentUri("external")

    val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
    val selectionArgs = arrayOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
    )

    context.contentResolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
        val folderColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
        if (folderColumn != -1) {
            while (cursor.moveToNext()) {
                val folderName = cursor.getString(folderColumn)
                if (!folderName.isNullOrEmpty()) {
                    folderSet.add(folderName)
                }
            }
        }
    }
    return folderSet.sortedWith(String.CASE_INSENSITIVE_ORDER)
}

fun loadAllMedia(context: Context, loadImages: Boolean, loadVideos: Boolean, selectedFolder: String?): List<MediaItem> {
    val mediaList = mutableListOf<MediaItem>()

    val sharedPrefs = context.getSharedPreferences("GalleryCleanerPrefs", Context.MODE_PRIVATE)
    val decidedIdsStrings = sharedPrefs.getStringSet("decided_media_ids", emptySet()) ?: emptySet()
    val decidedIds = decidedIdsStrings.mapNotNull { it.toLongOrNull() }.toSet()

    val selectionClauses = mutableListOf<String>()
    val selectionArgsList = mutableListOf<String>()

    val typeClauses = mutableListOf<String>()
    if (loadImages) {
        typeClauses.add("${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?")
        selectionArgsList.add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
    }
    if (loadVideos) {
        typeClauses.add("${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?")
        selectionArgsList.add(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
    }

    if (typeClauses.isEmpty()) return emptyList()

    var selection = "(" + typeClauses.joinToString(" OR ") + ")"

    if (!selectedFolder.isNullOrEmpty() && selectedFolder != "Alle Ordner") {
        selection += " AND ${MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME} = ?"
        selectionArgsList.add(selectedFolder)
    }

    val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.SIZE
    )
    val queryUri = MediaStore.Files.getContentUri("external")

    context.contentResolver.query(queryUri, projection, selection, selectionArgsList.toTypedArray(), null)?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)

            if (decidedIds.contains(id)) {
                continue
            }

            val type = cursor.getInt(typeColumn)
            val size = cursor.getLong(sizeColumn)
            val isVideo = type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO

            val contentUri = ContentUris.withAppendedId(
                if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                id
            )
            mediaList.add(MediaItem(id, contentUri, isVideo, size))
        }
    }
    return mediaList.shuffled()
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

// ============================================================================
// 2. HAUPTAKTIVITÄT & AUSFALLSICHERER VIDEO PLAYER
// ============================================================================

class MainActivity : ComponentActivity() {

    private val sharedPrefs by lazy { getSharedPreferences("GalleryCleanerPrefs", Context.MODE_PRIVATE) }
    private var pendingBytesToDelete: Long = 0

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val currentSaved = sharedPrefs.getLong("freed_bytes", 0L)
            val currentCount = sharedPrefs.getInt("deleted_count", 0)

            sharedPrefs.edit()
                .putLong("freed_bytes", currentSaved + pendingBytesToDelete)
                .putInt("deleted_count", currentCount + 1)
                .apply()

            finish()
            startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GalleryCleanerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val totalFreedBytes = remember { mutableStateOf(sharedPrefs.getLong("freed_bytes", 0L)) }
                    val totalDeletedRounds = remember { mutableStateOf(sharedPrefs.getInt("deleted_count", 0)) }

                    val totalDecisionTime = remember { mutableStateOf(sharedPrefs.getLong("total_decision_time_ms", 0L)) }
                    val totalDecisionCount = remember { mutableStateOf(sharedPrefs.getInt("total_decision_count", 0)) }

                    MainScreen(
                        totalFreed = totalFreedBytes.value,
                        totalRounds = totalDeletedRounds.value,
                        totalDecisionTimeMs = totalDecisionTime.value,
                        totalDecisionCount = totalDecisionCount.value,
                        onDeleteFinal = { uriList, totalBytes ->
                            if (uriList.isNotEmpty()) {
                                pendingBytesToDelete = totalBytes
                                val pendingIntent = MediaStore.createDeleteRequest(contentResolver, uriList)
                                val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                                deleteLauncher.launch(intentSenderRequest)
                            }
                        },
                        onMediaDecided = { mediaId, timeSpentMs ->
                            val currentDecided = sharedPrefs.getStringSet("decided_media_ids", emptySet()) ?: emptySet()
                            val updatedDecided = currentDecided.toMutableSet().apply { add(mediaId.toString()) }

                            val newTimeSum = totalDecisionTime.value + timeSpentMs
                            val newCountSum = totalDecisionCount.value + 1

                            totalDecisionTime.value = newTimeSum
                            totalDecisionCount.value = newCountSum

                            sharedPrefs.edit()
                                .putStringSet("decided_media_ids", updatedDecided)
                                .putLong("total_decision_time_ms", newTimeSum)
                                .putInt("total_decision_count", newCountSum)
                                .apply()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun VideoPlayer(videoUri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    key(videoUri) {
        var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

        DisposableEffect(videoUri) {
            val player = ExoPlayer.Builder(context).build().apply {
                setMediaItem(ExoMediaItem.fromUri(videoUri))
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f
                prepare()
                playWhenReady = true
            }
            exoPlayer = player

            onDispose {
                player.release()
                exoPlayer = null
            }
        }

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                }
            },
            update = { view ->
                if (view.player != exoPlayer) {
                    view.player = exoPlayer
                }
            },
            modifier = modifier
        )
    }
}

// ============================================================================
// 3. BENUTZEROBERFLÄCHE (STATISTIK & ORDNERAUSWAHL AUF STARTSEITE)
// ============================================================================

enum class AppScreen {
    START_DASHBOARD, SORTING, REVIEW
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    totalFreed: Long,
    totalRounds: Int,
    totalDecisionTimeMs: Long,
    totalDecisionCount: Int,
    onDeleteFinal: (List<Uri>, Long) -> Unit,
    onMediaDecided: (Long, Long) -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(false) }

    var allMedia by remember { mutableStateOf(listOf<MediaItem>()) }
    var currentIndex by remember { mutableStateOf(0) }
    val toDeleteList = remember { mutableStateListOf<MediaItem>() }
    var currentScreen by remember { mutableStateOf(AppScreen.START_DASHBOARD) }

    var filterImages by remember { mutableStateOf(true) }
    var filterVideos by remember { mutableStateOf(true) }

    var folderList by remember { mutableStateOf(listOf("Alle Ordner")) }
    var selectedFolder by remember { mutableStateOf("Alle Ordner") }
    var dropdownExpanded by remember { mutableStateOf(false) }

    var itemStartTime by remember { mutableStateOf(0L) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermission = permissions.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            ))
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            folderList = listOf("Alle Ordner") + getAllFolders(context)
        }
    }

    LaunchedEffect(currentIndex, currentScreen) {
        if (currentScreen == AppScreen.SORTING) {
            itemStartTime = System.currentTimeMillis()
        }
    }

    if (!hasPermission) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Die App benötigt Zugriff auf deine Galerie.")
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GallerySift") }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {

            when (currentScreen) {
                AppScreen.START_DASHBOARD -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 1. STATISTIK OBEN
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            Text(text = "Deine Aufräum-Erfolge ✨", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(20.dp))

                            Text(text = "Freigegebener Speicherplatz:", fontSize = 15.sp, color = Color.Gray)
                            Text(text = formatSize(totalFreed), fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF4CAF50))

                            Spacer(modifier = Modifier.height(10.dp))

                            val avgTimeSeconds = if (totalDecisionCount > 0) {
                                (totalDecisionTimeMs.toDouble() / totalDecisionCount.toDouble()) / 1000.0
                            } else {
                                0.0
                            }
                            val formattedAvgTime = String.format(Locale.GERMANY, "%.2f s", avgTimeSeconds)

                            Text(text = "Ø Entscheidungszeit:", fontSize = 15.sp, color = Color.Gray)
                            Text(text = formattedAvgTime, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(text = "Erfolgreiche Putz-Runden: $totalRounds", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }

                        // 2. FILTER & ORDNERAUSWAHL IN DER MITTE
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "Was möchtest du aufräumen?", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(16.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = filterImages, onCheckedChange = { filterImages = it })
                                Text("Fotos anzeigen", fontSize = 16.sp)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = filterVideos, onCheckedChange = { filterVideos = it })
                                Text("Videos anzeigen", fontSize = 16.sp)
                            }

                            Spacer(modifier = Modifier.height(24.dp))
                            Text(text = "Ordner auswählen:", fontSize = 14.sp, color = Color.Gray)
                            Spacer(modifier = Modifier.height(8.dp))

                            Box(modifier = Modifier.fillMaxWidth().wrapContentSize(Alignment.Center)) {
                                OutlinedCard(
                                    modifier = Modifier
                                        .fillMaxWidth(0.9f)
                                        .clickable { dropdownExpanded = true }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(text = selectedFolder, fontSize = 16.sp, fontWeight = FontWeight.Medium)

                                        Canvas(modifier = Modifier.size(24.dp)) {
                                            val path = Path().apply {
                                                moveTo(6.dp.toPx(), 9.dp.toPx())
                                                lineTo(12.dp.toPx(), 15.dp.toPx())
                                                lineTo(18.dp.toPx(), 9.dp.toPx())
                                            }
                                            drawPath(
                                                path = path,
                                                color = Color.Gray,
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                                    width = 2.dp.toPx(),
                                                    cap = StrokeCap.Round
                                                )
                                            )
                                        }
                                    }
                                }

                                DropdownMenu(
                                    expanded = dropdownExpanded,
                                    onDismissRequest = { dropdownExpanded = false },
                                    modifier = Modifier.fillMaxWidth(0.85f)
                                ) {
                                    folderList.forEach { folder ->
                                        DropdownMenuItem(
                                            text = { Text(folder) },
                                            onClick = {
                                                selectedFolder = folder
                                                dropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // 3. AKTIONEN UNTEN
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                        ) {
                            Button(
                                onClick = {
                                    allMedia = loadAllMedia(context, filterImages, filterVideos, selectedFolder)
                                    currentIndex = 0
                                    currentScreen = AppScreen.SORTING
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = filterImages || filterVideos
                            ) {
                                Text("Runde starten")
                            }
                        }
                    }
                }

                AppScreen.SORTING -> {
                    if (allMedia.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Keine neuen Medien in diesem Ordner gefunden!")
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(onClick = { currentScreen = AppScreen.START_DASHBOARD }) {
                                    Text("Zurück zum Hauptmenü")
                                }
                            }
                        }
                    } else if (currentIndex < allMedia.size) {
                        val currentItem = allMedia[currentIndex]

                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Datei ${currentIndex + 1} von ${allMedia.size}",
                                style = MaterialTheme.typography.titleMedium
                            )

                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (currentItem.isVideo) {
                                    VideoPlayer(videoUri = currentItem.uri, modifier = Modifier.fillMaxSize())
                                } else {
                                    AsyncImage(
                                        model = currentItem.uri,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                Button(
                                    onClick = {
                                        val duration = System.currentTimeMillis() - itemStartTime
                                        onMediaDecided(currentItem.id, duration)
                                        currentIndex++
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                                ) {
                                    Text("Behalten")
                                }

                                Button(
                                    onClick = {
                                        val duration = System.currentTimeMillis() - itemStartTime
                                        toDeleteList.add(currentItem)
                                        onMediaDecided(currentItem.id, duration)
                                        currentIndex++
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                                ) {
                                    Text("Löschen")
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = { currentScreen = AppScreen.REVIEW },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = toDeleteList.isNotEmpty()
                            ) {
                                Text("Fertig (${toDeleteList.size} überprüfen)")
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Alles durchgeschaut!")
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(onClick = { currentScreen = AppScreen.REVIEW }) {
                                    Text("Zur Übersicht")
                                }
                            }
                        }
                    }
                }

                AppScreen.REVIEW -> {
                    val finalDeleteSet = remember { mutableStateListOf<MediaItem>().apply { addAll(toDeleteList) } }

                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text("Auswahl überprüfen", style = MaterialTheme.typography.titleLarge)
                        Text("Entferne den Haken, falls du ein Bild/Video behalten willst.", style = MaterialTheme.typography.bodyMedium)

                        Spacer(modifier = Modifier.height(16.dp))

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(toDeleteList) { item ->
                                val isChecked = finalDeleteSet.contains(item)
                                Box(modifier = Modifier.aspectRatio(1f)) {
                                    AsyncImage(
                                        model = item.uri,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            if (checked) finalDeleteSet.add(item) else finalDeleteSet.remove(item)
                                        },
                                        modifier = Modifier.align(Alignment.TopEnd)
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            OutlinedButton(onClick = { currentScreen = AppScreen.SORTING }) {
                                Text("Zurück")
                            }
                            Button(
                                onClick = {
                                    val totalBytes = finalDeleteSet.sumOf { it.sizeInBytes }
                                    onDeleteFinal(finalDeleteSet.map { it.uri }, totalBytes)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                            ) {
                                Text("Ausgewählte löschen")
                            }
                        }
                    }
                }
            }
        }
    }
}