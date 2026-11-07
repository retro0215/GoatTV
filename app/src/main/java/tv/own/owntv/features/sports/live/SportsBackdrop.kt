package tv.own.owntv.features.sports.live

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Local, abstract sports backdrops: a dark tinted base, two floodlight glows, a faint perspective
 * field/court/rink/pitch motif, optional team-accent glows and a readability scrim. Drawn with
 * gradients and vector paths only — no photography, no runtime downloads, nothing to license.
 *
 * Text readability never depends on the motif or the accents: the scrim keeps the lower half dark,
 * and the motif lines stay below ~15% alpha even when focused.
 */
internal data class SportsBackdropPalette(
    val top: Color,
    val bottom: Color,
    /** Field/court/ice glow rising from the bottom. */
    val surface: Color,
    /** Floodlight tint. */
    val light: Color,
    /** Motif line color. */
    val line: Color,
    /** Optional second motif color (hockey red line, baseball infield dirt). */
    val secondary: Color? = null,
)

internal fun backdropPalette(kind: SportsVisualKind): SportsBackdropPalette = when (kind) {
    SportsVisualKind.FOOTBALL -> SportsBackdropPalette(
        top = Color(0xFF0B1220), bottom = Color(0xFF07100B), surface = Color(0xFF1F6B3A),
        light = Color(0xFFDDE8FF), line = Color(0xFFE8F5E9),
    )
    SportsVisualKind.COLLEGE_FOOTBALL -> SportsBackdropPalette(
        top = Color(0xFF16100C), bottom = Color(0xFF0A0F08), surface = Color(0xFF2E6B2A),
        light = Color(0xFFFFE2B8), line = Color(0xFFFFF3E0),
    )
    SportsVisualKind.BASKETBALL -> SportsBackdropPalette(
        top = Color(0xFF120C08), bottom = Color(0xFF0B0705), surface = Color(0xFF8A5A2B),
        light = Color(0xFFFFC27A), line = Color(0xFFFFE0B2),
    )
    SportsVisualKind.BASEBALL -> SportsBackdropPalette(
        top = Color(0xFF0A1022), bottom = Color(0xFF081109), surface = Color(0xFF256B33),
        light = Color(0xFFE3ECFF), line = Color(0xFFF1F8E9), secondary = Color(0xFFB07A4A),
    )
    SportsVisualKind.HOCKEY -> SportsBackdropPalette(
        top = Color(0xFF07121C), bottom = Color(0xFF0A1822), surface = Color(0xFF5FB4D9),
        light = Color(0xFFD6F1FF), line = Color(0xFFB3E5FC), secondary = Color(0xFFE53935),
    )
    SportsVisualKind.SOCCER -> SportsBackdropPalette(
        top = Color(0xFF09140F), bottom = Color(0xFF06100A), surface = Color(0xFF2A8A47),
        light = Color(0xFFE6FFEF), line = Color(0xFFE8F5E9),
    )
    SportsVisualKind.MMA -> SportsBackdropPalette(
        top = Color(0xFF0D0B12), bottom = Color(0xFF07060A), surface = Color(0xFF6A1F2B),
        light = Color(0xFFF2E6FF), line = Color(0xFFE8E0F0),
    )
    SportsVisualKind.BOXING -> SportsBackdropPalette(
        top = Color(0xFF120A0A), bottom = Color(0xFF080506), surface = Color(0xFF7A1E1E),
        light = Color(0xFFFFE8D6), line = Color(0xFFFFF0E6), secondary = Color(0xFFE53935),
    )
    SportsVisualKind.GENERIC -> SportsBackdropPalette(
        top = Color(0xFF0E1416), bottom = Color(0xFF080B0C), surface = Color(0xFF2B5F63),
        light = Color(0xFFE0F2F1), line = Color(0xFFE0F2F1),
    )
}

/**
 * Draws the backdrop behind the content. [focus] (0..1) is read in the draw phase only, so animating
 * it never recomposes the card. [awayAccent] / [homeAccent] glow behind the left / right crest area.
 */
