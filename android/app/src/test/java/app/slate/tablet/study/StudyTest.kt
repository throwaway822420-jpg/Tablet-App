package app.slate.tablet.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.cos
import kotlin.math.sin

class StudyTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun circle(cx: Float, cy: Float, r: Float, sweep: Double = 2 * Math.PI, n: Int = 40) =
        FloatArray(n * 2) { i -> val a = sweep * (i / 2) / (n - 1); if (i % 2 == 0) (cx + r * cos(a)).toFloat() else (cy + r * sin(a)).toFloat() }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 40) =
        FloatArray(n * 2) { i -> val t = (i / 2) / (n - 1f); if (i % 2 == 0) x0 + (x1 - x0) * t else y0 + (y1 - y0) * t }

    @Test fun detectsCirclesNotLinesOrScribbles() {
        assertTrue(Loops.isLoop(circle(500f, 400f, 120f)))
        assertTrue(Loops.isLoop(circle(500f, 400f, 120f, sweep = 1.85 * Math.PI))) // sloppy, not quite closed
        assertFalse(Loops.isLoop(line(0f, 0f, 600f, 10f))) // underline
        assertFalse(Loops.isLoop(circle(500f, 400f, 10f))) // a dot
        assertFalse(Loops.isLoop(circle(500f, 400f, 120f, sweep = Math.PI))) // an arc
    }

    @Test fun cropsAroundCirclesWithMargin() {
        val c = Loops.cropRect(listOf(circle(1000f, 600f, 150f), line(0f, 0f, 900f, 0f)), 2880, 1800)!!
        assertEquals(1000f - 150f * 1.3f - 0.5f, c[0], 2f)
        assertTrue(c[2] > 1150f && c[3] > 750f)
        assertNull(Loops.cropRect(listOf(line(0f, 0f, 900f, 0f)), 2880, 1800)) // nothing circled
        assertNull(Loops.cropRect(listOf(circle(1440f, 900f, 880f)), 2880, 1800)) // circled most of the screen
    }

    @Test fun storeKeepsSessionsAndEntries() {
        val s = StudyStore(tmp.root)
        val a = s.newSession("Calculus", "pc")
        s.addEntry(a.id, StudyStore.Entry("q1", 1, "hint", "q1.jpg", "", "pending"))
        s.updateSession(a.id) { it.copy(remote = "uuid-1") }
        assertEquals(a.id, s.updateEntry("q1") { it.copy(markdown = "Try $\\frac{d}{dx}$", state = "done") })
        assertNull(s.updateEntry("nope") { it })

        val again = StudyStore(tmp.root) // reloads from disk
        assertEquals("uuid-1", again.session(a.id)!!.remote)
        assertEquals("done", again.entries(a.id).single().state)
        val md = again.exportMarkdown(a.id)
        assertTrue(md.startsWith("# Calculus"))
        assertTrue(md.contains("## Just a hint"))
        assertTrue(md.contains("![](q1.jpg)"))
        again.deleteSession(a.id)
        assertTrue(again.sessions().isEmpty())
        assertNotNull(StudyStore.INTENT_LABELS["quiz"])
    }

    @Test fun storeKeepsTypedMessages() {
        val s = StudyStore(tmp.root)
        val a = s.newSession("Chat", "tablet")
        s.addEntry(a.id, StudyStore.Entry("m1", 1, "chat", "", "Because the slope falls.", "done", text = "Why is it negative?"))
        val e = StudyStore(tmp.root).entries(a.id).single()
        assertEquals("Why is it negative?", e.text)
        assertEquals("", e.image)
        val md = s.exportMarkdown(a.id)
        assertTrue(md.contains("## Message"))
        assertTrue(md.contains("> Why is it negative?"))
        assertTrue(!md.contains("![]("))
    }
}
