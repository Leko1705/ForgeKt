package com.leko.inhtest

import com.leko.forge.inh
import com.leko.forge.syn
import com.leko.forge.tree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

import com.leko.forge.*

// the original example from Main.kt
enum class Type { INT, STRING }
data class Function(val returnType: Type, val body: Expr)
interface Expr
data class Integer(val value: Int) : Expr
data class Plus(val left: Expr, val right: Expr) : Expr
data class Str(val value: String) : Expr
class This : Expr

val Expr.type: Type by syn()
val Integer.type: Type by syn { Type.INT }
val Plus.type: Type by syn { Type.INT }
val Str.type: Type by syn { Type.STRING }
val This.type: Type by syn { typeEnv }

val typeEnv: Type by inh<Type>().on<Function> { returnType }

private interface N
private data class Block(val depthBase: Int, val stmts: List<N>) : N
private data class Pair2(val first: N, val second: N) : N
private data class Leaf(val id: Int) : N

// receiver-less inh accessed from a (non-cached) syn
private val depth: Int by inh<Int>().on<Block> { depthBase }
private val Leaf.depthSyn: Int by syn(cached = false) { depth }

// inh with receiver, child argument and most specific equation
private val N.position: String by inh<String>()
    .on<N> { "generic" }
    .on<Pair2> { child -> if (child === first) "first" else "second" }
    .default { "root" }

class InhTest {

    @Test
    fun mainExample() {
        val p = tree(Function(Type.INT, This()))
        assertEquals(Type.INT, p.body.type)
    }

    @Test
    fun valueIsPropagatedThroughIntermediateNodesAndUsesIdentity() {
        val a = Leaf(1)
        val b = Leaf(1) // structurally equal to a, but in a different block
        tree(Block(10, listOf(Pair2(a, Leaf(2)))))
        tree(Block(20, listOf(b)))
        assertEquals(10, a.depthSyn)
        assertEquals(20, b.depthSyn)
    }

    @Test
    fun childArgumentMostSpecificEquationAndDefault() {
        val x = Leaf(1); val y = Leaf(2); val z = Leaf(3)
        val root = tree(Block(0, listOf(Pair2(x, y), z)))
        assertEquals("first", x.position)
        assertEquals("second", y.position)
        assertEquals("generic", z.position)
        assertEquals("root", root.position)
    }

    @Test
    fun unregisteredNodeFails() {
        assertFailsWith<IllegalStateException> { Leaf(5).depthSyn }
    }
}

class CachedSynTest {

    @Test
    fun cachedSynKeepsOneValuePerNode() {
        // before the fix, the first result was returned for every node
        val a = tree(Function(Type.INT, This()))
        val b = tree(Function(Type.STRING, This()))
        val c = tree(Function(Type.INT, Plus(Integer(1), Str("x"))))
        assertEquals(Type.INT, a.body.type)
        assertEquals(Type.STRING, b.body.type)
        assertEquals(Type.INT, c.body.type)
        assertEquals(Type.STRING, (c.body as Plus).right.type)
        assertEquals(Type.STRING, b.body.type) // cached value of b is still its own
    }

    @Test
    fun equalDataClassNodesAreCachedSeparately() {
        val x = This(); val y = This()
        tree(Function(Type.INT, Plus(x, Integer(1))))
        tree(Function(Type.STRING, Plus(y, Integer(1))))
        assertEquals(Type.INT, x.type)
        assertEquals(Type.STRING, y.type)
    }
}
