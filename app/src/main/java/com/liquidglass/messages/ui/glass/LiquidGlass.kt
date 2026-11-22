package com.liquidglass.messages.ui.glass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.liquidglass.messages.ui.theme.LiquidTheme
import com.liquidglass.messages.util.DevicePerformance
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Real "Liquid Glass" for Compose — not a translucent fill.
 *
 * How it works (the same approach Apple's material takes, rebuilt with
 * Android primitives):
 *  1. The scrolling content is recorded once per frame into a [GraphicsLayer]
 *     via [backdropSource]. On Android < 12 where RenderEffect isn't available,
 *     recording is skipped completely so entry-level GPUs (e.g. Redmi 9C) pay
 *     zero offscreen pass cost.
 *  2. Each glass element ([liquidGlass]) draws that same layer again, shifted
 *     to its own position, through a cached effect stack (blur on API 31+,
 *     plus AGSL lens shader on API 33+ with ample RAM).
 *  3. A cached specular rim and a soft tint are painted on top.
 */
@Stable
class Backdrop internal constructor(
    internal val layer: GraphicsLayer?,
    internal val enabled: Boolean = true,
    internal val lowRam: Boolean = false,
) {
    /** Top-left of the source in root coordinates. */
    internal var origin by mutableStateOf(Offset.Zero)
    internal var recorded by mutableStateOf(false)
}

@Composable
fun rememberBackdrop(): Backdrop {
    val view = LocalView.current
    val context = LocalContext.current
    val enabled = remember(view.isInEditMode) {
        !view.isInEditMode && DevicePerformance.supportsBackdropGlass()
    }
    val layer = if (enabled) rememberGraphicsLayer() else null
    val lowRam = remember(view.isInEditMode, context) {
        !view.isInEditMode && DevicePerformance.isLowRam(context)
    }
    return remember(layer, enabled, lowRam) { Backdrop(layer, enabled, lowRam) }
}

/** The backdrop glass elements refract; null = plain frosted fallback. */
val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Records this node's drawing into [backdrop] (and still draws it normally). */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier {
    val layer = backdrop.layer
    if (!backdrop.enabled || layer == null) return this
    return this
        .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            // RenderNodes only exist on hardware canvases; a software draw (e.g. a
            // system screenshot or View.draw into a Bitmap) must take the plain path.
            if (!drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                drawContent()
                return@drawWithContent
            }
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
            if (!backdrop.recorded) backdrop.recorded = true
        }
}

/** Tunables of the glass material. Defaults match iOS 26 controls. */
data class GlassStyle(
    val blur: Dp = 5.dp,
    /** How far in from the rim the lens bends the content. */
    val refractionHeight: Dp = 14.dp,
    /** Maximum displacement at the rim. */
    val refractionAmount: Dp = 18.dp,
    /** Chromatic dispersion (0 = none). */
    val dispersion: Float = 0.12f,
    val saturation: Float = 1.6f,
    /** Extra tint laid over the glass (theme default when null). */
    val tint: Color? = null,
    /** Menus/popovers: frosted enough that text behind never competes with the rows. */
    val menu: Boolean = false,
) {
    companion object {
        /** iOS 26 menu material: heavy blur, gentle lens, denser tint. */
        val Menu = GlassStyle(blur = 26.dp, refractionHeight = 18.dp, refractionAmount = 10.dp, dispersion = 0.06f, saturation = 1.8f, menu = true)
    }
}

private class GlassDrawCache {
    var size: Size = Size.Unspecified
    var layoutDirection: LayoutDirection = LayoutDirection.Ltr
    var isDark: Boolean = false
    var style: GlassStyle? = null
    var useLens: Boolean = false

    var outline: Outline? = null
    var path: Path? = null
    var rimBrush: Brush? = null
    var effect: RenderEffect? = null
    var vibrancyFilter: ColorFilter? = null
    var lastSaturation: Float = Float.NaN
    var margin: Int = 0
}

/**
 * Paints this element as Liquid Glass of [shape] over the current
 * [LocalBackdrop]. Content of the element (icons, text) draws on top.
 */
