package com.leko.errortest

import com.leko.binarytest.header
import com.leko.forge.*
import com.leko.parsertest.LeftAssoc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

object CaretErrors : ErrorFormatter {
    override fun format(error: TextParseError) =
        "error: expected ${describeExpected(error.expected)}\n" +
        "  ${error.lineText}\n" +
        "  ${" ".repeat(error.column - 1)}^"
}

object OneOfWording : ErrorFormatter {
    override fun describeExpected(expected: Set<String>) =
        if (expected.size == 1) expected.single() else "one of " + expected.sorted().joinToString(", ")
}

class ErrorFormatterTest {

    @Test
    fun defaultIsUsedWhenNoFormatterIsGiven() {
        val e = assertFailsWith<ParseException> { LeftAssoc.expr.parse("1 2") }
        assertEquals("1:3: expected '+' or end of input, found '2'", e.message)
        assertIs<TextParseError>(e.error)
    }

    @Test
    fun customTextFormatterWithCaret() {
        val e = assertFailsWith<ParseException> { LeftAssoc.expr.parse("1 +\n2 3", errors = CaretErrors) }
        assertEquals("error: expected '+' or end of input\n  2 3\n    ^", e.message)
        assertEquals(2, e.line)
        assertEquals(3, e.column)
    }

    @Test
    fun overridingOnlyTheWordingKeepsDefaultLayout() {
        val e = assertFailsWith<ParseException> { LeftAssoc.expr.parse("1 2", errors = OneOfWording) }
        assertEquals("1:3: expected one of '+', end of input, found '2'", e.message)

        // CaretErrors only overrides text errors; binary errors keep the default format
        val b = assertFailsWith<ParseException> { header.parse(byteArrayOf(0b0100_0000), errors = CaretErrors) }
        assertEquals("byte 0: expected 0b101, found 0x40", b.message)
        assertEquals(0, (b.error as BinaryParseError).byte)
    }
}
