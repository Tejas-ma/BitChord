package com.music.bitchord.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.withContext
import kotlin.math.abs
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * How far the picture may sit from the transport before it is seeked back: a fifth of a second is
 * under a frame's worth at the rates these play at, and a few of these drift past unnoticed while
 * a seek that fires every frame would stall the decode.
 */
private const val DRIFT_TOLERANCE_US = 300_000L

/**
 * The desktop's music video: the video-only track YouTube serves for a
 * `Song.isVideo`, decoded by the same FFmpeg pass that plays a Canvas clip and
 * drawn where the sleeve is.
 *
 * The picture follows the transport through [DesktopPlayerHost]'s own state —
 * it holds on the last frame while the track is paused — and the still sleeve
 * stays underneath until the first frame lands, so a video that will not open
 * (an age wall, a stream that expired) simply leaves the artwork in place.
 */
@Composable
internal fun DesktopMusicVideo(
    videoId: String,
    fit: Boolean,
    onRenderedChanged: (Boolean) -> Unit,
    modifier: Modifier,
) {
    var frame by remember(videoId) { mutableStateOf<ImageBitmap?>(null) }
    var rendered by remember(videoId) { mutableStateOf(false) }
    val report by rememberUpdatedState(onRenderedChanged)
    val host = DesktopPlayerHost

    LaunchedEffect(videoId) {
        val stream = withContext(Dispatchers.IO) { host.videoStream(videoId) } ?: return@LaunchedEffect
        val decoder = DesktopCanvasDecoder()
        val opened = withContext(Dispatchers.IO) { decoder.open(stream.url, stream.headers) }
        if (opened.isFailure) {
            DesktopTrackLog.log("video: ${stream.height}p would not open: ${opened.exceptionOrNull()?.message}")
            withContext(Dispatchers.IO) { decoder.close() }
            return@LaunchedEffect
        }
        host.publishVideoAspect(decoder.width.toFloat() / decoder.height.coerceAtLeast(1))
        try {
            val pixels = ByteArray(decoder.width * decoder.height * 4)
            val info = ImageInfo.makeN32(decoder.width, decoder.height, ColorAlphaType.OPAQUE)
            // Where the picture is, in the track's own timeline. Followed against the transport
            // each frame, so a seek in the player is a seek here rather than a permanent offset.
            var expectedUs = -1L
            while (isActive) {
                // Held with the track: the transport's own play state, so a pause stops decoding.
                snapshotFlow { host.transportPlaying.value }.first { it }
                val transportUs = host.transportPositionMs.value * 1_000L
                if (expectedUs < 0 || abs(transportUs - expectedUs) > DRIFT_TOLERANCE_US) {
                    withContext(Dispatchers.IO) { decoder.seek(transportUs) }
                    expectedUs = transportUs
                }
                val started = System.currentTimeMillis()
                val decoded = withContext(Dispatchers.IO) { decoder.nextFrame(pixels) }
                if (!decoded) break
                // Handed to Skia by copy, so the next frame cannot decode into the same array.
                frame = Image.makeRaster(info, pixels.copyOf(), decoder.width * 4).toComposeImageBitmap()
                rendered = true
                expectedUs += decoder.frameIntervalMillis * 1_000L
                delay((decoder.frameIntervalMillis - (System.currentTimeMillis() - started)).coerceAtLeast(0L))
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { decoder.close() }
        }
        rendered = false
    }

    LaunchedEffect(rendered) { report(rendered) }

    val picture = frame
    if (picture == null) {
        Box(modifier)
    } else {
        Image(
            bitmap = picture,
            contentDescription = null,
            contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
            modifier = modifier,
        )
    }
}
