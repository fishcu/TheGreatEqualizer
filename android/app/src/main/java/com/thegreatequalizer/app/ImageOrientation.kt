package com.thegreatequalizer.app

data class ImageRegion(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
) {
    init {
        require(left >= 0) { "Image region left edge must be non-negative" }
        require(top >= 0) { "Image region top edge must be non-negative" }
        require(width > 0) { "Image region width must be positive" }
        require(height > 0) { "Image region height must be positive" }
    }

    val right: Int
        get() = left + width

    val bottom: Int
        get() = top + height
}

data class OrientedPixels(
    val pixels: IntArray,
    val width: Int,
    val height: Int
)

object ImageOrientation {
    const val NORMAL = 1
    const val FLIP_HORIZONTAL = 2
    const val ROTATE_180 = 3
    const val FLIP_VERTICAL = 4
    const val TRANSPOSE = 5
    const val ROTATE_90 = 6
    const val TRANSVERSE = 7
    const val ROTATE_270 = 8

    fun swapsAxes(orientation: Int): Boolean {
        requireValid(orientation)
        return orientation in TRANSPOSE..ROTATE_270
    }

    fun orientedWidth(rawWidth: Int, rawHeight: Int, orientation: Int): Int {
        require(rawWidth > 0 && rawHeight > 0) { "Raw image dimensions must be positive" }
        return if (swapsAxes(orientation)) rawHeight else rawWidth
    }

    fun orientedHeight(rawWidth: Int, rawHeight: Int, orientation: Int): Int {
        require(rawWidth > 0 && rawHeight > 0) { "Raw image dimensions must be positive" }
        return if (swapsAxes(orientation)) rawWidth else rawHeight
    }

    fun orientedToRawRegion(
        region: ImageRegion,
        rawWidth: Int,
        rawHeight: Int,
        orientation: Int
    ): ImageRegion {
        val orientedWidth = orientedWidth(rawWidth, rawHeight, orientation)
        val orientedHeight = orientedHeight(rawWidth, rawHeight, orientation)
        require(region.right <= orientedWidth && region.bottom <= orientedHeight) {
            "Image region must be inside the oriented image bounds"
        }

        return when (orientation) {
            NORMAL -> region
            FLIP_HORIZONTAL -> ImageRegion(
                rawWidth - region.right,
                region.top,
                region.width,
                region.height
            )
            ROTATE_180 -> ImageRegion(
                rawWidth - region.right,
                rawHeight - region.bottom,
                region.width,
                region.height
            )
            FLIP_VERTICAL -> ImageRegion(
                region.left,
                rawHeight - region.bottom,
                region.width,
                region.height
            )
            TRANSPOSE -> ImageRegion(
                region.top,
                region.left,
                region.height,
                region.width
            )
            ROTATE_90 -> ImageRegion(
                region.top,
                rawHeight - region.right,
                region.height,
                region.width
            )
            TRANSVERSE -> ImageRegion(
                rawWidth - region.bottom,
                rawHeight - region.right,
                region.height,
                region.width
            )
            ROTATE_270 -> ImageRegion(
                rawWidth - region.bottom,
                region.left,
                region.height,
                region.width
            )
            else -> error("Unsupported EXIF orientation: $orientation")
        }
    }

    fun orientPixels(
        rawPixels: IntArray,
        rawWidth: Int,
        rawHeight: Int,
        orientation: Int
    ): OrientedPixels {
        require(rawWidth > 0 && rawHeight > 0) { "Raw image dimensions must be positive" }
        require(rawPixels.size == rawWidth * rawHeight) {
            "Pixel count must match the raw image dimensions"
        }
        requireValid(orientation)

        if (orientation == NORMAL) {
            return OrientedPixels(rawPixels, rawWidth, rawHeight)
        }

        val outputWidth = orientedWidth(rawWidth, rawHeight, orientation)
        val outputHeight = orientedHeight(rawWidth, rawHeight, orientation)
        val output = IntArray(rawPixels.size)
        for (rawY in 0 until rawHeight) {
            for (rawX in 0 until rawWidth) {
                val (outputX, outputY) = when (orientation) {
                    FLIP_HORIZONTAL -> rawWidth - 1 - rawX to rawY
                    ROTATE_180 -> rawWidth - 1 - rawX to rawHeight - 1 - rawY
                    FLIP_VERTICAL -> rawX to rawHeight - 1 - rawY
                    TRANSPOSE -> rawY to rawX
                    ROTATE_90 -> rawHeight - 1 - rawY to rawX
                    TRANSVERSE ->
                        rawHeight - 1 - rawY to rawWidth - 1 - rawX
                    ROTATE_270 -> rawY to rawWidth - 1 - rawX
                    else -> error("Unsupported EXIF orientation: $orientation")
                }
                output[outputY * outputWidth + outputX] =
                    rawPixels[rawY * rawWidth + rawX]
            }
        }
        return OrientedPixels(output, outputWidth, outputHeight)
    }

    fun regionSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        require(sourceWidth > 0 && sourceHeight > 0) {
            "Source dimensions must be positive"
        }
        require(targetWidth > 0 && targetHeight > 0) {
            "Target dimensions must be positive"
        }

        var sampleSize = 1
        while (
            sourceWidth / (sampleSize * 2) >= targetWidth &&
            sourceHeight / (sampleSize * 2) >= targetHeight
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun requireValid(orientation: Int) {
        require(orientation in NORMAL..ROTATE_270) {
            "Unsupported EXIF orientation: $orientation"
        }
    }
}
