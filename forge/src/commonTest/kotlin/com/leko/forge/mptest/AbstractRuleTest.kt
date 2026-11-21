package com.leko.forge.mptest

import com.leko.forge.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Expressions are parsed into fully parenthesized strings, so precedence is easy to see.
object Calc {
    val keywords = Keywords("if", "then", "else")
    val number by token("[0-9]+")
    val ident by token("[a-z]+", except = keywords)

    val expr: AbstractRule<String> by rule<String>()
        .on { number() }
        .on { ident() }
        .on { +"("; expr().also { +")" } }
        .prefix(30) { +"-"; "(-${operand<String>()})" }
        .infixLeft(10) { l -> +"+"; "($l + ${operand<String>()})" }
        .infixLeft(10) { l -> +"-"; "($l - ${operand<String>()})" }
        .infixLeft(20) { l -> +"*"; "($l * ${operand<String>()})" }
        .infixRight(40) { l -> +"^"; "($l ^ ${operand<String>()})" }
        .postfix(50) { e -> +"!"; "($e!)" }
        .postfix(60) { f -> +"("; val args = sepBy(expr, ","); +")"; "$f(${args.joinToString(", ")})" }
        .infixRight(2) { c -> +"?"; val a = expr(); +":"; "($c ? $a : ${operand<String>()})" }

    // a left recursive plain alternative
    val path: AbstractRule<String> by rule<String>()
        .on { val l = path(); +"."; "$l.${ident()}" }
        .on { ident() }
}

// A language extension: comparison operators and an if-expression, added from another module.
object Comparisons {
    init {
        Calc.keywords.add("not")
        Calc.expr
            .infixLeft(5) { l -> +"<="; "($l <= ${operand<String>()})" }
            .infixLeft(5) { l -> +"<"; "($l < ${operand<String>()})" }
            .prefix(3) { +"not"; "(not ${operand<String>()})" }
            .on { +"if"; val c = Calc.expr(); +"then"; val a = Calc.expr(); +"else"; "(if $c then $a else ${Calc.expr()})" }
    }
}

class AbstractRuleTest {

    private fun parse(s: String) = Calc.expr.parse(s)

    @Test
    fun precedenceAndAssociativity() {
        assertEquals("(1 + (2 * (3 ^ (4 ^ 5))))", parse("1 + 2 * 3 ^ 4 ^ 5"))
        assertEquals("((a - b) - c)", parse("a - b - c"))
        assertEquals("((a * b) + (c * d))", parse("a * b + c * d"))
        assertEquals("((1 + 2) * 3)", parse("(1 + 2) * 3"))
    }

    @Test
    fun prefixAndPostfix() {
        assertEquals("((-a) * b)", parse("-a * b"))
        assertEquals("(-(2 ^ 2))", parse("-2 ^ 2"))
        assertEquals("((-(n!)) + 1)", parse("-n! + 1"))
        assertEquals("((f(a, (b + 1))!) * 2)", parse("f(a, b + 1)! * 2"))
    }

    @Test
    fun mixfixConditionalIsRightAssociative() {
        assertEquals("(a ? b : (c ? d : e))", parse("a ? b : c ? d : e"))
        assertEquals("((a + 1) ? b : c)", parse("a + 1 ? b : c"))
    }

    @Test
    fun extensionAddsOperatorsAndAlternatives() {
        Comparisons // load the extension
        assertEquals("((a + 1) <= (b * 2))", parse("a + 1 <= b * 2"))
        assertEquals("(a < b)", parse("a < b"))
        assertEquals("(not (a < b))", parse("not a < b"))
        assertEquals("(if (a < b) then a else b)", parse("if a < b then a else b"))
    }

    @Test
    fun keywordsAreNotIdentifiers() {
        assertFailsWith<ParseException> { parse("then") }
        val e = assertFailsWith<ParseException> { parse("1 + else") }
        assertTrue("ident" in e.expected, e.message)
    }

    @Test
    fun operandOnlyInsideOperators() {
        val bad = rule<String>().on { operand<String>() }
        assertFailsWith<IllegalStateException> { bad.parse("x") }
        assertFailsWith<IllegalStateException> { rule<String>().parse("x") } // no alternatives
    }

    @Test
    fun leftRecursiveAlternativesStillWork() {
        assertEquals("a.b.c", Calc.path.parse("a.b.c"))
    }
}
