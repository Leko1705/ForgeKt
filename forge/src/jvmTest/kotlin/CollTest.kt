package com.leko.colltest

import com.leko.forge.*
import com.leko.parsertest.*
import com.leko.parsertest.Function
import com.leko.parsertest.Type
import kotlin.test.Test
import kotlin.test.assertEquals

data class Diagnostic(val msg: String)

// like `inh root: Root? = null; inh Root.root { decls[i] <- this }` in sample.forge
val root: Root? by inh<Root?>().on<Root> { this }.default { null }

// non-null wrapper so a syn can be used (syn requires a non-null type)
data class T(val type: Type?, val error: String? = null)
val Expr.t: T by syn()
val IntLit.t: T by syn { T(Type.INT) }
val StrLit.t: T by syn { T(Type.STR) }
val This.t: T by syn { receiver?.let { T(it) } ?: T(null, "can not use 'this' in this context") }
val Plus.t: T by syn {
    val l = lhs.t; val r = rhs.t
    when {
        l.type == null || r.type == null -> T(null)  // already reported
        l.type == r.type -> T(l.type)
        else -> T(null, "can not add ${l.type} and ${r.type}")
    }
}

// The base language declares the collection and its own contributions ...
val diagnostics = coll<List<Diagnostic>>(emptyList())
    .on<Plus> { diags -> diags + listOfNotNull(t.error?.let(::Diagnostic)) }
val Root.diags by diagnostics

// counts nodes per function: the default target is the nearest enclosing Function
val Function.exprCount by coll(0).on<Expr> { n -> n + 1 }

// a target of null skips the contribution
val Root.receiverFunctions by coll<List<String>>(emptyList())
    .on<Function>(to = { if (receiver != null) root else null }) { names -> names + name }

class CollTest {

    init {
        ThisChecks // load the extension module (see CollExtension.kt) before evaluating
    }

    private val program = SampleLang.program.parse(
        """
        fun ok(): int = 1 + 2
        fun bad(): int = 1 + "x"
        fun noThis(): int = this
        fun str.withThis(): str = this + "y"
        fun int.more(): int = this + 1 + "z"
        """
    )

    @Test
    fun collectsDiagnosticsInTreeOrder() {
        assertEquals(
            listOf(
                Diagnostic("can not add INT and STR"),
                Diagnostic("can not use 'this' in this context"),
                Diagnostic("can not add INT and STR"),
            ),
            program.diags
        )
    }

    @Test
    fun defaultTargetIsNearestEnclosingOwner() {
        assertEquals(listOf(3, 3, 1, 3, 5), program.decls.map { it.exprCount })
    }

    @Test
    fun nullTargetSkipsAndTargetsWithoutContributionsGetInitial() {
        assertEquals(listOf("withThis", "more"), program.receiverFunctions)
        val empty = SampleLang.program.parse("fun f(): int = 1")
        assertEquals(emptyList(), empty.diags)
        assertEquals(emptyList(), empty.receiverFunctions)
    }
}

val intCount = coll(0).on<IntLit> { n -> n + 1 }
val Root.ints by intCount

class CollExtensionTest {

    @Test
    fun contributionAddedAfterEvaluationClearsTheCache() {
        val program = SampleLang.program.parse("fun f(): int = 1 + 2 + this")
        assertEquals(2, program.ints)

        intCount.on<This>(to = { root }) { n -> n + 100 }
        assertEquals(102, program.ints)
    }
}
