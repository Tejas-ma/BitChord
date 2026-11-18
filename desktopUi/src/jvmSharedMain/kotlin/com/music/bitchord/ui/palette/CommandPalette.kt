package com.music.bitchord.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.Normalizer
import java.util.Locale

/**
 * One thing the palette can do or go to: a track, a playlist, a player action, an
 * output device. The host builds the list and gives each entry its own [action], so
 * the palette itself knows nothing about playback.
 */
data class CommandPaletteItem(
    val id: String,
    val title: String,
    /** The group it is listed under — "Songs", "Playlists", "Player", "Output". */
    val section: String,
    val subtitle: String? = null,
    /** Extra text a query matches on but nobody sees: an album, an artist id, a device kind. */
    val keywords: String = "",
    /** A right-hand label, e.g. the current value of a setting. */
    val hint: String? = null,
    val action: () -> Unit,
)

/**
 * Fuzzy matching for the palette, in the way a command palette is expected to behave:
 * "thsng" finds "This Song", word starts count for more than letters in the middle of
 * a word, and letters that follow each other count for more than ones that do not.
 *
 * Returns null when the query's characters do not all appear in [text], in order.
 */
object CommandFuzzy {

    fun score(query: String, text: String): Int? {
        if (query.isBlank()) return 0
        val needle = normalize(query)
        if (needle.isEmpty()) return 0
        val hay = normalize(text)
        if (hay.isEmpty()) return null

        var score = 0
        var from = 0
        var previous = -2
        for (char in needle) {
            val at = hay.indexOf(char, from)
            if (at < 0) return null
            score += 10
            // Start of a word: the letter before it is a space or the string's own start.
            if (at == 0 || hay[at - 1] == ' ') score += 18
            // Letters that follow each other read as one run, which is worth more than
            // a scatter of matches across a long title.
            if (at == previous + 1) score += 12
            // Every character skipped costs a little, so short titles of the same shape
            // rank above long ones.
            score -= (at - from).coerceAtLeast(0)
            previous = at
            from = at + 1
        }
        // A title that starts with the query is the one being typed, not merely one that
        // contains it.
        if (hay.startsWith(needle)) score += 40
        return score
    }

    /** The best score across a title, its subtitle and the hidden keywords. */
    fun best(query: String, vararg fields: String?): Int? {
        var best: Int? = null
        for (field in fields) {
            val value = field ?: continue
            val score = score(query, value) ?: continue
            if (best == null || score > best) best = score
        }
        return best
    }

    /**
     * Case, accents and punctuation fold away before matching: a palette is typed into
     * from memory, and nobody types "Beyoncé" with the accent to find her.
     */
    private fun normalize(text: String): String = Normalizer
        .normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private sealed interface PaletteRow {
    data class Header(val title: String) : PaletteRow
    data class Entry(val item: CommandPaletteItem, val order: Int) : PaletteRow
}

/**
 * The command palette: one box over everything, a query, and the things that match,
 * grouped by kind, with the keyboard driving it.
 *
 * ⌘K/Ctrl+K opens it from anywhere (the host owns that key), ⌘K or Escape closes it,
 * ↑/↓ move, and Return runs what is selected. Typing nothing lists everything the host
 * offered, actions first, so the palette is also a menu of what exists.
 */
@Composable
fun CommandPalette(
    items: List<CommandPaletteItem>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search songs, playlists, actions…",
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    val ranked = remember(query, items) {
        if (query.isBlank()) {
            items.map { it to 0 }
        } else {
            items.mapNotNull { item ->
                val score = CommandFuzzy.best(query, item.title, item.subtitle, item.keywords)
                    ?: return@mapNotNull null
                item to score
            }.sortedWith(compareByDescending<Pair<CommandPaletteItem, Int>> { it.second }.thenBy { it.first.title })
        }
    }

    val rows = remember(ranked) {
        val built = mutableListOf<PaletteRow>()
        var section: String? = null
        ranked.forEachIndexed { order, (item, _) ->
            if (item.section != section) {
                section = item.section
                built += PaletteRow.Header(item.section)
            }
            built += PaletteRow.Entry(item, order)
        }
        built
    }

    LaunchedEffect(ranked.size) {
        if (selected >= ranked.size) selected = (ranked.size - 1).coerceAtLeast(0)
    }
    // Keep the highlighted row on screen as the arrows walk past the fold.
    LaunchedEffect(selected) {
        val row = rows.indexOfFirst { it is PaletteRow.Entry && it.order == selected }
        if (row >= 0) listState.animateScrollToItem(row)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .padding(top = 96.dp)
                .widthIn(min = 420.dp, max = 620.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(PaletteBackground)
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
                // The panel swallows clicks so the scrim's dismiss does not fire through
                // a row that was only being tapped.
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                )
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> {
                            onDismiss()
                            true
                        }
                        Key.DirectionDown -> {
                            if (ranked.isNotEmpty()) selected = (selected + 1) % ranked.size
                            true
                        }
                        Key.DirectionUp -> {
                            if (ranked.isNotEmpty()) selected = (selected - 1 + ranked.size) % ranked.size
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            val item = ranked.getOrNull(selected)?.first ?: return@onPreviewKeyEvent false
                            onDismiss()
                            item.action()
                            true
                        }
                        else -> false
                    }
                },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        selected = 0
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    color = Color.White.copy(alpha = 0.38f),
                                    fontSize = 16.sp,
                                )
                            }
                            inner()
                        }
                    },
                )
                Text(
                    text = if (ranked.isEmpty()) "0" else "${selected + 1}/${ranked.size}",
                    color = Color.White.copy(alpha = 0.32f),
                    fontSize = 12.sp,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.07f)),
            )

            if (ranked.isEmpty()) {
                Text(
                    text = "Nothing matches “$query”",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 22.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) {
                    items(rows, key = { row ->
                        when (row) {
                            is PaletteRow.Header -> "h:${row.title}"
                            is PaletteRow.Entry -> "e:${row.item.id}"
                        }
                    }) { row ->
                        when (row) {
                            is PaletteRow.Header -> Text(
                                text = row.title.uppercase(Locale.ROOT),
                                color = Color.White.copy(alpha = 0.34f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(
                                    start = 18.dp,
                                    end = 18.dp,
                                    top = 14.dp,
                                    bottom = 6.dp,
                                ),
                            )
                            is PaletteRow.Entry -> PaletteRowView(
                                item = row.item,
                                selected = row.order == selected,
                                onHover = { selected = row.order },
                                onClick = {
                                    onDismiss()
                                    row.item.action()
                                },
                            )
                        }
                    }
                }
            }

            Text(
                text = "↑↓ to move · ⏎ to run · esc to close",
                color = Color.White.copy(alpha = 0.28f),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun PaletteRowView(
    item: CommandPaletteItem,
    selected: Boolean,
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onHover() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color.White.copy(alpha = 0.09f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = Color.White.copy(alpha = if (selected) 1f else 0.86f),
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item.hint?.takeIf { it.isNotBlank() }?.let { hint ->
            Text(
                text = hint,
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

private val PaletteBackground = Color(0xF218181A)

/** Kept so an empty palette still reserves the query row's height while loading. */
private val PaletteQueryHeight = 56.dp
