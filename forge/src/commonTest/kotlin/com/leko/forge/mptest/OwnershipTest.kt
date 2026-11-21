package com.leko.forge.mptest

import com.leko.forge.msi.*
import com.leko.forge.mptest.Conformance.BUFFER
import com.leko.forge.mptest.Conformance.I64
import com.leko.forge.mptest.Conformance.UNIT
import com.leko.forge.mptest.Conformance.VEC2
import com.leko.forge.mptest.Conformance.block
import com.leko.forge.mptest.Conformance.deref
import com.leko.forge.mptest.Conformance.fn
import com.leko.forge.mptest.Conformance.i
import com.leko.forge.mptest.Conformance.main
import com.leko.forge.mptest.Conformance.module
import com.leko.forge.mptest.Conformance.printNum
import com.leko.forge.mptest.Conformance.ref
import com.leko.forge.mptest.Conformance.v
import com.leko.forge.mptest.Conformance.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OwnershipTest {

    private val structs = Conformance.ownership.module.structs
    private val consume = Conformance.ownership.module.functions.first { it.name == "consume" }
    private val newBuffer = New("Buffer", i(0), NewArray(I64, i(4)))

    private fun problems(vararg functions: Func, extraStructs: List<Struct> = emptyList()): List<String> =
        module("m", *functions, consume, structs = structs + extraStructs).validate().map { it.message }

    private fun assertRejected(expected: String, vararg functions: Func, extraStructs: List<Struct> = emptyList()) {
        val messages = problems(*functions, extraStructs = extraStructs)
        assertTrue(messages.any { expected in it }, "expected \"$expected\" in $messages")
    }

    private fun assertAccepted(vararg functions: Func) = assertEquals(emptyList(), problems(*functions))

    @Test
    fun acceptedProgramRunsLikeOnEveryBackend() {
        assertTrue(Conformance.ownership.module.usesOwnership)
        assertEquals(emptyList(), Conformance.ownership.module.validate().map { it.message })
    }

    @Test
    fun useAfterMove() = assertRejected("use of moved value 'b'", main(
        Let("b", BUFFER, newBuffer),
        Eval(Call("consume", v("b"))),
        Eval(Call("consume", v("b"))),
    ))

    @Test
    fun moveInLoop() = assertRejected("use of moved value 'b'", main(
        Let("b", BUFFER, newBuffer),
        Loop(BoolLit(true), block(Eval(Call("consume", v("b"))))),
    ))

    @Test
    fun moveOnOnePathOnly() = assertRejected("use of moved value 'b'", main(
        Let("b", BUFFER, newBuffer),
        Branch(BoolLit(true), block(Eval(Call("consume", v("b"))))),
        Eval(Call("consume", v("b"))),
    ))

    @Test
    fun reassignmentAfterMoveIsFine() = assertAccepted(main(
        Let("b", BUFFER, newBuffer),
        Eval(Call("consume", v("b"))),
        Assign(v("b"), newBuffer),
        Eval(Call("consume", v("b"))),
    ))

    @Test
    fun uninitialized() = assertRejected("'b' may be used before it is initialized", main(
        Let("b", BUFFER),
        Eval(Call("consume", v("b"))),
    ))

    @Test
    fun readWhileMutablyBorrowed() = assertRejected("can not use 'v.x' while it is borrowed mutably", main(
        Let("v", VEC2, New("Vec2", i(1), i(2))),
        Let("r", ref(VEC2, mutable = true), Borrow(v("v"), mutable = true)),
        printNum("x", Get(v("v"), "x")),
        Assign(deref("r", "x"), i(3)),
    ))

    @Test
    fun nonLexicalLifetimes() = assertAccepted(main(
        Let("v", VEC2, New("Vec2", i(1), i(2))),
        Let("r", ref(VEC2, mutable = true), Borrow(v("v"), mutable = true)),
        Assign(deref("r", "x"), i(3)), // last use of r
        printNum("x", Get(v("v"), "x")),
    ))

    @Test
    fun twoMutableBorrowsInOneCall() = assertRejected("can not borrow mutably 'v' while it is borrowed mutably", fn("both", listOf("a" to ref(VEC2, true), "b" to ref(VEC2, true)), UNIT),
        main(Let("v", VEC2, New("Vec2", i(1), i(2))), Eval(Call("both", Borrow(v("v"), true), Borrow(v("v"), true)))))

    @Test
    fun assignWhileBorrowed() = assertRejected("can not assign to 'v' while it is borrowed", main(
        Let("v", VEC2, New("Vec2", i(1), i(2))),
        Let("r", ref(VEC2), Borrow(v("v"))),
        Assign(v("v"), New("Vec2", i(3), i(4))),
        printNum("x", deref("r", "x")),
    ))

    @Test
    fun moveWhileBorrowed() = assertRejected("can not move 'b' while it is borrowed", main(
        Let("b", BUFFER, newBuffer),
        Let("r", ref(BUFFER), Borrow(v("b"))),
        Eval(Call("consume", v("b"))),
        printNum("len", deref("r", "len")),
    ))

    @Test
    fun returningLocalData() = assertRejected("returns a reference to local data (v)",
        Func("bad", emptyList(), ref(VEC2, lifetime = "a"), block(Let("v", VEC2, New("Vec2", i(1), i(2))), Return(Borrow(v("v")))), lifetimes = listOf("a")))

    @Test
    fun lifetimesMustOutliveTheResult() {
        val body = block(Return(v("b")))
        val params = listOf(Param("a", ref(VEC2, lifetime = "a")), Param("b", ref(VEC2, lifetime = "b")))
        assertRejected("returns a reference with lifetime 'b where 'a is required",
            Func("pick", params, ref(VEC2, lifetime = "a"), body, lifetimes = listOf("a", "b")))
        assertAccepted(Func("pick", params, ref(VEC2, lifetime = "a"), body, lifetimes = listOf("a", "b"), outlives = listOf(Outlives("b", "a"))))
    }

    @Test
    fun elisionNeedsExactlyOneReferenceParameter() = assertRejected("needs a lifetime: it has 2 reference parameters",
        fn("two", listOf("a" to ref(VEC2), "b" to ref(VEC2)), ref(VEC2), Return(v("a"))))

    @Test
    fun ownedValuesCanNotBeCopied() = assertRejected("owned values can not be copied", main(
        Let("b", BUFFER, newBuffer), Let("c", BUFFER, Copy(v("b")))))

    @Test
    fun noMovingOutOfFields() = assertRejected("can not move owned field inner out of a struct",
        main(Let("o", Type.Struct("Outer"), New("Outer", newBuffer)), Let("x", BUFFER, Get(v("o"), "inner"))),
        extraStructs = listOf(Struct("Outer", listOf(Field("inner", BUFFER)), kind = MemoryKind.OWNED)))

    @Test
    fun sharedStructsCanNotHoldOwnedValues() = assertRejected("a shared struct can not hold owned values",
        main(), extraStructs = listOf(Struct("Bag", listOf(Field("b", BUFFER)))))

    @Test
    fun closureCapturesAreBorrows() = assertRejected("can not borrow 'x' while it is borrowed mutably", main(
        Let("x", I64, i(1)),
        Let("f", Type.Function(emptyList(), UNIT), Lambda(emptyList(), UNIT, block(Assign(v("x"), v("x") + i(1))))),
        Let("r", ref(I64), Borrow(v("x"))),
        Eval(Invoke(v("f"))),
        printNum("x", Deref(v("r"))),
    ))

    @Test
    fun dynamicExclusivity() {
        val m = module("m", main(
            Let("c", Type.Struct("Counter"), New("Counter", i(0))),
            Access(v("c"), true, block(Access(v("c"), false, block(printNum("n", Get(v("c"), "n")))))),
        ), structs = structs)
        assertEquals(emptyList(), m.validate().map { it.message })
        val e = assertFailsWith<MsiRuntimeException> { Interpreter(m, mapOf("print" to { _ -> Unit })).call("main") }
        assertTrue("simultaneous access" in e.message!!, e.message)
    }

    @Test
    fun modulesWithoutOwnershipAreUnaffected() {
        for (p in Conformance.all.filter { it !== Conformance.ownership }) {
            assertTrue(!p.module.usesOwnership, p.module.name)
            assertEquals(emptyList(), p.module.validate().map { it.message })
        }
    }
}

