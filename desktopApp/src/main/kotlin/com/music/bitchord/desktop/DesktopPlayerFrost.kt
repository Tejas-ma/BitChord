package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalGraphicsContext
import com.music.bitchord.ui.player.PlayerFrost
import kotlinx.coroutines.launch
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect

/**
 * The desktop's player frost: the phone's still-under-a-blur, taken on Skia
 * rather than through Android's backdrop library.
 *
 * The still is recorded into a graphics layer on the frame [capture] asks for
 * and rasterised off the draw thread, the way a long-press hold is; the panels
 * then draw it again under themselves, blurred by a Skia image filter — one
 * draw of a finished picture per frame rather than the player being re-rendered
 * blurred on every frame anything in it moves.
 */
internal class DesktopPlayerFrost : PlayerFrost {

    /** Bumped by [capture]; read inside the draw so the next frame is the one recorded. */
    private val requests = mutableIntStateOf(0)
    private var handled = 0

    private var still by mutableStateOf<ImageBitmap?>(null)

    @Composable
    override fun Modifier.frostSource(): Modifier {
        val graphicsContext = LocalGraphicsContext.current
        val scope = rememberCoroutineScope()
        return this.drawWithContent {
            drawContent()
            val request = requests.intValue
            if (request == handled) return@drawWithContent
            handled = request
            val layer = graphicsContext.createGraphicsLayer()
            layer.record {
                this@drawWithContent.drawContent()
            }
            scope.launch {
                try {
                    still = layer.toImageBitmap()
                } finally {
                    graphicsContext.releaseGraphicsLayer(layer)
                }
            }
        }
    }

    override fun capture() {
        requests.intValue++
    }

    override fun release() {
        still = null
    }

    /** One blur for every panel: the drawer slides, so this is drawn on each of its frames. */
    private val panelPaint = Paint().apply {
        imageFilter = ImageFilter.makeBlur(FROST_SIGMA, FROST_SIGMA, FilterTileMode.CLAMP)
    }

    override fun Modifier.frostPanel(shape: Shape, fill: Color): Modifier =
        this.clip(shape)
            .drawBehind {
                val image = still ?: return@drawBehind
                val bitmap = Image.makeFromBitmap(image.asSkiaBitmap())
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawImageRect(
                        bitmap,
                        Rect.makeWH(bitmap.width.toFloat(), bitmap.height.toFloat()),
                        Rect.makeWH(size.width, size.height),
                        panelPaint,
                    )
                }
            }
            .background(fill)
}

/**
 * How much the still is blurred. Roughly the phone's 24dp at a panel's scale:
 * enough that nothing in the player reads as a shape, little enough that its
 * colours still are what the panel is tinted by.
 */
private const val FROST_SIGMA = 28f
