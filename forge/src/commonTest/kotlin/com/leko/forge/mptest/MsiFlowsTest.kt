package com.leko.forge.mptest

import com.leko.forge.circular
import com.leko.forge.msi.*
import com.leko.forge.msi.Let
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

// liveness on the default MSI control flow graph, with two circular equations
private fun usesOf(v: Any): Set<String> {
    val out = HashSet<String>()
    fun visit(n: Any?) {
        when (n) {
            is Local -> out += n.name
            is Lambda -> {}
            is Block -> {}
            is com.leko.forge.TreeNode -> n.children.forEach(::visit)
        }
    }
    when (v) {
        is Let -> visit(v.init)
        is Assign -> { visit(v.value); if (v.target !is Local) visit(v.target) }
        is Eval -> visit(v.expr)
        is Return -> visit(v.value)
        is Throw -> visit(v.value)
        is Branch -> visit(v.condition)
        is Loop -> visit(v.condition)
        is Match -> visit(v.subject)
    }
    return out
}
private fun defsOf(v: Any): Set<String> = when (v) {
    is Let -> setOf(v.name)
    is Assign -> (v.target as? Local)?.let { setOf(it.name) } ?: emptySet()
    else -> emptySet()
}
private val Any.liveOut: Set<String> by circular(emptySet()) {
    val f = (this as? com.leko.forge.TreeNode)?.function
    if (f == null) emptySet() else msiFlows.succ(this).flatMap { it.liveIn }.toSet()
}
private val Any.liveIn: Set<String> by circular(emptySet()) { usesOf(this) + (liveOut - defsOf(this)) }

class MsiFlowsTest {

    @Test
    fun controlFlowGraphOfLoopsMatchesAndTry() {
        val program = Conformance.exceptions.module
        program.validate()
        val main = program.functions.first { it.name == "main" }
        val g = main.controlFlow()
        assertTrue(g.vertices.any { it is CatchDispatch })
        val attempt = program.functions.first { it.name == "attempt" }.controlFlow()
        val dispatch = attempt.vertices.single { it is CatchDispatch }
        assertEquals(listOf<Any?>("Failure", "str", "Signal", "uncaught"), attempt.outEdges(dispatch).map { it.label })
        assertSame(attempt.exit(throwPort), attempt.succ(dispatch).last())
    }

    @Test
    fun labeledBreakLeavesTheOuterLoop() {
        val program = Conformance.control.module
        program.validate()
        val main = program.functions.first { it.name == "main" }
        val g = main.controlFlow()
        val brk = g.vertices.single { it is Break }
        val afterLoop = g.succ(brk).single()
        assertTrue(afterLoop is Eval, "break leads to the print after the outer loop, not to $afterLoop")
        val outer = g.vertices.filterIsInstance<Loop>().first { it.label == "outer" }
        assertEquals(listOf<Any?>(true, false), g.outEdges(outer).map { it.label })
    }

    @Test
    fun livenessWithCircularAttributes() {
        val program = Conformance.control.module
        program.validate()
        val fib = program.functions.first { it.name == "firstOver" }
        val g = fib.controlFlow()
        val loop = g.vertices.single { it is Loop } as Loop
        assertEquals(setOf("i", "limit"), loop.liveIn)
        val let = fib.body!!.stmts[0] as Let
        assertEquals(setOf("limit"), let.liveIn)
    }
}