internal fun Modifier.sportsBackdrop(
    kind: SportsVisualKind,
    focus: () -> Float,
    awayAccent: Color? = null,
    homeAccent: Color? = null,
    /** Vertical center of the accent glows (fraction of height). */
    accentY: Float = 0.52f,
): Modifier = drawWithCache {
    val p = backdropPalette(kind)
    val w = size.width
    val h = size.height
    val base = Brush.verticalGradient(listOf(p.top, p.bottom))
    val motif = motifPath(kind, size)
    val secondary = secondaryPath(kind, size)
    val stroke = Stroke(width = (h / 160f).coerceIn(1f, 3f))
    val scrim = Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.10f),
        0.45f to Color.Black.copy(alpha = 0.18f),
        1f to Color.Black.copy(alpha = 0.62f),
    )
    val edgeVignette = Brush.horizontalGradient(
        0f to Color.Black.copy(alpha = 0.35f),
        0.18f to Color.Transparent,
        0.82f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.35f),
    )
    onDrawBehind {
        val f = focus().coerceIn(0f, 1f)
        drawRect(base)
        // Field / court / ice atmosphere rising from the bottom.
        drawRect(
            Brush.radialGradient(
                colors = listOf(p.surface.copy(alpha = 0.34f + 0.12f * f), Color.Transparent),
                center = Offset(w * 0.5f, h * 1.05f),
                radius = maxOf(w, h) * 0.85f,
            ),
        )
        // Floodlights.
        for (cx in floatArrayOf(0.12f, 0.88f)) {
            drawRect(
                Brush.radialGradient(
                    colors = listOf(p.light.copy(alpha = 0.10f + 0.10f * f), Color.Transparent),
                    center = Offset(w * cx, -h * 0.15f),
                    radius = maxOf(w, h) * 0.62f,
                ),
            )
        }
        if (motif != null) drawPath(motif, p.line.copy(alpha = 0.07f + 0.06f * f), style = stroke)
        if (secondary != null && p.secondary != null) drawPath(secondary, p.secondary.copy(alpha = 0.10f + 0.08f * f), style = stroke)
        // Team accents: soft glows behind the crests, never a full-card color.
        awayAccent?.let { drawAccent(it, Offset(w * 0.2f, h * accentY), f) }
        homeAccent?.let { drawAccent(it, Offset(w * 0.8f, h * accentY), f) }
        drawRect(edgeVignette)
        drawRect(scrim)
    }
}

private fun DrawScope.drawAccent(color: Color, center: Offset, focus: Float) {
    drawRect(
        Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.20f + 0.12f * focus), Color.Transparent),
            center = center,
            radius = size.minDimension * 0.75f,
        ),
    )
}

