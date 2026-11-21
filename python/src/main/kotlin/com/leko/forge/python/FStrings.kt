package com.leko.forge.python

import com.leko.forge.Child
import com.leko.forge.syn
import com.leko.forge.token
import com.leko.forge.python.PythonGrammar.expr

/** A part of an f-string: text, or `{expression!conversion:format}`. */
data class FormattedValue(@Child val value: PyExpr, val conversion: Char?, @Child val format: PyExpr?) : PyExpr
data class FString(@Child val parts: List<PyExpr>) : PyExpr

/**
 * f-strings: `f"{name!r:>10} = {value:.2f}"`. The literal is split into text and replacement fields;
 * the expressions are parsed with the expression grammar, so every expression feature works inside.
 */
object FStrings : PythonFeature("f-strings") {
    private val literal by token("(?i:f|rf|fr)(\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"(\\\\[\\s\\S]|[^\"\\\\\\n])*\"|'(\\\\[\\s\\S]|[^'\\\\\\n])*')")

    init {
        expr.on {
            val parts = some { oneOf({ fstring(literal()) }, { listOf(Constant(CoreExpressions.unescape(CoreExpressions.stringLiteral()), ConstantKind.STR)) }) }
            if (parts.size == 1 && parts[0].singleOrNull() is Constant) fail("an f-string")
            FString(parts.flatten())
        }
    }

    /** Splits an f-string literal into constant text and formatted values. */
    private fun fstring(literal: String): List<PyExpr> {
        val prefixEnd = literal.indexOfFirst { it == '"' || it == '\'' }
        val raw = literal.substring(0, prefixEnd).contains('r', ignoreCase = true)
        val q = if (literal.startsWith("\"\"\"", prefixEnd) || literal.startsWith("'''", prefixEnd)) 3 else 1
        val body = literal.substring(prefixEnd + q, literal.length - q)
        val parts = ArrayList<PyExpr>()
        val text = StringBuilder()
        fun flush() { if (text.isNotEmpty()) { parts += Constant(if (raw) text.toString() else CoreExpressions.unescape("'" + text.toString().replace("'", "\\'") + "'"), ConstantKind.STR); text.clear() } }
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                body.startsWith("{{", i) -> { text.append('{'); i += 2 }
                body.startsWith("}}", i) -> { text.append('}'); i += 2 }
                c == '{' -> {
                    flush()
                    // the expression ends at a top level '!', ':' or '}'
                    var depth = 0; var j = i + 1; var quote: Char? = null
                    while (j < body.length) {
                        val d = body[j]
                        if (quote != null) { if (d == quote) quote = null }
                        else if (d == '\'' || d == '"') quote = d
                        else if (d == '(' || d == '[' || d == '{') depth++
                        else if (d == ')' || d == ']' || d == '}') { if (depth == 0) break; depth-- }
                        else if (depth == 0 && (d == ':' || (d == '!' && body.getOrNull(j + 1) != '='))) break
                        j++
                    }
                    var source = body.substring(i + 1, j)
                    // `{x=}`: the text of the expression, then its repr (or its format)
                    val debug = Regex("""=\s*$""").find(source)?.takeIf { m -> source.getOrNull(m.range.first - 1).let { it == null || it !in "=!<>" } }
                    if (debug != null) { parts += Constant(source, ConstantKind.STR); source = source.substring(0, debug.range.first) }
                    val value = PythonCompiler.parseExpression(source)
                    var conversion: Char? = null
                    if (body.getOrNull(j) == '!') { conversion = body[j + 1]; j += 2 }
                    var format: PyExpr? = null
                    if (body.getOrNull(j) == ':') {
                        var k = j + 1; var nested = 0
                        while (k < body.length && (body[k] != '}' || nested > 0)) { if (body[k] == '{') nested++ else if (body[k] == '}') nested--; k++ }
                        val spec = body.substring(j + 1, k)
                        format = if ('{' in spec) FString(fstring("f'" + spec.replace("'", "\\'") + "'")) else Constant(spec, ConstantKind.STR)
                        j = k
                    }
                    if (debug != null && conversion == null && format == null) conversion = 'r'
                    parts += FormattedValue(value, conversion, format)
                    i = j + 1
                }
                else -> { text.append(c); i++ }
            }
        }
        flush()
        return parts
    }

    private val FString.js: String by syn { if (parts.isEmpty()) "\"\"" else "(" + parts.joinToString(" + ") { if (it is Constant) it.js else it.js } + ")" }
    private val FormattedValue.js: String by syn {
        val v = when (conversion) { 'r' -> "\$repr(${value.js})"; 's' -> "\$str(${value.js})"; 'a' -> "\$repr(${value.js})"; else -> value.js }
        "\$format($v, ${format?.js ?: "\"\""})"
    }

    // ---- Python ------------------------------------------------------------------------------

    private fun pyPart(p: PyExpr): String = when (p) {
        is Constant -> pyEscape(p.value as String, '"', bytes = false).replace("{", "{{").replace("}", "}}")
        is FormattedValue -> {
            val e = pyOperand(p.value, PyPrecedence.IF)
            val spec = when (val f = p.format) { null -> ""; is FString -> ":" + f.parts.joinToString("") { pyPart(it) }; else -> ":" + pyPart(f) }
            "{" + (if (e.startsWith("{")) " $e" else e) + (p.conversion?.let { "!$it" } ?: "") + spec + "}"
        }
        else -> error("unexpected f-string part $p")
    }

    private val FString.py: String by syn { "f\"" + parts.joinToString("") { pyPart(it) } + "\"" }
    private val FormattedValue.py: String by syn { "f\"${pyPart(this)}\"" }
}
