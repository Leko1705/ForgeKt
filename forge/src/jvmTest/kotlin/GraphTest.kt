package com.leko.graphtest

import com.leko.forge.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// ---- a small imperative language ------------------------------------------------------------

sealed interface Stmt
sealed interface Ex
data class Func(val name: String, val body: Stmt)
data class Block(val stmts: List<Stmt>) : Stmt
data class Assign(val name: String, val value: Ex) : Stmt
data class While(val cond: Ex, val body: Stmt) : Stmt
data class If(val cond: Ex, val thenStmt: Stmt, val elseStmt: Stmt? = null) : Stmt
class Break : Stmt { override fun toString() = "break" }
data class Return(val value: Ex) : Stmt
data class Num(val value: Int) : Ex
data class Var(val name: String) : Ex
data class Add(val l: Ex, val r: Ex) : Ex
data class Lt(val l: Ex, val r: Ex) : Ex
data class And(val l: Ex, val r: Ex) : Ex
data class Not(val e: Ex) : Ex

// synthetic vertices, not part of the AST
class PushTrue { override fun toString() = "push true" }
class PushFalse { override fun toString() = "push false" }

// ---- control flow graph ---------------------------------------------------------------------

val onTrue = port("onTrue")
val onFalse = port("onFalse")
val brk = port("break", inherited = true)    // leaves any number of nested statements
val ret = port("return", inherited = true)   // unbound at the root: leads to exit(return)

/** Leaves an expression vertex: as a condition (both branch ports bound) or as a value. */
fun FlowScope<*>.leave(v: Any) {
    if (bound(onTrue)) {
        v then onTrue label true
        v then onFalse label false
    } else v then next
}

val cfg = graph<Func>()
    .flow<Func> { f -> entry(f.body); f.body then next }
    .flow<Block> { b -> sequence(b.stmts) }
    .flow<Assign> { a -> entry(a.value); a.value then a; a then next }
    .flow<While> { (cond, body) ->
        entry(cond)
        onTrue(cond) then body
        onFalse(cond) then next
        body then cond
        brk(body) then next
    }
    .flow<If> { s ->
        entry(s.cond)
        onTrue(s.cond) then s.thenStmt
        onFalse(s.cond) then (s.elseStmt ?: next)
        s.thenStmt then next
        s.elseStmt?.let { it then next }
    }
    .flow<Break> { entry(node); node then brk }
    .flow<Return> { r -> entry(r.value); r.value then r; r then ret }
    .flow<Num> { entry(node); leave(node) }
    .flow<Var> { entry(node); leave(node) }
    .flow<Add> { (l, r) -> entry(l); l then r; r then node; leave(node) }
    .flow<Lt> { (l, r) -> entry(l); l then r; r then node; leave(node) }
    .flow<And> { (l, r) ->
        entry(l)
        onTrue(l) then r
        if (bound(onTrue)) {          // used as a condition: pure jumps
            onFalse(l) then onFalse
            onTrue(r) then onTrue
            onFalse(r) then onFalse
        } else {                      // used as a value: produce 0 / 1
            val t = vertex(PushTrue())
            val f = vertex(PushFalse())
            onFalse(l) then f
            onTrue(r) then t
            onFalse(r) then f
            t then next
            f then next
        }
    }
    .flow<Not> { (e) ->
        entry(e)
        if (bound(onTrue)) {
            onTrue(e) then onFalse
            onFalse(e) then onTrue
        } else {
            e then node
            leave(node)
        }
    }

// ---- analyses as circular attributes --------------------------------------------------------

fun uses(v: Any): Set<String> = if (v is Var) setOf(v.name) else emptySet()
fun defs(v: Any): Set<String> = if (v is Assign) setOf(v.name) else emptySet()

val Any.liveOut: Set<String> by circular(emptySet()) { cfg.succ(this).flatMap { it.liveIn }.toSet() }
val Any.liveIn: Set<String> by circular(emptySet()) { uses(this) + (liveOut - defs(this)) }

/** Dominators as vertex indices. `null` means "all vertices", the start value of the fixpoint. */
val Any.dom: Set<Int>? by circular(null) {
    val g = cfg.of(this)!!
    if (this === g.entry) setOf(g.indexOf(this))
    else g.pred(this).mapNotNull { it.dom }.reduceOrNull { a, b -> a intersect b }?.plus(g.indexOf(this))
}

fun dominates(a: Any, b: Any) = cfg.of(a)!!.indexOf(a) in b.dom!!


class GraphTest {

    // x = 0
    // y = 10
    // while (x < y) {
    //     if (x < 5 && !(y < 3)) x = x + 1 else break
    // }
    // return x
    private val x0 = Num(0)
    private val ten = Num(10)
    private val loopX = Var("x")
    private val loopCond = Lt(loopX, Var("y"))
    private val condX = Var("x")
    private val condLt = Lt(condX, Num(5))
    private val notY = Var("y")
    private val notLt = Lt(notY, Num(3))
    private val inc = Assign("x", Add(Var("x"), Num(1)))
    private val brkStmt = Break()
    private val retX = Var("x")
    private val retStmt = Return(retX)
    private val assignY = Assign("y", ten)
    private val f = tree(Func("f", Block(listOf(
        Assign("x", x0),
        assignY,
        While(loopCond, If(And(condLt, Not(notLt)), inc, brkStmt)),
        retStmt,
    ))))

