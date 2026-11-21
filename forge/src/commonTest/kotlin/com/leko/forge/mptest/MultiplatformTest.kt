package com.leko.forge.mptest

import com.leko.forge.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Runs on every platform: no reflection. Children come from TreeNode or children<...> { },
// subtypes for "most specific" come from supertypes<...>().

sealed interface E
data class Program(val defs: List<Let>, val result: E) : TreeNode {
    override val children get() = defs + result
}
data class Let(val name: String, val value: E)
data class Num(val n: Int) : E
data class Ref(val name: String) : E
data class Plus(val l: E, val r: E) : E, TreeNode {
    override val children get() = listOf(l, r)
}
data class Neg(val e: E) : E

private fun registerModel() {
    children<Let> { listOf(value) }
    children<Neg> { listOf(e) }
    supertypes<Num>(E::class)
    supertypes<Ref>(E::class)
    supertypes<Plus>(E::class)
    supertypes<Neg>(E::class)
}

private object Lang {
    init { registerModel() }

    val ident by token("[a-z]+")
    val number by token("[0-9]+")
    val program by rule { Program(many(let), expr()) }
    val let by rule { +"let"; val n = ident(); +"="; val v = expr(); +";"; Let(n, v) }
    val expr: Rule<E> by rule { oneOf(plus, unary) }
    val plus: Rule<E> by rule { val l = expr(); +"+"; Plus(l, unary()) }
    val unary: Rule<E> by rule { oneOf({ +"-"; Neg(unary()) }, { Num(number().toInt()) }, { Ref(ident()) }) }
}

// synthesized, with dispatch through the interface
val E.value: Int by syn()
val Num.value: Int by syn { n }
val Plus.value: Int by syn { l.value + r.value }
val Neg.value: Int by syn { -e.value }
val Ref.value: Int by syn { definitions[name]?.value?.value ?: 0 }

// inherited: the definitions visible at a node (only earlier lets, like a sequence of statements)
val definitions: Map<String, Let> by inh<Map<String, Let>>()
    .on<Program> { child ->
        val before = if (child is Let) defs.takeWhile { it !== child } else defs
        before.associateBy { it.name }
    }

// collection with an explicit owner (receiver types are only known by reflection)
val unknown = coll<List<String>>(emptyList(), owner = Program::class)
    .on<Ref> { names -> if (definitions.containsKey(name)) names else names + name }
val Program.unknownNames by unknown

// equally specific: the one added last wins; more specific beats less specific
val kinds = inh<String>().on<Program> { "program" }
val E.kind: String by kinds

val labels = inh<String>().on<E> { "expr" }.on<Plus> { "in plus" }.on<E> { "expr, replaced" }
val E.label: String by labels

class Vertex(val name: String) { override fun toString() = name }

val reach = HashMap<Vertex, List<Vertex>>()
val Vertex.reachable: Set<String> by circular(emptySet()) {
    reach[this].orEmpty().flatMap { it.reachable + it.name }.toSet()
}

class MultiplatformTest {

    private val program = Lang.program.parse("let a = 2; let b = a + 3; -b + a + c")

    @Test
    fun parsesAndRegistersTheTreeWithoutReflection() {
        val plus = program.result as Plus
        assertSame(program, parentOf(plus))
        assertSame(program.defs[1], parentOf(program.defs[1].value))
        assertEquals(3, childrenOf(program).size)
    }

    @Test
    fun synInhAndColl() {
        assertEquals(-5 + 2 + 0, program.result.value)
        assertEquals(5, program.defs[1].value.value)
        assertEquals(listOf("c"), program.unknownNames)
        assertEquals("program", program.result.kind)
    }

    @Test
    fun mostSpecificWithRegisteredSupertypes() {
        val p = Lang.program.parse("-1 + 2")
        val neg = (p.result as Plus).l as Neg
        // the parent of Neg is Plus: the Plus equation is more specific than the E equations
        assertEquals("in plus", neg.label)
        // the parent of Num(1) is Neg, an E: of the two E equations the last one wins
        assertEquals("expr, replaced", neg.e.label)
    }

    @Test
    fun circularOnACyclicGraph() {
        val a = Vertex("a"); val b = Vertex("b"); val c = Vertex("c")
        reach[a] = listOf(b); reach[b] = listOf(c, a); reach[c] = emptyList()
        assertEquals(setOf("a", "b", "c"), a.reachable)
        assertEquals(emptySet(), c.reachable)
    }

    @Test
    fun graphsAndFlows() {
        val cfg = graph<Program>()
            .flow<Program> { p -> sequence(p.defs + p.result) }
            .flow<Let> { l -> entry(l.value); l.value then l; l then next }
            .flow<Plus> { (l, r) -> entry(l); l then r; r then node; node then next }
            .flow<Neg> { (e) -> entry(e); e then node; node then next }
            .flow<Num> { entry(node); node then next }
            .flow<Ref> { entry(node); node then next }
        val g = cfg[program]
        assertSame(program.defs[0].value, g.entry)
        assertEquals(g.vertices.size, g.reachable().size)
        assertSame(g.exit(), g.succ(program.result).single())
    }

    @Test
    fun binaryAndErrors() {
        val header by rule { +hex("CAFE"); +bits("101"); val n = uint(5).toInt(); n }
        assertEquals(9, header.parse(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0b1010_1001.toByte())))
        val e = assertFailsWith<ParseException> { Lang.program.parse("let = 1", source = "x.calc") }
        assertTrue(e.message!!.startsWith("x.calc:1:5: expected ident"), e.message)
    }
}
