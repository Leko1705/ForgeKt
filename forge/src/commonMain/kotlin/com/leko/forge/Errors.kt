package com.leko.forge

/** Everything known about a failed parse. The formatter decides how to present it. */
public sealed interface ParseError {
    /** Character index for text input and bit index for binary input. */
    public val offset: Int

    /** What the parser would have accepted at [offset]: literals as `'fun'`, rules and tokens by name. */
    public val expected: Set<String>

    /** Name of the input, e.g. the file name, or `null`. */
    public val source: String?
}

public class TextParseError internal constructor(
    override val offset: Int,
    override val expected: Set<String>,
    /** The parsed text. */
    public val input: String,
    override val source: String? = null,
) : ParseError {
    /** 1-based line of [offset]. */
    public val line: Int = input.substring(0, offset).count { it == '\n' } + 1

    /** 1-based column of [offset]. */
    public val column: Int = offset - (input.lastIndexOf('\n', offset - 1) + 1) + 1

    /** The character at [offset], or `null` at the end of input. */
    public val found: Char? get() = input.getOrNull(offset)

    /** The whole line containing the error, without its line break. Useful for caret displays. */
    public val lineText: String
        get() {
            val start = input.lastIndexOf('\n', offset - 1) + 1
            val end = input.indexOf('\n', offset).let { if (it < 0) input.length else it }
            return input.substring(start, end).removeSuffix("\r")
        }
}

public class BinaryParseError internal constructor(
    override val offset: Int,
    override val expected: Set<String>,
    /** The parsed bytes. */
    public val input: ByteArray,
    override val source: String? = null,
) : ParseError {
    /** Index of the byte containing [offset]. */
    public val byte: Int get() = offset / 8

    /** Bit within [byte] (0 is the most significant bit). */
    public val bit: Int get() = offset % 8

    /** The byte at [byte] as 0..255, or `null` at the end of input. */
    public val found: Int? get() = input.getOrNull(byte)?.toInt()?.and(0xFF)
}


/**
 * Turns parse errors into messages. Pass an implementation to `Rule.parse(..., errors = ...)`.
 *
 * Every method has a default, so override only what you want to change. For example,
 * override just [describeExpected] to reword the list of alternatives, or [format] for text
 * errors to show a caret under the source line:
 *
 * ```
 * object CaretErrors : ErrorFormatter {
 *     override fun format(error: TextParseError) =
 *         "error at ${error.line}:${error.column}: expected ${describeExpected(error.expected)}\n" +
 *         "  ${error.lineText}\n" +
 *         "  ${" ".repeat(error.column - 1)}^"
 * }
 * ```
 */
public interface ErrorFormatter {

    /** Message for errors in text input. Default: `2:6: expected number or 'this', found end of input`. */
    public fun format(error: TextParseError): String {
        val found = error.found?.let { "'$it'" } ?: "end of input"
        return "${prefix(error)}${error.line}:${error.column}: expected ${describeExpected(error.expected)}, found $found"
    }

    /** Message for errors in binary input. Default: `byte 3, bit 5: expected 0b101, found 0x4F`. */
    public fun format(error: BinaryParseError): String {
        val where = if (error.bit == 0) "byte ${error.byte}" else "byte ${error.byte}, bit ${error.bit}"
        val found = error.found?.let { "0x" + hexByte(it) } ?: "end of input"
        return "${prefix(error)}$where: expected ${describeExpected(error.expected)}, found $found"
    }

    /** Put before the position: `"example.tiny:"` if the input has a [ParseError.source], else nothing. */
    public fun prefix(error: ParseError): String = error.source?.let { "$it:" } ?: ""

    /** How the expected alternatives are listed. Default: `'+' or end of input`. */
    public fun describeExpected(expected: Set<String>): String = expected.joinToString(" or ")
}

/** The formatter used when no other is given. */
public object DefaultErrorFormatter : ErrorFormatter


public class ParseException(
    /** The structured error, for tools that want more than the message. */
    public val error: ParseError,
    message: String,
) : RuntimeException(message) {
    /** Character index for text input and bit index for binary input. */
    public val offset: Int get() = error.offset

    /** 1-based for text input and 0 for binary input. */
    public val line: Int get() = (error as? TextParseError)?.line ?: 0

    /** 1-based for text input and 0 for binary input. */
    public val column: Int get() = (error as? TextParseError)?.column ?: 0

    public val expected: Set<String> get() = error.expected
}


/** Two upper case hex digits, e.g. `0F`. */
internal fun hexByte(value: Int): String = (value and 0xFF).toString(16).uppercase().padStart(2, '0')
