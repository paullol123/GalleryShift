package com.gallerysift.app

import android.Manifest
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onNavigateToTinder: (Boolean, Boolean, String, String) -> Unit) {
    AnimatedGradientBackground {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        var refreshKey by remember { mutableIntStateOf(0) }
        var showDialog by remember { mutableStateOf(false) }
        var showInfoDialog by remember { mutableStateOf(false) }

        var includeImages by remember { mutableStateOf(true) }
        var includeVideos by remember { mutableStateOf(true) }
        var folderList by remember { mutableStateOf(listOf(Pair("Alle Ordner", 0))) }
        var selectedFolder by remember { mutableStateOf("Alle Ordner") }
        var expanded by remember { mutableStateOf(false) }

        val sortOptions = listOf("Zufall", "Neuste", "Älteste", "Größte", "Kleinste")
        var selectedSort by remember { mutableStateOf("Zufall") }
        var sortExpanded by remember { mutableStateOf(false) }

        var totalImages by remember { mutableIntStateOf(0) }
        var totalVideos by remember { mutableIntStateOf(0) }
        var remainingImages by remember { mutableIntStateOf(0) }
        var remainingVideos by remember { mutableIntStateOf(0) }

        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
        LaunchedEffect(Unit) { launcher.launch(permissions) }

        DisposableEffect(lifecycleOwner) {
            val observer = object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) { refreshKey++ }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        LaunchedEffect(refreshKey) {
            val prefs = PreferenceHelper.getPrefs(context)
            val seenIds = prefs.getStringSet("seen_media_ids", emptySet()) ?: emptySet()
            val bucketCounts = mutableMapOf<String, Int>()

            val cursor = context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME),
                null, null, null
            )

            var tI = 0; var tV = 0; var rI = 0; var rV = 0
            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val typeCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val bucketCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)

                while (it.moveToNext()) {
                    val bucket = it.getString(bucketCol) ?: "Unbekannt"
                    bucketCounts[bucket] = bucketCounts.getOrDefault(bucket, 0) + 1
                    if (selectedFolder != "Alle Ordner" && bucket != selectedFolder) continue

                    val id = it.getString(idCol)
                    val type = it.getInt(typeCol)
                    if (type == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) {
                        tI++; if (!seenIds.contains(id)) rI++
                    } else if (type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                        tV++; if (!seenIds.contains(id)) rV++
                    }
                }
            }
            folderList = listOf(Pair("Alle Ordner", tI + tV)) + bucketCounts.toList().sortedBy { it.first }
            totalImages = tI; totalVideos = tV
            remainingImages = rI; remainingVideos = rV
        }

        val savedBytes = PreferenceHelper.getPrefs(context).getLong("saved_bytes", 0L)
        val readableSize = if (savedBytes < 1024 * 1024) "${savedBytes / 1024} KB"
        else if (savedBytes < 1024 * 1024 * 1024) "${"%.1f".format(savedBytes.toFloat() / (1024 * 1024))} MB"
        else "${"%.1f".format(savedBytes.toFloat() / (1024 * 1024 * 1024))} GB"

        val canStart = (includeImages && remainingImages > 0) || (includeVideos && remainingVideos > 0)
        val imageProgress = if (totalImages > 0) (((totalImages - remainingImages).toFloat() / totalImages) * 100f) else 100f
        val videoProgress = if (totalVideos > 0) (((totalVideos - remainingVideos).toFloat() / totalVideos) * 100f) else 100f

        Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Gallery Shift", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Color.White)
                Row {
                    IconButton(onClick = { showInfoDialog = true }) { Icon(Icons.Default.Info, contentDescription = "Info", tint = Color.White) }
                    IconButton(onClick = { showDialog = true }) { Icon(Icons.Default.Refresh, contentDescription = "Reset", tint = Color.White) }
                }
            }

            if (showDialog) {
                AlertDialog(
                    onDismissRequest = { showDialog = false },
                    title = { Text("Fortschritt zurücksetzen?") },
                    text = { Text("Alle Entscheidungen, Speicherstatistiken und Speedtests löschen?") },
                    confirmButton = { TextButton(onClick = {
                        PreferenceHelper.getPrefs(context).edit().remove("seen_media_ids").remove("saved_bytes").remove("total_speed_sum").remove("session_count").remove("last_speed").apply()
                        refreshKey++; showDialog = false
                    }) { Text("JA") } },
                    dismissButton = { TextButton(onClick = { showDialog = false }) { Text("NEIN") } }
                )
            }

            if (showInfoDialog) {
                AlertDialog(
                    onDismissRequest = { showInfoDialog = false },
                    title = { Text("Über Gallery Shift") },
                    text = { Text("Gallery Shift hilft dir dabei, Speicherplatz freizugeben, indem du schnell durch Bilder und Videos klickst.\n\nVersion 0.5") },
                    confirmButton = { TextButton(onClick = { showInfoDialog = false }) { Text("OK") } }
                )
            }

            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Speicherplatz freigegeben:", color = Color.White)
                    Text(text = readableSize, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Black.copy(alpha = 0.3f))) {
                    Text(selectedFolder, color = Color.White)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.ArrowDropDown, null, tint = Color.White)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    folderList.forEach { folderPair ->
                        DropdownMenuItem(
                            text = { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(folderPair.first); Text("${folderPair.second}", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                            onClick = { selectedFolder = folderPair.first; expanded = false; refreshKey++ }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { sortExpanded = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Black.copy(alpha = 0.3f))) {
                    Text("Sortierung: $selectedSort", color = Color.White)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.ArrowDropDown, null, tint = Color.White)
                }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    sortOptions.forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = { selectedSort = option; sortExpanded = false })
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Was soll weg?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeImages, onCheckedChange = { includeImages = it }, colors = CheckboxDefaults.colors(uncheckedColor = Color.White, checkedColor = Color.White))
                        Column { Text("Bilder ($remainingImages / $totalImages)", color = Color.White); Text("${"%.1f".format(imageProgress)}% geprüft", style = MaterialTheme.typography.bodySmall, color = Color.LightGray) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeVideos, onCheckedChange = { includeVideos = it }, colors = CheckboxDefaults.colors(uncheckedColor = Color.White, checkedColor = Color.White))
                        Column { Text("Videos ($remainingVideos / $totalVideos)", color = Color.White); Text("${"%.1f".format(videoProgress)}% geprüft", style = MaterialTheme.typography.bodySmall, color = Color.LightGray) }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = { onNavigateToTinder(includeImages, includeVideos, selectedFolder, selectedSort) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = canStart
            ) { Text(if (canStart) "Starten" else "Alles erledigt!") }

            Spacer(modifier = Modifier.height(20.dp))

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("🚀 Deine Sortier-Geschwindigkeit", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
                        IconButton(onClick = {
                            PreferenceHelper.getPrefs(context).edit().remove("total_speed_sum").remove("session_count").remove("last_speed").apply()
                            refreshKey++
                        }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Refresh, "Zeit zurücksetzen", tint = Color.White) }
                    }
                    val prefs = PreferenceHelper.getPrefs(context)
                    val totalSum = prefs.getFloat("total_speed_sum", 0f)
                    val sessionCount = prefs.getInt("session_count", 0)
                    val average = if (sessionCount > 0) totalSum / sessionCount else 0f
                    Text(
                        text = if (average > 0) "Durchschnitt: ${"%.1f".format(average)} Bilder/Sek. (über $sessionCount Sitzungen)."
                        else "Zeige, wie schnell du sortieren kannst!",
                        style = MaterialTheme.typography.bodyMedium, color = Color.LightGray
                    )
                }
            }
        }
    }
}