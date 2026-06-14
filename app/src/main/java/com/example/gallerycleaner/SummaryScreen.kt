package com.gallerysift.app

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(deletedItems: List<MediaItem>, navController: NavController) {
    val context = LocalContext.current
    val selectedItems = remember { mutableStateListOf<MediaItem>().apply { addAll(deletedItems) } }

    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            navController.navigate("main_screen") { popUpTo(0) { inclusive = true } }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Löschliste (${selectedItems.size})") }) },
        bottomBar = {
            val isListEmpty = selectedItems.isEmpty()

            Button(
                onClick = {
                    if (isListEmpty) {
                        navController.navigate("main_screen") { popUpTo(0) { inclusive = true } }
                    } else {
                        val uris = selectedItems.map { it.uri }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val sender = MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
                            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                        } else {
                            selectedItems.forEach { context.contentResolver.delete(it.uri, null, null) }
                            navController.navigate("main_screen") { popUpTo(0) { inclusive = true } }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isListEmpty) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                )
            ) {
                Text(if (isListEmpty) "Zurück zum Menü" else "Jetzt ${selectedItems.size} Dateien endgültig löschen")
            }
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            items(selectedItems, key = { it.id }) { item ->
                Box(Modifier.aspectRatio(1f).padding(2.dp)) {
                    if (item.isVideo) {
                        var bitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
                        LaunchedEffect(item.id) {
                            bitmap = context.contentResolver.loadThumbnail(item.uri, Size(300, 300), null)
                        }

                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(Modifier.fillMaxSize().background(Color.DarkGray))
                        }
                        Icon(Icons.Default.PlayArrow, null, Modifier.align(Alignment.Center), tint = Color.White)
                    } else {
                        AsyncImage(
                            model = item.uri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }

                    IconButton(
                        onClick = { selectedItems.remove(item) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}