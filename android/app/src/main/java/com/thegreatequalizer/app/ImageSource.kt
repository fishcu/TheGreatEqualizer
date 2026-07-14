package com.thegreatequalizer.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

interface ImageSource : Closeable {
    val width: Int
    val height: Int

    fun decodePreview(maxEdge: Int): Bitmap

    fun decodeRegion(
        region: ImageRegion,
        outputWidth: Int,
        outputHeight: Int
    ): Bitmap
}

class UriImageSource private constructor(
    private val sourceFile: File,
    private val regionDecoder: BitmapRegionDecoder?,
    private val rawWidth: Int,
    private val rawHeight: Int,
    private val orientation: Int
) : ImageSource {
    override val width =
        ImageOrientation.orientedWidth(rawWidth, rawHeight, orientation)
    override val height =
        ImageOrientation.orientedHeight(rawWidth, rawHeight, orientation)

    private var fallbackFullBitmap: Bitmap? = null
    private var closed = false

    @Synchronized
    override fun decodePreview(maxEdge: Int): Bitmap {
        require(maxEdge > 0) { "Preview edge limit must be positive" }
        checkOpen()

        val longerEdge = maxOf(width, height)
        val outputWidth: Int
        val outputHeight: Int
        if (longerEdge <= maxEdge) {
            outputWidth = width
            outputHeight = height
        } else {
            val scale = maxEdge.toDouble() / longerEdge.toDouble()
            outputWidth = (width * scale).toInt().coerceAtLeast(1)
            outputHeight = (height * scale).toInt().coerceAtLeast(1)
        }

        if (regionDecoder == null) {
            return decodeFallbackPreview(outputWidth, outputHeight)
        }
        return decodeRegion(
            ImageRegion(0, 0, width, height),
            outputWidth,
            outputHeight
        )
    }

    @Synchronized
    override fun decodeRegion(
        region: ImageRegion,
        outputWidth: Int,
        outputHeight: Int
    ): Bitmap {
        checkOpen()
        require(region.right <= width && region.bottom <= height) {
            "Requested region must be inside the image"
        }
        require(outputWidth in 1..region.width) {
            "Decoded region width must be a positive downsample"
        }
        require(outputHeight in 1..region.height) {
            "Decoded region height must be a positive downsample"
        }

        val decoder = regionDecoder
        if (decoder == null) {
            return decodeFallbackRegion(region, outputWidth, outputHeight)
        }

        val rawRegion = ImageOrientation.orientedToRawRegion(
            region,
            rawWidth,
            rawHeight,
            orientation
        )
        val targetRawWidth =
            if (ImageOrientation.swapsAxes(orientation)) outputHeight else outputWidth
        val targetRawHeight =
            if (ImageOrientation.swapsAxes(orientation)) outputWidth else outputHeight
        val sampleSize = ImageOrientation.regionSampleSize(
            rawRegion.width,
            rawRegion.height,
            targetRawWidth,
            targetRawHeight
        )
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        val rawBitmap = requireNotNull(
            decoder.decodeRegion(
                Rect(
                    rawRegion.left,
                    rawRegion.top,
                    rawRegion.right,
                    rawRegion.bottom
                ),
                options
            )
        ) {
            "Region decoder returned no bitmap"
        }
        return transformDecodedBitmap(
            rawBitmap,
            outputWidth,
            outputHeight
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        regionDecoder?.recycle()
        fallbackFullBitmap?.recycle()
        fallbackFullBitmap = null
        sourceFile.delete()
        sourceFile.parentFile?.delete()
    }

    private fun transformDecodedBitmap(
        rawBitmap: Bitmap,
        outputWidth: Int,
        outputHeight: Int
    ): Bitmap {
        if (
            orientation == ImageOrientation.NORMAL &&
            rawBitmap.width == outputWidth &&
            rawBitmap.height == outputHeight
        ) {
            return rawBitmap
        }

        val sourcePoints = floatArrayOf(
            0.0f,
            0.0f,
            rawBitmap.width.toFloat(),
            0.0f,
            0.0f,
            rawBitmap.height.toFloat()
        )
        val destinationPoints = when (orientation) {
            ImageOrientation.NORMAL -> floatArrayOf(
                0.0f, 0.0f,
                outputWidth.toFloat(), 0.0f,
                0.0f, outputHeight.toFloat()
            )
            ImageOrientation.FLIP_HORIZONTAL -> floatArrayOf(
                outputWidth.toFloat(), 0.0f,
                0.0f, 0.0f,
                outputWidth.toFloat(), outputHeight.toFloat()
            )
            ImageOrientation.ROTATE_180 -> floatArrayOf(
                outputWidth.toFloat(), outputHeight.toFloat(),
                0.0f, outputHeight.toFloat(),
                outputWidth.toFloat(), 0.0f
            )
            ImageOrientation.FLIP_VERTICAL -> floatArrayOf(
                0.0f, outputHeight.toFloat(),
                outputWidth.toFloat(), outputHeight.toFloat(),
                0.0f, 0.0f
            )
            ImageOrientation.TRANSPOSE -> floatArrayOf(
                0.0f, 0.0f,
                0.0f, outputHeight.toFloat(),
                outputWidth.toFloat(), 0.0f
            )
            ImageOrientation.ROTATE_90 -> floatArrayOf(
                outputWidth.toFloat(), 0.0f,
                outputWidth.toFloat(), outputHeight.toFloat(),
                0.0f, 0.0f
            )
            ImageOrientation.TRANSVERSE -> floatArrayOf(
                outputWidth.toFloat(), outputHeight.toFloat(),
                outputWidth.toFloat(), 0.0f,
                0.0f, outputHeight.toFloat()
            )
            ImageOrientation.ROTATE_270 -> floatArrayOf(
                0.0f, outputHeight.toFloat(),
                0.0f, 0.0f,
                outputWidth.toFloat(), outputHeight.toFloat()
            )
            else -> error("Unsupported EXIF orientation: $orientation")
        }

        val output = Bitmap.createBitmap(
            outputWidth,
            outputHeight,
            Bitmap.Config.ARGB_8888
        )
        var succeeded = false
        try {
            val matrix = Matrix()
            check(
                matrix.setPolyToPoly(
                    sourcePoints,
                    0,
                    destinationPoints,
                    0,
                    3
                )
            ) {
                "Could not construct the image orientation transform"
            }
            Canvas(output).drawBitmap(
                rawBitmap,
                matrix,
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            succeeded = true
            return output
        } finally {
            rawBitmap.recycle()
            if (!succeeded) {
                output.recycle()
            }
        }
    }

    private fun decodeFallbackPreview(
        outputWidth: Int,
        outputHeight: Int
    ): Bitmap {
        return ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(sourceFile)
        ) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(outputWidth, outputHeight)
        }
    }

    private fun decodeFallbackRegion(
        region: ImageRegion,
        outputWidth: Int,
        outputHeight: Int
    ): Bitmap {
        val fullBitmap = fallbackFullBitmap ?: decodeFallbackFullBitmap().also {
            fallbackFullBitmap = it
        }
        val output = Bitmap.createBitmap(
            outputWidth,
            outputHeight,
            Bitmap.Config.ARGB_8888
        )
        Canvas(output).drawBitmap(
            fullBitmap,
            Rect(region.left, region.top, region.right, region.bottom),
            Rect(0, 0, outputWidth, outputHeight),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        return output
    }

    private fun decodeFallbackFullBitmap(): Bitmap {
        val bitmap = ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(sourceFile)
        ) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        check(bitmap.width == width && bitmap.height == height) {
            "Fallback decoder dimensions do not match the image metadata"
        }
        return bitmap
    }

    private fun checkOpen() {
        check(!closed) { "Image source is closed" }
    }

    companion object {
        private const val CACHE_DIRECTORY = "image_sources"
        private val SESSION_DIRECTORY = UUID.randomUUID().toString()

        fun create(
            contentResolver: ContentResolver,
            uri: Uri,
            cacheDir: File
        ): UriImageSource {
            val sourceDirectory = File(
                File(cacheDir, CACHE_DIRECTORY),
                SESSION_DIRECTORY
            )
            check(sourceDirectory.exists() || sourceDirectory.mkdirs()) {
                "Could not create the image source cache"
            }
            val sourceFile = File(
                sourceDirectory,
                "source-${UUID.randomUUID()}"
            )

            try {
                val input = requireNotNull(contentResolver.openInputStream(uri)) {
                    "Could not open the selected image"
                }
                input.use { stream ->
                    FileOutputStream(sourceFile).use(stream::copyTo)
                }
                check(sourceFile.length() > 0L) {
                    "Selected image contains no data"
                }

                val bounds = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
                check(bounds.outWidth > 0 && bounds.outHeight > 0) {
                    "Could not read image dimensions"
                }
                val orientation = readOrientation(
                    sourceFile
                )
                val decoder = createRegionDecoder(sourceFile)
                return UriImageSource(
                    sourceFile,
                    decoder,
                    bounds.outWidth,
                    bounds.outHeight,
                    orientation
                )
            } catch (error: Exception) {
                sourceFile.delete()
                throw error
            }
        }

        fun clearStaleCache(cacheDir: File) {
            val sourceRoot = File(cacheDir, CACHE_DIRECTORY)
            sourceRoot.listFiles()
                ?.filter { it.name != SESSION_DIRECTORY }
                ?.forEach(File::deleteRecursively)
        }

        private fun readOrientation(sourceFile: File): Int {
            val orientation = try {
                ExifInterface(sourceFile)
                    .getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
            } catch (error: IOException) {
                ImageOrientation.NORMAL
            }
            return if (
                orientation in ImageOrientation.NORMAL..ImageOrientation.ROTATE_270
            ) {
                orientation
            } else {
                ImageOrientation.NORMAL
            }
        }

        @Suppress("DEPRECATION")
        private fun createRegionDecoder(
            sourceFile: File
        ): BitmapRegionDecoder? {
            return try {
                BitmapRegionDecoder.newInstance(
                    sourceFile.absolutePath,
                    false
                )
            } catch (error: IOException) {
                null
            }
        }
    }
}
