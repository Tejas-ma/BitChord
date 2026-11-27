package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.security.CollectionLocks

/**
 * The lock marker drawn over a locked playlist's or album's cover.
 *
 * Monochrome and high contrast on purpose: it sits on artwork of any colour, so
 * it is a white glyph on near-black glass with a light rim rather than a tinted
 * shape that could disappear into a bright cover.
 */
@Composable
fun LockBadge(
    modifier: Modifier = Modifier,
    size: Int = 26,
) {
    Box(
        modifier = modifier
            .padding(6.dp)
            .size(size.dp)
            .background(Color.Black.copy(alpha = 0.72f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.34f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Lock,
            contentDescription = "Locked",
            tint = Color.White,
            modifier = Modifier.size((size * 0.58f).dp),
        )
    }
}

/**
 * The live locked set, read once per card rather than per pixel.
 *
 * Cards call this so a lock applied from a menu redraws them immediately —
 * the registry is the same one the menu wrote to.
 */
@Composable
fun rememberLockedCollections(): Set<String> =
    CollectionLocks.locked.collectAsStateWithLifecycle().value

/** Whether [collectionId] is locked right now, recomposing when that changes. */
@Composable
fun isCollectionLocked(collectionId: String?): Boolean {
    val locked = rememberLockedCollections()
    val key = CollectionLocks.normalize(collectionId)
    return key.isNotEmpty() && key in locked
}
