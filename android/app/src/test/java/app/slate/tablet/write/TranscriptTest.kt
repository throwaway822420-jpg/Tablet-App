package app.slate.tablet.write

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {
    private val MATHML = """<math display='inline'><mfrac><mi>dy</mi><mi>dx</mi></mfrac></math>"""
    private val reply = """{"segments":[
        {"type":"text","text":"Still ","latex":"","mathml":""},
        {"type":"math","text":"y = dy/dx","latex":"y = \\frac{dy}{dx}","mathml":"$MATHML"},
        {"type":"text","text":" ok","latex":"","mathml":""}]}"""

    @Test fun rendersEachMode() {
        val t = Transcript.fromJson(reply)
        assertEquals("Still y = dy/dx ok", t.plain(OutputMode.UNICODE))
        assertEquals("Still \$y = \\frac{dy}{dx}\$ ok", t.plain(OutputMode.LATEX))
        assertEquals("Still y = dy/dx ok", t.plain(OutputMode.EQUATION)) // shown as Unicode on the tablet
    }

    @Test fun equationWireWrapsMathmlWithUnicodeFallback() {
        val wire = Transcript.fromJson(reply).wire(OutputMode.EQUATION, trailingSpace = true)
        assertEquals("Still \uE000$MATHML\uE004y = dy/dx\uE001 ok ", wire)
    }

    @Test fun unicodeModeStillFixesAsciiMaths() {
        val t = Transcript(listOf(Transcript.Segment(true, "x^2 <= 4")))
        assertEquals("x² ≤ 4", t.plain(OutputMode.UNICODE))
        assertEquals("x² ≤ 4 ", t.wire(OutputMode.UNICODE, trailingSpace = true))
    }

    @Test fun oversizedEquationFallsBackToUnicode() {
        val huge = "<math>" + "<mi>x</mi>".repeat(600) + "</math>"
        val t = Transcript(listOf(Transcript.Segment(true, "x", "x", huge)))
        assertEquals("x", t.wire(OutputMode.EQUATION, trailingSpace = false))
    }

    @Test fun emptyAndClipboard() {
        assertTrue(Transcript.fromJson("""{"segments":[]}""").isEmpty)
        assertEquals("\uE0021/3\uE003<math/>", Transcript.clipboardWire("1/3", "<math/>"))
        assertEquals("\uE0021/3", Transcript.clipboardWire("1/3", null))
        assertEquals(OutputMode.EQUATION, OutputMode.UNICODE.next())
        assertEquals(OutputMode.UNICODE, OutputMode.LATEX.next())
    }
}

class CalculatorTest {
    private fun reply(result: String, arithmetic: String) = """{
        "expression":{"text":"expr","latex":"","mathml":""},
        "result":{"text":"$result","latex":"$result","mathml":"<math><mn>$result</mn></math>"},
        "arithmetic":"$arithmetic","steps":[]}"""

    @Test fun localArithmeticOverridesAWrongAnswer() {
        val c = Calculation.fromJson(reply("41", "6*7"))
        assertEquals("42", c.answer.plain(OutputMode.UNICODE))
        assertTrue(c.checked)
    }

    @Test fun agreeingAnswerIsKeptAndChecked() {
        val c = Calculation.fromJson(reply("2.5", "10/4"))
        assertEquals("2.5", c.answer.plain(OutputMode.UNICODE))
        assertTrue(c.checked)
        assertNull(c.approx)
    }

    @Test fun exactFormsGetADecimal() {
        assertEquals("0.333333333333", Calculation.fromJson(reply("1/3", "1/3")).approx)
        assertEquals("1.41421356237", Calculation.fromJson(reply("√2", "sqrt(2)")).approx)
    }

    @Test fun symbolicAnswersAreNotChecked() {
        val c = Calculation.fromJson(reply("x = 3", ""))
        assertFalse(c.checked)
        assertEquals("x = 3", c.answer.plain(OutputMode.UNICODE))
    }

    @Test fun arithmeticEvaluator() {
        assertEquals(42.0, Arithmetic.evaluate("6 × 7")!!, 0.0)
        assertEquals(-8.0, Arithmetic.evaluate("-2^3")!!, 0.0)
        assertEquals(512.0, Arithmetic.evaluate("2^3^2")!!, 0.0) // right-associative
        assertEquals(14.0, Arithmetic.evaluate("2(3+4)")!!, 0.0)
        assertEquals(0.5, Arithmetic.evaluate("50%")!!, 0.0)
        assertEquals(88.0, Arithmetic.evaluate("(3.5+2)*4^2")!!, 1e-12)
        assertEquals(2 * Math.PI, Arithmetic.evaluate("2π")!!, 1e-12)
        assertNull(Arithmetic.evaluate("1/0"))
        assertNull(Arithmetic.evaluate("x+1"))
        assertNull(Arithmetic.evaluate(""))
        assertEquals("−1234.5", Arithmetic.format(-1234.5))
        assertEquals("1E+20", Arithmetic.format(1e20))
    }
}
