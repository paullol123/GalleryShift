package com.gallerysift.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

class MainActivity : ComponentActivity(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    var itemsToDelete by remember { mutableStateOf(listOf<MediaItem>()) }

                    NavHost(navController = navController, startDestination = "main_screen") {
                        composable("main_screen") {
                            // Hier wurde der 4. Parameter 'sort' hinzugefügt
                            MainScreen(onNavigateToTinder = { images, videos, folder, sort ->
                                navController.navigate("tinder_screen/$images/$videos/$folder/$sort")
                            })
                        }
                        composable(
                            route = "tinder_screen/{images}/{videos}/{folder}/{sort}",
                            arguments = listOf(
                                navArgument("images") { type = NavType.BoolType },
                                navArgument("videos") { type = NavType.BoolType },
                                navArgument("folder") { type = NavType.StringType },
                                navArgument("sort") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val images = backStackEntry.arguments?.getBoolean("images") ?: true
                            val videos = backStackEntry.arguments?.getBoolean("videos") ?: true
                            val folder = backStackEntry.arguments?.getString("folder") ?: "Alle Ordner"
                            val sort = backStackEntry.arguments?.getString("sort") ?: "Zufall"

                            TinderScreen(
                                includeImages = images,
                                includeVideos = videos,
                                folder = folder,
                                sortOption = sort, // Hier wird die Sortierung übergeben
                                navController = navController,
                                onNavigateToSummary = { items ->
                                    itemsToDelete = items
                                    navController.navigate("summary_screen")
                                }
                            )
                        }
                        composable("summary_screen") {
                            SummaryScreen(itemsToDelete, navController)
                        }
                    }
                }
            }
        }
    }
}