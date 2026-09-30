package app.slate.tablet.input

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AreaLayoutTest {
    private fun check(expected: FloatArray, actual: FloatArray) = assertArrayEquals(expected, actual, 0.01f)

    @Test fun fullScreenWhenNoAspect() = check(floatArrayOf(0f, 0f, 2560f, 1600f), AreaLayout.bounds(2560f, 1600f, 0f, 1f))

    @Test fun letterboxesToPcAspect() =
        // 16:9 PC on a 16:10 tablet: full width, bands top and bottom.
        check(floatArrayOf(0f, 80f, 2560f, 1520f), AreaLayout.bounds(2560f, 1600f, 16f / 9f, 1f))

    @Test fun halfScaleIsCentredAndKeepsAspect() =
        check(floatArrayOf(640f, 440f, 1920f, 1160f), AreaLayout.bounds(2560f, 1600f, 16f / 9f, 0.5f))

    @Test fun scaleIsClampedToQuarter() =
        check(AreaLayout.bounds(2560f, 1600f, 0f, 0.25f), AreaLayout.bounds(2560f, 1600f, 0f, 0.01f))

    @Test fun emptyViewGivesEmptyArea() = check(floatArrayOf(0f, 0f, 0f, 0f), AreaLayout.bounds(0f, 1600f, 1f, 1f))
}
