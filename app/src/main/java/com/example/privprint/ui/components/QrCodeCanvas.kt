package com.example.privprint.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Standard ISO/IEC 18004 compliant QR Code Canvas Renderer.
 * Uses ZXing QRCodeWriter to generate authentic QR code matrices with
 * standard Reed-Solomon error correction and finder patterns scannable by
 * any physical camera or scanner.
 */
@Composable
fun QrCodeCanvas(
    payload: String,
    modifier: Modifier = Modifier,
    moduleColor: Color = Color(0xFF0F172A),
    backgroundColor: Color = Color.White
) {
    val matrix = remember(payload) {
        generateRealQrMatrix(payload)
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (matrix.isNotEmpty()) {
                val rows = matrix.size
                val cols = matrix[0].size
                val moduleSizeX = size.width / cols
                val moduleSizeY = size.height / rows

                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        if (matrix[r][c]) {
                            drawRect(
                                color = moduleColor,
                                topLeft = Offset(c * moduleSizeX, r * moduleSizeY),
                                size = Size(moduleSizeX + 0.5f, moduleSizeY + 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun generateRealQrMatrix(payload: String): Array<BooleanArray> {
    return try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val bitMatrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
        val width = bitMatrix.width
        val height = bitMatrix.height
        Array(height) { r ->
            BooleanArray(width) { c ->
                bitMatrix.get(c, r)
            }
        }
    } catch (e: Exception) {
        // Fallback finder frame in extreme failure cases
        val n = 21
        Array(n) { r ->
            BooleanArray(n) { c ->
                (r in 0..6 && c in 0..6) || (r in 0..6 && c in 14..20) || (r in 14..20 && c in 0..6)
            }
        }
    }
}
