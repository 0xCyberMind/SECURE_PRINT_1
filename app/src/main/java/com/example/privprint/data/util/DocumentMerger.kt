package com.example.privprint.data.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.example.privprint.data.model.SelectedDocument
import java.io.ByteArrayOutputStream
import java.io.File

object DocumentMerger {

    /**
     * Merges multiple selected documents (PDFs and/or images) into a single unified
     * multi-page PDF document. If only one document is provided, it is returned directly.
     */
    fun mergeDocuments(context: Context, docs: List<SelectedDocument>): SelectedDocument {
        if (docs.isEmpty()) throw IllegalArgumentException("No documents provided to merge")
        if (docs.size == 1) return docs.first()

        val pdfDoc = PdfDocument()
        var pageNumber = 1

        try {
            for (doc in docs) {
                val isPdf = doc.mimeType.contains("pdf", ignoreCase = true) || doc.name.endsWith(".pdf", ignoreCase = true)
                if (isPdf) {
                    var tempFile: File? = null
                    var pfd: ParcelFileDescriptor? = null
                    var renderer: PdfRenderer? = null
                    try {
                        tempFile = File.createTempFile("privprint_merge_", ".pdf", context.cacheDir)
                        tempFile.writeBytes(doc.rawBytes)
                        pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                        renderer = PdfRenderer(pfd)
                        val count = renderer.pageCount
                        for (i in 0 until count) {
                            val pdfPage = renderer.openPage(i)
                            val w = pdfPage.width
                            val h = pdfPage.height
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            pdfPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            pdfPage.close()

                            val pageInfo = PdfDocument.PageInfo.Builder(w, h, pageNumber++).create()
                            val page = pdfDoc.startPage(pageInfo)
                            page.canvas.drawBitmap(bmp, 0f, 0f, null)
                            pdfDoc.finishPage(page)
                            bmp.recycle()
                        }
                    } catch (_: Exception) {
                        // In case of any read exception, fallback to raw bitmap decode
                        val bmp = BitmapFactory.decodeByteArray(doc.rawBytes, 0, doc.rawBytes.size)
                        if (bmp != null) {
                            addBitmapPage(pdfDoc, bmp, pageNumber++)
                            bmp.recycle()
                        }
                    } finally {
                        runCatching { renderer?.close() }
                        runCatching { pfd?.close() }
                        tempFile?.delete()
                    }
                } else {
                    val bmp = BitmapFactory.decodeByteArray(doc.rawBytes, 0, doc.rawBytes.size)
                    if (bmp != null) {
                        addBitmapPage(pdfDoc, bmp, pageNumber++)
                        bmp.recycle()
                    }
                }
            }

            val totalPages = pageNumber - 1
            if (totalPages == 0) {
                return docs.first()
            }

            val baos = ByteArrayOutputStream()
            pdfDoc.writeTo(baos)
            val mergedBytes = baos.toByteArray()

            val baseName = docs.first().name.substringBeforeLast('.')
            val combinedName = "${baseName}_and_${docs.size - 1}_more.pdf"

            return SelectedDocument(
                name = combinedName,
                mimeType = "application/pdf",
                sizeBytes = mergedBytes.size.toLong(),
                pageCount = totalPages,
                rawBytes = mergedBytes
            )
        } finally {
            pdfDoc.close()
        }
    }

    private fun addBitmapPage(pdfDoc: PdfDocument, bmp: Bitmap, pageNum: Int) {
        val a4W = 595
        val a4H = 842
        val pageInfo = PdfDocument.PageInfo.Builder(a4W, a4H, pageNum).create()
        val page = pdfDoc.startPage(pageInfo)
        val scale = minOf(a4W.toFloat() / bmp.width, a4H.toFloat() / bmp.height)
        val scaledW = (bmp.width * scale).toInt()
        val scaledH = (bmp.height * scale).toInt()
        val left = (a4W - scaledW) / 2f
        val top = (a4H - scaledH) / 2f
        val dstRect = RectF(left, top, left + scaledW, top + scaledH)
        page.canvas.drawBitmap(bmp, null, dstRect, null)
        pdfDoc.finishPage(page)
    }
}
