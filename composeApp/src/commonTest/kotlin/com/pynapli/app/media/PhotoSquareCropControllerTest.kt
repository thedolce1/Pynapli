package com.pynapli.app.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhotoSquareCropControllerTest {
    @Test
    fun initialCropIsTheCenteredMaxSquare() {
        val landscape = PhotoSquareCropController(imageWidth = 1200, imageHeight = 800)
        assertEquals(PhotoCropRect(left = 200, top = 0, side = 800), landscape.cropRect())

        val portrait = PhotoSquareCropController(imageWidth = 600, imageHeight = 1000)
        assertEquals(PhotoCropRect(left = 0, top = 200, side = 600), portrait.cropRect())

        val square = PhotoSquareCropController(imageWidth = 512, imageHeight = 512)
        assertEquals(PhotoCropRect(left = 0, top = 0, side = 512), square.cropRect())
    }

    @Test
    fun panIsClampedToTheImageEdges() {
        val controller = PhotoSquareCropController(imageWidth = 1200, imageHeight = 800)

        // Dragging the image far to the right pins the crop window at the left edge.
        controller.applyGesture(panX = 10_000f, panY = 0f, zoomFactor = 1f, viewportSide = 800f)
        assertEquals(0, controller.cropRect().left)

        // And far to the left pins it at the right edge.
        controller.applyGesture(panX = -20_000f, panY = 0f, zoomFactor = 1f, viewportSide = 800f)
        assertEquals(400, controller.cropRect().left)
    }

    @Test
    fun panDistanceScalesWithZoom() {
        val controller = PhotoSquareCropController(imageWidth = 2000, imageHeight = 2000)
        controller.applyGesture(panX = 0f, panY = 0f, zoomFactor = 2f, viewportSide = 1000f)

        // At zoom 2 the crop side is 1000 image px over a 1000px viewport: 1 viewport px = 1 image px.
        controller.applyGesture(panX = -100f, panY = 0f, zoomFactor = 1f, viewportSide = 1000f)
        assertEquals(1100f, controller.centerX)
    }

    @Test
    fun zoomIsClampedBetweenOneAndMax() {
        val controller = PhotoSquareCropController(imageWidth = 1000, imageHeight = 1000)

        controller.applyGesture(panX = 0f, panY = 0f, zoomFactor = 0.01f, viewportSide = 1000f)
        assertEquals(1f, controller.zoom)

        controller.applyGesture(panX = 0f, panY = 0f, zoomFactor = 100f, viewportSide = 1000f)
        assertEquals(PhotoSquareCropController.MAX_ZOOM, controller.zoom)
        assertEquals(250, controller.cropRect().side)
    }

    @Test
    fun zoomingOutReclampsAnEdgePinnedCenter() {
        val controller = PhotoSquareCropController(imageWidth = 1600, imageHeight = 900)

        // Zoom in and pin against the left edge.
        controller.applyGesture(panX = 0f, panY = 0f, zoomFactor = 3f, viewportSide = 900f)
        controller.applyGesture(panX = 50_000f, panY = 0f, zoomFactor = 1f, viewportSide = 900f)
        assertEquals(0, controller.cropRect().left)

        // Zooming back out grows the square; the center must be pushed inward so the
        // rect still fits.
        controller.applyGesture(panX = 0f, panY = 0f, zoomFactor = 1f / 3f, viewportSide = 900f)
        val rect = controller.cropRect()
        assertEquals(900, rect.side)
        assertTrue(rect.left >= 0)
        assertTrue(rect.left + rect.side <= 1600)
        assertTrue(rect.top >= 0)
        assertTrue(rect.top + rect.side <= 900)
    }

    @Test
    fun cropRectAlwaysFitsInsideTheImage() {
        val controller = PhotoSquareCropController(imageWidth = 1013, imageHeight = 771)
        val gestures =
            listOf(
                Triple(123f, -872f, 1.7f),
                Triple(-4_000f, 9_000f, 0.4f),
                Triple(31f, 17f, 2.9f),
                Triple(-1f, -1f, 0.2f),
            )

        for ((panX, panY, zoomFactor) in gestures) {
            controller.applyGesture(panX = panX, panY = panY, zoomFactor = zoomFactor, viewportSide = 640f)
            val rect = controller.cropRect()
            assertTrue(rect.side >= 1)
            assertTrue(rect.left >= 0)
            assertTrue(rect.top >= 0)
            assertTrue(rect.left + rect.side <= 1013, "rect $rect exceeds width")
            assertTrue(rect.top + rect.side <= 771, "rect $rect exceeds height")
        }
    }
}
