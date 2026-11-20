package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.search.UnifiedSearchFailure
import com.music.bitchord.data.search.UnifiedSearchHit
import com.music.bitchord.data.search.UnifiedSearchSource
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.PlayingAccent
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.data.model.playingRowMatcher

/**
 * The source chips above the unified results.
 *
 * Every catalogue that answered gets a chip, whether or not it returned rows,
 * so a source that found nothing is visible as an answer rather than as a
 * silence — and a failing one is dimmed with its reason instead of vanishing.
 *
 * No selection means every source; toggling one narrows the list to it.
 */
@Composable
fun UnifiedSourceChips(
    sources: List<UnifiedSearchSource>,
    hits: List<UnifiedSearchHit>,
    failures: List<UnifiedSearchFailure>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val counts = hits.groupingBy { it.sourceId }.eachCount()
    val failed = failures.associateBy { it.sourceId }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chip(
            label = "All sources",
            count = hits.size,
            selected = selected.isEmpty(),
            failed = false,
            onClick = onClear,
        )
        sources.forEach { source ->
            Chip(
                label = source.label,
                count = counts[source.id] ?: 0,
                selected = source.id in selected,
                failed = failed.containsKey(source.id),
                onClick = { onToggle(source.id) },
            )
        }
        if (failures.isNotEmpty()) {
            Text(
                text = failures.joinToString(" · ") { "${it.sourceLabel}: ${it.reason}" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun Chip(
    label: String,
    count: Int,
    selected: Boolean,
    failed: Boolean,
    onClick: () -> Unit,
) {
    val background = when {
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
    val foreground = when {
        failed -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = foreground,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (failed) "—" else "$count",
            style = MaterialTheme.typography.labelSmall,
            color = foreground.copy(alpha = 0.7f),
        )
    }
}

/**
 * The heading over the unified block, and the loader while the slow catalogues
 * are still answering.
 */
@Composable
fun UnifiedSearchHeading(
    hits: List<UnifiedSearchHit>,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "From every source",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(8.dp))
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.width(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (hits.isNotEmpty()) {
            Text(
                text = "${hits.size} results",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One unified row: an ordinary [SongRow] with the catalogue's name on it, so a
 * tap plays that exact copy rather than a substitute.
 */
@Composable
fun UnifiedSearchResultRow(
    hit: UnifiedSearchHit,
    currentSong: Song?,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val matches = playingRowMatcher(listOf(hit.song), currentSong)
    SongRow(
        song = hit.song,
        onClick = onClick,
        onLongPress = onLongPress,
        modifier = modifier,
        isCurrent = matches(hit.song),
        isPlaying = isPlaying && matches(hit.song),
        searchPlayingStyle = true,
        activeTint = PlayingAccent,
        sourceBadge = hit.sourceLabel,
    )
}