    private fun Graph.labeled(from: Any, label: Any?) = outEdges(from).single { it.label == label }.to

    @Test
    fun buildsControlFlowGraph() {
        val g = cfg[f]
        assertSame(x0, g.entry)
        // the loop header is reached from before the loop and from the end of the body
        assertEquals(2, g.pred(loopX).size)
        assertTrue(g.pred(loopX).any { it === assignY } && g.pred(loopX).any { it === inc })
        // while: false leaves the loop, true enters the body
        assertSame(retX, g.labeled(loopCond, false))
        assertSame(condX, g.labeled(loopCond, true))
        // && and ! as conditions: pure jumps, ! swaps the branches
        assertSame(notY, g.labeled(condLt, true))
        assertSame(brkStmt, g.labeled(condLt, false))
        assertSame(brkStmt, g.labeled(notLt, true))
        assertSame(inc.value.let { (it as Add).l }, g.labeled(notLt, false))
        // the loop body goes back to the condition, break leaves the loop
        assertEquals(listOf<Any>(loopX), g.succ(inc))
        assertEquals(listOf<Any>(retX), g.succ(brkStmt))
        // return leads to the exit vertex of the inherited `return` port
        assertSame(g.exit(ret), g.succ(retStmt).single())
        assertNull(g.exit(Port.next))
        assertEquals(g.vertices.size, g.reachable().size)
    }

    @Test
    fun andAsValueCreatesSyntheticVertices() {
        val a = Var("a"); val b = Var("b"); val assign = Assign("z", And(a, b))
        val g = cfg[tree(Func("v", assign))]
        val t = g.labeled(b, true)
        val fl = g.labeled(a, false)
        assertTrue(t is PushTrue)
        assertTrue(fl is PushFalse)
        assertSame(b, g.labeled(a, true))
        assertSame(fl, g.labeled(b, false))
        assertEquals(listOf<Any>(assign), g.succ(t))
        assertEquals(listOf<Any>(assign), g.succ(fl))
        assertSame(g, cfg.of(t)) // synthetic vertices find their graph too
    }

    @Test
    fun livenessAsTwoCircularEquations() {
        assertEquals(emptySet(), x0.liveIn)
        assertEquals(setOf("x"), ten.liveIn)
        assertEquals(setOf("x", "y"), assignY.liveOut)
        assertEquals(setOf("x", "y"), loopX.liveIn)
        assertEquals(setOf("x"), retX.liveIn)
        assertEquals(emptySet(), retStmt.liveIn)
    }

    @Test
    fun dominatorsAsCircularAttribute() {
        assertTrue(dominates(loopX, inc))
        assertTrue(dominates(loopX, retX))
        assertTrue(dominates(condLt, brkStmt))
        assertTrue(!dominates(inc, retX))
        assertTrue(!dominates(brkStmt, retX))
    }

    @Test
    fun extensionAddsStatementFromAnotherFile() {
        DoWhileExtension // load the extension
        val body = Assign("i", Add(Var("i"), Num(1)))
        val cond = Lt(Var("i"), Num(3))
        val after = Return(Var("i"))
        val g = cfg[tree(Func("d", Block(listOf(DoWhile(body, cond), after))))]
        assertSame((body.value as Add).l, g.entry)          // the body runs first
        assertSame((body.value as Add).l, g.labeled(cond, true))
        assertSame(after.value, g.labeled(cond, false))
    }

    @Test
    fun mostSpecificAndLastAddedFlowWins() {
        val g = graph<Func>()
            .flow<Func> { fn -> entry(fn.body); fn.body then next }
            .flow<Stmt> { entry(node); node then next }       // generic
            .flow<Assign> { error("replaced below") }
            .flow<Assign> { a -> entry(a); a then next }      // same type, added later: wins
        val a = Assign("x", Num(1))
        val built = g[tree(Func("s", a))]
        assertEquals(listOf<Any>(a, built.exit()!!), built.reversePostorder())
    }

    @Test
    fun unconnectedPortIsReported() {
        val g = graph<Func>()
            .flow<Func> { fn -> entry(fn.body) }
            .flow<Assign> { entry(node); node then next }
        val e = assertFailsWith<IllegalStateException> { g[tree(Func("u", Assign("x", Num(1))))] }
        assertContains(e.message!!, "port next of Assign(name=x, value=Num(value=1)) is not connected")
    }

    @Test
    fun handBuiltGraphs() {
        // e.g. a call graph, without flows
        val calls = graphOf("main", listOf(Edge("main", "parse"), Edge("main", "eval"), Edge("eval", "eval")))
        assertEquals(listOf<Any>("main", "eval", "parse"), calls.reversePostorder())
        assertEquals(listOf<Any>("main", "eval"), calls.pred("eval"))
        assertContains(calls.toDot(), "n0 -> n1;")
    }
}
