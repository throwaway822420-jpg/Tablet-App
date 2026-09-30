package app.slate.tablet.input

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

class PenCaptureTest {
    private fun rad(deg: Double) = (deg * PI / 180).toFloat()

    @Test fun uprightPenHasNoTilt() = assertEquals(0 to 0, PenCapture.tiltXY(0f, rad(73.0)))

    @Test fun penPointingUpLeansTowardUser() = assertEquals(0 to 30, PenCapture.tiltXY(rad(30.0), 0f))

    @Test fun penPointingRightLeansLeft() = assertEquals(-45 to 0, PenCapture.tiltXY(rad(45.0), rad(90.0)))

    @Test fun penPointingLeftLeansRight() = assertEquals(45 to 0, PenCapture.tiltXY(rad(45.0), rad(-90.0)))

    @Test fun diagonalSplitsAcrossBothAxes() {
        val (x, y) = PenCapture.tiltXY(rad(60.0), rad(-45.0))
        assertEquals(51, x) // atan(tan 60° · sin 45°)
        assertEquals(51, y)
    }

    @Test fun flatPenClampsTo90() = assertEquals(0 to 90, PenCapture.tiltXY(rad(90.0), 0f))

    @Test fun normalizesIntoActiveArea() {
        assertEquals(0f, PenCapture.normalize(40f, 100f, 800f), 0f) // in the letterbox band
        assertEquals(0.5f, PenCapture.normalize(500f, 100f, 800f), 0f)
        assertEquals(1f, PenCapture.normalize(2000f, 100f, 800f), 0f)
        assertEquals(0f, PenCapture.normalize(10f, 0f, 0f), 0f)
    }

    @Test fun contactAlwaysHasPressure() {
        assertEquals(1, PenCapture.pressureToWire(0f))
        assertEquals(512, PenCapture.pressureToWire(0.5f))
        assertEquals(1024, PenCapture.pressureToWire(1.3f))
        assertEquals(1, PenCapture.pressureToWire(Float.NaN))
    }
}
