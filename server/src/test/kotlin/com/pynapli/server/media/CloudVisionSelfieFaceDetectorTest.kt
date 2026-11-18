package com.pynapli.server.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The face-count read of a Vision FACE_DETECTION response (the HTTP call needs a live endpoint). */
class CloudVisionSelfieFaceDetectorTest {
    @Test
    fun noFaceAnnotationsIsZeroFaces() {
        assertEquals(0, parseVisionFaceCount("""{"responses":[{}]}"""))
    }

    @Test
    fun countsEachFaceAnnotation() {
        assertEquals(1, parseVisionFaceCount("""{"responses":[{"faceAnnotations":[{"detectionConfidence":0.98}]}]}"""))
        assertEquals(
            2,
            parseVisionFaceCount("""{"responses":[{"faceAnnotations":[{"detectionConfidence":0.9},{"detectionConfidence":0.8}]}]}"""),
        )
    }

    @Test
    fun aPerImageErrorOrMissingResponseCannotBeTrusted() {
        assertNull(parseVisionFaceCount("""{"responses":[{"error":{"code":7,"message":"denied"}}]}"""))
        assertNull(parseVisionFaceCount("""{"responses":[]}"""))
    }
}