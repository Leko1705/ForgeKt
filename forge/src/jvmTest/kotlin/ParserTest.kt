package com.leko.parsertest

import com.leko.forge.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

// The sample.forge language from the old Forge project
enum class Type { INT, STR }

data class Root(val decls: List<Function>)
data class Function(val receiver: Type?, val name: String, val params: List<Parameter>, val returnType: Type, val body: Expr)
data class Parameter(val name: String, val type: Type)
sealed interface Expr
data class IntLit(val value: Int) : Expr
data class StrLit(val value: String) : Expr
data class Plus(val lhs: Expr, val rhs: Expr) : Expr
data class This(val id: Unit = Unit) : Expr

object SampleLang {
    val ident by token("[A-Za-z_]\\w*")
    val number by token("\\d+")
    val string by token("\"[^\"]*\"")

    val program by rule { Root(many(function)) }
    val function by rule {
        +"fun"
        val recv = optional { type().also { +"." } }
        val name = ident()
        +"("; val params = sepBy(parameter, ","); +")"
        +":"; val ret = type()
        +"="; val body = expr()
        Function(recv, name, params, ret, body)
    }
    val parameter by rule { val name = ident(); +":"; Parameter(name, type()) }
    val type by rule { oneOf({ +"int"; Type.INT }, { +"str"; Type.STR }) }

    val expr: Rule<Expr> by rule { oneOf(plus, int, str, self) }
    val plus: Rule<Plus> by rule { val l = expr(); +"+"; Plus(l, expr()) }
    val int by rule { IntLit(number().toInt()) }
    val str by rule { StrLit(string().removeSurrounding("\"")) }
    val self by rule { +"this"; This() }
}

object LeftAssoc {
    val number by token("\\d+")
    val expr: Rule<Expr> by rule { oneOf(plus, atom) }
    val plus: Rule<Plus> by rule { val l = expr(); +"+"; Plus(l, atom()) }
    val atom: Rule<Expr> by rule { oneOf({ IntLit(number().toInt()) }, { +"("; expr().also { +")" } }) }
}

val receiver: Type? by inh<Type?>().on<Function> { receiver }
val This.recv: String by syn(cached = false) { receiver.toString() }

class ParserTest {

    @Test
    fun parsesSampleLanguage() {
        val root = SampleLang.program.parse(
            """
            fun int.add(a: int, b: str): int = this + 1
            fun hello(): str = "hi"
            """
        )
        assertEquals(
            Root(listOf(
                Function(Type.INT, "add", listOf(Parameter("a", Type.INT), Parameter("b", Type.STR)), Type.INT, Plus(This(), IntLit(1))),
                Function(null, "hello", emptyList(), Type.STR, StrLit("hi")),
            )),
            root
        )
    }

    @Test
    fun parsedTreeIsRegisteredForInheritedAttributes() {
        val root = SampleLang.program.parse("fun str.f(): int = this")
        val self = root.decls[0].body as This
        assertSame(root.decls[0], parentOf(self))
        assertEquals("STR", self.recv)
    }

    @Test
    fun leftRecursionIsLeftAssociative() {
        assertEquals(Plus(Plus(IntLit(1), IntLit(2)), IntLit(3)), LeftAssoc.expr.parse("1 + 2 + 3"))
        assertEquals(Plus(IntLit(1), Plus(IntLit(2), IntLit(3))), LeftAssoc.expr.parse("1 + (2 + 3)"))
    }

    @Test
    fun keywordsNeedWordBoundary() {
        assertFailsWith<ParseException> { SampleLang.expr.parse("thisx") }
    }

    @Test
    fun reportsFurthestErrorWithPosition() {
        val e = assertFailsWith<ParseException> { SampleLang.program.parse("fun f(a: int): int =\n  1 +") }
        assertEquals(2, e.line)
        assertEquals(6, e.column)
        assertEquals(setOf("number", "string", "'this'"), e.expected)

        val e2 = assertFailsWith<ParseException> { LeftAssoc.expr.parse("1 2") }
        assertEquals("1:3: expected '+' or end of input, found '2'", e2.message)
    }
}