class InsertCopiesTest {
    private val vec2 = Conformance.ownership.module.structs.first { it.name == "Vec2" }
    private val show = fn("show", listOf("p" to VEC2), UNIT, printNum("x", Get(v("p"), "x")))

    /** Written like a Swift front-end would lower it: plain assignments of value types. */
    private val program = module("swift",
        show,
        main(
            Let("a", VEC2, New("Vec2", i(1), i(2))),
            Let("b", VEC2, v("a")),                 // a copy: a is used again
            Assign(Get(v("b"), "x"), i(5)),
            Eval(Call("show", v("a"))),             // a copy: a is used again below
            Eval(Call("show", v("b"))),             // the last use of b: a move
            Let("i", I64),
            Loop(Binary(BinaryOp.LT, v("i"), i(2)), block(
                Eval(Call("show", v("a"))),         // a copy: used again in the next iteration
                Assign(v("i"), v("i") + i(1)),
            )),
        ),
        structs = listOf(vec2),
    )

    private fun copies(node: Any): Int = (if (node is Copy) 1 else 0) + com.leko.forge.childrenOf(node).sumOf { copies(it) }

    @Test
    fun withoutCopiesTheProgramUsesMovedValues() {
        assertTrue(program.validate().any { "use of moved value 'a'" in it.message })
    }

    @Test
    fun insertsExactlyTheNeededCopies() {
        val copied = program.insertCopies()
        assertEquals(emptyList(), copied.validate().map { it.message })
        assertEquals(3, copies(copied))
        val out = mutableListOf<String>()
        Interpreter(copied, mapOf("print" to { args -> out += args[0] as String; Unit })).call("main")
        assertEquals(listOf("x=1", "x=5", "x=1", "x=1"), out) // value semantics: b's change does not affect a
    }
}