/** Abstract perspective motif per sport (null = lights only). */
private fun motifPath(kind: SportsVisualKind, size: Size): Path? {
    val w = size.width
    val h = size.height
    return when (kind) {
        SportsVisualKind.FOOTBALL, SportsVisualKind.COLLEGE_FOOTBALL -> Path().apply {
            // Yard lines converging toward a horizon.
            val horizon = h * 0.40f
            moveTo(0f, horizon); lineTo(w, horizon)
            for (i in -7..7) {
                moveTo(w / 2 + i * w * 0.045f, horizon)
                lineTo(w / 2 + i * w * 0.15f, h)
            }
        }
        SportsVisualKind.SOCCER -> Path().apply {
            // Pitch in perspective: touchlines, halfway line, center circle, penalty box.
            val horizon = h * 0.42f
            moveTo(w * 0.22f, horizon); lineTo(w * -0.25f, h)
            moveTo(w * 0.78f, horizon); lineTo(w * 1.25f, h)
            moveTo(w * 0.22f, horizon); lineTo(w * 0.78f, horizon)
            val mid = h * 0.70f
            moveTo(w * 0.02f, mid); lineTo(w * 0.98f, mid)
            addOval(Rect(Offset(w * 0.5f, mid), Size(w * 0.34f, h * 0.20f)).translateCenter())
            moveTo(w * 0.36f, horizon); lineTo(w * 0.33f, horizon + h * 0.09f); lineTo(w * 0.67f, horizon + h * 0.09f); lineTo(w * 0.64f, horizon)
        }
        SportsVisualKind.BASKETBALL -> Path().apply {
            // Hardwood planks + center circle + key.
            val horizon = h * 0.44f
            for (i in -12..12) {
                moveTo(w / 2 + i * w * 0.03f, horizon)
                lineTo(w / 2 + i * w * 0.09f, h)
            }
            moveTo(0f, horizon); lineTo(w, horizon)
            addOval(Rect(Offset(w * 0.5f, h * 0.78f), Size(w * 0.30f, h * 0.22f)).translateCenter())
            moveTo(w * 0.40f, horizon); lineTo(w * 0.38f, h * 0.60f); lineTo(w * 0.62f, h * 0.60f); lineTo(w * 0.60f, horizon)
        }
        SportsVisualKind.BASEBALL -> Path().apply {
            // Diamond + foul lines + outfield arc.
            val home = Offset(w * 0.5f, h * 0.98f)
            moveTo(home.x, home.y); lineTo(w * 0.70f, h * 0.74f); lineTo(w * 0.5f, h * 0.58f); lineTo(w * 0.30f, h * 0.74f); close()
            moveTo(home.x, home.y); lineTo(w * 1.05f, h * 0.36f)
            moveTo(home.x, home.y); lineTo(w * -0.05f, h * 0.36f)
            arcTo(Rect(Offset(w * 0.5f, h * 0.42f), Size(w * 1.1f, h * 0.36f)).translateCenter(), 190f, 160f, true)
        }
        SportsVisualKind.HOCKEY -> Path().apply {
            // Boards, blue lines and a faceoff circle.
            val horizon = h * 0.40f
            moveTo(w * 0.15f, horizon); lineTo(w * 0.85f, horizon)
            moveTo(w * 0.15f, horizon); lineTo(w * -0.20f, h)
            moveTo(w * 0.85f, horizon); lineTo(w * 1.20f, h)
            moveTo(w * 0.06f, h * 0.56f); lineTo(w * 0.94f, h * 0.56f)
            moveTo(w * -0.05f, h * 0.90f); lineTo(w * 1.05f, h * 0.90f)
            addOval(Rect(Offset(w * 0.5f, h * 0.73f), Size(w * 0.26f, h * 0.18f)).translateCenter())
        }
        SportsVisualKind.MMA -> Path().apply {
            // Octagon cage in perspective (flattened) + a centre mark.
            val cx = w * 0.5f
            val cy = h * 0.72f
            val rx = w * 0.42f
            val ry = h * 0.24f
            for (i in 0..8) {
                val a = Math.toRadians(22.5 + i * 45.0)
                val x = cx + rx * kotlin.math.cos(a).toFloat()
                val y = cy + ry * kotlin.math.sin(a).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            addOval(Rect(Offset(cx, cy), Size(w * 0.10f, h * 0.06f)).translateCenter())
        }
        SportsVisualKind.BOXING -> Path().apply {
            // Ring: three ropes converging to corner posts in perspective.
            val left = w * 0.08f
            val right = w * 0.92f
            for (k in 0..2) {
                val y = h * (0.52f + k * 0.11f)
                moveTo(left, y + h * 0.10f); lineTo(w * 0.22f, y - h * 0.06f); lineTo(w * 0.78f, y - h * 0.06f); lineTo(right, y + h * 0.10f)
            }
            moveTo(w * 0.22f, h * 0.36f); lineTo(w * 0.22f, h * 0.80f)
            moveTo(w * 0.78f, h * 0.36f); lineTo(w * 0.78f, h * 0.80f)
        }
        SportsVisualKind.GENERIC -> null
    }
}

private fun secondaryPath(kind: SportsVisualKind, size: Size): Path? {
    val w = size.width
    val h = size.height
    return when (kind) {
        SportsVisualKind.HOCKEY -> Path().apply { moveTo(w * 0.02f, h * 0.73f); lineTo(w * 0.98f, h * 0.73f) } // red line
        SportsVisualKind.BASEBALL -> Path().apply {
            addOval(Rect(Offset(w * 0.5f, h * 0.76f), Size(w * 0.10f, h * 0.06f)).translateCenter()) // mound
        }
        else -> null
    }
}

/** Treats [Rect.topLeft] as the desired center (helper for the motif builders above). */
private fun Rect.translateCenter(): Rect = Rect(
    left = left - width / 2,
    top = top - height / 2,
    right = right - width / 2,
    bottom = bottom - height / 2,
)
