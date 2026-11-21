package com.leko.forge.python

import com.leko.forge.spanOf
import com.leko.forge.syn

/*
 * Python generation: the main target. Every feature implements `py` for its nodes; extensions lower
 * their constructs to standard Python. The generated code keeps the line numbers of the source
 * (see [pyBlock]), so tracebacks point to the source lines.
 */

/** The Python code of an expression. Every feature implements it for its expressions. */
val PyExpr.py: String by syn()

/** The Python code of a statement (one or more lines, without indentation). Every feature implements it. */
val PyStmt.py: String by syn()

/**
 * How strongly an expression binds, to put parentheses only where they are needed (see [pyOperand]).
 * Atoms (names, literals, calls, displays, and everything printed with its own parentheses) are
 * [PyPrecedence.ATOM], the default.
 */
val PyExpr.pyPrecedence: Int by syn { PyPrecedence.ATOM }

/** Python's precedence levels, from the weakest. */
object PyPrecedence {
    const val LAMBDA: Int = 1
    const val IF: Int = 2
    const val OR: Int = 3
    const val AND: Int = 4
    const val NOT: Int = 5
    const val COMPARE: Int = 6
    const val BIT_OR: Int = 7
    const val BIT_XOR: Int = 8
    const val BIT_AND: Int = 9
    const val SHIFT: Int = 10
    const val SUM: Int = 11
    const val PRODUCT: Int = 12
    const val UNARY: Int = 13
    const val POWER: Int = 14
    const val AWAIT: Int = 15
    const val ATOM: Int = 16
}

/** The code of [e], in parentheses if it binds weaker than [min]. */
fun pyOperand(e: PyExpr, min: Int): String = if (e.pyPrecedence < min) "(${e.py})" else e.py

/** The code of an expression where any expression is allowed except an unparenthesized lambda or conditional. */
fun pyItem(e: PyExpr): String = pyOperand(e, PyPrecedence.IF)

/**
 * The code of an expression where a tuple needs no parentheses (assignments, `return`, `for`
 * targets): `a, b` instead of `(a, b)`.
 */
fun pyTuple(e: PyExpr): String =
    if (e is TupleExpr && e.elements.isNotEmpty()) e.elements.joinToString(", ") { pyItem(it) } + (if (e.elements.size == 1) "," else "") else e.py

/** Indents every line of [code] by four spaces. */
fun pyIndent(code: String): String = code.lines().joinToString("\n") { if (it.isEmpty()) it else "    $it" }

/** Marks the first line of a statement with the source line it comes from (resolved by [alignLines]). */
private const val LINE_MARK = '\u0001'

/**
 * The code of a block of statements (`pass` if it is empty), not indented. Statements are marked with
 * their source lines: [alignLines] moves them down to these lines.
 */
fun pyBlock(stmts: List<PyStmt>): String {
    if (stmts.isEmpty()) return "pass"
    return stmts.joinToString("\n") { s ->
        val line = sourceLine(s)
        if (line == null) s.py else "$LINE_MARK$line$LINE_MARK${s.py}"
    }
}

/** An indented block: [pyBlock] indented by one level. */
fun pySuite(stmts: List<PyStmt>): String = pyIndent(pyBlock(stmts))

private fun sourceLine(s: PyStmt): Int? {
    val span = spanOf(s) ?: return null
    return s.module.layout.position(span.start).first
}

/**
 * Resolves the line marks of [pyBlock]: blank lines are inserted until every marked statement is on
 * its source line (or later, if the generated code is longer than the source).
 */
internal fun alignLines(code: String): String {
    val out = StringBuilder()
    var line = 1
    for (l in code.lines()) {
        val i = l.indexOf(LINE_MARK)
        var text = l
        if (i >= 0) {
            val end = l.indexOf(LINE_MARK, i + 1)
            val target = l.substring(i + 1, end).toInt()
            text = l.substring(0, i) + l.substring(end + 1)
            while (line < target) { out.append('\n'); line++ }
            // a statement can contain further marks (e.g. `if x: y` lowered to one line): drop them
            while (LINE_MARK in text) { val a = text.indexOf(LINE_MARK); text = text.substring(0, a) + text.substring(text.indexOf(LINE_MARK, a + 1) + 1) }
        }
        out.append(text).append('\n')
        line++
    }
    return out.toString()
}

private var pyTemps = 0

/** A fresh Python name for generated code (it can't clash with user names). */
fun pyTemp(prefix: String = "t"): String = "_forge_${prefix}${pyTemps++}"

/** A Python string literal. */
fun pyString(s: String, prefix: String = ""): String {
    val quote = if ('\'' in s && '"' !in s) '"' else '\''
    return prefix + quote + pyEscape(s, quote, bytes = false) + quote
}

/** A Python bytes literal of the characters 0 to 255 of [s]. */
fun pyBytes(s: String): String {
    val quote = if ('\'' in s && '"' !in s) '"' else '\''
    return "b" + quote + pyEscape(s, quote, bytes = true) + quote
}

/** Escapes [s] for a literal delimited by [quote]. */
fun pyEscape(s: String, quote: Char, bytes: Boolean): String = buildString {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == quote -> append('\\').append(c)
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c.code < 0x20 || c.code == 0x7f || (bytes && c.code > 0x7e) -> append("\\x").append(c.code.toString(16).padStart(2, '0'))
            c.code in 0x80..0x9f || c == ' ' || c == ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> { append(c).append(s[i + 1]); i++ }
            c.isSurrogate() -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
        i++
    }
}

/** The Python program of a module: support code of the enabled features, then the statements. */
internal fun pythonModule(m: PyModule, features: Collection<PythonFeature>): String {
    val support = features.mapNotNull { it.pythonSupportCode }.distinct()
    if (support.isEmpty()) return alignLines(pyBlock(m.body).let { if (m.body.isEmpty()) "" else it })
    // a docstring and `from __future__` imports must stay first
    val leading = m.body.takeWhile { (it is ExprStmt && it.value is Constant && (it.value as Constant).kind == ConstantKind.STR) || (it is ImportFrom && it.moduleName == "__future__") }
    val rest = m.body.drop(leading.size)
    val head = if (leading.isEmpty()) "" else pyBlock(leading) + "\n"
    return alignLines(head + support.joinToString("\n") + "\n" + (if (rest.isEmpty()) "" else pyBlock(rest)))
}
