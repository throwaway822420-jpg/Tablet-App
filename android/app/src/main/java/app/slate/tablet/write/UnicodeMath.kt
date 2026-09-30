package app.slate.tablet.write

/**
 * Converts ASCII/LaTeX maths that slips into a transcription (x^2, a_1, <=, \frac{a}{b}, \pi …)
 * into Unicode, so the result doesn't depend on the model following its instructions exactly.
 * Only unambiguous notation is converted; ordinary words are never touched.
 */
object UnicodeMath {
    private val SUPER = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '−' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ', 'f' to 'ᶠ', 'g' to 'ᵍ', 'h' to 'ʰ', 'i' to 'ⁱ',
        'j' to 'ʲ', 'k' to 'ᵏ', 'l' to 'ˡ', 'm' to 'ᵐ', 'n' to 'ⁿ', 'o' to 'ᵒ', 'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ',
        't' to 'ᵗ', 'u' to 'ᵘ', 'v' to 'ᵛ', 'w' to 'ʷ', 'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ',
    )

    private val SUB = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '−' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ', 'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ',
        'o' to 'ₒ', 'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ', 'v' to 'ᵥ', 'x' to 'ₓ',
    )

    private val LATEX_SYMBOLS = linkedMapOf(
        "\\leq" to "≤", "\\geq" to "≥", "\\le" to "≤", "\\ge" to "≥", "\\neq" to "≠", "\\ne" to "≠",
        "\\approx" to "≈", "\\pm" to "±", "\\mp" to "∓", "\\times" to "×", "\\div" to "÷", "\\cdot" to "·",
        "\\infty" to "∞", "\\int" to "∫", "\\oint" to "∮", "\\sum" to "∑", "\\prod" to "∏", "\\partial" to "∂",
        "\\nabla" to "∇", "\\to" to "→", "\\rightarrow" to "→", "\\leftarrow" to "←", "\\Rightarrow" to "⇒",
        "\\iff" to "⇔", "\\in" to "∈", "\\notin" to "∉", "\\subset" to "⊂", "\\cup" to "∪", "\\cap" to "∩",
        "\\forall" to "∀", "\\exists" to "∃", "\\degree" to "°", "\\circ" to "°",
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ", "\\epsilon" to "ε", "\\varepsilon" to "ε",
        "\\zeta" to "ζ", "\\eta" to "η", "\\theta" to "θ", "\\iota" to "ι", "\\kappa" to "κ", "\\lambda" to "λ",
        "\\mu" to "μ", "\\nu" to "ν", "\\xi" to "ξ", "\\pi" to "π", "\\rho" to "ρ", "\\sigma" to "σ", "\\tau" to "τ",
        "\\phi" to "φ", "\\varphi" to "φ", "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω",
        "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ", "\\Pi" to "Π", "\\Sigma" to "Σ",
        "\\Phi" to "Φ", "\\Psi" to "Ψ", "\\Omega" to "Ω",
    )

    private val ASCII_OPERATORS = listOf("<=" to "≤", ">=" to "≥", "!=" to "≠", "+-" to "±", "->" to "→", "=>" to "⇒")

    // \command followed by a non-letter (so \in doesn't eat \infty, handled by longest-first order too).
    private val LATEX_COMMAND = Regex("""\\([A-Za-z]+)(?![A-Za-z])""")
    private val FRAC = Regex("""\\[dt]?frac\s*\{([^{}]*)\}\s*\{([^{}]*)\}""")
    private val SQRT = Regex("""\\sqrt\s*\{([^{}]*)\}""")
    private val SQRT_WORD = Regex("""\bsqrt\s*\(""")
    private val MATH_DELIMS = Regex("""\\[()\[\]]|\$\$?""")
    private val TEXT_CMD = Regex("""\\(?:text|mathrm|mathit|mathbf|operatorname)\s*\{([^{}]*)\}""")

    // ^ or _ after a symbol, followed by {group}, (group), sign+digits, or a single letter that
    // isn't the start of a word (so snake_case words like file_name are left alone).
    private val SCRIPT = Regex("""(?<=[A-Za-z0-9)\]}'′α-ωΑ-Ω∫∮∑∏])([\^_])(?:\{([^{}]*)\}|\(([^()]*)\)|([+\-−]?\d+|[A-Za-z](?![A-Za-z])))""")

    fun convert(input: String): String {
        var s = input
        s = MATH_DELIMS.replace(s, "")
        s = TEXT_CMD.replace(s) { it.groupValues[1] }
        s = FRAC.replace(s) { m -> "${wrap(m.groupValues[1])}/${wrap(m.groupValues[2])}" }
        s = SQRT.replace(s) { m -> "√" + wrap(m.groupValues[1]) }
        s = SQRT_WORD.replace(s, "√(")
        s = LATEX_COMMAND.replace(s) { m -> LATEX_SYMBOLS["\\" + m.groupValues[1]] ?: m.value }
        for ((ascii, uni) in ASCII_OPERATORS) s = s.replace(ascii, uni)
        s = SCRIPT.replace(s) { m -> script(m) }
        s = unwrapSimpleRoots(s)
        return s
    }

    private fun script(m: MatchResult): String {
        val table = if (m.groupValues[1] == "^") SUPER else SUB
        val body = m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] }.replace(" ", "")
        if (body.isEmpty() || body.any { it !in table }) return m.value // no Unicode form: leave it readable as is
        return body.map { table.getValue(it) }.joinToString("")
    }

    /** Brackets a fraction part or root argument only when it has more than one term. */
    private fun wrap(part: String): String {
        val t = part.trim()
        return if (t.length <= 1 || t.all { it.isLetterOrDigit() || it == '.' }) t else "($t)"
    }

    /** √(2) → √2 and √(x) → √x; √(x+1) and √(xy) keep their brackets. */
    private fun unwrapSimpleRoots(s: String): String =
        Regex("""√\(([0-9.]+|[A-Za-z])\)""").replace(s) { "√" + it.groupValues[1] }
}
