package com.leko.forge.python

/**
 * Python's layout as explicit markers, so the grammar can be a normal (scannerless) Forge grammar:
 * every logical line ends with [NEWLINE], indentation changes become [INDENT] and [DEDENT].
 * Comments, blank lines and line breaks inside brackets or after a backslash disappear.
 *
 * [origin] maps every character of [text] back to its offset in the source, so error positions
 * refer to the source.
 */
class Layout(val text: String, val origin: IntArray, val source: String) {

    companion object {
        const val NEWLINE: Char = '\n'
        const val INDENT: Char = '\u0002'
        const val DEDENT: Char = '\u0003'

        fun of(source: String): Layout = Builder(source).build()
    }

    /** `line:column` (1-based) in the source of an offset in [text]. */
    fun position(offset: Int): Pair<Int, Int> {
        val o = if (offset < origin.size) origin[offset] else source.length
        val line = source.substring(0, o.coerceAtMost(source.length)).count { it == '\n' } + 1
        val column = o - (source.lastIndexOf('\n', o - 1) + 1) + 1
        return line to column
    }

    private class Builder(val source: String) {
        val out = StringBuilder()
        val origin = ArrayList<Int>()
        val indents = ArrayDeque(listOf(0))
        var i = 0
        var depth = 0 // open brackets: line breaks inside are ignored
        val open = ArrayDeque<Int>()

        fun emit(c: Char, at: Int) { out.append(c); origin += at }

        fun build(): Layout {
            while (i < source.length) line()
            if (open.isNotEmpty()) throw LayoutException("'${source[open.last()]}' was never closed", open.last())
            if (out.isNotEmpty() && out.last() != NEWLINE) emit(NEWLINE, source.length)
            while (indents.size > 1) { indents.removeLast(); emit(DEDENT, source.length) }
            return Layout(out.toString(), origin.toIntArray(), source)
        }

        /** One logical line, starting at the beginning of a physical line. */
        fun line() {
            // indentation
            var width = 0
            val start = i
            while (i < source.length && (source[i] == ' ' || source[i] == '\t')) { width = if (source[i] == '\t') (width / 8 + 1) * 8 else width + 1; i++ }
            // blank and comment lines do not count
            if (i >= source.length || source[i] == '\n' || source[i] == '#' || source[i] == '\r') {
                while (i < source.length && source[i] != '\n') i++
                i++
                return
            }
            if (width > indents.last()) { indents.addLast(width); emit(INDENT, start) }
            else while (width < indents.last()) { indents.removeLast(); emit(DEDENT, i) }
            if (width != indents.last()) throw LayoutException("unindent does not match any outer indentation level", i)
            content()
        }

        /** The rest of a logical line, up to and including its end. */
        fun content() {
            while (i < source.length) {
                val c = source[i]
                when {
                    c == '#' -> { while (i < source.length && source[i] != '\n') i++ }
                    c == '\\' && i + 1 < source.length && source[i + 1] == '\n' -> { emit(' ', i); i += 2 }
                    c == '\n' || c == '\r' && source.getOrNull(i + 1) == '\n' -> {
                        if (c == '\r') i++
                        if (depth > 0) { emit(' ', i); i++ } else { emit(NEWLINE, i); i++; return }
                    }
                    c == '"' || c == '\'' -> string()
                    c == '(' || c == '[' || c == '{' -> { depth++; open.addLast(i); emit(c, i); i++ }
                    c == ')' || c == ']' || c == '}' -> { if (depth > 0) { depth--; open.removeLast() }; emit(c, i); i++ }
                    else -> { emit(c, i); i++ }
                }
            }
        }

        /** A string literal, copied as it is (also over several lines). */
        fun string() {
            val quote = source[i]
            val triple = source.startsWith("$quote$quote$quote", i)
            val delimiter = if (triple) "$quote$quote$quote" else "$quote"
            val start = i
            repeat(delimiter.length) { emit(source[i], i); i++ }
            while (i < source.length) {
                if (source.startsWith(delimiter, i)) { repeat(delimiter.length) { emit(source[i], i); i++ }; return }
                if (source[i] == '\\' && i + 1 < source.length) { emit(source[i], i); i++ }
                else if (source[i] == '\n' && !triple) throw LayoutException("unterminated string literal", start)
                emit(source[i], i); i++
            }
            throw LayoutException("unterminated string literal", start)
        }
    }
}

class LayoutException(message: String, val offset: Int) : RuntimeException(message)
