package app.slate.tablet.mirror

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTest {
    // A 2560×1600 tablet showing a 1920×1080 PC screen.
    private fun vp() = Viewport().apply { setView(2560f, 1600f); setFrame(1920f, 1080f) }
    private fun near(a: Float, b: Float) = assertEquals(a, b, 0.5f)

    @Test fun fitsWithLetterbox() {
        assertArrayEquals(floatArrayOf(0f, 80f, 2560f, 1520f), vp().displayed(), 0.5f)
    }

    @Test fun shrinkCentres() {
        val v = vp().apply { reset(0.5f) }
        assertArrayEquals(floatArrayOf(640f, 440f, 1920f, 1160f), v.displayed(), 0.5f)
        assertFalse(v.isZoomedIn)
    }

    @Test fun pinchKeepsThePointUnderTheFingers() {
        val v = vp()
        val before = v.viewToFrame(2000f, 400f)
        v.zoomAt(3f, 2000f, 400f)
        val after = v.viewToFrame(2000f, 400f)
        near(before.first * 1000, after.first * 1000)
        near(before.second * 1000, after.second * 1000)
        assertTrue(v.isZoomedIn)
    }

    @Test fun panningStopsAtTheEdges() {
        val v = vp().apply { zoomAt(2f, 1280f, 800f) }
        v.panBy(100000f, 100000f) // far past the top-left corner
        val d = v.displayed()
        near(0f, d[0]); near(0f, d[1]) // top-left of the PC screen sits at the tablet's corner
    }

    @Test fun mapsBothWays() {
        val v = vp().apply { zoomAt(2.5f, 900f, 700f) }
        val (vx, vy) = v.frameToView(0.3f, 0.7f)
        val (nx, ny) = v.viewToFrame(vx, vy)
        assertEquals(0.3f, nx, 1e-4f); assertEquals(0.7f, ny, 1e-4f)
    }

    @Test fun followKeepsTheCursorInView() {
        val v = vp().apply { zoomAt(4f, 0f, 80f) } // zoomed into the top-left
        assertTrue(v.follow(0.9f, 0.9f)) // cursor far bottom-right
        val (x, y) = v.frameToView(0.9f, 0.9f)
        assertTrue(x in 0f..2560f && y in 0f..1600f)
        assertFalse(v.follow(0.9f, 0.9f)) // already visible: no movement
        assertFalse(vp().follow(0.1f, 0.1f)) // not zoomed: never pans
    }

    @Test fun scaleIsLimited() {
        val v = vp().apply { zoomAt(1000f, 0f, 0f) }
        assertEquals(Viewport.MAX_SCALE, v.scale, 0f)
        v.zoomAt(0.00001f, 0f, 0f)
        assertEquals(Viewport.MIN_SCALE, v.scale, 0f)
    }
}

class ShortcutsTest {
    @Test fun parsesLabelEqualsCombo() {
        val list = app.slate.tablet.ui.Prefs.parseShortcuts("Undo=Ctrl+Z\n\nbad line\n  Save = Ctrl+S \n=x\nx=")
        assertEquals(listOf("Undo" to "Ctrl+Z", "Save" to "Ctrl+S"), list)
        assertEquals(10, app.slate.tablet.ui.Prefs.parseShortcuts(app.slate.tablet.ui.Prefs.DEFAULT_SHORTCUTS).size)
    }
}
