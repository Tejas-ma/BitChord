package com.music.bitchord.ui.social

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*

import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodSummaryScreen(
    onBack: () -> Unit
) {
    var showYearRecap by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Mood", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            CurrentMoodSection()
            Spacer(modifier = Modifier.height(32.dp))
            ThisWeekSection()
            Spacer(modifier = Modifier.height(32.dp))
            ThisMonthSection()
            Spacer(modifier = Modifier.height(32.dp))
            YearInMoodsSection()
            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { showYearRecap = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("View Year Recap")
            }
            Spacer(modifier = Modifier.height(32.dp))

        }
    }

    if (showYearRecap) {
        Dialog(
            onDismissRequest = { showYearRecap = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            YearRecapScreen(onBack = { showYearRecap = false })
        }
    }
}

@Composable
private fun CurrentMoodSection() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Current Mood",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "😎",
            fontSize = 72.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Chill",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun ThisWeekSection() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "This Week",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(16.dp))

        val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val values = listOf(0.4f, 0.7f, 0.5f, 0.9f, 0.3f, 0.8f, 0.6f)
        val barColorPrimary = MaterialTheme.colorScheme.primary
        val barColorSecondary = MaterialTheme.colorScheme.secondary
        val barColorTertiary = MaterialTheme.colorScheme.tertiary

        val colors = listOf(
            barColorPrimary, barColorSecondary, barColorPrimary,
            barColorTertiary, barColorSecondary, barColorPrimary, barColorTertiary
        )
        val onBackgroundColor = MaterialTheme.colorScheme.onBackground

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(16.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val barWidth = (canvasWidth / days.size) * 0.6f
                val spacing = (canvasWidth / days.size) * 0.4f
                val cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)

                for (i in days.indices) {
                    val x = i * (barWidth + spacing) + spacing / 2
                    val barHeight = values[i] * canvasHeight
                    val y = canvasHeight - barHeight

                    drawRoundRect(
                        color = colors[i],
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barHeight),
                        cornerRadius = cornerRadius
                    )
                }
            }
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            days.forEach { day ->
                Text(
                    text = day,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ThisMonthSection() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "This Month's Dominant Mood",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "🔥",
                fontSize = 56.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Energetic",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun YearInMoodsSection() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Year in Moods",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(16.dp))

        val months = listOf(
            "Jan", "Feb", "Mar", "Apr",
            "May", "Jun", "Jul", "Aug",
            "Sep", "Oct", "Nov", "Dec"
        )
        val emojis = listOf(
            "😎", "😴", "🔥", "😎",
            "😌", "🔥", "😎", "🤩",
            "😴", "😌", "🤩", "🔥"
        )

        val columns = 4
        val rows = months.size / columns

        Column {
            for (i in 0 until rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    for (j in 0 until columns) {
                        val index = i * columns + j
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Text(
                                text = emojis[index],
                                fontSize = 28.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = months[index],
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}
