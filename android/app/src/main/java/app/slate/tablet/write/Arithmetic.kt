package app.slate.tablet.write

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.PI
import kotlin.math.E
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A small exact-as-possible evaluator for plain arithmetic (numbers, + − × ÷ ^, brackets, √, π, e,
 * %), used to double-check Claude's arithmetic. Returns null for anything else.
 */
object Arithmetic {
    fun evaluate(expression: String): Double? = try {
        Parser(normalize(expression)).parse()?.takeIf { it.isFinite() }
    } catch (e: ArithmeticException) {
        null
    }

    /** Formats like a calculator: up to 12 significant digits, no trailing zeros or exponent for normal sizes. */
    fun format(value: Double): String {
        if (value == 0.0) return "0"
        val bd = BigDecimal(value).round(MathContext(12)).stripTrailingZeros()
        val plain = if (abs(value) >= 1e-6 && abs(value) < 1e15) bd.toPlainString() else bd.toString()
        return plain.replace('-', '−')
    }

    /** True when two results agree to 10 significant digits. */
    fun agrees(a: Double, b: Double): Boolean = a == b || abs(a - b) <= 1e-10 * maxOf(abs(a), abs(b))

    private fun normalize(s: String) = s
        .replace('×', '*').replace('·', '*').replace('÷', '/').replace('−', '-').replace('–', '-')
        .replace("²", "^2").replace("³", "^3").replace(",", "").replace(" ", "")

    private class Parser(val s: String) {
        var i = 0

        fun parse(): Double? {
            if (s.isEmpty()) return null
            val v = expr() ?: return null
            return if (i == s.length) v else null
        }

        fun expr(): Double? {
            var v = term() ?: return null
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = term() ?: return null
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        fun term(): Double? {
            var v = unary() ?: return null
            while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '(' || s[i] == '√' || s[i] == 'π')) {
                val op = if (s[i] == '*' || s[i] == '/') s[i++] else '*' // implicit multiplication: 2(3), 2π, 2√3
                val r = unary() ?: return null
                if (op == '/' && r == 0.0) throw ArithmeticException("division by zero")
                v = if (op == '*') v * r else v / r
            }
            return v
        }

        fun unary(): Double? {
            if (i < s.length && s[i] == '-') { i++; return unary()?.let { -it } }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            return power()
        }

        fun power(): Double? {
            val base = postfix() ?: return null
            if (i < s.length && s[i] == '^') {
                i++
                val exp = unary() ?: return null // right-associative
                return base.pow(exp)
            }
            return base
        }

        fun postfix(): Double? {
            var v = atom() ?: return null
            while (i < s.length && s[i] == '%') { i++; v /= 100 }
            return v
        }

        fun atom(): Double? {
            if (i >= s.length) return null
            val c = s[i]
            return when {
                c == '(' -> { i++; val v = expr(); if (i < s.length && s[i] == ')') { i++; v } else null }
                c == '√' -> { i++; unary()?.let { if (it < 0) null else sqrt(it) } }
                s.startsWith("sqrt(", i) -> { i += 4; atom()?.let { if (it < 0) null else sqrt(it) } }
                c == 'π' -> { i++; PI }
                s.startsWith("pi", i) -> { i += 2; PI }
                c == 'e' && (i + 1 >= s.length || !s[i + 1].isLetter()) -> { i++; E }
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    s.substring(start, i).toDoubleOrNull()
                }
                else -> null
            }
        }
    }
}
