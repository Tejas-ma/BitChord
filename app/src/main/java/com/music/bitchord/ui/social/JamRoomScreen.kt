package com.music.bitchord.ui.social

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

@Composable
fun JamRoomScreen(
    roomId: String,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        Text("Jam Room: $roomId")
    }
}
