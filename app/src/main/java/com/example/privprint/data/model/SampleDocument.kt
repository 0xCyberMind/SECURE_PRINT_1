package com.example.privprint.data.model

data class SelectedDocument(
    val name: String,
    val sizeBytes: Long,
    val pageCount: Int,
    val mimeType: String,
    val rawBytes: ByteArray,
    val isSample: Boolean = false
) {
    val formattedSize: String
        get() = when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
            else -> "%.1f MB".format(sizeBytes.toDouble() / (1024 * 1024))
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SelectedDocument
        return name == other.name && sizeBytes == other.sizeBytes && pageCount == other.pageCount
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + pageCount
        return result
    }

    companion object {
        fun createSample(title: String, pages: Int, approxBytes: Long): SelectedDocument {
            val dummyContent = "PRIVPRINT_SECURE_PAYLOAD_$title".toByteArray()
            return SelectedDocument(
                name = title,
                sizeBytes = approxBytes,
                pageCount = pages,
                mimeType = if (title.endsWith(".png")) "image/png" else "application/pdf",
                rawBytes = dummyContent,
                isSample = true
            )
        }

        val SAMPLES = listOf(
            createSample("Confidential_Employment_Contract.pdf", 3, 145_400),
            createSample("National_ID_Card_Front_Back.png", 1, 88_200),
            createSample("Medical_Prescription_Records.pdf", 2, 112_800),
            createSample("University_Semester_GradeCard.pdf", 4, 234_500)
        )
    }
}
