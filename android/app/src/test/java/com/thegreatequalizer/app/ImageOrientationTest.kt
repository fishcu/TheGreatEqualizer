package com.thegreatequalizer.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageOrientationTest {
    private val rawPixels = intArrayOf(
        1, 2, 3,
        4, 5, 6
    )

    @Test
    fun allExifOrientationsProduceExpectedPixels() {
        assertOrientation(
            ImageOrientation.NORMAL,
            3,
            2,
            intArrayOf(1, 2, 3, 4, 5, 6)
        )
        assertOrientation(
            ImageOrientation.FLIP_HORIZONTAL,
            3,
            2,
            intArrayOf(3, 2, 1, 6, 5, 4)
        )
        assertOrientation(
            ImageOrientation.ROTATE_180,
            3,
            2,
            intArrayOf(6, 5, 4, 3, 2, 1)
        )
        assertOrientation(
            ImageOrientation.FLIP_VERTICAL,
            3,
            2,
            intArrayOf(4, 5, 6, 1, 2, 3)
        )
        assertOrientation(
            ImageOrientation.TRANSPOSE,
            2,
            3,
            intArrayOf(1, 4, 2, 5, 3, 6)
        )
        assertOrientation(
            ImageOrientation.ROTATE_90,
            2,
            3,
            intArrayOf(4, 1, 5, 2, 6, 3)
        )
        assertOrientation(
            ImageOrientation.TRANSVERSE,
            2,
            3,
            intArrayOf(6, 3, 5, 2, 4, 1)
        )
        assertOrientation(
            ImageOrientation.ROTATE_270,
            2,
            3,
            intArrayOf(3, 6, 2, 5, 1, 4)
        )
    }

    @Test
    fun allExifOrientationsMapDisplayRegionsToRawCoordinates() {
        val displayRegion =
            ImageRegion(left = 1, top = 2, width = 3, height = 4)
        val expectedRegions = mapOf(
            ImageOrientation.NORMAL to
                ImageRegion(left = 1, top = 2, width = 3, height = 4),
            ImageOrientation.FLIP_HORIZONTAL to
                ImageRegion(left = 6, top = 2, width = 3, height = 4),
            ImageOrientation.ROTATE_180 to
                ImageRegion(left = 6, top = 14, width = 3, height = 4),
            ImageOrientation.FLIP_VERTICAL to
                ImageRegion(left = 1, top = 14, width = 3, height = 4),
            ImageOrientation.TRANSPOSE to
                ImageRegion(left = 2, top = 1, width = 4, height = 3),
            ImageOrientation.ROTATE_90 to
                ImageRegion(left = 2, top = 16, width = 4, height = 3),
            ImageOrientation.TRANSVERSE to
                ImageRegion(left = 4, top = 16, width = 4, height = 3),
            ImageOrientation.ROTATE_270 to
                ImageRegion(left = 4, top = 1, width = 4, height = 3)
        )

        for ((orientation, expectedRegion) in expectedRegions) {
            assertEquals(
                expectedRegion,
                ImageOrientation.orientedToRawRegion(
                    displayRegion,
                    rawWidth = 10,
                    rawHeight = 20,
                    orientation
                )
            )
        }
    }

    @Test
    fun regionSamplingKeepsAtLeastTheRequestedResolution() {
        assertEquals(
            4,
            ImageOrientation.regionSampleSize(
                sourceWidth = 8000,
                sourceHeight = 6000,
                targetWidth = 1500,
                targetHeight = 1000
            )
        )
        assertEquals(
            1,
            ImageOrientation.regionSampleSize(
                sourceWidth = 1024,
                sourceHeight = 768,
                targetWidth = 1024,
                targetHeight = 768
            )
        )
    }

    private fun assertOrientation(
        orientation: Int,
        expectedWidth: Int,
        expectedHeight: Int,
        expectedPixels: IntArray
    ) {
        val oriented = ImageOrientation.orientPixels(
            rawPixels,
            rawWidth = 3,
            rawHeight = 2,
            orientation
        )

        assertEquals(expectedWidth, oriented.width)
        assertEquals(expectedHeight, oriented.height)
        assertArrayEquals(expectedPixels, oriented.pixels)
    }
}