fun Modifier.liquidGlass(
    shape: Shape,
    style: GlassStyle = GlassStyle(),
): Modifier = composed {
    val backdrop = LocalBackdrop.current
    val context = LocalContext.current
    val colors = LiquidTheme.colors
    val glassLayer = if (backdrop != null && backdrop.enabled && Build.VERSION.SDK_INT >= 31) {
        rememberGraphicsLayer()
    } else {
        null
    }
    val drawCache = remember { GlassDrawCache() }
    var position by remember { mutableStateOf(Offset.Zero) }
    val useLens = remember(context, backdrop?.lowRam) {
        DevicePerformance.supportsLensShader(context) && backdrop?.lowRam != true
    }
    val shader = remember(useLens) { if (useLens && Build.VERSION.SDK_INT >= 33) LensShader.create() else null }

    val tint = style.tint ?: when {
        style.menu && colors.isDark -> Color(0x9E1C1C1E)
        style.menu -> Color(0xA6FFFFFF)
        colors.glassTinted && colors.isDark -> Color(0xA61C1C1E)
        colors.glassTinted -> Color(0xC2F7F7F9)
        colors.isDark -> Color(0x40202024)
        else -> Color(0x59FFFFFF)
    }
    val fallbackFill = if (style.menu) colors.glassFillStrong else colors.glassFill
    val outerStrokeColor = if (colors.isDark) Color(0x1FFFFFFF) else Color(0x14000000)

    this
        .then(
            if (backdrop != null && backdrop.enabled) {
                Modifier.onGloballyPositioned { position = it.positionInRoot() }
            } else {
                Modifier
            }
        )
        .drawBehind {
            val sizeChanged = drawCache.size != size || drawCache.layoutDirection != layoutDirection
            val themeChanged = drawCache.isDark != colors.isDark
            val styleChanged = drawCache.style != style || drawCache.useLens != useLens

            if (sizeChanged || drawCache.outline == null) {
                val created = shape.createOutline(size, layoutDirection, this)
                drawCache.outline = created
                drawCache.path = created.toPath()
                drawCache.size = size
                drawCache.layoutDirection = layoutDirection
            }
            if (sizeChanged || themeChanged || drawCache.rimBrush == null) {
                drawCache.rimBrush = Brush.linearGradient(
                    colors = if (colors.isDark) DARK_RIM_COLORS else LIGHT_RIM_COLORS,
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
                drawCache.isDark = colors.isDark
            }
            if (drawCache.lastSaturation != style.saturation || drawCache.vibrancyFilter == null) {
                drawCache.vibrancyFilter = ColorFilter.colorMatrix(vibrancy(style.saturation))
                drawCache.lastSaturation = style.saturation
            }

            val outline = drawCache.outline!!
            val path = drawCache.path!!
            val canRefract = backdrop != null && backdrop.enabled && backdrop.recorded &&
                backdrop.layer != null && glassLayer != null &&
                Build.VERSION.SDK_INT >= 31 &&
                drawContext.canvas.nativeCanvas.isHardwareAccelerated

            if (canRefract) {
                if (sizeChanged || styleChanged) {
                    val margin = if (useLens) {
                        (style.refractionAmount.toPx() + style.blur.toPx() * 3f).roundToInt()
                    } else {
                        (style.blur.toPx() * 3f).roundToInt()
                    }
                    drawCache.margin = margin
                    drawCache.effect = effectFor(style, if (useLens) shader else null, margin, outline)
                    drawCache.style = style
                    drawCache.useLens = useLens
                }
                drawRefractedBackdrop(
                    backdrop = backdrop!!,
                    glassLayer = glassLayer!!,
                    clip = path,
                    position = position,
                    margin = drawCache.margin,
                    renderEffect = drawCache.effect,
                    vibrancyFilter = drawCache.vibrancyFilter,
                )
                drawOutline(outline, tint)
            } else {
                drawOutline(outline, fallbackFill)
            }

            // Specular rim: bright where light hits (top-left / bottom-right),
            // fading along the sides — what gives the glass its thickness.
            drawOutline(outline, drawCache.rimBrush!!, style = Stroke(width = 1.2.dp.toPx()))
            // Faint outer definition so white glass reads on white backgrounds.
            drawOutline(outline, outerStrokeColor, style = Stroke(0.5.dp.toPx()))
        }
}

private val DARK_RIM_COLORS = listOf(Color(0x66FFFFFF), Color(0x14FFFFFF), Color(0x0AFFFFFF), Color(0x40FFFFFF))
private val LIGHT_RIM_COLORS = listOf(Color(0xF2FFFFFF), Color(0x55FFFFFF), Color(0x26FFFFFF), Color(0xCCFFFFFF))

private fun DrawScope.drawRefractedBackdrop(
    backdrop: Backdrop,
    glassLayer: GraphicsLayer,
    clip: Path,
    position: Offset,
    margin: Int,
    renderEffect: RenderEffect?,
    vibrancyFilter: ColorFilter?,
) {
    val w = size.width.roundToInt() + margin * 2
    val h = size.height.roundToInt() + margin * 2
    if (w <= 0 || h <= 0) return

    val sourceLayer = backdrop.layer ?: return
    glassLayer.topLeft = IntOffset(-margin, -margin)
    if (glassLayer.renderEffect != renderEffect) glassLayer.renderEffect = renderEffect
    if (glassLayer.colorFilter != vibrancyFilter) glassLayer.colorFilter = vibrancyFilter
    glassLayer.record(size = IntSize(w, h)) {
        translate(
            left = backdrop.origin.x - position.x + margin,
            top = backdrop.origin.y - position.y + margin,
        ) {
            drawLayer(sourceLayer)
        }
    }
    clipPath(clip) { drawLayer(glassLayer) }
}

private fun DrawScope.effectFor(style: GlassStyle, shader: Any?, margin: Int, outline: Outline): RenderEffect? {
    if (Build.VERSION.SDK_INT < 31) return null
    val blurPx = style.blur.toPx()
    val blur = if (blurPx > 0f) BlurEffect(blurPx, blurPx, TileMode.Clamp) else null
    if (Build.VERSION.SDK_INT >= 33 && shader != null) {
        return runCatching {
            lensEffect(shader as RuntimeShader, blur, style, margin, cornerRadiusOf(outline))
        }.getOrNull() ?: blur
    }
    return blur
}

@RequiresApi(33)
private fun DrawScope.lensEffect(
    shader: RuntimeShader,
    blur: RenderEffect?,
    style: GlassStyle,
    margin: Int,
    radius: Float,
): RenderEffect {
    shader.setFloatUniform("origin", margin.toFloat(), margin.toFloat())
    shader.setFloatUniform("size", size.width, size.height)
    shader.setFloatUniform("radius", min(radius, min(size.width, size.height) / 2f))
    shader.setFloatUniform("height", style.refractionHeight.toPx())
    shader.setFloatUniform("amount", style.refractionAmount.toPx())
    shader.setFloatUniform("chroma", style.dispersion)
    val lens = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
    val chained = if (blur != null) {
        android.graphics.RenderEffect.createChainEffect(lens, blur.asAndroidRenderEffect())
    } else {
        lens
    }
    return chained.asComposeRenderEffect()
}

/** Corner radius of a rounded outline (circles/capsules report half their size). */
private fun DrawScope.cornerRadiusOf(outline: Outline): Float = when (outline) {
    is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
    is Outline.Rectangle -> 0f
    is Outline.Generic -> min(size.width, size.height) / 2f
}

private fun Outline.toPath(): Path = when (this) {
    is Outline.Generic -> path
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Rectangle -> Path().apply { addRect(rect) }
}

/** Saturation boost with a slight lift — Apple's "vibrancy" under glass. */
private fun vibrancy(saturation: Float): ColorMatrix {
    val m = ColorMatrix().apply { setToSaturation(saturation) }
    val lift = 0.04f * 255f
    val v = m.values
    v[4] += lift; v[9] += lift; v[14] += lift
    return m
}

/** AGSL lens: bends content near the rim of a rounded rect, like thick glass. */
@RequiresApi(33)
private object LensShader {
    private const val SRC = """
        uniform shader content;
        uniform float2 origin;
        uniform float2 size;
        uniform float radius;
        uniform float height;
        uniform float amount;
        uniform float chroma;

        float sdRoundRect(float2 p, float2 b, float r) {
            float2 q = abs(p) - b + r;
            return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
        }

        half4 main(float2 coord) {
            float2 halfSize = size * 0.5;
            float2 p = coord - origin - halfSize;
            float d = sdRoundRect(p, halfSize, radius);
            if (d > 0.0 || height <= 0.0) { return content.eval(coord); }

            // Outward normal of the rim from the SDF gradient.
            float e = 1.0;
            float2 n = float2(
                sdRoundRect(p + float2(e, 0.0), halfSize, radius) - sdRoundRect(p - float2(e, 0.0), halfSize, radius),
                sdRoundRect(p + float2(0.0, e), halfSize, radius) - sdRoundRect(p - float2(0.0, e), halfSize, radius));
            float len = length(n);
            n = len > 0.0001 ? n / len : float2(0.0);

            // Circular lens profile: 0 in the flat middle, strongest at the rim.
            float x = clamp(1.0 + d / height, 0.0, 1.0);
            float bend = (1.0 - sqrt(max(1.0 - x * x, 0.0))) * amount;
            float2 offs = -n * bend;

            half4 g = content.eval(coord + offs);
            if (chroma <= 0.0) { return g; }
            half4 r = content.eval(coord + offs * (1.0 + chroma));
            half4 b = content.eval(coord + offs * (1.0 - chroma));
            return half4(r.r, g.g, b.b, g.a);
        }
    """

    fun create(): RuntimeShader? = runCatching { RuntimeShader(SRC) }
        .onFailure { android.util.Log.w("LiquidGlass", "Lens shader unavailable", it) }
        .getOrNull()
}

