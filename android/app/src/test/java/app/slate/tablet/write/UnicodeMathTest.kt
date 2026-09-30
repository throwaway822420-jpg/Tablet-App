package app.slate.tablet.write

import org.junit.Assert.assertEquals
import org.junit.Test

class UnicodeMathTest {
    private fun check(expected: String, input: String) = assertEquals(expected, UnicodeMath.convert(input))

    @Test fun superscripts() {
        check("x² + y² = r²", "x^2 + y^2 = r^2")
        check("e⁻ˣ", "e^(-x)")
        check("e⁻ˣ", "e^{-x}")
        check("xⁿ⁺¹", "x^(n+1)")
        check("10¹²", "10^12")
        check("(a+b)²", "(a+b)^2")
    }

    @Test fun subscripts() {
        check("a₁ + a₂", "a_1 + a_2")
        check("xₙ₊₁", "x_{n+1}")
    }

    @Test fun leavesWhatHasNoUnicodeFormReadable() {
        check("x^(q)", "x^(q)")
        check("a_(bc)", "a_(bc)")
    }

    @Test fun leavesProseAlone() {
        check("save file_name to my_folder", "save file_name to my_folder")
        check("The alpha version is pi-shaped", "The alpha version is pi-shaped")
        check("Hello, world!", "Hello, world!")
        check("x² ≤ π", "x² ≤ π") // already Unicode
    }

    @Test fun operatorsAndRoots() {
        check("x ≤ 3 and y ≥ 2, z ≠ 0, ±1, a → b", "x <= 3 and y >= 2, z != 0, +-1, a -> b")
        check("√2 + √(x+1)", "sqrt(2) + sqrt(x+1)")
    }

    @Test fun latex() {
        check("(x+1)/(x−1)", "\\frac{x+1}{x−1}")
        check("1/2", "\\frac{1}{2}")
        check("√(x²+1)", "\\sqrt{x^2+1}")
        check("∫₀¹ x² dx = 1/3", "\\int_0^1 x^2 dx = \\frac{1}{3}")
        check("θ ≈ π/4", "\$\\theta \\approx \\pi/4\$")
        check("∑ᵢ aᵢ ∈ ℝ", "\\sum_i a_i \\in ℝ")
    }
}
