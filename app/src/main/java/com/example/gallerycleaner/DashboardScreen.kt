package com.example.gallerycleaner

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun DashboardScreen(onNavigateToSorting: () -> Unit) {
    val context = LocalContext.current

    // Wir initialisieren die Daten in einem State, nicht direkt im Body
    var totalFreed by remember { mutableStateOf("0 B") }
    var folders by remember { mutableStateOf(listOf("Alle Ordner")) }

    LaunchedEffect(Unit) {
        // Sicherer Zugriff: Daten werden erst geladen, wenn der Screen bereit ist
        try {
            val sharedPrefs = context.getSharedPreferences("GalleryCleanerPrefs", Context.MODE_PRIVATE)
            val bytes = sharedPrefs.getLong("freed_bytes", 0L)
            totalFreed = formatSize(bytes)

            // Ordner laden
            folders = listOf("Alle Ordner") + getAllFolders(context)
        } catch (e: Exception) {
            // Falls hier ein Fehler passiert, crasht die App nicht, sondern zeigt "Fehler"
            totalFreed = "Fehler beim Laden"
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Freigeschaufelt: $totalFreed")

        Spacer(modifier = Modifier.height(20.dp))

        Button(onClick = onNavigateToSorting) {
            Text("Runde starten")
        }
    }
}