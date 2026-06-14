package com.gallerysift.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

@Composable
fun AnimatedGradientBackground(content: @Composable () -> Unit) {
    val colors = listOf(Color(0xFF6200EA), Color(0xFF03DAC5), Color(0xFFBB86FC))
    val infiniteTransition = rememberInfiniteTransition(label = "gradient")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1000f,
        animationSpec = infiniteRepeatable(animation = tween(10000, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "offset"
    )
    val brush = Brush.linearGradient(
        colors = colors,
        start = androidx.compose.ui.geometry.Offset(offset, offset),
        end = androidx.compose.ui.geometry.Offset(offset + 500f, offset + 500f)
    )
    Box(modifier = Modifier.fillMaxSize().background(brush)) { content() }
}