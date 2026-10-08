package com.privprint.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Enterprise vector iconography rendered with Compose Canvas.
 * Guaranteed 100% pixel-perfect crisp rendering on all Windows DPI scales
 * without external raster assets or unpredictable OS emoji variations.
 */
object StationIcons {

    @Composable
    fun Shield(
        modifier: Modifier = Modifier.size(16.dp),
        color: Color = Color.Unspecified
    ) {
        Canvas(modifier = modifier) {
            val c = if (color != Color.Unspecified) color else Color.White
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.5f, h * 0.08f)
                cubicTo(w * 0.78f, h * 0.08f, w * 0.90f, h * 0.16f, w * 0.90f, h * 0.28f)
                cubicTo(w * 0.90f, h * 0.65f, w * 0.65f, h * 0.85f, w * 0.50f, h * 0.94f)
                cubicTo(w * 0.35f, h * 0.85f, w * 0.10f, h * 0.65f, w * 0.10f, h * 0.28f)
                cubicTo(w * 0.10f, h * 0.16f, w * 0.22f, h * 0.08f, w * 0.50f, h * 0.08f)
                close()
            }
            drawPath(path, c, style = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round))

            // Checkmark inside shield
            val checkPath = Path().apply {
                moveTo(w * 0.35f, h * 0.50f)
                lineTo(w * 0.46f, h * 0.62f)
                lineTo(w * 0.66f, h * 0.38f)
            }
            drawPath(checkPath, c, style = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    @Composable
    fun Dashboard(
        modifier: Modifier = Modifier.size(16.dp),
        color: Color = Color.Unspecified
    ) {
        Canvas(modifier = modifier) {
            val c = if (color != Color.Unspecified) color else Color.White
            val w = size.width
            val h = size.height
            val gap = w * 0.15f
            val itemW = (w - gap - w * 0.16f) / 2f
            val itemH = (h - gap - h * 0.16f) / 2f
            val r = CornerRadius(w * 0.08f)

            // Top-left
            drawRoundRect(c, Offset(w * 0.08f, h * 0.08f), Size(itemW, itemH), r)
            // Top-right
            drawRoundRect(c, Offset(w * 0.08f + itemW + gap, h * 0.08f), Size(itemW, itemH), r)
            // Bottom-left
            drawRoundRect(c, Offset(w * 0.08f, h * 0.08f + itemH + gap), Size(itemW, itemH), r)
            // Bottom-right
            drawRoundRect(c, Offset(w * 0.08f + itemW + gap, h * 0.08f + itemH + gap), Size(itemW, itemH), r)
        }
    }

    @Composable
    fun PrintQueue(
        modifier: Modifier = Modifier.size(16.dp),
        color: Color = Color.Unspecified
    ) {
        Canvas(modifier = modifier) {
            val c = if (color != Color.Unspecified) color else Color.White
            val w = size.width
            val h = size.height
            val sw = w * 0.085f

            // Top sheet
            val p1 = Path().apply {
                moveTo(w * 0.22f, h * 0.20f)
                lineTo(w * 0.78f, h * 0.20f)
            }
            drawPath(p1, c, style = Stroke(width = sw, cap = StrokeCap.Round))

            // Middle sheet
            val p2 = Path().apply {
                moveTo(w * 0.15f, h * 0.44f)
                lineTo(w * 0.85f, h * 0.44f)
            }
            drawPath(p2, c, style = Stroke(width = sw, cap = StrokeCap.Round))

            // Main tray / box
            val p3 = Path().apply {
                moveTo(w * 0.10f, h * 0.60f)
                lineTo(w * 0.10f, h * 0.82f)
                cubicTo(w * 0.10f, h * 0.88f, w * 0.15f, h * 0.90f, w * 0.22f, h * 0.90f)
                lineTo(w * 0.78f, h * 0.90f)
                cubicTo(w * 0.85f, h * 0.90f, w * 0.90f, h * 0.88f, w * 0.90f, h * 0.82f)
                lineTo(w * 0.90f, h * 0.60f)
                lineTo(w * 0.70f, h * 0.60f)
                lineTo(w * 0.62f, h * 0.72f)
                lineTo(w * 0.38f, h * 0.72f)
                lineTo(w * 0.30f, h * 0.60f)
                close()
            }
            drawPath(p3, c, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    @Composable
    fun Printer(
        modifier: Modifier = Modifier.size(16.dp),
        color: Color = Color.Unspecified
    ) {
        Canvas(modifier = modifier) {
            val c = if (color != Color.Unspecified) color else Color.White
            val w = size.width
            val h = size.height
            val sw = w * 0.085f

            // Top paper feed
            val topPaper = Path().apply {
                moveTo(w * 0.26f, h * 0.40f)
                lineTo(w * 0.26f, h * 0.15f)
                lineTo(w * 0.74f, h * 0.15f)
                lineTo(w * 0.74f, h * 0.40f)
            }
            drawPath(topPaper, c, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))

            // Printer main body
            drawRoundRect(
                c,
                Offset(w * 0.10f, h * 0.40f),
                Size(w * 0.80f, h * 0.40f),
                CornerRadius(w * 0.10f),
                style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Paper exit tray slot
            val exitTray = Path().apply {
                moveTo(w * 0.26f, h * 0.68f)
                lineTo(w * 0.26f, h * 0.88f)
                lineTo(w * 0.74f, h * 0.88f)
                lineTo(w * 0.74f, h * 0.68f)
            }
            drawPath(exitTray, c, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))

            // LED indicator dot
            drawCircle(c, radius = w * 0.04f, center = Offset(w * 0.76f, h * 0.52f))
        }
    }

    @Composable
    fun QrCode(
        modifier: Modifier = Modifier.size(16.dp),
        color: Color = Color.Unspecified
    ) {
        Canvas(modifier = modifier) {
            val c = if (color != Color.Unspecified) color else Color.White
            val w = size.width
            val h = size.height
            val sw = w * 0.09f
            val boxSize = w * 0.30f

            // Top-left finder
            drawRoundRect(c, Offset(w * 0.08f, h * 0.08f), Size(boxSize, boxSize), CornerRadius(w * 0.06f), style = Stroke(sw))
            drawCircle(c, radius = w * 0.06f, center = Offset(w * 0.08f + boxSize / 2f, h * 0.08f + boxSize / 2f))

            // Top-right finder
            drawRoundRect(c, Offset(w * 0.62f, h * 0.08f), Size(boxSize, boxSize), CornerRadius(w * 0.06f), style = Stroke(sw))
            drawCircle(c, radius = w * 0.06f, center = Offset(w * 0.62f + boxSize / 2f, h * 0.08f + boxSize / 2f))

            // Bottom-left finder
            drawRoundRect(c, Offset(w * 0.08f, h * 0.62f), Size(boxSize, boxSize), CornerRadius(w * 0.06f), style = Stroke(sw))
            drawCircle(c, radius = w * 0.06f, center = Offset(w * 0.08f + boxSize / 2f, h * 0.62f + boxSize / 2f))

            // Bottom-right data pattern dots
            drawCircle(c, radius = w * 0.05f, center = Offset(w * 0.68f, h * 0.68f))
            drawCircle(c, radius = w * 0.05f, center = Offset(w * 0.84f, h * 0.68f))
            drawCircle(c, radius = w * 0.05f, center = Offset(w * 0.76f, h * 0.84f))
        }
    }

    @Composable
    fun Audit(
        modifier: Modifier = Modifier.size(16.dp),
        color: Color = Color.Unspecified
    ) {
        Canvas(modifier = modifier) {
            val c = if (color != Color.Unspecified) color else Color.White
            val w = size.width
            val h = size.height
            val sw = w * 0.085f

            // Outer shield
            val path = Path().apply {
                moveTo(w * 0.50f, h * 0.10f)
                cubicTo(w * 0.76f, h * 0.10f, w * 0.88f, h * 0.18f, w * 0.88f, h * 0.32f)
                cubicTo(w * 0.88f, h * 0.64f, w * 0.64f, h * 0.84f, w * 0.50f, h * 0.92f)
                cubicTo(w * 0.36f, h * 0.84f, w * 0.12f, h * 0.64f, w * 0.12f, h * 0.32f)
                cubicTo(w * 0.12f, h * 0.18f, w * 0.24f, h * 0.10f, w * 0.50f, h * 0.10f)
                close()
            }
            drawPath(path, c, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))

            // Pulse line inside
            val pulse = Path().apply {
                moveTo(w * 0.25f, h * 0.50f)
                lineTo(w * 0.40f, h * 0.50f)
                lineTo(w * 0.47f, h * 0.34f)
                lineTo(w * 0.53f, h * 0.66f)
                lineTo(w * 0.60f, h * 0.50f)
                lineTo(w * 0.75f, h * 0.50f)
            }
            drawPath(pulse, c, style = Stroke(width = sw * 0.95f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    @Composable
    fun Station(
        modifier: Modifier = Modifier.size(16.dp),
        color: Color = Color.Unspecified
    ) {
        Canvas(modifier = modifier) {
            val c = if (color != Color.Unspecified) color else Color.White
            val w = size.width
            val h = size.height
            val sw = w * 0.085f

            // Screen
            drawRoundRect(
                c,
                Offset(w * 0.12f, h * 0.12f),
                Size(w * 0.76f, h * 0.56f),
                CornerRadius(w * 0.08f),
                style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Stand neck
            drawLine(c, Offset(w * 0.50f, h * 0.68f), Offset(w * 0.50f, h * 0.85f), strokeWidth = sw, cap = StrokeCap.Round)

            // Stand base
            drawLine(c, Offset(w * 0.30f, h * 0.85f), Offset(w * 0.70f, h * 0.85f), strokeWidth = sw, cap = StrokeCap.Round)
        }
    }

    @Composable
    fun Settings(
        modifier: Modifier = Modifier.size(16.dp),
        color: Color = Color.Unspecified
    ) {
        Canvas(modifier = modifier) {
            val c = if (color != Color.Unspecified) color else Color.White
            val w = size.width
            val h = size.height
            val sw = w * 0.085f

            // Center hole
            drawCircle(c, radius = w * 0.18f, center = Offset(w * 0.50f, h * 0.50f), style = Stroke(sw))

            // Outer teeth circle
            drawCircle(c, radius = w * 0.35f, center = Offset(w * 0.50f, h * 0.50f), style = Stroke(sw * 0.75f))

            // Cogs
            val toothLen = w * 0.10f
            for (i in 0 until 6) {
                val angle = Math.toRadians((i * 60).toDouble())
                val cos = Math.cos(angle).toFloat()
                val sin = Math.sin(angle).toFloat()
                val start = Offset(w * 0.50f + cos * (w * 0.33f), h * 0.50f + sin * (h * 0.33f))
                val end = Offset(w * 0.50f + cos * (w * 0.33f + toothLen), h * 0.50f + sin * (h * 0.33f + toothLen))
                drawLine(c, start, end, strokeWidth = sw * 1.2f, cap = StrokeCap.Round)
            }
        }
    }

    @Composable
    fun Check(
        modifier: Modifier = Modifier.size(14.dp),
        color: Color = Color.Unspecified
    ) {
        Canvas(modifier = modifier) {
            val c = if (color != Color.Unspecified) color else Color.White
            val w = size.width
            val h = size.height
            val sw = w * 0.14f
            val p = Path().apply {
                moveTo(w * 0.18f, h * 0.52f)
                lineTo(w * 0.42f, h * 0.76f)
                lineTo(w * 0.82f, h * 0.24f)
            }
            drawPath(p, c, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    @Composable
    fun Close(
        modifier: Modifier = Modifier.size(14.dp),
        color: Color = Color.Unspecified
    ) {
        Canvas(modifier = modifier) {
            val c = if (color != Color.Unspecified) color else Color.White
            val w = size.width
            val h = size.height
            val sw = w * 0.13f
            drawLine(c, Offset(w * 0.22f, h * 0.22f), Offset(w * 0.78f, h * 0.78f), strokeWidth = sw, cap = StrokeCap.Round)
            drawLine(c, Offset(w * 0.78f, h * 0.22f), Offset(w * 0.22f, h * 0.78f), strokeWidth = sw, cap = StrokeCap.Round)
        }
    }

    @Composable
    fun Refresh(
        modifier: Modifier = Modifier.size(14.dp),
        color: Color = Color.Unspecified
    ) {
        Canvas(modifier = modifier) {
            val c = if (color != Color.Unspecified) color else Color.White
            val w = size.width
            val h = size.height
            val sw = w * 0.11f

            // Circular arc
            drawArc(
                color = c,
                startAngle = 45f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(w * 0.16f, h * 0.16f),
                size = Size(w * 0.68f, h * 0.68f),
                style = Stroke(width = sw, cap = StrokeCap.Round)
            )

            // Arrow head
            val arrow = Path().apply {
                moveTo(w * 0.75f, h * 0.10f)
                lineTo(w * 0.90f, h * 0.32f)
                lineTo(w * 0.66f, h * 0.36f)
            }
            drawPath(arrow, c, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    @Composable
    fun Document(
        modifier: Modifier = Modifier.size(16.dp),
        color: Color = Color.Unspecified
    ) {
        Canvas(modifier = modifier) {
            val c = if (color != Color.Unspecified) color else Color.White
            val w = size.width
            val h = size.height
            val sw = w * 0.085f

            val doc = Path().apply {
                moveTo(w * 0.18f, h * 0.10f)
                lineTo(w * 0.58f, h * 0.10f)
                lineTo(w * 0.82f, h * 0.34f)
                lineTo(w * 0.82f, h * 0.90f)
                lineTo(w * 0.18f, h * 0.90f)
                close()
            }
            drawPath(doc, c, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))

            // Dog-ear fold
            val fold = Path().apply {
                moveTo(w * 0.58f, h * 0.10f)
                lineTo(w * 0.58f, h * 0.34f)
                lineTo(w * 0.82f, h * 0.34f)
            }
            drawPath(fold, c, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))

            // Text lines
            drawLine(c, Offset(w * 0.30f, h * 0.50f), Offset(w * 0.70f, h * 0.50f), strokeWidth = sw * 0.9f, cap = StrokeCap.Round)
            drawLine(c, Offset(w * 0.30f, h * 0.66f), Offset(w * 0.60f, h * 0.66f), strokeWidth = sw * 0.9f, cap = StrokeCap.Round)
        }
    }

    @Composable
    fun Eye(
        modifier: Modifier = Modifier.size(16.dp),
        color: Color = Color.Unspecified
    ) {
        Canvas(modifier = modifier) {
            val c = if (color != Color.Unspecified) color else Color.White
            val w = size.width
            val h = size.height
            val sw = w * 0.085f

            val eye = Path().apply {
                moveTo(w * 0.10f, h * 0.50f)
                cubicTo(w * 0.25f, h * 0.25f, w * 0.75f, h * 0.25f, w * 0.90f, h * 0.50f)
                cubicTo(w * 0.75f, h * 0.75f, w * 0.25f, h * 0.75f, w * 0.10f, h * 0.50f)
                close()
            }
            drawPath(eye, c, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))

            // Iris
            drawCircle(c, radius = w * 0.14f, center = Offset(w * 0.50f, h * 0.50f))
        }
    }

    @Composable
    fun Lock(
        modifier: Modifier = Modifier.size(14.dp),
        color: Color = Color.Unspecified
    ) {
        Canvas(modifier = modifier) {
            val c = if (color != Color.Unspecified) color else Color.White
            val w = size.width
            val h = size.height
            val sw = w * 0.09f

            // Lock body
            drawRoundRect(
                c,
                Offset(w * 0.18f, h * 0.44f),
                Size(w * 0.64f, h * 0.48f),
                CornerRadius(w * 0.10f),
                style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Shackle
            val shackle = Path().apply {
                moveTo(w * 0.32f, h * 0.44f)
                lineTo(w * 0.32f, h * 0.28f)
                cubicTo(w * 0.32f, h * 0.12f, w * 0.68f, h * 0.12f, w * 0.68f, h * 0.28f)
                lineTo(w * 0.68f, h * 0.44f)
            }
            drawPath(shackle, c, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))

            // Keyhole
            drawCircle(c, radius = w * 0.06f, center = Offset(w * 0.50f, h * 0.64f))
            drawLine(c, Offset(w * 0.50f, h * 0.64f), Offset(w * 0.50f, h * 0.76f), strokeWidth = sw * 1.1f, cap = StrokeCap.Round)
        }
    }

    @Composable
    fun ChevronRight(
        modifier: Modifier = Modifier.size(12.dp),
        color: Color = Color.Unspecified
    ) {
        Canvas(modifier = modifier) {
            val c = if (color != Color.Unspecified) color else Color.White
            val w = size.width
            val h = size.height
            val sw = w * 0.14f
            val p = Path().apply {
                moveTo(w * 0.32f, h * 0.18f)
                lineTo(w * 0.68f, h * 0.50f)
                lineTo(w * 0.32f, h * 0.82f)
            }
            drawPath(p, c, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    @Composable
    fun Search(
        modifier: Modifier = Modifier.size(14.dp),
        color: Color = Color.Unspecified
    ) {
        Canvas(modifier = modifier) {
            val c = if (color != Color.Unspecified) color else Color.White
            val w = size.width
            val h = size.height
            val sw = w * 0.10f

            drawCircle(c, radius = w * 0.28f, center = Offset(w * 0.42f, h * 0.42f), style = Stroke(sw))
            drawLine(c, Offset(w * 0.62f, h * 0.62f), Offset(w * 0.85f, h * 0.85f), strokeWidth = sw * 1.2f, cap = StrokeCap.Round)
        }
    }

    @Composable
    fun User(
        modifier: Modifier = Modifier.size(16.dp),
        color: Color = Color.Unspecified
    ) {
        Canvas(modifier = modifier) {
            val c = if (color != Color.Unspecified) color else Color.White
            val w = size.width
            val h = size.height
            val sw = w * 0.085f

            // Head
            drawCircle(c, radius = w * 0.20f, center = Offset(w * 0.50f, h * 0.30f), style = Stroke(sw))

            // Body
            val body = Path().apply {
                moveTo(w * 0.18f, h * 0.85f)
                cubicTo(w * 0.18f, h * 0.65f, w * 0.34f, h * 0.60f, w * 0.50f, h * 0.60f)
                cubicTo(w * 0.66f, h * 0.60f, w * 0.82f, h * 0.65f, w * 0.82f, h * 0.85f)
            }
            drawPath(body, c, style = Stroke(width = sw, cap = StrokeCap.Round))
        }
    }

    @Composable
    fun Alert(
        modifier: Modifier = Modifier.size(16.dp),
        color: Color = Color.Unspecified
    ) {
        Canvas(modifier = modifier) {
            val c = if (color != Color.Unspecified) color else Color.White
            val w = size.width
            val h = size.height
            val sw = w * 0.09f

            val tri = Path().apply {
                moveTo(w * 0.50f, h * 0.12f)
                lineTo(w * 0.90f, h * 0.84f)
                lineTo(w * 0.10f, h * 0.84f)
                close()
            }
            drawPath(tri, c, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))

            drawLine(c, Offset(w * 0.50f, h * 0.40f), Offset(w * 0.50f, h * 0.62f), strokeWidth = sw * 1.1f, cap = StrokeCap.Round)
            drawCircle(c, radius = w * 0.045f, center = Offset(w * 0.50f, h * 0.74f))
        }
    }

    @Composable
    fun Logout(
        modifier: Modifier = Modifier.size(14.dp),
        color: Color = Color.Unspecified
    ) {
        Canvas(modifier = modifier) {
            val c = if (color != Color.Unspecified) color else Color.White
            val w = size.width
            val h = size.height
            val sw = w * 0.10f

            // Door frame
            val frame = Path().apply {
                moveTo(w * 0.55f, h * 0.15f)
                lineTo(w * 0.20f, h * 0.15f)
                lineTo(w * 0.20f, h * 0.85f)
                lineTo(w * 0.55f, h * 0.85f)
            }
            drawPath(frame, c, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))

            // Exit arrow
            drawLine(c, Offset(w * 0.40f, h * 0.50f), Offset(w * 0.85f, h * 0.50f), strokeWidth = sw, cap = StrokeCap.Round)
            val arrow = Path().apply {
                moveTo(w * 0.70f, h * 0.35f)
                lineTo(w * 0.88f, h * 0.50f)
                lineTo(w * 0.70f, h * 0.65f)
            }
            drawPath(arrow, c, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    @Composable
    fun Location(
        modifier: Modifier = Modifier.size(16.dp),
        color: Color = Color.Unspecified
    ) {
        Canvas(modifier = modifier) {
            val c = if (color != Color.Unspecified) color else Color.White
            val w = size.width
            val h = size.height
            val sw = w * 0.085f

            // Pin head and tip
            val path = Path().apply {
                moveTo(w * 0.5f, h * 0.92f)
                cubicTo(w * 0.35f, h * 0.70f, w * 0.15f, h * 0.48f, w * 0.15f, h * 0.35f)
                cubicTo(w * 0.15f, h * 0.16f, w * 0.31f, h * 0.08f, w * 0.5f, h * 0.08f)
                cubicTo(w * 0.69f, h * 0.08f, w * 0.85f, h * 0.16f, w * 0.85f, h * 0.35f)
                cubicTo(w * 0.85f, h * 0.48f, w * 0.65f, h * 0.70f, w * 0.5f, h * 0.92f)
                close()
            }
            drawPath(path, c, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))

            // Inner circle
            drawCircle(c, radius = w * 0.13f, center = Offset(w * 0.5f, h * 0.35f), style = Stroke(width = sw))
        }
    }
}
